package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressParser
import io.github.YGHFv.ReaPressExtend.core.ExpressPlatform
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.geoPointOf
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 菜鸟本地包裹数据的采集 hook —— 挂 hybrid 层的查询门面 `HybridDoradoApi`。
 *
 * ## 六代挂点，只有最后一代能用（全是真机实测，别再往回走）
 *
 * 1. `mtopsdk.mtop.domain.MtopResponse#parseJsonByte()`：覆盖确实全，但首页包裹数据
 *    **不在任何 MTOP 响应里**。`mtop.cainiao.pcs.packageservice.querypackagedynlist.cn` 的
 *    `result` 只有 `id` / `uuid` / `packageDynInfo`（"90%概率今天到代收点"），一个身份字段都没有。
 * 2. `com.cainiao.wireless.mtop.response.MtopResponse#getData()`：首页响应类
 *    （`MtopCainiaoLpcPackageserviceQuerypackagedynlistResponse`）**自己覆写了 `getData()`**，
 *    挂父类不覆盖覆写方法 —— 整份日志零调用。
 * 3. fastjson 的四个 `JSON → 实体` 入口：全部挂上（`4 entry point(s) hooked`），**零命中**。
 *    那条路是 JS Bridge 专用的。
 * 4. `cdss.orm.util.DataUtil#injectDataToObject`：回调和 XML 都对上了（`x2`），但**零调用**。
 *    根因两条：(a) 它只服务 `queryByUUID` 那条实体化路径；(b) 它服务的实体
 *    `PackageListV2PackageInfo` 在首页路径上是**死代码**（五个实体类构造函数 hook 全程 0 命中）。
 * 5. ORM 实现类（`oy`，混淆名）+ 运行时发现，挂在 `dalvik.system.BaseDexClassLoader#findClass` 上：
 *    **会让菜鸟卡在启动闪屏**。两个原因叠加 ——
 *    (a) 量级：类定义入口在冷启动期间被回调数千次，而回调体里要跑一遍
 *        `clientIface.isAssignableFrom(defined)`；
 *    (b) 风险：`isAssignableFrom` 会让**刚刚 defineClass、尚未 resolve** 的类去解析继承链，
 *        而调用者是持着类加载锁进栈的 —— 这是「自己等自己」的经典形状。
 *    方法论结论：**类加载路径上不挂任何东西**，也不在那里做任何可能触发类加载的反射。
 *    另外：为了绕开 `oy` 这个混淆名去搞运行时发现，方向本身就错了 ——
 *    正确做法是**继续往上找名字未混淆的调用方**，也就是现在的第 6 代。
 * 6. `com.cainiao.wireless.components.hybrid.api.HybridDoradoApi#query` / `#queryByTableName`
 *    —— **当前方案**。
 *
 * ## 为什么第 6 代是对的
 *
 * 静态证据（MT Manager 反编译 `菜鸟_8.11.923.apk`）：
 *
 * ```smali
 * .class public Lcom/cainiao/wireless/components/hybrid/api/HybridDoradoApi;
 *   public query(Lcom/cainiao/wireless/components/hybrid/model/DoradoQueryModel;)Lcom/alibaba/fastjson/JSONArray;
 *   public queryByTableName(Lcom/cainiao/wireless/components/hybrid/model/DoradoQueryModel;)Lcom/alibaba/fastjson/JSONArray;
 *
 * # query 的方法体核心三行（.line 116-117）
 * new-instance v0, L.../cdss/orm/model/b;                    # 用 DoradoQueryModel.topic 建 mapping
 * invoke-static {}, L.../cdss/orm/a;->XO()L.../cdss/orm/a;   # 取 ORM 单例（实现类 `oy`）
 * invoke-virtual {v1, v0, p1}, orm/a;->query(DBMappingProtocol;Ljava/lang/String;)Lcom/alibaba/fastjson/JSONArray;
 * return-object p1                                           # ORM 的返回值**原样透传**，无二次加工
 * ```
 *
 * 三条结论：
 * - 类名、方法名、参数类型、返回类型**全部未混淆**，由接口契约决定，发版不会变。
 * - 它是 ORM 唯一漏斗 `com.cainiao.wireless.cdss.orm.DoradoOrmClient#query` 的调用点里
 *   **唯一走首页路径**的那个（另外三个是 `queryByTableName` 自身与
 *   `JsHybridPushNotifyTurnOnGuide#getPickUpStationPushTip`，剩下一个是单字母混淆类）。
 * - 返回值无加工 —— **挂在这儿等价于挂在 ORM 出口上**，根本不需要碰 `oy`。
 *
 * 真机证据（`log/run6`，主进程 `com.cainiao.wireless`）：
 *
 * ```
 * orm.stack[package_list_v4_package_info]
 *   < com.cainiao.wireless.components.hybrid.api.HybridDoradoApi.query:117
 *   < com.cainiao.wireless.components.bifrost.hybrid.JsHybridDoradoModule$3.execute:161
 * ```
 *
 * `:117` 与 smali 的 `.line 117` 对上 —— 是同一条调用，不是同名巧合。
 *
 * ## 判定包裹行：只认行形状，不认表名
 *
 * `DoradoQueryModel.topic` **不是表名**。读 `cdss.orm.model.b.<init>(String)` 的 smali：
 * 它先把入参落到 `topicName` 字段，再经 `cdss.core.e#pP(topic)` 查 `SchemaConfigDO`
 * → `DBInfoDO` → `buildMappingInfo()` 才得到真表名。拿 `topic` 判表必然为空 ——
 * 第 5 代就死在这上面（`cainiao pkg: orm table=` 一行都没打出来）。
 *
 * 既然表名拿不到，就按**行字段形状**认，判据见 [looksLikePackageRow]。
 *
 * ## 取的字段
 *
 * 运单号、取件码、驿站名、商品名、电商来源、入站时间、运单动态、驿站营业时间。
 * **快递员不要** —— 它只会让模型和 UI 复杂化。宿主来源都取自真机字段 dump，不是猜的：
 *
 * | 字段 | 宿主 key | 位置 |
 * |---|---|---|
 * | 快递公司 | `partnerName` → `partnerCode` → 运单号前缀 | 主表平铺字段（三级兜底，见 [FIELD_PARTNER_NAME]） |
 * | 商品名 | `packageItem[0].itemTitle` | 主表内嵌数组（宿主自己 join 出来的） |
 * | 电商来源 | `pkgSourceDesc` | 主表平铺字段 |
 * | 入站时间 | `logisticsGmtModified` | 主表平铺字段（毫秒） |
 * | 运单动态 | `lastLogisticDetail` | 主表平铺字段 |
 * | 营业时间 | `packageStation.officeTime` | 主表内嵌驿站对象 |
 */
internal object CainiaoPackageHook {

    private const val CAINIAO = "com.cainiao.wireless"

    /**
     * 宿主 hybrid 层的查询门面。
     *
     * 全类名 / 方法名 / 参数类型 / 返回类型均未混淆 —— 依据见类注释的 smali 片段。
     */
    private const val DORADO_API = "com.cainiao.wireless.components.hybrid.api.HybridDoradoApi"

