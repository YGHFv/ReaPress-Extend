/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * 拼多多的包裹数据采集 hook —— 挂它**网络回调的 JSON 出口**
 * `CommonCallback#parseResponseString(String)`。
 *
 * ## 为什么挂在网络回调的出口，而不是像菜鸟那样挂一个业务门面
 *
 * 菜鸟那条能挂 `HybridDoradoApi#query`，是因为它本地有一张 **ORM 表**，查询门面是唯一漏斗。
 * 拼多多不一样：**「取快递」整个页面是 H5**（`NewPageActivity` + `meco.webkit.WebView`，
 * 真机 18:27 `dumpsys` 实锤），实体只在「响应字符串 → 实体」那一刻存在，且响应由
 * H5 直接消费。所以采集分两条腿：
 * 1. **富化**：挂 `CommonCallback` 的 parse 出口（本 hook）——用户点开某件详情时
 *    `NewShipping`/`CabinetInfo` 全字段到手；
 * 2. **发现**：读宿主自己的 HTTP 响应缓存 `cache/pdd_cache`（模块跑在宿主进程里，
 *    用宿主 uid 读自己的缓存完全合法）—— 里面是订单/快递卡的原始 JSON，
 *    正则可稳定抽出 `tracking_number` + `order_sn` + `pick_up_desc`（真机 18:44
 *    一次抽出 14 件跨 4 家快递，全对）。
 *
 * ## 静态证据（MT Manager 反编译 `拼多多_8.26.0.apk`，2026-09-27）
 *
 * 1. 实体包 **`com.xunmeng.pinduoduo.express.entry.*` 整体未混淆**，且字段名就是 Gson 的
 *    JSON 契约（所以 R8 不敢动）——`dex_strings` 里 `trackingNumber` / `shippingStatus` /
 *    `pickupCode` 这些字符串只出现在这几个类里，正是它们在参与反射绑定：
 *    - `NewExpressEntity`：`list:List`（包裹列表）/ `shipping` / `hasMore` / `isSupport` / `result`
 *    - `NewShipping`：`trackingNumber` / `shippingName` / `shippingStatus` / `address` /
 *      `traces:List<NewTrace>` / `cabinetInfo:CabinetSendInfo{code,address}` / `thumbUrl` /
 *      `packageInfo` / `title` / `orderSn` …
 *    - `NewTrace`：`info` / `time` / `status` / `address` / `people`
 *    - `CabinetInfo$Result`：`pickupCodeTip` / `code` / `address` / `companyName` / `status`
 * 2. 消费方 `Lbb1/w;`（**混淆名**的 RecyclerView ViewHolder）读的就是
 *    `NewShipping->trackingNumber / shippingName / shippingStatus / thumbUrl / getPackageInfo`
 *    —— 证明列表项就是 `NewShipping`，且这三四个字段就是卡片上显示的东西。
 * 3. 出口链（smali 逐行核过）：
 *    `Ldb1/a$a;->parseResponseStringWrapper(String)` → `->k(String)`（做埋点）→
 *    `invoke-super CommonCallback->parseResponseStringWrapper` →
 *    `CommonCallback->parseResponseString(String)` → `Lcom/google/gson/Gson;->fromJson`
 *
 *    ⚠️ **`db1/a$a` / `db1/a` / `bb1/w` 全是混淆短名，绝不能挂**（发版必变）。
 *    能挂的只有 `com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback` —— 它在
 *    PDD 自己的 basekit 层，类名 / 方法名 / 签名都由框架契约决定，与业务混淆无关。
 * 4. `CommonCallback` 是泛型基类，`parseResponseString` 通过
 *    `Lic2/d;->c(getClass(), CommonCallback.class, "CommonCallback")` 解析出真实类型 `T`，
 *    再用 Gson 反序列化 —— 也就是说**它的返回值就是那个实体对象本身**，
 *    这里接住等于接住宿主刚解析好的对象（与菜鸟「复用宿主已解析的对象」是同一条原则，
 *    拼多多的 `anti_content` 在 native 层算不出来，我们本来也只能这么做）。
 *
 * ## ⚠️ 这是全 App 最热的路径之一
 *
 * `parseResponseString` 对**每一个** HTTP 响应都会跑一次。所以判定必须是「最便宜的那一步」：
 * 只看返回对象的类名前缀（`Class#getName()` 返回的是 Class 里缓存的字符串，不分配），
 * 不是 `express.entry.*` 就直接 return —— 不读设置、不碰 SharedPreferences、不建对象。
 *
 * ## 真机实测（2026-09-27 17:28，第一版探针上机）
 *
 * ```
 * host probe: NewExpressEntity (首次见到) fields=[hasMore,isSupport,list,result,serverTime,shipping]
 * host probe: CabinetInfo (首次见到) fields=[countDown,result]
 * host probe: NewExpressEntity
 * ```
 *
 * 教训：`NewExpressEntity.list` 存在但**为 null**，两次响应都走了「未知实体」分支 ——
 * 详情页那条路是把单件装在 **`shipping`** 里，不是 `list`。所以 `describe()` 必须同时认
 * 三个形状：`list`（列表）/ `shipping`（详情）/ 裸 `NewShipping`。顺带核到的字段签名
 * （MT outline，`public` 即 Gson 契约）：
 *
 * - `NewExpressEntity`：`list:Ljava/util/List;`、`shipping:L NewShipping;`、
 *   `result:Ljava/lang/String;`（**是字符串不是对象**）、`hasMore:Z`、`isSupport:Z`、
 *   `serverTime:J`、`mRequestScene:String`（private + `getRequestScene()`）
 * - `NewShipping`：`trackingNumber` / `shippingId` / `shippingName` / `shippingStatus:I` /
 *   `title` / `shippingTitle` / `address` / `addressTip` / `orderSn` / `thumbUrl` /
 *   `traces:List` 全是 public；`packageInfo` 与 `signCodeInfo` 是 **private 字段 + 公开 getter**；
 *   `cabinetInfo:NewShipping$CabinetSendInfo`（public，内含 `code` / `address` / `virtualNumber`）
 * - `NewShipping$q`（= `signCodeInfo`）字段是 **`a` / `b` / `c`** —— 这个类不是 Gson 绑定
 *   对象，所以被 R8 改了名。本版探针照读（只是取证据），**不能作为长期判据**
 * - `NewTrace`：`info` / `time` / `display_time` / `status` / `address` / `people` / `remark`
 *   都是 public 稳定名
 *
 * ### 第二轮真机（17:33，`shipping` 分支修好之后）
 *
 * ```
 * NewExpressEntity tn=770012340000005 sid=1 cp=申通快递 st=20
 *   addr=安徽省某市某区阳光花园16号楼2单元201 addrTip=收货地址
 *   sn=260922-999000000000001 traces=15 \
 *   newest=2026-09-24 11:10:22|快件已暂存至阳光花园店菜鸟驿站…/IN_CABINET \
 *   oldest=2026-09-22 12:15:37|您已提交订单，请等待系统确认/CREATE img=1
 * CabinetInfo fields=[countDown:boolean,result:Result] → Result{address:String,code:String,
 *   displayShareEntry:boolean,navigationEntry:c,pickupCodeTip:String,scanCodeInfo:ScanCodeInfo,
 *   shareEntryDesc:String,showQrCodePickUp:boolean,siteFeedBackHighLayer:List,status:int}
 * ```
 *
 * （上面的值已按隐私红线脱敏 —— 形状与真机一致，内容是化名。）
 *
 * 由此定下的三件事：
 * 1. **`traces` 是新的在前**（`[0]` = 最新）。别搞反。
 * 2. `NewTrace.status` 是**字符串枚举**（`CREATE` / `IN_CABINET` …）—— 状态映射应当以它为准，
 *    而不是 `shippingStatus` 那个含义未知的 int。
 * 3. **取件信息在 `CabinetInfo` 这条独立接口里**，而且**驿站也会调它**（那件在「菜鸟驿站 |
 *    阳光花园店」，响应照样来了）。`CabinetInfo$Result` 全是 public 稳定名：
 *    `code` / `pickupCodeTip` / `status` / `address` / `showQrCodePickUp` / `displayShareEntry` /
 *    `shareEntryDesc` / `scanCodeInfo{scanCodeDisplay, showScan}`；private + getter 的有
 *    `companyName` / `contactPhone` / `delivery` / `needDisplayMobile`。
 *    ⟹ 三种取件方式要分清：**有码**（`code`）/ **出示手机号**（`needDisplayMobile`）/
 *    **扫码**（`scanCodeInfo.showScan`）。那次的 `pickupCodeTip` = 「取件出示手机号码188****0000」，
 *    正是界面上那句话 —— 说明这一件**本来就没有取件码**，不是我们没读到。
 *
 * ## 现在的分工：本 hook 管富化腿的探针 + 触发发现重扫；落库在 [PddCacheScanner]
 *
 * - **发现（已落库）**：[PddCacheScanner] 扫 `cache/pdd_cache`，解析在
 *   `core/PddCacheDiscovery`（纯函数），产出走 `sendEnrichment` → 模块侧
 *   `ExpressRecordStore.enrich`（只填空不覆盖、配不上才新建）。
 * - **富化（探针阶段）**：本 hook 挂的三条 parse 出口仍然只**描述并上报**（describe 一族），
 *   不构造记录 —— `NewShipping` 的字段语义虽然核了大半（见上），但「状态码表 →
 *   `ExpressStatus`」的完整映射还没定，此刻落库等于写没核过的数据。
 *   本 hook 在见到快递实体时顺手调 [PddCacheScanner.noteExpressActivity]
 *   （节流重扫缓存）—— 用户在看拼多多快递页时缓存刚被刷新，那是重扫的正确时机。
 *
 * 判据链：
 *
 * ```
 * pdd package hook installed: ... hooked=N          ← 挂上了
 * pdd express: NewExpressEntity list=3 | ...        ← 走到了，且看到列表
 * pdd cache scan (install): files=N discovered=M …  ← 扫描器跑过了
 * host probe: discovery(...): … sent=K              ← 送回去几件（模块侧日志）
 * enriched: tn=…                                    ← 模块侧真的落库了
 * ```
 */
