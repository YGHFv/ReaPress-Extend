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
     * 宿主进程 → 模块进程：同步淘宝登录态 cookie。
     *
     * 轨迹拉取收拢到模块进程后（见 [ACTION_TRACE_REQUEST] 的注释），模块进程要能自己
     * 发 MTOP 请求 —— 而 cookie 只存在菜鸟的私有目录里，只能由 hook 侧读出来送过来。
     *
     * ## 「cookie 不外传」原则的修订（2026-09-26）
     *
     * 最初「请求放菜鸟进程发」的理由之一是 cookie 不离开宿主。用户拍板要「菜鸟不在后台
     * 也能点详情静默获取」（对照 ExpressAssistant 的行为），这条路只有 cookie 出宿主一条走法。
     * 收窄后的边界：cookie 只进**模块进程内存**（[TraceCookieCache]，不落盘、不进日志），
     * 模块进程死了就丢，等宿主下次投递再同步。广播本身用显式组件寻址，第三方截不到 extras。
     */
    const val ACTION_COOKIE_SYNC = "io.github.YGHFv.ReaPressExtend.COOKIE_SYNC"

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
     * [ACTION_TRACE_REQUEST] 的发送方凭证（signature 级权限，模块 APK 声明 + 自持）。
     * 宿主进程注册 receiver 时挂上它 —— 字符串契约，两侧都要原样一致。
     */
    const val PERMISSION_TRACE_REQUEST = "io.github.YGHFv.ReaPressExtend.permission.TRACE_REQUEST"

    /** 宿主（菜鸟）包名。广播请求的寻址目标 —— relay 是字符串契约层，这里只此一处。 */
    const val HOST_PACKAGE = "com.cainiao.wireless"

    /**
     * 模块自身来源标记。
     *
     * 模块自己发的通知也会经过 `NotificationManagerService.enqueueNotificationInternal`，
     * 不排除就会「拦截 → 重发 → 又被拦截」无限循环。这个标记是三重防护的第一重
     * （另外两重是包名判定和 ThreadLocal 重入锁）。
     */
    const val EXTRA_MODULE_ORIGIN = "io.github.YGHFv.ReaPressExtend.extra.MODULE_ORIGIN"
}