    /**
     * 查询模型。`topic` / `tableName` / `where` 三个字段**全是 public 且未混淆**
     * （fastjson 从 JS 反序列化，字段名就是契约），所以宿主进程自查时可以直接按名写进去。
     */
    private const val DORADO_QUERY_MODEL =
        "com.cainiao.wireless.components.hybrid.model.DoradoQueryModel"

    /**
     * 两个入口都挂。
     *
     * `query` 用 `DoradoQueryModel.topic`（主题名），`queryByTableName` 用 `.tableName`，
     * 两条都落到同一个 ORM 漏斗，返回值形状也一致，所以判定逻辑可以共用。
     */
    private val QUERY_METHODS = listOf("query", "queryByTableName")

    /**
     * 入参 `DoradoQueryModel` 的三个 public 字段。
     *
     * `DoradoQueryModel` **未混淆且 `implements Serializable`**（fastjson 从 JS 反序列化，
     * 字段名就是契约），所以直接按名读即可。
     *
     * **只用于诊断日志** —— 不参与任何判定，理由见类注释。
     */
    private const val FIELD_TOPIC = "topic"
    private const val FIELD_TABLE_NAME = "tableName"
    private const val FIELD_WHERE = "where"

    /**
     * 包裹行的形状判据 —— 抄自 `log/run6` 里 `package_list_v4_package_info` 第一行的真实 key。
     *
     * 必须同时有 [SHAPE_MAIL_NO] 和 [SHAPE_STATUS]，再从 [SHAPE_EXTRA] 里命中至少一个。
     * 三重与的理由：单看 `mailNo` 会误收「寄件」「搜索历史」这类同样带运单号的表，
     * 加上状态描述与业务字段之后，基本锁死包裹主表。
     *
     * 用 `has()`（key 存在）而不是「取值非空」：运单号可能还没下发（"已下单"的件），
     * 但 key 结构是稳定的 —— 用取值判会整表跳过。
     */
    private const val SHAPE_MAIL_NO = "mailNo"
    private const val SHAPE_STATUS = "logisticsStatusDesc"
    private val SHAPE_EXTRA = listOf("orderCode", "partnerCode", "packageStation", "logisticsStatus")

    /** 主表内嵌的驿站对象 —— 取件码、驿站名、营业时间都在这里面。 */
    private const val FIELD_STATION = "packageStation"
    private const val FIELD_AUTH_CODE = "authCode"
    private const val FIELD_STATION_NAME = "stationName"
    private const val FIELD_SHOW_AUTH_CODE = "showAuthCode"

    /**
     * 驿站营业时间。
     *
     * 在主表内嵌的 `packageStation` 对象上（同一张 dump 里和 `stationName` / `authCode` 并列），
     * 所以跟着驿站对象一起读。宿主常常给空串（该驿站没填），当成「没有」处理。
     */
    private const val FIELD_OFFICE_TIME = "officeTime"

    /**
     * 驿站坐标（WGS84 度），和驿站名同在主表内嵌的 `packageStation` 对象上。
     *
     * 只服务一件事：身份码弹窗里「离我最近的那个驿站」。宿主不给（真机很常见）就当没有 ——
     * 那时退回「最近有来件的那个」，不猜。
     */
    private const val FIELD_STATION_LAT = "stationLat"
    private const val FIELD_STATION_LNG = "stationLng"

    /**
     * 运单动态 —— 最新一条物流详情，如「已发往【上海转运中心】」。
     *
     * 真机证据：`log/run5` 的主表 dump 里它与 `logisticsStatusDesc`（`已下单`）并列，
     * 取值更具体（`商品已经下单`）—— 后者是状态名，前者是给用户看的那句话。
     */
    private const val FIELD_LOGISTICS_DETAIL = "lastLogisticDetail"

    /** 宿主给的快递公司代码，如 `ZTO`。认不出时退回运单号前缀。 */
    private const val FIELD_PARTNER_CODE = "partnerCode"

    /**
     * 宿主给的快递公司**中文名**，如 `中通快递` / `邮政快递包裹`。
     *
     * 它比 [FIELD_PARTNER_CODE] 更该先用：代码是宿主自己的枚举（`POSTB` 这种要靠码表才知道
     * 是哪家，而那份码表在服务端），中文名是宿主已经映射好、直接显示在它自己卡片上的东西 ——
     * 模块只需要认出来，不需要维护任何码表。两者在同一行里（`log/run6` 的 key dump 可见
     * `partnerCode partnerLogoUrl partnerName` 三连）。
     *
     * 用户上报「邮储的快递只显示单号」就死在这条上：只有 `partnerCode=POSTB` +
     * 9 开头 13 位的运单号，两条都不在模块的识别表里 —— 而宿主其实把中文名写在了
     * `partnerName` 里，模块以前压根没读这个字段。
     */
    private const val FIELD_PARTNER_NAME = "partnerName"

    /** 主表内嵌的商品数组（`packageItem[]`）—— 商品名取第 0 个（菜鸟卡片也只显示一件）。 */
    private const val FIELD_PACKAGE_ITEM = "packageItem"
    private const val FIELD_ITEM_TITLE = "itemTitle"

    /**
     * 电商来源 —— 宿主卡片第一行左边那个词（「天猫」「淘宝」）。
     *
     * 用 `pkgSourceDesc`，**不用** `featureObj.eComPlatform`：后者在 8.11.923 上实测是
     * **空串**，而 `pkgSourceDesc` 是宿主自己映射好的中文名（`pkgSource` 只是它的代码形式，
     * 如 `TMALL`），拿来直接就能显示，模块不需要维护一张平台码表。
     *
     * 真机一次性诊断证据（`cainiao pkg: src-candidates ...`）：
     * `pkgSource=TMALL pkgSourceDesc=天猫 showEComPlatform=false isTao=1`，
     * 而同一行的 `featureObj` 里 `"eComPlatform":""`。
     */
    private const val FIELD_PLATFORM = "pkgSourceDesc"

    /**
     * 宿主物流记录里「最后一次状态变更时间」，毫秒。
     *
     * 到站件上那一次变更就是入站，所以它同时是 `ExpressRecord.arrivalAt` 的来源。
     * 依据：`log/run5` 的主表 dump 里它是 13 位毫秒时间戳（`1790328637000`）。
     */
    private const val FIELD_LOGISTICS_GMT_MODIFIED = "logisticsGmtModified"

    /**
     * 已投递指纹上限。
     *
     * 一次首页刷新会把整张表重读一遍，指纹只增不减会一直涨。超过就直接清空重来 ——
     * 菜鸟首页最多几十件，清空的代价（多投几次）远小于集合无限增长。
     */
    private const val MAX_DELIVERED = 512

    /** 诊断用：最多记这么多条「查询走了、但行不是包裹」。 */
    private const val MAX_SHAPE_LOGS = 12

    /** 诊断用：最多记这么多条「命中的查询」。 */
    private const val MAX_QUERY_LOGS = 24

