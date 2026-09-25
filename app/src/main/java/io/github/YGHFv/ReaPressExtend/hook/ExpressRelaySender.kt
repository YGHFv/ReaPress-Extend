package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers

/**
 * system_server → 模块 App 进程的事件投递。
 *
 * ## 为什么是广播
 *
 * - `RemotePreferences` 是给设置用的（hooked 侧只读），不适合流式事件
 * - `XposedService` 的 binder 只递到模块 App 进程，system_server 里拿不到
 * - 广播 + **显式组件** + `FLAG_INCLUDE_STOPPED_PACKAGES` 是唯一能在「模块 App 从没启动过」
 *   的情况下把事件送到的通道。模块装完长期处于 stopped 状态，这个 flag 是必需的。
 *
 * ## Context 来源
 *
 * 优先用 `param.thisObject`（NotificationManagerService 实例）的 `mContext` 字段 —— 最可靠。
 * 取到后通过 [SystemContextHolder.upgrade] 缓存起来，后续事件不必再反射。
 * 详见 [SystemContextHolder] 的注释。
 */
internal object ExpressRelaySender {

    /** 取不到 context 时只记一次日志，避免刷屏。 */
    @Volatile private var contextFailureLogged = false

    fun send(record: ExpressRecord, thisObject: Any? = null) {
        runCatching {
            // 运行期从 NMS 实例取 context 并缓存 —— 这是 system_server 里唯一可靠的途径。
            SystemContextHolder.upgrade(thisObject)
            val context = SystemContextHolder.acquire()
            if (context == null) {
                if (!contextFailureLogged) {
                    contextFailureLogged = true
                    XposedBridge.logError(
                        "cannot obtain system context for relay — events will be dropped",
                    )
                }
                return
            }

            val intent = buildIntent(record)
            // 显式指定组件：模块 App 的接收器。隐式广播在 Android 8+ 受限，且我们本来就知道
            // 目标是谁 —— 显式投递既可靠又不会被别的应用截获。
            intent.setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            // 投到 system_server 自己所在的用户。用 Process.myUserHandle() 而不是
            // UserHandle.of(0)（后者在部分 SDK 上是 @hide，编译期过不去）。
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())

            XposedBridge.log(
                "relayed to module: pkg=${record.sourcePackage} key=${record.dedupeKey} " +
                    "status=${record.status}",
            )
        }.onFailure {
            // 投递失败只记日志，绝不让异常冒到 NMS 调用栈上。
            XposedBridge.logError("relay failed", it)
        }
    }

    private fun buildIntent(record: ExpressRecord): Intent = Intent(ExpressRelay.ACTION_DELIVER).apply {
        // 模块 App 可能处于 stopped 状态（装完没打开过），没有这个 flag 收不到广播。
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        // 提升优先级：快递通知有时效性，别排在后台广播队列末尾。
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        putExtra(ExpressRelay.EXTRA_SOURCE_PACKAGE, record.sourcePackage)
        putExtra(ExpressRelay.EXTRA_TITLE, record.title)
        putExtra(ExpressRelay.EXTRA_TEXT, record.rawText)
        putExtra(ExpressRelay.EXTRA_TRACKING, record.trackingNumber)
        putExtra(ExpressRelay.EXTRA_COURIER, record.courier.name)
        putExtra(ExpressRelay.EXTRA_PICKUP_CODE, record.pickupCode)
        putExtra(ExpressRelay.EXTRA_STATION, record.station)
        putExtra(ExpressRelay.EXTRA_STATUS, record.status.name)
        putExtra(ExpressRelay.EXTRA_CONFIDENCE, record.confidence)
        putExtra(ExpressRelay.EXTRA_KEYWORDS, record.matchedKeywords.toTypedArray())
        putExtra(ExpressRelay.EXTRA_TIMESTAMP, record.timestamp)
    }

    /**
     * 从 NotificationManagerService 实例的 `mContext` 取。
     *
     * 字段名在 AOSP 各版本稳定；取不到就返回 null 走 [SystemContextHolder]。
     */
    private fun fromNotificationManagerService(thisObject: Any?): Context? {
        if (thisObject == null) return null
        return runCatching {
            XposedHelpers.getObjectField(thisObject, "mContext") as? Context
        }.getOrNull()
    }
}
