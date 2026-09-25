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

    /** 投递动作。 */
    const val ACTION_DELIVER = "io.github.YGHFv.ReaPressExtend.DELIVER_EXPRESS"

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

    /**
     * 模块自身来源标记。
     *
     * 模块自己发的通知也会经过 `NotificationManagerService.enqueueNotificationInternal`，
     * 不排除就会「拦截 → 重发 → 又被拦截」无限循环。这个标记是三重防护的第一重
     * （另外两重是包名判定和 ThreadLocal 重入锁）。
     */
    const val EXTRA_MODULE_ORIGIN = "io.github.YGHFv.ReaPressExtend.extra.MODULE_ORIGIN"
}