    /** 诊断用：最多记这么多条「有运单号却读不出商品名」的**缺法**（按缺法去重，不是按件）。 */
    private const val MAX_GOODS_LOGS = 8

    /** 指纹里**带上状态**：状态推进（运输中→待取件）必须能再投一次，否则会自己吞掉自己。 */
    private val delivered = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 诊断用：见过的「非包裹行」key 集合指纹。 */
    private val shapeSeen = Collections.synchronizedSet(HashSet<String>())

    /** 诊断用：见过的命中查询指纹。 */
    private val querySeen = Collections.synchronizedSet(HashSet<String>())

    /** 诊断用：见过的「商品名读不出」的缺法指纹。 */
    private val goodsSeen = Collections.synchronizedSet(HashSet<String>())

    /** 诊断用：被当成「收件类型词」丢掉的来源原始值（只在第一次见到时打一条）。 */
    private val platformDropped = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var hooked = false

    /** 没拿到 Context 时只记一次。 */
    @Volatile private var contextFailureLogged = false

    @Volatile private var installed = false

    /**
     * [install] 收到的那个宿主 ClassLoader。
     *
     * 存下来是给 [traceRequestReceiver] 用的：它是个 BroadcastReceiver，触发时机（模块发来的
     * 刷新请求）与 `install` 的调用栈无关，**捕获不到 `install` 的局部变量**。而自查要走反射
     * 拿宿主的类，必须要宿主自己的 ClassLoader —— 用 `context.classLoader` 虽然通常就是它，
     * 但那是「通常」，而这里错了的表现是「自查静默地一行不查」。
     */
    @Volatile private var hookedClassLoader: ClassLoader? = null

    /**
     * 本进程是不是菜鸟主进程（由 [ExpressHookDispatcher] 判定后传入）。
     *
     * 存下来是给 [deliver] 用的：它也会兜底注册身份码索取通道，而身份码**只在主进程取**
     * （见 [install] 里那段），非主进程注册上去只会回一条「取不到」。
     */
    @Volatile private var mainProcess = true

    @Volatile private var recordsSeen = 0

