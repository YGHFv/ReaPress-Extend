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

package io.github.YGHFv.ReaPressExtend.relay

/**
 * 拦截事件从 system_server 投递到模块 App 进程的广播协议。
 *
 * **两侧共用这一份常量**：一侧是被注入 system_server 的代码，另一侧是模块 App 进程，
 * 两边包名相同但类加载器完全不同 —— 只能靠字符串契约对齐，所以集中定义在这里。
 *
 * 为什么走广播而不是 binder：
 * - `RemotePreferences` 是给设置用的（hooked 侧只读），不适合流式事件
 * - `XposedService` 只在模块 App 进程可用，system_server 里拿不到
 * - 广播 + 显式组件 + `FLAG_INCLUDE_STOPPED_PACKAGES` 是唯一能在「模块 App 没启动过」
 *   的情况下把事件送到的通道
 */
object ExpressRelay {

    /** 模块自己的包名。system_server 侧用它做递归防护与显式组件寻址。 */
    const val MODULE_PACKAGE = "io.github.YGHFv.ReaPressExtend"

    const val RECEIVER_CLASS = "io.github.YGHFv.ReaPressExtend.relay.ExpressRelayReceiver"

    /** 投递动作：system_server 拦到一条快递通知。 */
    const val ACTION_DELIVER = "io.github.YGHFv.ReaPressExtend.DELIVER_EXPRESS"

    /**
     * 投递动作：宿主进程富化出一条包裹信息。
     *
     * 与 [ACTION_DELIVER] 分开而不是共用一个动作，是因为**接收端的处理完全不同**：
     * deliver 要落记录 + 发通知，enrich 只落记录（补已有或新建一条）、**不发通知**。
     * 用 action 而不是一个 boolean extra 来区分，接收端就不可能出现「忘了判 extra」这种错
     * ——漏判时 `when` 直接落到 else 分支被忽略，而不是误发一条通知。
     */
    const val ACTION_ENRICH = "io.github.YGHFv.ReaPressExtend.ENRICH_EXPRESS"

    /**
     * 反向请求：模块 App 进程 → 宿主进程，「帮我拉这个单号的全轨迹」。
     *
     * 2026-09-26 起轨迹**不再在宿主首页刷新时批量自动拉**（1.5s 五连发后就吃到淘宝
     * `RGV587` 风控），改为用户点开详情页时按需拉单个 —— 用户的一次点击就是一次请求，
     * 节奏天然和人手一致，风控压力最小化。extras：[EXTRA_TRACE_TRACKING]。
     *
     * ⚠️ 依赖宿主进程存活：菜鸟不在后台时这条广播没人收，详情页按超时降级（见 UI 层）。
     */
    const val ACTION_TRACE_REQUEST = "io.github.YGHFv.ReaPressExtend.TRACE_REQUEST"

    /** 单个运单号的轨迹拉取已完成并落库（模块进程内部广播，UI 收到后重读存储）。 */
    const val ACTION_TRACE_ARRIVED = "io.github.YGHFv.ReaPressExtend.TRACE_ARRIVED"

    /**
     * 批量包裹数据刚落完库，首页该重读一遍了（模块进程内部广播）。
     *
     * ## 为什么不能复用 [ACTION_TRACE_ARRIVED]
     *
     * 那个的接收侧除了重读列表，还会**收掉详情页的刷新指示器**（`detailRefreshing = false`）。
     * 套用到「宿主自查灌完一批」这个场景上就是错的：详情页可能正等着一单的轨迹，
     * 被这一下提前收掉指示器，用户看到的是「暂无物流轨迹」—— 而其实还在拉。
     * 两条消息的**接收期望不同**，就不能共用一条 action（这个判断在
     * [ACTION_ENRICH] 那边也是同一个理由）。
     *
     * ## 为什么要它（2026-09-26）
     *
     * 「打开模块 → 静默拉起菜鸟 → 数据更新」这条链路里，数据是**用户已经停在首页之后**才到的
     * （宿主自查有 2 秒起步延迟，失败还要重试到十几秒）。没有这个信号，界面会一直停在打开那一刻
     * 读到的旧快照上 —— 功能明明成了，用户看到的却是「刷了跟没刷一样」，
     * 这比真没刷还难查。
     *
     * 触发点只有一个：[ACTION_HOST_QUERY_REPORT] 到达时（那时宿主那一批已经全部投递并落库 ——
     * 自查是同步的，调用返回后才发的播报）。**不跟着 [ACTION_ENRICH] 逐条发**：
     * 首页刷新一次十几条，逐条发就是把列表重读十几遍（那正是 [ACTION_TRACE_ARRIVED]
     * 只在真带轨迹数据时才发的理由）。
     */
    const val ACTION_RECORDS_CHANGED = "io.github.YGHFv.ReaPressExtend.RECORDS_CHANGED"