internal object PddPackageHook {

    /**
     * 宿主网络回调的公共基类。
     *
     * 它在 `basekit`（PDD 自己的基础设施层）里，**类名与方法名都未混淆**；
     * 业务方（`db1/a$a` 这种混淆类）覆写 `parseResponseStringWrapper` 之后仍会
     * `invoke-super` 回到这里，所以它是这条链上唯一稳定的锚点。
     */
    private const val COMMON_CALLBACK = "com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback"

    /**
     * 解析出口（都在 `CommonCallback` 上，都是 Gson）：
     * - `parseResponseStringWrapper(String)` —— **通用出口**。标准分发链先调它，它在
     *   方法体内**虚调用** `parseResponseString`；就算某个业务回调覆写了
     *   `parseResponseString` 且不调 super（真机 2026-09-27 17:58「我的收件」列表页
     *   零探针的最可能解释 —— 只挂 `parseResponseString` 会漏掉这类覆写），
     *   wrapper 的**返回值**仍然是解析好的实体。所以主挂它。
     * - `parseResponseString(String)` → 单实体（详情页那一路，兼容不走 wrapper 的调用方）
     * - `parseResponseStringToEmbeddedList(String, String)` → **List**（smali 已核：
     *   `ic2/d.c(...)` 拿泛型 Type 后调 `JSONFormatUtils.b(json, key, type)` 返回 List，
     *   不经过 wrapper）。列表接口走的是这条 —— 真机 2026-09-27 17:40 实测只挂
     *   `parseResponseString` 时列表页零探针。
     *
     * wrapper 与 parse 两条会因虚调用**同一次响应报两遍**，靠内容指纹去重吸收。
     * 全部都在 basekit 层、类名与方法签名由框架契约决定。
     */
    private val PARSE_METHODS = arrayOf(
        "parseResponseStringWrapper",
        "parseResponseString",
        "parseResponseStringToEmbeddedList",
    )

