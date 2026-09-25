package io.github.YGHFv.ReaPressExtend.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore

/**
 * 接收 system_server 投来的拦截事件，在本进程发替换通知。
 *
 * ## 为什么必须由模块自己的进程发
 *
 * `POST_NOTIFICATIONS` 是**按应用**授予的，通知渠道也归发起的应用所有。从 system_server
 * 或以宿主 App 的身份发，通知会挂在别人名下、用别人的渠道和图标 —— 用户看到的还是原来那个
 * App 的通知，替换就失去意义。所以这里绕一圈：system_server 判定 → 广播 → 模块进程发。
 *
 * ## 为什么在 onReceive 里同步发通知
 *
 * `onReceive` 有 10 秒上限，发一条通知远不到。开 Service 或 goAsync 反而引入新的失败点
 * （后台启动限制、进程被冻结）。这里就是「收广播 → 发通知」两行，做完即返回。
 */
class ExpressRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // 日志缓冲要绑定落盘位置，否则模块进程的日志重启就丢。
        ModuleLogBuffer.attach(app)

        when (intent.action) {
            ExpressRelay.ACTION_DELIVER -> handleDeliver(app, intent)
            WatchdogReporter.ACTION_WATCHDOG_STATUS -> {
                WatchdogReporter.persistLocally(app, intent)
                val installed = intent.getBooleanExtra(WatchdogReporter.EXTRA_INSTALLED, false)
                ModuleAndroidLog.legacy(LOG_TAG, "watchdog state from system_server: installed=$installed")
            }
            else -> ModuleAndroidLog.legacy(LOG_TAG, "ignored action=${intent.action}")
        }
    }

    private fun handleDeliver(app: Context, intent: Intent) {
        val record = parseRecord(intent) ?: run {
            ModuleAndroidLog.error(LOG_TAG, "malformed relay intent, dropped")
            return
        }
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "relay received pkg=${record.sourcePackage} key=${record.dedupeKey} " +
                "status=${record.status} conf=${record.confidence}",
        )
        // 先落结构化记录再发通知：通知可能因权限/系统限制发不出去，但包裹信息要留住 ——
        // 首页靠它聚合展示，丢一次用户就少一个包裹。
        ExpressRecordStore.upsert(app, record)
        ExpressNotificationPoster.post(app, record)
    }

    private fun parseRecord(intent: Intent): ExpressRecord? {
        val sourcePackage = intent.getStringExtra(ExpressRelay.EXTRA_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() } ?: return null
        val text = intent.getStringExtra(ExpressRelay.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return null

        return ExpressRecord(
            sourcePackage = sourcePackage,
            rawText = text,
            trackingNumber = intent.getStringExtra(ExpressRelay.EXTRA_TRACKING)?.takeIf { it.isNotBlank() },
            courier = runCatching {
                Courier.valueOf(intent.getStringExtra(ExpressRelay.EXTRA_COURIER).orEmpty())
            }.getOrDefault(Courier.UNKNOWN),
            pickupCode = intent.getStringExtra(ExpressRelay.EXTRA_PICKUP_CODE)?.takeIf { it.isNotBlank() },
            station = intent.getStringExtra(ExpressRelay.EXTRA_STATION)?.takeIf { it.isNotBlank() },
            status = runCatching {
                ExpressStatus.valueOf(intent.getStringExtra(ExpressRelay.EXTRA_STATUS).orEmpty())
            }.getOrDefault(ExpressStatus.UNKNOWN),
            title = intent.getStringExtra(ExpressRelay.EXTRA_TITLE),
            matchedKeywords = intent.getStringArrayExtra(ExpressRelay.EXTRA_KEYWORDS)?.toList().orEmpty(),
            confidence = intent.getIntExtra(ExpressRelay.EXTRA_CONFIDENCE, 0),
            timestamp = intent.getLongExtra(ExpressRelay.EXTRA_TIMESTAMP, 0L),
        )
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
    }
}
