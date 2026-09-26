package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressParser
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import org.json.JSONObject
import java.util.Collections

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

    /** 指纹里**带上状态**：状态推进（运输中→待取件）必须能再投一次，否则会自己吞掉自己。 */
    private val delivered = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 诊断用：见过的「非包裹行」key 集合指纹。 */
    private val shapeSeen = Collections.synchronizedSet(HashSet<String>())

    /** 诊断用：见过的命中查询指纹。 */
    private val querySeen = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var hooked = false

    /** 没拿到 Context 时只记一次。 */
    @Volatile private var contextFailureLogged = false

    @Volatile private var installed = false

    @Volatile private var recordsSeen = 0

    fun install(classLoader: ClassLoader): Boolean {
        if (installed) {
            XposedBridge.logAlways("cainiao package hook already installed, skipping")
            return true
        }

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
        // 顺手把「按需拉轨迹」的请求通道立起来。拿不到 context 也不影响主 hook ——
        // deliver 时 [ensureTraceReceiver] 还会再试一次（Application 那时已创建）。
        runCatching { HostContextHolder.acquire() }
            .getOrNull()
            ?.let { ensureTraceReceiver(it) }
        XposedBridge.logAlways(
            "cainiao package hook installed: $DORADO_API hooked=$count " +
                "(this layer is not on the class-loading path, safe to keep)",
        )
        return hooked
    }

    /** 模块 App 点开详情页时发来的轨迹拉取请求（`ACTION_TRACE_REQUEST`）落在这里。 */
    private val traceRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ExpressRelay.ACTION_TRACE_REQUEST) return
            val tracking = intent.getStringExtra(ExpressRelay.EXTRA_TRACE_TRACKING)
                ?.takeIf { it.isNotBlank() } ?: return
            // 跑在主线程 —— 拉取本身是网络 IO，必须丢给 Fetcher 自己的 executor。
            // 全包住：这条链路任何异常都不该带崩宿主。
            runCatching {
                CainiaoTraceFetcher.requestFetch(
                    cookieProvider = { CainiaoCookieSource.cookie(context) },
                    tracking = tracking,
                    deliver = { ExpressRelaySender.sendEnrichment(it, context) },
                )
            }.onFailure { XposedBridge.logError("cainiao trace request dispatch failed", it) }
        }
    }

    @Volatile private var traceReceiverRegistered = false

    /**
     * 注册轨迹请求 receiver（幂等）。
     *
     * 注册要求**发送方持有** [ExpressRelay.PERMISSION_TRACE_REQUEST]（signature 级，定义在
     * 模块 APK 里、只有模块自己签得出）—— 没有这道闸，设备上任何 app 都能拿用户的菜鸟
     * 登录态替自己查任意单号的轨迹。
     *
     * 注册时机铺了两条路（[install] 时一次 + 每次 deliver 时补一次）：Application 创建的
     * 早晚不确定，而 deliver 时 context 一定在手。
     */
    private fun ensureTraceReceiver(context: Context) {
        if (traceReceiverRegistered) return
        synchronized(this) {
            if (traceReceiverRegistered) return
            val filter = IntentFilter(ExpressRelay.ACTION_TRACE_REQUEST)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(
                        traceRequestReceiver,
                        filter,
                        ExpressRelay.PERMISSION_TRACE_REQUEST,
                        /* scheduler = */ null,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(
                        traceRequestReceiver,
                        filter,
                        ExpressRelay.PERMISSION_TRACE_REQUEST,
                        /* scheduler = */ null,
                    )
                }
                traceReceiverRegistered = true
                XposedBridge.logAlways("cainiao trace request receiver registered")
            }.onFailure {
                XposedBridge.logError("cainiao trace request receiver register failed", it)
            }
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
                "hours=${record.stationHours} dyn=${record.logisticsDetail?.take(24)}",
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

        if (mailNo == null && pickupCode == null && station == null) return null

        val statusDesc = stringOf(row, SHAPE_STATUS)
        val partnerCode = stringOf(row, FIELD_PARTNER_CODE)
        val partnerName = stringOf(row, FIELD_PARTNER_NAME)

        // 电商来源是主表上的一个**平铺字段**（不是内嵌对象）—— 依据见 [FIELD_PLATFORM]。
        val platform = stringOf(row, FIELD_PLATFORM)

        // 商品名在 `packageItem[]` 里，取第 0 个 —— 菜鸟首页卡片也只显示一件。
        val goodsName = row.optJSONArray(FIELD_PACKAGE_ITEM)
            ?.optJSONObject(0)
            ?.opt(FIELD_ITEM_TITLE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

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