    /**
     * 快递实体的包前缀。
     *
     * 判定**只认这个前缀**，不在模块里引用宿主的类：模块不编译依赖宿主的 dex，
     * 拿不到 `NewShipping` 这种类型；而按前缀过滤顺带覆盖了全部同族实体
     * （`CabinetInfo` / `AcquireResponse` / `QueryReceiptResponse` …），
     * 下一轮要接别的实体时不必改这里。
     */
    private const val ENTRY_PREFIX = "com.xunmeng.pinduoduo.express.entry."

    /** 一次探针最多记这么多个包裹（一个列表接口一次几十件，全打就是刷屏）。 */
    private const val MAX_ITEMS_PER_REPORT = 3

    /** 单段文本上限 —— 富文本元素的 `toString` 能整屏，日志得还能看。 */
    private const val MAX_PART = 90

    /** 未知实体最多列几个字段（只记形状，不记值）。 */
    private const val MAX_SHAPE_FIELDS = 16

    /** 轨迹 `status` 枚举最多记几种（用来反推 PDD 的状态机）。 */
    private const val MAX_STATUSES = 12

    /** 探针报告的指纹上限 —— 列表页来回刷会重复投同一批。 */
    private const val MAX_SEEN = 256

    /** 已经报过的（实体类型 + 内容指纹）。内容不变就不再报，避免刷屏。 */
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 诊断用：见过的「非列表实体」类名（只记一次，帮下一轮定位还有哪些接口）。 */
    private val otherEntities = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * 已经摊过值的富文本元素类型（`字段名:元素类名`）。
     *
     * 这些元素的字段名全是 R8 短名，值也每件不同 —— 不设上限会让每个包裹都重打一遍同样的
     * 12 个字段。摊一次拿到「哪一位是文字」就够了。
     */
    private val shapeSeen = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile private var installed = false