    /**
     * 宿主进程 → 模块进程：同步淘宝登录态 cookie。
     *
     * 轨迹拉取收拢到模块进程后（见 [ACTION_TRACE_REQUEST] 的注释），模块进程要能自己
     * 发 MTOP 请求 —— 而 cookie 只存在菜鸟的私有目录里，只能由 hook 侧读出来送过来。
     *
     * ## 「cookie 不外传」原则的修订（2026-09-26）
     *
     * 最初「请求放菜鸟进程发」的理由之一是 cookie 不离开宿主。用户拍板要「菜鸟不在后台
     * 也能点详情静默获取」（对照 ExpressAssistant 的行为），这条路只有 cookie 出宿主一条走法。
     * 收窄后的边界：cookie 进**模块进程内存 + 模块私有目录**（[TraceCookieCache] /
     * `TraceCookieStore`，**落盘**是用户随后拍板的第二次修订 —— 模块进程一天重启六次，
     * 只存内存等于每次都要重新求宿主），**不进日志**。广播本身用显式组件寻址，第三方截不到 extras。
     */
    const val ACTION_COOKIE_SYNC = "io.github.YGHFv.ReaPressExtend.COOKIE_SYNC"

    /**
     * 反向请求：模块进程 → 宿主进程，「把你的登录态再发我一遍」。
     *
     * 为什么需要它：`ACTION_COOKIE_SYNC` 是**宿主主动**发的，而宿主只在首页查询
     * （`deliver`）时才想得起来发，且发的时候还有节流。于是有一个很难受的窗口 ——
     * 用户点开模块（模块进程冷启动、内存缓存为空）→ 宿主这半天没刷新过首页 →
     * 模块手里没有 cookie → 轨迹那条路就拉不动，而用户就站在驿站门口。
     * （身份码不再走这条路：它请菜鸟用自己的会话取，见 [ACTION_IDENTITY_REQUEST]。）
     *
     * 有了这条，模块可以在**需要的那一刻**直接问一次，不用等宿主想起来。
     * 与 [ACTION_TRACE_REQUEST] 同一套寻址与权限：发给宿主进程里注册的 receiver，
     * 发送方必须持有 [PERMISSION_TRACE_REQUEST]（signature 级）——
     * 否则设备上任何 app 都能索要用户的淘宝登录态。
     */
    const val ACTION_COOKIE_REQUEST = "io.github.YGHFv.ReaPressExtend.COOKIE_REQUEST"

    /**
     * 反向请求：模块进程 → 菜鸟进程，「把你本地那张包裹表重查一遍，结果按老规矩投回来」。
     *
     * ## 为什么除了唤醒销还需要它（2026-09-26 实测补上的一环）
     *
     * 「打开模块 → 数据是新的」原本只靠唤醒销 + 宿主进程侧的冷启动自查（见
     * `CainiaoPackageHook#scheduleSelfQuery`）。那套有个洞：自查挂在
     * `Application#onCreate` 捕获到的 context 上，**只在进程头一次起来时跑一次**。
     * 而小米上菜鸟常年以推送进程（`:channel`）的形式活着 —— 主进程没死，唤醒销是一记
     * 空操作，`onCreate` 不会再触发，于是「打开模块」什么也不会发生，用户看到的是
     * 「刷了跟没刷一样」。真机日志里 `host wake（打开模块）: 唤醒销已发出` 后面一片空白
     * 就是这个形状。
     *
     * ## 它和唤醒销的分工（两条互补，缺一不可）
     *
     * | 宿主进程 | 唤醒销（清单接收者，显式组件） | 这条（动态接收者） |
     * |---|---|---|
     * | 不在 | **能拉起**（投递清单接收者会创建进程） | 没人接，被静默丢弃 |
     * | 已存活 | 空操作 | **能叫它立刻查一次** |
     *
     * 动态注册的接收者只在进程存活时存在，所以它**永远叫不醒一个死进程** —— 这也是
     * 唤醒销不能删的原因。
     *
     * 与 [ACTION_TRACE_REQUEST] / [ACTION_COOKIE_REQUEST] 同一套寻址与权限：
     * 发给宿主进程里注册的 receiver，发送方必须持有 [PERMISSION_TRACE_REQUEST]。
     */
    const val ACTION_REFRESH_REQUEST = "io.github.YGHFv.ReaPressExtend.REFRESH_REQUEST"

