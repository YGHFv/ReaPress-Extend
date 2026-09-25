package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 被注入进程 → 模块 App 进程的事件投递。
 *
 * ## 两个来源，同一份协议
 *
 * - [send]：system_server 拦到通知后投递（`ACTION_DELIVER`）
 * - [sendEnrichment]：宿主 App 进程富化出包裹后投递（`ACTION_ENRICH`）
 *
 * 两者的 Context 来源完全不同，这是这个类里唯一需要分情况的地方：
 * system_server 没有现成 Context（见 [SystemContextHolder]），而宿主进程里
 * `ActivityThread.currentApplication()` 直接就能拿到（见 [HostContextHolder]）。
 * Intent 的构造、显式组件寻址、flag 组合则完全共用 —— 协议只有一份，
 * 两个发送端如果各写一份 `putExtra` 迟早会漏字段。
 *
 * ## 为什么是广播
 *
 * - `RemotePreferences` 是给设置用的（hooked 侧只读），不适合流式事件
 * - `XposedService` 的 binder 只递到模块 App 进程，别的进程里拿不到
 * - 广播 + **显式组件** + `FLAG_INCLUDE_STOPPED_PACKAGES` 是唯一能在「模块 App 从没启动过」
 *   的情况下把事件送到的通道。模块装完长期处于 stopped 状态，这个 flag 是必需的。
 */
internal object ExpressRelaySender {

    /** 取不到 context 时只记一次日志，避免刷屏。 */
    @Volatile private var contextFailureLogged = false

    /** system_server 侧：拦到通知之后投递。 */
    fun send(record: ExpressRecord, thisObject: Any? = null) {
        // 运行期从 NMS 实例取 context 并缓存 —— 这是 system_server 里唯一可靠的途径。
        SystemContextHolder.upgrade(thisObject)
        val context = SystemContextHolder.acquire() ?: return logContextFailure()
        deliver(context, record, ExpressRelay.ACTION_DELIVER)
    }

    /** 宿主 App 进程侧：富化出包裹之后投递。 */
    fun sendEnrichment(record: ExpressRecord, context: Context?) {
        val resolved = context ?: HostContextHolder.acquire()
        if (resolved == null) return logContextFailure()
        deliver(resolved, record, ExpressRelay.ACTION_ENRICH)
    }

    private fun logContextFailure() {
        if (!contextFailureLogged) {
            contextFailureLogged = true
            XposedBridge.logError("cannot obtain context for relay — events will be dropped")
        }
    }

    private fun deliver(context: Context, record: ExpressRecord, action: String) {
        runCatching {
            val intent = buildIntent(record, action)
            // 显式指定组件：模块 App 的接收器。隐式广播在 Android 8+ 受限，且我们本来就知道
            // 目标是谁 —— 显式投递既可靠又不会被别的应用截获。
            intent.setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            // 投到**发起方所在用户**。system_server 里用 Process.myUserHandle()；
            // 宿主进程里用自己进程的 user —— 两者语义一致，避免写死 UserHandle.of(0)
            // （后者在部分 SDK 上是 @hide，编译期过不去）。
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())

            XposedBridge.log(
                "relayed to module: action=${action.substringAfterLast('.')} " +
                    "pkg=${record.sourcePackage} key=${record.dedupeKey} status=${record.status}",
            )
        }.onFailure {
            // 投递失败只记日志，绝不让异常冒到宿主/系统的调用栈上。
            XposedBridge.logError("relay failed", it)
        }
    }

    private fun buildIntent(record: ExpressRecord, action: String): Intent =
        Intent(action).apply {
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
            putExtra(ExpressRelay.EXTRA_PLATFORM, record.platform)
            putExtra(ExpressRelay.EXTRA_GOODS_NAME, record.goodsName)
            // 0 表示「没有」：putExtra 收不了 null，而时间戳本身不可能是 0。
            putExtra(ExpressRelay.EXTRA_ARRIVAL_AT, record.arrivalAt ?: 0L)
            putExtra(ExpressRelay.EXTRA_LOGISTICS_DETAIL, record.logisticsDetail)
            putExtra(ExpressRelay.EXTRA_STATION_HOURS, record.stationHours)
            putExtra(ExpressRelay.EXTRA_PHONE_TAIL, record.phoneTail)
            putExtra(ExpressRelay.EXTRA_ORIGIN, record.origin.name)
        }
}