    @Volatile private var hooked = false

    @Volatile private var reports = 0

    /** 本进程是不是拼多多主进程（由 [ExpressHookDispatcher] 传入，只进日志）。 */
    @Volatile private var mainProcess = true

    @Volatile private var contextFailureLogged = false

    fun install(classLoader: ClassLoader, isMainProcess: Boolean = true): Boolean {
        if (installed) {
            XposedBridge.logAlways("pdd package hook already installed, skipping")
            return true
        }
        installed = true
        mainProcess = isMainProcess

        // 报告要一个宿主 Context 才发得出去。和菜鸟那条一样：`onPackageReady` 早于
        // `Application#onCreate`，此刻 acquire() 必然为空，所以走「就绪回调」而不是
        // 「拿不到就算了，等下一次事件再兜底」（那个写法在菜鸟上埋过一次雷，见
        // HostContextHolder 的类注释）。发现腿的首次扫描同样等它。
        HostContextHolder.installCapture(classLoader)

        val callback = runCatching { XposedHelpers.findClass(COMMON_CALLBACK, classLoader) }
            .getOrElse { error ->
                // 找不到就整个不装。装上去只会空转，日志里却会出现一行像「装好了」的记录，
                // 把下一轮排查带偏（菜鸟那边踩过同一个坑）。
                XposedBridge.logError("pdd package hook: $COMMON_CALLBACK not found, aborted", error)
                return false
            }

        val count = PARSE_METHODS.sumOf { name ->
            runCatching {
                XposedBridge.hookAllMethods(
                    callback,
                    name,
                    object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            // 跑在宿主的网络回调线程上，抛异常会带崩拼多多 —— 全包住，出错即不干预。
                            runCatching { consume(param.result) }
                                .onFailure {
                                    XposedBridge.logError("pdd package hook body failed (fail-open)", it)
                                }
                        }
                    },
                ).size
            }.getOrElse { error ->
                XposedBridge.logError("pdd package hook: hook $name threw", error)
                0
            }
        }

        hooked = count > 0

        // 发现腿：宿主进程起来就扫一遍缓存（里面躺着的就是上次会话的全部快递数据）。
        // 扫描器自己等 Context 就绪、自己节流，这里只点火。
        PddCacheScanner.scanOnInstallAsync()

        XposedBridge.logAlways(
            "pdd package hook installed: $COMMON_CALLBACK hooked=$count " +
                "methods=${PARSE_METHODS.joinToString("/")} " +
                "main=$isMainProcess process=${currentProcessName()} " +
                "(this layer is not on the class-loading path, safe to keep)",
        )
        return hooked
    }

    // ---------------------------------------------------------------- 探针

    /**
     * 一次响应解析落地了。
     *
     * ⚠️ **热路径**：先看类名前缀，不是快递实体立刻返回。`Class#getName()` 取的是 Class
     * 对象里缓存的字符串，不会分配 —— 这一步的开销可以忽略。
     */
    private fun consume(result: Any?) {
        result ?: return
        // embedded-list 那条出口的返回值就是 List —— 元素才是实体，整批摊一行。
        if (result is List<*>) {
            consumeList(result)
            return
        }
        val className = result.javaClass.name
        if (!className.startsWith(ENTRY_PREFIX)) return

        // 见到快递实体 = 用户正在看拼多多快递页，缓存可能刚被刷新 ——
        // 让发现腿按节流窗口重扫一遍（见 PddCacheScanner.noteExpressActivity）。
        PddCacheScanner.noteExpressActivity()

        val text = runCatching { describe(result, className) }.getOrNull() ?: return
        dedupeAndReport(text)
    }

    /** `parseResponseStringToEmbeddedList` 的返回值：列表接口一次吐一批。 */
    private fun consumeList(list: List<*>) {
        val entries = list.filterNotNull().filter { it.javaClass.name.startsWith(ENTRY_PREFIX) }
        if (entries.isEmpty()) return

        // 列表接口也在被消费 = 快递页活着，同样给发现腿一个节流重扫信号。
        PddCacheScanner.noteExpressActivity()

        val text = runCatching {
            "embeddedList n=${entries.size} " +
                entries.take(MAX_ITEMS_PER_REPORT).joinToString(" ; ") { describeShipping(it) }
        }.getOrNull() ?: return
        dedupeAndReport(text)
    }

    /** 内容指纹去重（列表页来回刷会重复投同一批），上限 [MAX_SEEN]。 */
    private fun dedupeAndReport(text: String) {
        if (!seen.add(text)) return
        if (seen.size > MAX_SEEN) {
            seen.clear()
            seen.add(text)
        }
        reports++
        report(text)
    }

    /**
     * 把实体摊成一行可读的探针文本。
     *
     * **只读固定字段名**（那些名字来自反编译，见类注释），不做「遍历全部字段」的反射
     * —— 实体上有几十个字段、还有内嵌对象与列表，全量展开既慢又会把手机号之类的
     * 无关隐私带进日志。
     */
    private fun describe(result: Any, className: String): String {
        val simple = className.substringAfterLast('.')
        val scene = call(result, "getRequestScene")?.toString().orEmpty().scene()

        // 取件信息接口。名字叫 CabinetInfo（快递柜），但真机实测**驿站也用这条**：
        // 用户那件在「菜鸟驿站 | 阳光花园店」，响应照样来了。取件码就在 result 里。
        if (simple == CABINET_INFO) return "$simple$scene ${describeCabinet(result)}"

        // 列表实体：展开 list 里的每一件（原地列表页就是它）。
        val list = field(result, FIELD_LIST) as? List<*>
        if (list != null) {
            val head = list.filterNotNull().take(MAX_ITEMS_PER_REPORT)
            val items = head.joinToString(" ; ") { describeShipping(it) }
            return "$simple$scene list=${list.size} hasMore=${field(result, "hasMore")} $items"
        }

        // 详情页那条路：单件装在 `shipping` 里（第一版只认 list，真机整条漏掉 —— 见类注释）。
        field(result, "shipping")?.let { return "$simple$scene ${describeShipping(it)}" }

        // 万一有接口直接返回 NewShipping 本体。
        if (field(result, FIELD_TRACKING) != null || field(result, FIELD_TRACES) != null) {
            return "$simple$scene ${describeShipping(result)}"
        }

        // 其它同族实体（快递柜 / 取件回执 / 订阅…）：第一次见到只记类名 + 字段形状，
        // 下一轮据此决定要不要接（字段值不记 —— 那些还没核过语义）。
        if (otherEntities.add(simple)) {
            return "$simple$scene (首次见到) ${describeShape(result)}"
        }
        return "$simple"
    }

    /**
     * `CabinetInfo` 的取值 —— **取件码就是这里**。
     *
     * ⚠️ 名字是「快递柜」，但对**驿站**同样返回（真机实测）。字段全部是 public 稳定名，
     * 只有 `companyName` / `contactPhone` / `delivery` / `needDisplayMobile` 等几个是
     * private + getter（那些暂时不需要，`pickupCodeTip` 已带服务端脱敏后的展示文案）。
     *
     * 这段要解决的正是关键问题：**拼多多驿站（非快递柜）的取件码落在哪**。
     * 真机那次 `pickupCodeTip` = 「取件出示手机号码188****0000」+ `needDisplayMobile`，
     * 即「这个驿站不用取件码、出示手机号」。所以三种取件方式要分清：
     * 有码（`code` / `pickupCodeTip` 里带码）、出示手机号（`needDisplayMobile`）、
     * 扫码（`scanCodeInfo.showScan`）。
     */
    private fun describeCabinet(entity: Any): String {
        val result = field(entity, "result") ?: return "result=null"
        val parts = mutableListOf<String>()
        str(result, "code")?.let { parts += "code=$it" }
        str(result, "pickupCodeTip")?.let { parts += "tip=${it.trunc()}" }
        intOf(result, "status")?.let { parts += "st=$it" }
        str(result, "address")?.let { parts += "addr=${it.trunc()}" }
        boolOf(result, "showQrCodePickUp")?.let { parts += "showQr=$it" }
        boolOf(result, "displayShareEntry")?.let { parts += "share=$it" }
        str(result, "shareEntryDesc")?.let { parts += "shareDesc=${it.trunc()}" }
        call(result, "isNeedDisplayMobile")?.let { parts += "needMobile=$it" }
        callStr(result, "getCompanyName")?.let { parts += "cp=${it.trunc()}" }
        // `delivery` 是 private + getter：界面上「菜鸟驿站 | 阳光花园店」的第二段
        // 在 `companyName` 和 `address` 里都对不上号（真机 17:42 两者都只有「菜鸟驿站」），
        // 先把它也打出来核对。
        callStr(result, "getDelivery")?.let { parts += "delivery=${it.trunc()}" }
        field(result, "scanCodeInfo")?.let { scan ->
            str(scan, "scanCodeDisplay")?.let { parts += "scan=${it.trunc()}" }
            boolOf(scan, "showScan")?.let { parts += "showScan=$it" }
        }
        return parts.joinToString(" ").ifEmpty { "result=(无已核字段)" }
    }

    /**
     * 未知实体的**形状**：`字段名:类型简名`，外加一层内嵌（若某个字段的类型也在
     * `express.entry` 里）。
     *
     * 只记形状不记值 —— 这些字段的语义还没核过，先看类型才敢读值。一层内嵌刚好覆盖
     * `CabinetInfo → CabinetInfo$Result` 这种「外壳 + 真数据在 result 里」的常见形状。
     */
    private fun describeShape(entity: Any): String {
        val fields = runCatching { entity.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) } }
            .getOrDefault(emptyList())
        val own = shapeOf(fields)
        val nested = fields.asSequence()
            .mapNotNull { runCatching { it.get(entity) }.getOrNull() }
            .firstOrNull { it.javaClass.name.startsWith(ENTRY_PREFIX) }
            ?.let { value ->
                val inner = runCatching {
                    shapeOf(value.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) })
                }.getOrNull()
                inner?.let { "${value.javaClass.simpleName}{$it}" }
            }
        return "fields=[$own]" + (nested?.let { " → $it" } ?: "")
    }

    private fun shapeOf(fields: List<java.lang.reflect.Field>): String =
        fields.take(MAX_SHAPE_FIELDS).joinToString(",") { "${it.name}:${it.type.simpleName.take(24)}" }

    /** 一个包裹实体的关键字段。名字全部来自反编译的 `NewShipping`（见类注释的证据表）。 */
    private fun describeShipping(item: Any): String {
        val parts = mutableListOf<String>()
        str(item, "trackingNumber")?.let { parts += "tn=$it" }
        str(item, "shippingId")?.let { parts += "sid=$it" }
        str(item, "shippingName")?.let { parts += "cp=$it" }
        intOf(item, "shippingStatus")?.let { parts += "st=$it" }
        str(item, "title")?.let { parts += "ttl=${it.trunc()}" }
        str(item, "shippingTitle")?.let { parts += "stl=${it.trunc()}" }
        str(item, "address")?.let { parts += "addr=${it.trunc()}" }
        str(item, "addressTip")?.let { parts += "addrTip=${it.trunc()}" }
        str(item, "orderSn")?.let { parts += "sn=$it" }
        callStr(item, "getPackageInfo")?.let { parts += "pkg=${it.trunc()}" }

        // 快递柜取件码：字段名未混淆（Gson 契约）。
        field(item, "cabinetInfo")?.let { cabinet ->
            str(cabinet, "code")?.let { parts += "cabCode=$it" }
            str(cabinet, "address")?.let { parts += "cabAddr=${it.trunc()}" }
        }
        // 驿站取件码的候选之一：`signCodeInfo` 本体只有公开 getter，里面是 `a`/`b`/`c`
        // 这种被 R8 改过的短名（它不是 Gson 绑定对象，所以字段名保不住）。
        // **只在本版探针里读**，用于定位哪一位是取件码；定下来后必须换成稳定判据。
        call(item, "getSignCodeInfo")?.let { sign ->
            val abc = listOf("a", "b", "c").mapNotNull { k -> str(sign, k)?.let { "$k=$it" } }
            if (abc.isNotEmpty()) parts += "signCode(${abc.joinToString("/")})"
        }

        // 富文本标题 / 状态行。元素是 `NewShipping$c`（字段 a..l 全被 R8 改名，且
        // **没有覆写 toString** —— 直接打出来是 `NewShipping$c@ef550a3` 这种没用的东西）。
        // 所以这里按「每个类只摊一次」把字段**值**打出来，用来定位哪一位是文字。
        richTextProbe(item, "titleRichText")?.let { parts += "ttlRT={$it}" }
        richTextProbe(item, "headStatusRichText")?.let { parts += "headRT={$it}" }
        richTextProbe(item, "titleJumpText")?.let { parts += "ttlJump={$it}" }

        (field(item, FIELD_TRACES) as? List<*>)?.let { traces ->
            parts += "traces=${traces.size}"
            // 轨迹 `status` 是**字符串枚举**（真机见 `CREATE` / `IN_CABINET`）—— 比
            // `shippingStatus` 那个 int 更适合当状态映射依据（值与语义一一对应）。
            traces.filterNotNull().mapNotNull { str(it, "status") }.distinct().take(MAX_STATUSES).let { codes ->
                if (codes.isNotEmpty()) parts += "codes=[${codes.joinToString("/")}]"
            }
            // ⚠️ 顺序是**新的在前**（真机实测：`traces[0]` = 最新那条「暂存至驿站」，
            // 末条 = 最早的「已提交订单」）。映射成 `ExpressRecord.trace` 时别搞反。
            traces.firstOrNull()?.let { parts += "newest=${traceLine(it)}" }
            if (traces.size > 1) traces.lastOrNull()?.let { parts += "oldest=${traceLine(it)}" }
        }
        str(item, "thumbUrl")?.let { parts += "img=1" }
        if (parts.isEmpty()) parts += "(无已核字段)"
        return parts.joinToString(" ")
    }

    /** 一条轨迹：`时间|正文`。正文截断，否则一条就能刷屏。 */
    private fun traceLine(trace: Any): String {
        val time = str(trace, "time") ?: str(trace, "display_time") ?: "?"
        val info = str(trace, "info").orEmpty().trunc()
        val status = str(trace, "status")?.let { "/$it" }.orEmpty()
        return "$time|$info$status"
    }

    /**
     * 富文本列表的**取值**探针：每个「字段名 + 元素类名」只摊一次。
     *
     * 为什么不能直接 `toString`：这些元素类不覆写 `toString`，打出来是
     * `NewShipping$c@ef550a3`。而它们的字段名是 R8 短名 —— 只能读一遍值，
     * 从**内容**（哪一位是中文文案、哪一位像码）反推语义，而不是从名字。
     */
    private fun richTextProbe(item: Any, name: String): String? {
        val list = field(item, name) as? List<*> ?: return null
        val first = list.firstOrNull() ?: return null
        if (!shapeSeen.add("$name:${first.javaClass.name}")) return null
        val values = runCatching {
            first.javaClass.fields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .take(MAX_SHAPE_FIELDS)
                .mapNotNull { f ->
                    runCatching { f.get(first) }.getOrNull()?.let { v -> "${f.name}=${v.toString().trunc()}" }
                }
        }.getOrDefault(emptyList())
        return "n=${list.size} ${values.joinToString(" ")}"
    }

    /**
     * 把探针结论送回模块进程，落进 `files/module-log.txt`。
     *
     * 为什么不能就地 `XposedBridge.logAlways`：MIUI / HyperOS 上 logcat 整条不可读，
     * LSPosed 自己的日志文件 adb 也碰不到 —— 宿主进程里的结论在模块侧**不落字就等于没发生**。
     * 这条与菜鸟自查那条（[ExpressRelay.ACTION_HOST_QUERY_REPORT]）是同一个理由的两份实例，
     * 但不共用 action：那条的接收侧会顺手给「菜鸟直连兜底」报个到，按到拼多多头上是错的。
     */
    private fun report(text: String) {
        XposedBridge.logAlways("pdd express: $text")
        val context = runCatching { HostContextHolder.acquire() }.getOrNull()
        if (context == null) {
            if (!contextFailureLogged) {
                contextFailureLogged = true
                XposedBridge.logError("pdd package hook: no host context yet, reports will be dropped")
            }
            return
        }
        ExpressRelaySender.sendHostProbe(context, text)
    }

    // ---------------------------------------------------------------- 反射小工具

    /**
     * 读宿主对象上的 public 字段（`express.entry` 那批实体全是 public 字段 + 无 getter，
     * 这是 Gson 反序列化的形状 —— 反编译已核）。
     *
     * 认不出的字段返回 null：**不能猜**（拼多多驿站取件码写错比留空更糟，用户会照着念错）。
     */
    private fun field(instance: Any, name: String): Any? =
        runCatching { instance.javaClass.getField(name).get(instance) }.getOrNull()

    /**
     * 调一个公开无参 getter。
     *
     * `packageInfo` / `signCodeInfo` / `mRequestScene` 都是 **`private` 字段 + 公开 getter**
     * （反编译已核），字段反射拿不到，只能走 getter。这些 getter 都是「返回字段」的两三条
     * 指令、没有副作用 —— 但依然包在 runCatching 里：探针本身绝不能影响宿主。
     */
    private fun call(instance: Any, name: String): Any? =
        runCatching { instance.javaClass.getMethod(name).invoke(instance) }.getOrNull()

    private fun callStr(instance: Any, name: String): String? =
        (call(instance, name) as? String)?.takeIf { it.isNotBlank() }

    private fun str(instance: Any, name: String): String? =
        (field(instance, name) as? String)?.takeIf { it.isNotBlank() }

    private fun intOf(instance: Any, name: String): Int? =
        (field(instance, name) as? Number)?.toInt()

    private fun boolOf(instance: Any, name: String): Boolean? =
        field(instance, name) as? Boolean

    /** 单段文本截断 —— 富文本 / JSON 元素的 toString 会很长。 */
    private fun String.trunc(): String = if (length <= MAX_PART) this else take(MAX_PART) + "…"

    /** 场景名（`mRequestScene`）有就带上：它区分「列表查询 / 详情查询 / 取件…」这类上下文。 */
    private fun String.scene(): String = if (isBlank()) "" else " scene=$this"

    /** 进程名走 `/proc/self/cmdline`（理由与 `ExpressHookDispatcher` 里那份一致）。 */
    private fun currentProcessName(): String =
        runCatching { java.io.File("/proc/self/cmdline").readText() }
            .getOrNull()
            .orEmpty()
            .substringBefore('\u0000')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "?"

    private const val FIELD_LIST = "list"
    private const val FIELD_TRACKING = "trackingNumber"
    private const val FIELD_TRACES = "traces"

    /** 取件信息接口（名字是快递柜，但对驿站同样返回）。 */
    private const val CABINET_INFO = "CabinetInfo"

    /** 供诊断展示。 */
    fun describe(): String =
        "installed=$installed hooked=$hooked reports=$reports others=${otherEntities.size}"
}