    /**
     * 宿主进程 → 模块进程：**自查结果的一句播报**（查到了几行 / 三次都没查成）。
     *
     * ## 为什么必须有这条（2026-09-26）
     *
     * 自查跑在宿主进程里，它的 `XposedBridge.logAlways` 走 libxposed 的日志出口。
     * 而 MIUI / HyperOS 上 logcat **读不出来**（`logcat -g` 报
     * `main: ring buffer is 64 KiB (62 KiB consumed, 0 B readable)`，连 `adb shell log`
     * 打进去的行也读不到），LSPosed 自己的日志文件又不是 adb 能碰的。
     * 于是「自查到底跑了没有」在宿主侧**没有任何可观测渠道** —— 而这恰恰是排查时最想知道的
     * 一件事（跑没跑 / 跑了但查成空 / 压根没跑，三种情况的修法完全不同）。
     *
     * 所以把结论**经 relay 送回模块进程**，由它记进 `files/module-log.txt` —— 那是唯一
     * 一个 `adb shell run-as` 能直接读、且与「简洁日志」开关无关的地方。
     * 这也是项目里那条老规矩的延续：判断富化到没到，只看模块侧的 `files/module-log.txt`。
     *
     * 载荷：[EXTRA_HOST_QUERY_REPORT]（一句人话，只进日志，不含任何用户数据）。
     */
    const val ACTION_HOST_QUERY_REPORT = "io.github.YGHFv.ReaPressExtend.HOST_QUERY_REPORT"

    /**
     * 反向请求：模块进程 → 菜鸟进程，「用你自己的会话替我取一份身份码」。
     *
     * ## 为什么不能让模块自己取（2026-09-26 两轮真机实证）
     *
     * 身份码那个接口在 **H5 通道上是关着的**：预热请求连 `_m_h5_tk` 都不下发，直接回
     * `FAIL_SYS_SESSION_EXPIRED` —— 而**同一时刻、同一份 cookie 拉轨迹是成功的**，所以那
     * 不是登录态的问题。名字里带 `native` 的那个版本更是只在 APP 通道可用。
     *
     * 结论：唯一可靠的做法是让菜鸟**用它自己的会话**取一次（`CainiaoIdentityBridge`）——
     * 那就是宿主自己要发的那个请求，服务端看到的是一个正常的、由宿主签出来的请求。
     *
     * ## 与 [ACTION_COOKIE_REQUEST] 的区别
     *
     * 那条要的是「登录态」这个原材料，这条要的是**结果**。身份码是有时效的短凭据，
     * 它不该离开菜鸟进程去别处被复刻一遍 —— 也就顺带没有了「复刻出来的码和宿主显示的不一样」
     * 这个风险（报错的取件码会让用户在柜台前出丑，比「这次没显示」严重得多）。
     */
    const val ACTION_IDENTITY_REQUEST = "io.github.YGHFv.ReaPressExtend.IDENTITY_REQUEST"

    /**
     * 菜鸟进程 → 模块进程：身份码（或取不到的原因）。
     *
     * **三种来路共用这一个动作**，接收侧不需要区分 —— 都是「一份宿主取到的码」：
     * - 对 [ACTION_IDENTITY_REQUEST] 的**应答**；
     * - 宿主自己取到码时的**主动推送**（用户打开了菜鸟的身份码页）。这一条不是冗余：
     *   小米上菜鸟不在后台时，模块发去的广播会被系统**静默丢弃**（见 MEMORY 的环境陷阱），
     *   那一刻模块手里就只有这份推送过的码可用；
     * - 宿主取不到时的**失败回执**（只带 [EXTRA_IDENTITY_ERROR]，不带 [EXTRA_IDENTITY_CODE]）。
     *
     * ⚠️ 还有**第四种**：纯状态通知（只带 [EXTRA_IDENTITY_BRIDGE_STATUS]）。它不是对某次
     * 索取的答复，而是「索取通道已经立好了」的一次播报 —— 模块收到只记一行日志。
     * 加它的理由：通道没立起来时两边都写不出日志（模块的广播没人接、宿主的 receiver 不存在），
     * 而「静默」是最难查的故障形态；有一句播报，排查就从「猜」变成「看」。
     */
    const val ACTION_IDENTITY_SYNC = "io.github.YGHFv.ReaPressExtend.IDENTITY_SYNC"