    fun install(classLoader: ClassLoader, isMainProcess: Boolean = true): Boolean {
        if (installed) {
            XposedBridge.logAlways("cainiao package hook already installed, skipping")
            return true
        }

        // 先存下来：接收器（模块发来的刷新请求）在别的调用栈上要用它做反射。
        hookedClassLoader = classLoader

        val api = runCatching { XposedHelpers.findClass(DORADO_API, classLoader) }.getOrElse {
            // 找不到就整个不装：装上去只会空转，而日志里会出现一行像是"装好了"的记录，
            // 把下一轮排查带偏。
            XposedBridge.logError("cainiao package hook: $DORADO_API not found, aborted")
            return false
        }

        var count = 0
        for (name in QUERY_METHODS) {
            count += XposedBridge.hookAllMethods(
                api,
                name,
                object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // 跑在宿主的查询线程上，抛异常会带崩菜鸟 —— 全包住，出错就退化成不干预。
                        runCatching { consumeQuery(param) }
                            .onFailure {
                                XposedBridge.logError("cainiao package hook body failed (fail-open)", it)
                            }
                    }
                },
            ).size
        }

        hooked = count > 0
        installed = true
        mainProcess = isMainProcess
        // 两条反向通道（按需拉轨迹 / 身份码索取）都要一个宿主 Context 才立得起来。
        //
        // ⚠️ **不能只在这里 `acquire()` 一下就算了**（2026-09-26 真机踩到）：`onPackageReady`
        // 早于 `Application#onCreate`，此刻 `acquire()` 必然返回 null，于是注册点只剩 `deliver`
        // —— 而 `deliver` 要宿主**查出包裹**才会跑。唤醒销拉起一个从没打开过界面的菜鸟时，
        // 那个进程不查包裹，索取通道就从来没立起来：模块发的广播没人接，**连一句错都不报**。
        // 所以：装 `Application#onCreate` 捕获钩子（淘宝那条早就在用，菜鸟这条之前漏了），
        // 并把注册动作挂到「context 到手」的回调上 —— 早到晚到都会被执行。
        //
        // ⚠️ 注册放在 `CainiaoIdentityBridge.install` **之后**：context 已经就绪时 [onReady]
        // 会同步执行回调，那时桥必须已经装好。
        HostContextHolder.installCapture(classLoader)
        // 身份码：挂宿主的 `IdentityBean#setIdentityCode`（宿主取到码时白得一份缓存），
        // 并准备好「模块要求时用宿主自己的会话现取」那条路。
        //
        // ⚠️ **只在主进程装**：身份码模块活在主进程里，而非主进程（`:channel` / `:tools` …）
        // 同样会收到模块发来的广播、也同样会回一条「取不到」的回执 —— 那会盖掉主进程刚取到的
        // 码。这类「多进程各装一份」在别的 hook 上是对的（各进程数据独立），在这里是错的。
        val identityOk = if (isMainProcess) {
            runCatching { CainiaoIdentityBridge.install(classLoader) }.getOrElse { error ->
                XposedBridge.logError("cainiao identity bridge install threw", error)
                false
            }
        } else {
            XposedBridge.logAlways("cainiao identity bridge: 非主进程，跳过（身份码只在主进程取）")
            false
        }
        HostContextHolder.onReady { context ->
            ensureTraceReceiver(context)
            if (isMainProcess) CainiaoIdentityBridge.ensureReceiver(context)
            // 「打开模块静默更新快递信息」那一环（2026-09-26 加）：
            // 宿主进程起来了就替它查一次本地包裹表 —— 挂点本身只在**用户打开菜鸟首页**时
            // 才被调用，而用户要的恰恰是不打开。见 [scheduleSelfQuery]。
            if (isMainProcess) scheduleSelfQuery(classLoader)
        }
        XposedBridge.logAlways("cainiao identity bridge installed=$identityOk")
        XposedBridge.logAlways(
            "cainiao package hook installed: $DORADO_API hooked=$count " +
                "(this layer is not on the class-loading path, safe to keep)",
        )
        return hooked
    }

    // ---------------------------------------------------------------- 宿主进程自查

    /** 宿主本地包裹表的查询主题（`DoradoQueryModel.topic`，**不是表名**——理由见类注释）。 */
    private const val SELF_QUERY_TOPIC = "package_list_v4"

    /*
     * 自查用的 where：**没有**。也就是全表查询。
     *
     * ## 为什么是全表，而不是抄宿主那条增量条件
     *
     * 宿主自己发的是 `where  (logistics_gmt_modified>1785179214106 and logistics_status…)`
     * 这类增量条件（真机原文见 `log/run10.txt`），回答的是「自上次刷新以来有什么变化」。
     * 我们要的恰恰不是它：模块可能刚装上、也可能好几天没打开过，任何一种情况下都拿不到宿主
     * 那份「上次刷新时间」，用它去算增量只会漏掉看不见的那一批。
     *
     * ## ⛔ 为什么「把时间戳换成 0」是错的（2026-09-26 真机实证）
     *
     * 最初就是这么写的（`logistics_gmt_modified > 0`），**一行都查不出来，而且看不出来是失败**。
     * 反编译 ORM 的唯一实现类 `oy#query(DBMappingProtocol, String)`（`orm.a` 是 abstract）
     * 才看清 where 的真实契约 —— 这个字段是**裸 SQL 片段**，会被直接接到 `FROM <表名>` 后面：
     *
     * ```smali
     * const-string v5, "SELECT * FROM "
     * invoke-virtual {v4, v5}, …                        # 先拼 "SELECT * FROM " + 表名
     * invoke-static {p2}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
     * if-nez v5, :cond_64                                # where 为空就整段跳过
     * const-string v4, " "
     * invoke-virtual {v5, v4}, … .append(p2)             # 非空则「一个空格 + 原样拼」
     * ```
     *
     * 两件事因此确定：
     *
     * 1. **字段里不带 `WHERE` 关键字** —— 宿主那条串里自带，是它自己那层的写法（`run10.txt` 里
     *    `where=` 后面跟的原文就是 `where  (…)`，前面那个 `where` 属于字段值本身）。
     * 2. 于是 `logistics_gmt_modified > 0` 拼出来是
     *    `SELECT * FROM package_list_v4_package_info logistics_gmt_modified > 0` —— **非法 SQL**，
     *    `rawQuery` 抛异常 → 被 `oy#query` 的 `catch (Exception)` 吞掉（只留一行
     *    `DORADO:checkTableAndQuery exception`）→ **返回空 JSONArray**。
     *    表现就是「自查跑完了、一行都没有、还没有任何错误浮上来」，最难查的那种形状。
     *
     * 传空则走 `if-nez` 那条短路，得到干净的 `SELECT * FROM <表>`。
     * 顺带还**不依赖任何一个列名** —— 宿主改字段名也带不倒它。
     *
     * ⚠️ 这里刻意**没有**常量：[invokeSelfQuery] 干脆不给 `where` 字段赋值（默认就是 null），
     * 少一个可能被后人「顺手改成有意义的值」的地方。
     */

    /** 宿主进程刚起来时 ORM 可能还没初始化好，先等一拍再查。 */
    private const val SELF_QUERY_INITIAL_DELAY_MS = 2_000L

    /** 单次失败后的重试间隔。 */
    private const val SELF_QUERY_RETRY_MS = 6_000L

    /** 最多试几次。三次不成说明这条主题名在这一版上不成立（或 ORM 起不来），再试也是白试。 */
    private const val SELF_QUERY_ATTEMPTS = 3

    /**
     * 两次自查之间的最小间隔。
     *
     * 自查有两个触发源：宿主进程冷启动（[scheduleSelfQuery]）与模块的刷新请求
     * （[ExpressRelay.ACTION_REFRESH_REQUEST]，见 [submitSelfQuery]）。两者可能**紧挨着**
     * 发生 —— 唤醒销刚把进程拉起来、模块紧接着发的刷新请求就到了。没有这道闸，
     * 一次冷启动会查两遍，而每一遍都是一条全表查询 + 一轮逐行序列化。
     *
     * ⚠️ 计时按**提交时刻**算，而一发自查内部的有限重试最多跨 14 秒（2 + 6 + 6）。
     * 所以这个值必须大于那个窗口，否则第二发只会排在还在重试的第一发后面 ——
     * 闸门就只剩「不并发」，没有「不重复」。
     */
    private const val SELF_QUERY_MIN_INTERVAL_MS = 20_000L

    /**
     * **冷启动那一发**只做一次（每进程）。
     *
     * `AtomicBoolean` 而不是 `@Volatile var` + if：它由「context 到手」回调触发，而那个回调
     * 不止一条路会走到（`onCreate` 捕获、`acquire`、`upgrade`），并发进来两次就是两次全表查询。
     *
     * ⚠️ 它管的是**触发**不是**执行**：模块发来的刷新请求走 [submitSelfQuery]，
     * 不受这个标志影响（用户再打开一次模块就该再查一次），只受 [SELF_QUERY_MIN_INTERVAL_MS] 节流。
     */
    private val selfQueryStarted = AtomicBoolean(false)

    /** [SELF_QUERY_MIN_INTERVAL_MS] 的闸门。跨线程读，所以 volatile。 */
    @Volatile private var lastSelfQueryAt = 0L

    /** 闸门的「比较 + 写入」必须原子，否则两个线程会同时通过检查。 */
    private val selfQueryGate = Any()

    /**
     * 自查跑在自己的**单线程**上。
     *
     * 不能用宿主的线程池（那是它的资源，也不保证回调顺序），更不能占主线程 ——
     * 这是一次同步 DB 全表查询，宿主的主线程被卡住就是掉帧甚至 ANR。
     * daemon 线程：宿主正常退出时它不该拖着进程不走。
     */
    private val selfQueryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cainiao-self-query").apply { isDaemon = true }
    }

    /**
     * 宿主进程**冷启动**了 —— 替它查一次本地包裹表。
     *
     * ## 为什么需要这一步（2026-09-26 加）
     *
     * 挂点是 `HybridDoradoApi#query`，而调用它的是**宿主的首页 JS**（`JsHybridDoradoModule`）。
     * 也就是说：只有用户打开菜鸟首页，模块才会收到数据。而用户要的恰恰是「不打开菜鸟」。
     * 宿主进程被唤醒（我们发的唤醒销、或它自己的推送）之后，本地 DB 里可能已经有新包裹了，
     * 但**没有人去读它** —— 这一步就是那个人。
     *
     * ## 为什么调宿主的 API 而不是自己去读它的 SQLite
     *
     * 真表名要经 `SchemaConfigDO` → `DBInfoDO` → `buildMappingInfo()` 映射得到（`topic`
     * 只是个主题名，不是表名）。自己去拼 SQL 等于把宿主那层 schema 逻辑复刻一遍，还会在它
     * 改结构的那天**静默读错表** —— 而调它的门面由它自己完成映射，返回值还会经过我们已装的
     * hook，**一行解析代码都不用新写**（[consumeQuery] 就是那条路）。
     *
     * ⚠️ **它只覆盖「进程刚起」那一半**。宿主常年以推送进程的形式活着、主进程不重启时，
     * `Application#onCreate` 不会再触发，模块打开多少次都不会有第二发 —— 那半边由模块发来的
     * [ExpressRelay.ACTION_REFRESH_REQUEST] 补（见 [submitSelfQuery]）。
     */
    private fun scheduleSelfQuery(classLoader: ClassLoader) {
        if (!selfQueryStarted.compareAndSet(false, true)) return
        submitSelfQuery(classLoader, "宿主冷启动")
    }

    /**
     * 提交一发自查。可重入（模块每次要都算数），只受 [SELF_QUERY_MIN_INTERVAL_MS] 节流。
     *
     * @param reason 谁要的这一发。它进两处：宿主侧日志，以及回给模块的播报。
     *   排查时靠它区分「进程起来自己查的」和「用户打开模块要我查的」——
     *   两者的后续应该不同，混在一起就分不出来了。
     *
     * ## 失败是常态，不是异常
     *
     * 进程刚起来时 ORM 可能还没就绪、这一版主题名也可能变了。所以延迟一拍再试、有限次重试、
     * 全程 `runCatching`。**绝不能因为这一步把宿主弄崩** —— 它是我们主动加进去的动作，
     * 宿主本来没打算在这里做任何事。
     *
     * ## 为什么「查到 0 行」也算失败、要继续重试
     *
     * 冷启动头几秒 ORM 没就绪时，`oy#query` 内部的 catch 会把它变成**空数组**而不是异常
     * （见 where 那段注释里的同一处机制），于是「还没就绪」和「表里真的没有」在这一层
     * 长得一模一样。区分不了就一律当「还没就绪」再试 —— 真为空的表多查两次也没什么代价。
     */
    private fun submitSelfQuery(classLoader: ClassLoader, reason: String) {
        val now = System.currentTimeMillis()
        synchronized(selfQueryGate) {
            if (now - lastSelfQueryAt < SELF_QUERY_MIN_INTERVAL_MS) {
                XposedBridge.logAlways(
                    "cainiao self query: 距上一发不足 ${SELF_QUERY_MIN_INTERVAL_MS}ms，跳过（$reason）",
                )
                return
            }
            lastSelfQueryAt = now
        }
        selfQueryExecutor.execute {
            repeat(SELF_QUERY_ATTEMPTS) { attempt ->
                Thread.sleep(
                    if (attempt == 0) SELF_QUERY_INITIAL_DELAY_MS else SELF_QUERY_RETRY_MS,
                )
                val rows = runCatching { invokeSelfQuery(classLoader) }
                    .onFailure { error ->
                        XposedBridge.logError(
                            "cainiao self query failed (attempt ${attempt + 1}/$SELF_QUERY_ATTEMPTS)",
                            error,
                        )
                    }
                    .getOrNull()
                if (rows != null && rows > 0) {
                    reportSelfQuery("rows=$rows（$reason）")
                    return@execute
                }
                if (attempt == SELF_QUERY_ATTEMPTS - 1) {
                    reportSelfQuery(
                        if (rows != null) {
                            "rows=0（$reason，查通了但本地表是空的）"
                        } else {
                            "没查成，已试 $SELF_QUERY_ATTEMPTS 次（$reason）"
                        },
                    )
                }
            }
        }
    }

    /**
     * 把自查的结论送回模块进程（[ExpressRelay.ACTION_HOST_QUERY_REPORT]）。
     *
     * ## 这一条为什么必须存在
     *
     * 自查跑在**宿主进程**里，它的 `XposedBridge.logAlways` 只会落到 logcat 与 LSPosed 自己的
     * 日志文件；而 MIUI / HyperOS 上 logcat 是**读不出来**的（`logcat -g` 报
     * `main: 62 KiB consumed, 0 B readable`，连 `adb shell log` 打进去的行也读不到），
     * LSPosed 的日志文件 adb 也碰不到。于是「自查到底跑了没有」在宿主侧完全不可观测。
     *
     * 而这一句正是排查时最需要的信息 —— 跑没跑 / 跑了但查成空 / 压根没跑，三种情况的修法完全不同。
     * 所以经 relay 送回模块，落到 `files/module-log.txt`：那是唯一一处
     * `adb shell run-as` 能直接读、且不受「简洁日志」开关影响的地方。
     *
     * 判据链（缺一不可，顺序即排查顺序）：
     * `host self query: rows=N` → 自查通了；`enrichment received` → 数据真的落进了模块。
     *
     * 拿不到 context 就直接放弃：这只是诊断信息，不值得为它引入新的失败点。
     */
    private fun reportSelfQuery(text: String) {
        XposedBridge.logAlways("cainiao self query: $text")
        val context = runCatching { HostContextHolder.acquire() }.getOrNull() ?: return
        ExpressRelaySender.sendHostQueryReport(context, text)
    }

    /**
     * 真的去调一次宿主的查询门面，返回结果行数。
     *
     * 结果本身**不在这里处理**：[consumeQuery] 已经挂在 `query` 上，我们这个调用同样会经过它，
     * 于是「认包裹行 → 建记录 → 落库 → 投给模块」整条路一行都不用重写。
     * 这里只要行数，用来判断这次调用算不算成功。
     *
     * ⚠️ **不给 `where` 赋值** —— `DoradoQueryModel` 的三个 public 字段全都保持默认 null。
     * 理由与反编译依据见上面对 [SELF_QUERY_TOPIC] 之后那段（一句话：这个字段是裸 SQL 片段，
     * 拼错会静默返回空数组）。
     */
    private fun invokeSelfQuery(classLoader: ClassLoader): Int {
        // 两个类的无参构造都是 public，反编译已核：
        // `HybridDoradoApi#<init>()V` 只初始化两个 ConcurrentHashMap，
        // `DoradoQueryModel#<init>()V` 只调 super —— 直接 newInstance 是安全的。
        val apiClass = XposedHelpers.findClass(DORADO_API, classLoader)
        val modelClass = XposedHelpers.findClass(DORADO_QUERY_MODEL, classLoader)
        val model = modelClass.getDeclaredConstructor().newInstance().also {
            XposedHelpers.setObjectField(it, FIELD_TOPIC, SELF_QUERY_TOPIC)
        }
        val api = apiClass.getDeclaredConstructor().newInstance()
        // 返回值是 fastjson 的 JSONArray（implements List）—— `consumeQuery` 里也是这么读的。
        val result = apiClass.getMethod("query", modelClass).invoke(api, model)
        return (result as? List<*>)?.size ?: 0
    }

    /**
     * 模块进程发来的请求 —— 三个动作共用这一个接收器。
     *
     * - [ExpressRelay.ACTION_TRACE_REQUEST]：拉某个单号的全轨迹；
     * - [ExpressRelay.ACTION_COOKIE_REQUEST]：模块刚重启 / 磁盘那份不可用，索要一次登录态；
     * - [ExpressRelay.ACTION_REFRESH_REQUEST]：用户打开了模块，要一份现在的本地包裹表
     *   （见 [submitSelfQuery]，以及为什么唤醒销单独不够用）。
     *
     * 三个都由模块「在最需要的那一刻」发起，所以都不走**宿主侧自己的**节流：
     * 节流防的是宿主反复主动传，不是防接收方明确要了一次。
     * 唯一的例外是自查内部那条闸（[SELF_QUERY_MIN_INTERVAL_MS]），它防的是两个触发源撞车。
     */
    private val traceRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ExpressRelay.ACTION_TRACE_REQUEST -> {
                    val tracking = intent.getStringExtra(ExpressRelay.EXTRA_TRACE_TRACKING)
                        ?.takeIf { it.isNotBlank() } ?: return
                    // 顺手把登录态也补给模块（非 force，走它自己的 30 分钟节流，不是每次都传
                    // 一千多字符的 cookie）。
                    //
                    // 模块会发这个请求，前提就是**它手里没有登录态** —— 只代拉当前这一单的话，
                    // 用户每点一次详情都得求一次菜鸟进程，而菜鸟一旦不在后台（小米上广播会被
                    // 静默丢弃）就永远拿不到。补上之后，模块下一次点详情开始就能自己拉了，
                    // 这才对得上「不打开菜鸟也能获取」。
                    runCatching { ExpressRelaySender.sendCookieSync(context) }
                    // 跑在主线程 —— 拉取本身是网络 IO，必须丢给 Fetcher 自己的 executor。
                    // 全包住：这条链路任何异常都不该带崩宿主。
                    runCatching {
                        CainiaoTraceFetcher.requestFetch(
                            cookieProvider = { HostCredentialSource.cookie(context) },
                            tracking = tracking,
                            deliver = { ExpressRelaySender.sendEnrichment(it, context) },
                        )
                    }.onFailure { XposedBridge.logError("cainiao trace request dispatch failed", it) }
                }
                ExpressRelay.ACTION_COOKIE_REQUEST ->
                    runCatching { ExpressRelaySender.sendCookieSync(context, force = true) }
                        .onFailure { XposedBridge.logError("cainiao cookie request failed", it) }
                ExpressRelay.ACTION_REFRESH_REQUEST -> {
                    // 用户打开了模块，要一份**现在**的本地包裹表。
                    //
                    // 为什么不能只靠 [scheduleSelfQuery]：那个挂在 Application 创建的回调上，
                    // 而宿主常年以推送进程的形式活着、主进程不重启 —— 这时它一次都不会再跑。
                    // 这条请求是那半边的补丁（两条路的分工见 [ExpressRelay.ACTION_REFRESH_REQUEST]）。
                    //
                    // 注意这里**不做节流**：`onResume` 那一侧已经按分钟级节流过了
                    // （见 `ExpressMainActivity`），宿主侧再叠一层只会让「用户刚打开却查到旧数据」
                    // 这种最难解释的现象多一个成因。自查自己那条闸（[SELF_QUERY_MIN_INTERVAL_MS]）
                    // 只用来挡住「唤醒销刚拉起的冷启动自查」与这一发撞车。
                    //
                    // 丢给自查自己的单线程去跑（里面立刻 return，不阻塞 onReceive）——
                    // 一次全表查询不能站在广播的 10 秒预算上。
                    val loader = hookedClassLoader
                    // ⚠️ **只在主进程查**（2026-09-26 真机实测补上这道门）。
                    // 接收器是**每个进程各注册一份**的（`ensureTraceReceiver` 走
                    // `onReady`，不分主次进程），所以不判这一下，`com.cainiao.wireless:channel`
                    // 会跟着查一次：它的 ORM 活得下来，但那张表在它的视图里还不存在
                    // （`oy#query` 开头的 `ox#qB(表名)` 判表不存在就直接返回空数组，
                    // **连异常都不抛**），于是稳定回一个 `rows=0`。
                    // 后果是日志里同一时刻出现两条互相矛盾的回报（主进程 50 行、:channel 0 行），
                    // 下次排查会把「0 行」当成失败信号去修一个根本不存在的问题。
                    // 包裹表的真相只有主进程那一份（`:channel` 是推送通道，不持有业务库），
                    // 所以这里和冷启动那条一样，只认主进程。
                    if (!mainProcess) {
                        XposedBridge.logAlways("cainiao refresh request: 非主进程，跳过自查")
                    } else if (loader == null) {
                        XposedBridge.logError("cainiao refresh request: 还没有 ClassLoader，忽略")
                    } else {
                        submitSelfQuery(loader, "模块刷新请求")
                    }
                }
                else -> Unit
            }
        }
    }

    @Volatile private var traceReceiverRegistered = false

    /**
     * 注册反向请求 receiver（幂等）。
     *
     * 注册要求**发送方持有** [ExpressRelay.PERMISSION_TRACE_REQUEST]（signature 级，定义在
     * 模块 APK 里、只有模块自己签得出）—— 没有这道闸，设备上任何 app 都能拿用户的菜鸟
     * 登录态替自己查任意单号的轨迹，或者直接索要登录态本身。
     *
     * 注册时机铺了两条路（[install] 时一次 + 每次 deliver 时补一次）：Application 创建的
     * 早晚不确定，而 deliver 时 context 一定在手。
     */
    private fun ensureTraceReceiver(context: Context) {
        if (traceReceiverRegistered) return
        synchronized(this) {
            if (traceReceiverRegistered) return
            // 注册本身（权限要求、Android 13+ 的导出性 flag）交给 [HostReceiverRegistrar]：
            // 淘宝进程要注册同一个 action，那边漏一个 flag 就是当场崩、漏个权限就是静默开放。
            traceReceiverRegistered = HostReceiverRegistrar.register(
                context,
                traceRequestReceiver,
                ExpressRelay.ACTION_TRACE_REQUEST,
                ExpressRelay.ACTION_COOKIE_REQUEST,
                ExpressRelay.ACTION_REFRESH_REQUEST,
            )
            XposedBridge.logAlways("cainiao reverse request receiver registered=$traceReceiverRegistered")
        }
    }

    /**
     * 一次 hybrid 查询落地了。
     *
     * **只在第一行上做形状判定**：整表序列化（每行上百个字段、还有嵌套的
     * `packageStation` 与 `packageItem[]`）比「看一行」贵两个数量级，而形状判断失败时
     * 那些开销全是白花的。
     */
    private fun consumeQuery(param: XC_MethodHook.MethodHookParam) {
        val rows = param.result as? List<*> ?: return
        if (rows.isEmpty()) return

        val model = param.args.firstOrNull()
        val topic = model?.let { stringField(it, FIELD_TOPIC) }
        val tableName = model?.let { stringField(it, FIELD_TABLE_NAME) }

        val head = rows.firstOrNull()?.let { asJsonObject(it) } ?: return
        if (!looksLikePackageRow(head)) {
            noteUnmatched(param.method.name, rows, head, topic, tableName)
            return
        }

        val where = model?.let { stringField(it, FIELD_WHERE) }
        val probe = "${param.method.name}|$topic|$tableName|${where?.take(48)}|${rows.size}"
        if (querySeen.size < MAX_QUERY_LOGS && querySeen.add(probe)) {
            XposedBridge.logAlways(
                "cainiao pkg: ${param.method.name} topic=$topic table=$tableName " +
                    "rows=${rows.size} where=${where?.take(60)}",
            )
        }

        for (row in rows) {
            val node = row?.let { asJsonObject(it) } ?: continue
            emit(node)
        }
    }

    /**
     * 「查询走了、但行不是包裹」也要能看见 —— 否则下一轮又只能靠猜。
     *
     * 只记 key 形状（截断到 200 字符），并按指纹去重、按 [MAX_SHAPE_LOGS] 封顶：
     * 首页一次刷新会发好几条查询，不封顶就是刷屏。
     */
    private fun noteUnmatched(
        method: String,
        rows: List<*>,
        head: JSONObject,
        topic: String?,
        tableName: String?,
    ) {
        if (shapeSeen.size >= MAX_SHAPE_LOGS) return

        val keys = StringBuilder()
        val iterator = head.keys()
        while (iterator.hasNext()) {
            if (keys.isNotEmpty()) keys.append(',')
            keys.append(iterator.next())
            if (keys.length > 200) break
        }

        val fingerprint = "$method|${head.length()}|$keys"
        if (!shapeSeen.add(fingerprint)) return
        XposedBridge.logAlways(
            "cainiao pkg: $method rows=${rows.size} topic=$topic table=$tableName " +
                "not-a-package-row keys=[$keys]",
        )
    }

    private fun looksLikePackageRow(row: JSONObject): Boolean =
        row.has(SHAPE_MAIL_NO) && row.has(SHAPE_STATUS) && SHAPE_EXTRA.any { row.has(it) }

    /**
     * 「这行有运单号，却读不出商品名」—— 把**缺在哪一步**记一次。
     *
     * 三种缺法的处置完全不同，而界面上它们长得一模一样（卡片那行是空的，用户看到的
     * 只是「物品详情读取不到」）：
     *
     * - **没有 `packageItem` 数组** → 宿主这次查询根本没 join 子表
     *   （`package_list_v4_package_item`）。要么等它 join，要么改去挂子表那条查询 ——
     *   而后者得先确认真机上确实发生过这条查询，不能凭「子表存在」就动手。
     * - **是空数组** → 宿主给了空容器，同样是没 join 到。
     * - **有元素但没有 `itemTitle`**（或它是空串）→ 字段形状/键名变了，得回头按反编译重新定。
     *
     * 所以这里把第 0 个元素的**键名**打出来：下一轮日志一次就能分清是三种里的哪一种，
     * 不用再猜。指纹里**不带运单号** —— 同一个缺法在几十件上重复出现，我们只关心有几种缺法。
     *
     * `emit` 是在宿主 DB 线程上逐行跑的，所以按缺法去重 + 按 [MAX_GOODS_LOGS] 封顶（热路径不许刷屏）。
     */
    private fun noteMissingGoods(mailNo: String, items: JSONArray?) {
        if (goodsSeen.size >= MAX_GOODS_LOGS) return

        val detail = when {
            items == null -> "no-packageItem"
            items.length() == 0 -> "empty-packageItem"
            else -> {
                val first = items.optJSONObject(0)
                val keys = StringBuilder()
                val iterator = first?.keys()
                while (iterator != null && iterator.hasNext()) {
                    if (keys.isNotEmpty()) keys.append(',')
                    keys.append(iterator.next())
                    if (keys.length > 160) break
                }
                "keys=[$keys] itemTitle=${first?.opt(FIELD_ITEM_TITLE)}"
            }
        }

        if (!goodsSeen.add(detail)) return
        XposedBridge.logAlways("cainiao pkg: 商品名读不出 tn=${mailNo.take(6)}… $detail")
    }

    /** 一行 → 一条记录 → 投出去。全路径只有一个出口，去重与日志都只在这里做。 */
    private fun emit(node: JSONObject) {
        val record = buildRecord(node) ?: return
        if (!deliver(record)) return

        recordsSeen++
        XposedBridge.logAlways(
            "cainiao pkg: tn=${record.trackingNumber} pickup=${record.pickupCode} " +
                "station=${record.station} cp=${record.courier.displayName} " +
                // pn 是宿主给的中文公司名 —— 公司判定第一优先看的就是它。带上它，
                // 下一轮日志能直接看出「认不出是没给名字，还是名字没进识别表」。
                "pn=${stringOf(node, FIELD_PARTNER_NAME)} " +
                "status=${record.status.displayName} platform=${record.platform} " +
                "goods=${record.goodsName?.take(18)} at=${record.arrivalAt} " +
                "hours=${record.stationHours} dyn=${record.logisticsDetail?.take(24)} " +
                // 坐标只在真机验证「身份码弹窗能不能算最近驿站」时才有用，而它常常缺 ——
                // 打出来才能一眼分清「宿主没给」和「给了但我们没读」。
                "geo=${record.stationLat},${record.stationLng}",
        )
    }

    private fun buildRecord(row: JSONObject): ExpressRecord? {
        val stationObject = row.optJSONObject(FIELD_STATION)

        val mailNo = row.opt(SHAPE_MAIL_NO)?.toString()?.takeIf { it.isNotBlank() }

        // 取件码要尊重宿主的开关：`showAuthCode=false` 时菜鸟自己不显示，我们也不该透出来 ——
        // 它可能是「还没到站、先别来取」的场景。取件码是用户照着去货架上拿件的凭据，
        // 写错比留空更糟。
        val pickupCode = stationObject
            ?.takeIf { it.optBoolean(FIELD_SHOW_AUTH_CODE, false) }
            ?.opt(FIELD_AUTH_CODE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        // 驿站名不跟着 showAuthCode 关：驿站名是「去哪取」，不是凭据，早点看到没有坏处。
        val station = stationObject
            ?.opt(FIELD_STATION_NAME)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        // 营业时间也不是凭据，同驿站名一起读；宿主没填时是空串，退回「没有」。
        val stationHours = stationObject
            ?.opt(FIELD_OFFICE_TIME)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        // 坐标同理不是凭据。`optDouble` 缺键 / 非数字都给 NaN —— 一律当「没有」：
        // 0 是几内亚湾上的一个点，当成有效坐标会让「最近的驿站」永远算到非洲去。
        //
        // ⚠️ 宿主下发的是**放大 1e5 的整数**（真机实测 `stationLat=3177340` 实为 31.77340），
        // 归一化交给 core 的 [geoPointOf]：它同时管「缺一半 / (0,0) / 越界」这几种没有坐标的
        // 情形，并且**幂等**（历史记录里存下来的放大值，读取侧还会再过一遍同一函数）。
        val stationPosition = geoPointOf(
            stationObject?.optDouble(FIELD_STATION_LAT, Double.NaN)?.takeIf { !it.isNaN() },
            stationObject?.optDouble(FIELD_STATION_LNG, Double.NaN)?.takeIf { !it.isNaN() },
        )

        if (mailNo == null && pickupCode == null && station == null) return null

        val statusDesc = stringOf(row, SHAPE_STATUS)
        val partnerCode = stringOf(row, FIELD_PARTNER_CODE)
        val partnerName = stringOf(row, FIELD_PARTNER_NAME)

        // 电商来源是主表上的一个**平铺字段**（不是内嵌对象）—— 依据见 [FIELD_PLATFORM]。
        // 归一化交给 core 层的 [ExpressPlatform]：`pkgSourceDesc` 对**非淘包裹**装的是
        // 「普通收件」这类收件类型词，而不是平台名。不洗掉它的话，卡片那行是
        // `平台 · 商品名` 的拼法，泛称会把商品名的位置占住 —— 看上去就像商品名读不出来。
        val rawPlatform = stringOf(row, FIELD_PLATFORM)
        val platform = ExpressPlatform.normalize(rawPlatform)
        if (platform == null && rawPlatform != null && platformDropped.add(rawPlatform)) {
            // 只打一次原始值：「哪一行是泛称」将来要能靠日志扩充词表，而不是只信代码里那张表。
            XposedBridge.logAlways(
                "cainiao pkg: 来源字段是收件类型词，按「没有来源」处理 raw=$rawPlatform",
            )
        }

        // 商品名在 `packageItem[]` 里，取第 0 个 —— 菜鸟首页卡片也只显示一件。
        // 读不出来时把**缺在哪一步**记一次（见 [noteMissingGoods]）：三种缺法的处置完全不同，
        // 而界面上它们长得一模一样（卡片那行是空的）。
        val items = row.optJSONArray(FIELD_PACKAGE_ITEM)
        val goodsName = items
            ?.optJSONObject(0)
            ?.opt(FIELD_ITEM_TITLE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        if (goodsName == null && mailNo != null) noteMissingGoods(mailNo, items)

        // 0 不是合法时间戳 —— 缺键时 org.json 的 optLong 就给 0，正好用来表示「没有」。
        val arrivalAt = row.optLong(FIELD_LOGISTICS_GMT_MODIFIED).takeIf { it > 0L }

        // 运单动态：宿主已经写好的那句人话，模块不加工（加工就等于自己编物流信息）。
        val logisticsDetail = stringOf(row, FIELD_LOGISTICS_DETAIL)

        return ExpressRecord(
            sourcePackage = CAINIAO,
            rawText = buildRawText(mailNo, station, pickupCode),
            trackingNumber = mailNo,
            // 三级判定：中文公司名 → 公司代码 → 运单号前缀。规则与「为什么不能串 ?:」见
            // Courier.resolve —— 那段逻辑放在 core 层是为了能在 JVM 单测里钉住。
            courier = Courier.resolve(partnerName, partnerCode, mailNo),
            pickupCode = pickupCode,
            station = station,
            platform = platform,
            goodsName = goodsName,
            arrivalAt = arrivalAt,
            logisticsDetail = logisticsDetail,
            stationHours = stationHours,
            stationLat = stationPosition?.lat,
            stationLng = stationPosition?.lng,
            status = resolveStatus(statusDesc, logisticsDetail),
            origin = ExpressOrigin.ENRICHMENT,
            // 这条是从宿主自己的数据库里读出来的，不是推断 —— 置信度给满分，
            // 让它能作为独立记录落库（通知缺失时它是唯一来源）。
            confidence = 100,
            timestamp = System.currentTimeMillis(),
        )
    }

    /**
     * 状态判定：`logisticsStatusDesc` 为主，lastLogisticDetail 在它失真时纠偏。
     *
     * 真机实证（2026-09-26，极兔 JT3188…）：宿主对**还没揽收**的件，statusDesc 也笼统写
     * 「运输中」，而同行的 logisticsDetail 是「包裹正在等待揽收」。后者是更具体的证据 ——
     * detail 判出 CREATED 且 desc 判成 IN_TRANSIT / UNKNOWN 时，信 detail。其他组合不动：
     * 「派送中」+ 旧 detail 的情形 detail 落后于 desc，反过来才对。
     */
    private fun resolveStatus(statusDesc: String?, logisticsDetail: String?): ExpressStatus {
        val fromDesc = statusDesc?.let { ExpressParser.parseStatus(it) } ?: ExpressStatus.UNKNOWN
        if (fromDesc == ExpressStatus.IN_TRANSIT || fromDesc == ExpressStatus.UNKNOWN) {
            val fromDetail = logisticsDetail?.let { ExpressParser.parseStatus(it) }
            if (fromDetail == ExpressStatus.CREATED) return ExpressStatus.CREATED
        }
        return fromDesc
    }

    /**
     * 诊断用原文。
     *
     * 不编造用户没见过的文案：这是模块自己拼的，只进日志和去重键，界面上展示的字段
     * 全部来自真实字段。
     */
    private fun buildRawText(mailNo: String?, station: String?, pickupCode: String?): String =
        listOfNotNull(
            mailNo?.let { "运单号 $it" },
            station?.let { "站点 $it" },
            pickupCode?.let { "取件码 $it" },
        ).joinToString(" ")

    /** @return 这一条是不是新投出去的（用于日志与计数）。 */
    private fun deliver(record: ExpressRecord): Boolean {
        val fingerprint =
            "${record.trackingNumber}|${record.pickupCode}|${record.station}|${record.status.name}"
        if (!delivered.add(fingerprint)) return false
        if (delivered.size > MAX_DELIVERED) {
            delivered.clear()
            delivered.add(fingerprint)
        }

        val context = HostContextHolder.acquire()
        if (context == null) {
            if (!contextFailureLogged) {
                contextFailureLogged = true
                XposedBridge.logError(
                    "cainiao package hook: no host context yet, drop ${record.trackingNumber}",
                )
            }
            // 拿不到 Context 只是这一次投不出去，指纹已经占位会让后续重试也被吞掉 ——
            // 所以这里把它撤掉，等下一次查询再试。
            delivered.remove(fingerprint)
            return false
        }

        ExpressRelaySender.sendEnrichment(record, context)
        // 轨迹拉取收拢到模块进程（按需 / 自动都由模块侧跑），cookie 由这里同步过去
        // （30 分钟节流，只进对方内存）。同时把请求 receiver 立起来做兜底：
        // 模块进程还没收到 cookie 时，菜鸟活着也能替它拉。
        ExpressRelaySender.sendCookieSync(context)
        ensureTraceReceiver(context)
        // 身份码的索取通道同理：Application 创建的早晚不确定，而这里一定已经拿到 context。
        // ⚠️ 只有主进程才注册 —— 非主进程回的那条「取不到」会盖掉主进程刚取到的码。
        if (mainProcess) CainiaoIdentityBridge.ensureReceiver(context)
        return true
    }

    /**
     * 一行查询结果转成 JSON 对象。
     *
     * 行是 fastjson 的 `JSONObject`，它的 `toString()` 就是 JSON 文本
     * （实测 `mailNo` / `packageStation.authCode` 这些内嵌结构都能原样解出来）。
     * 模块不引 fastjson，所以走 `toString()` + `org.json`（后者是 Android 框架自带）。
     */
    private fun asJsonObject(row: Any): JSONObject? =
        runCatching { JSONObject(row.toString()) }.getOrNull()

    private fun stringOf(node: JSONObject, name: String): String? =
        node.opt(name)?.toString()?.takeIf { it.isNotBlank() }

    /** 读宿主对象上的 public 字段（`DoradoQueryModel` 的 `topic` / `tableName` / `where` 都是 public）。 */
    private fun stringField(instance: Any, name: String): String? =
        runCatching { XposedHelpers.getObjectField(instance, name) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** 供诊断展示。 */
    fun describe(): String =
        "installed=$installed hooked=$hooked records=$recordsSeen delivered=${delivered.size}"
}
