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

    // ---- 只有通知侧给得出的字段 ----

    /** 收件手机号尾号（通知里的「手机尾号1234」）。 */
    const val EXTRA_PHONE_TAIL = "phoneTail"

    /** 记录来源，取值是 [io.github.YGHFv.ReaPressExtend.core.ExpressOrigin] 的名字。 */
    const val EXTRA_ORIGIN = "origin"

    /**
     * 模块自身来源标记。
     *
     * 模块自己发的通知也会经过 `NotificationManagerService.enqueueNotificationInternal`，
     * 不排除就会「拦截 → 重发 → 又被拦截」无限循环。这个标记是三重防护的第一重
     * （另外两重是包名判定和 ThreadLocal 重入锁）。
     */
    const val EXTRA_MODULE_ORIGIN = "io.github.YGHFv.ReaPressExtend.extra.MODULE_ORIGIN"
}