    // ---- extras ----
    const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_TRACKING = "trackingNumber"
    const val EXTRA_COURIER = "courier"
    const val EXTRA_PICKUP_CODE = "pickupCode"
    const val EXTRA_STATION = "station"
    const val EXTRA_STATUS = "status"
    const val EXTRA_CONFIDENCE = "confidence"
    const val EXTRA_KEYWORDS = "matchedKeywords"
    const val EXTRA_TIMESTAMP = "timestamp"

    // ---- 只有宿主富化给得出的字段（通知文案里没有）。缺省一律表示「没有」。 ----

    /** 电商平台（淘宝 / 天猫）。 */
    const val EXTRA_PLATFORM = "platform"

    /** 商品名称。 */
    const val EXTRA_GOODS_NAME = "goodsName"

    /** 到站 / 入站时间（毫秒）。`0` 表示没有。 */
    const val EXTRA_ARRIVAL_AT = "arrivalAt"

    /** 运单动态（宿主 `lastLogisticDetail`，如「已发往【上海转运中心】」）。 */
    const val EXTRA_LOGISTICS_DETAIL = "logisticsDetail"

    /** 驿站营业时间（宿主 `packageStation.officeTime`）。 */
    const val EXTRA_STATION_HOURS = "stationHours"

    /**
     * 驿站坐标（宿主 `packageStation.stationLat` / `stationLng`，WGS84 度）。
     *
     * Intent 的 `putExtra` 收不了 null，"没有"用 `NaN` 表示 —— 它同时也不是合法坐标
     * （0 是几内亚湾上的一个点，当有效值会让「最近的驿站」算到非洲去，见 [EXTRA_STATION_LAT] 的读取侧）。
     */
    const val EXTRA_STATION_LAT = "stationLat"
    const val EXTRA_STATION_LNG = "stationLng"

    // ---- 轨迹接口（queryalltrace）补出来的字段 ----

    /** 驿站完整地址（轨迹末条的 `address`，只在到站件上有）。与驿站名 `station` 分开。 */
    const val EXTRA_STATION_ADDRESS = "stationAddress"

    /** 商品图 URL。 */
    const val EXTRA_GOODS_IMAGE = "goodsImage"

    /**
     * 全轨迹，`ExpressTraceCodec` 编码后的字符串。
     *
     * 不拆成多个 extra（`putStringArrayListExtra` 之类）：轨迹条数不定，拆开就得在两端
     * 各维护一套拼接规则；一个 JSON 字符串只有一个编码入口、一个解码入口。
     */
    const val EXTRA_TRACE = "trace"

    // ---- 只有通知侧给得出的字段 ----

    /** 收件手机号尾号（通知里的「手机尾号1234」）。 */
    const val EXTRA_PHONE_TAIL = "phoneTail"

    /** 记录来源，取值是 [io.github.YGHFv.ReaPressExtend.core.ExpressOrigin] 的名字。 */
    const val EXTRA_ORIGIN = "origin"

    /** [ACTION_TRACE_REQUEST] 的载荷：要拉全轨迹的运单号。 */
    const val EXTRA_TRACE_TRACKING = "traceTracking"

    /** [ACTION_COOKIE_SYNC] 的载荷：拼好的 cookie 请求头（`name=value; …`）。 */
    const val EXTRA_COOKIE = "traceCookie"

    /** [EXTRA_COOKIE] 的随行：宿主 WebView 的真实 UA（让请求画像与登录态一致）。 */
    const val EXTRA_COOKIE_UA = "traceCookieUa"

    /**
     * 登录态**取不到**时的人话原因（宿主回执）。
     *
     * 有它之前，`sendCookieSync` 读不到 cookie 时是**静默 return** —— 模块侧看到的现象
     * 就是「什么都没发生」：没有 `cookie synced`、没有轨迹、没有报错，和「菜鸟没刷新过首页」
     * 长得一模一样。用户报「这台设备怎么都获取不了」时，这条链路根本无从观测（2026-09-26）。
     *
     * 所以现在读不到也发一条广播，只带这个键、不带 [EXTRA_COOKIE]：接收侧据此记一条明确
     * 日志，把「宿主没有登录态」和「广播没送到」区分开。**键可以加，语义不能改** ——
     * 接收侧见到 `EXTRA_COOKIE` 为空即视为回执，不做任何缓存写入。
     */
    const val EXTRA_COOKIE_ERROR = "traceCookieError"

    // ---- 身份码（[ACTION_IDENTITY_SYNC]）----

    /**
     * 身份码本体。
     *
     * ⚠️ **它等于一次取件凭据**：宿主侧与模块侧都**只让它进内存**，两端都不落盘、不写日志
     * （日志里只出现长度与来源）。唯一的出口是弹窗上那串要念给店员听的数字。
     */
    const val EXTRA_IDENTITY_CODE = "identityCodeValue"

    /**
     * 身份码有效期（epoch ms）。`0` 表示宿主没给 —— 那时一律当作可用。
     *
     * 有它才能做「缓存里那份还能不能用」的判断（用户的码是服务端定时刷的，拿一份过期的
     * 去柜台等于没有）。
     */
    const val EXTRA_IDENTITY_EXPIRE_AT = "identityExpireAt"

    /** 这条是不是离线码（宿主 `IdentityBean.isOffLine`）。只影响界面上那一行小字。 */
    const val EXTRA_IDENTITY_OFFLINE = "identityOffline"

    /** 这条从哪来：`host` = 菜鸟现取 / `cache` = 菜鸟进程内缓存。**只用于日志**。 */
    const val EXTRA_IDENTITY_PROVENANCE = "identityProvenance"

    /**
     * 取不到原因（宿主回执）。语义与 [EXTRA_COOKIE_ERROR] 完全相同：
     * **见到它即视为回执**，接收侧据此区分「宿主说它取不到」与「广播没送到」。
     */
    const val EXTRA_IDENTITY_ERROR = "identityError"

    /**
     * 索取通道的状态播报（宿主 → 模块）。
     *
     * 与上面几条**互斥**：带它的那条广播既不表示「有码」也不表示「取不到」，只表示
     * 「宿主进程里的索取通道已经立好了」。模块侧的处理只有一件事：记一行日志。
     * 它是「唤醒销到底叫醒菜鸟没有」这件事的**唯一可观测判据**（见 [ACTION_IDENTITY_SYNC] 的说明）。
     */
    const val EXTRA_IDENTITY_BRIDGE_STATUS = "identityBridgeStatus"

    /**
     * [ACTION_HOST_QUERY_REPORT] 的载荷：宿主自查本地包裹表的结论（一句人话）。
     *
     * 只描述「自查这个动作」本身 —— 查到几行、失败几次，**不带运单号、不带包裹内容**。
     * 包裹数据走它原来那条路（[ACTION_ENRICH]），这里的用途只有一个：让「自查跑没跑成」
     * 这件事在模块侧留下可读的痕迹。
     */
    const val EXTRA_HOST_QUERY_REPORT = "hostQueryReport"

    /**
     * [ACTION_TRACE_REQUEST] 的发送方凭证（signature 级权限，模块 APK 声明 + 自持）。
     * 宿主进程注册 receiver 时挂上它 —— 字符串契约，两侧都要原样一致。
     */
    const val PERMISSION_TRACE_REQUEST = "io.github.YGHFv.ReaPressExtend.permission.TRACE_REQUEST"

    /** 宿主（菜鸟）包名。广播请求的寻址目标 —— relay 是字符串契约层，这里只此一处。 */
    const val HOST_PACKAGE = "com.cainiao.wireless"

    /**
     * 淘宝包名 —— 第二个「宿主」。
     *
     * 它不是包裹数据的来源，而是**登录态的备用来源**：菜鸟没绑淘宝账号时，它的 WebView
     * cookie 库里就没有 `.taobao.com` 域，而淘宝 App 里必然有（见
     * [io.github.YGHFv.ReaPressExtend.hook.TaobaoCredentialHook]）。
     *
     * 需要用户在 LSPosed 里把本模块的作用域勾上淘宝 —— 没勾就不会有 `onPackageReady`。
     */
    const val TAOBAO_PACKAGE = "com.taobao.taobao"

    /**
     * 索要登录态时**要通知的所有宿主**。
     *
     * 广播的寻址是「包名 + 注册的 action」，一个 Intent 只能落到一个包；而哪一边有凭据
     * 事先并不知道（菜鸟有、淘宝没有，或反过来）。所以两边都发 —— 收到的那一侧会回
     * [ACTION_COOKIE_SYNC]，模块侧的后到覆盖先到，重复投递无害。
     */
    val CREDENTIAL_PACKAGES = listOf(HOST_PACKAGE, TAOBAO_PACKAGE)

    /**
     * 模块自身来源标记。
     *
     * 模块自己发的通知也会经过 `NotificationManagerService.enqueueNotificationInternal`，
     * 不排除就会「拦截 → 重发 → 又被拦截」无限循环。这个标记是三重防护的第一重
     * （另外两重是包名判定和 ThreadLocal 重入锁）。
     */
    const val EXTRA_MODULE_ORIGIN = "io.github.YGHFv.ReaPressExtend.extra.MODULE_ORIGIN"
}
