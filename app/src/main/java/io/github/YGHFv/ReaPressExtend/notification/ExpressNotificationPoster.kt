package io.github.YGHFv.ReaPressExtend.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.YGHFv.ReaPressExtend.R
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay

/**
 * 发替换通知。
 *
 * ## 通知 ID 的取法
 *
 * 用 [ExpressRecord.dedupeKey] 的哈希派生 ID，同一个包裹的状态更新会**覆盖**同一条通知，
 * 而不是堆一列。这正是「替换」相对「原样放行」的价值：菜鸟的待取件提醒每小时重复一次，
 * 放行的话通知栏会堆满，替换后始终只有一条最新的。
 *
 * 哈希后掩到 16 位再偏移一个基数：避免与模块自己的其他通知（前台服务等）撞 ID，
 * 也避免负数（`NotificationManager.notify` 接受负数但不推荐）。
 *
 * ## 权限
 *
 * Android 13+ 没有 `POST_NOTIFICATIONS` 就发不出去。这里**不弹窗**（Receiver 里弹不出来），
 * 只记日志并落一条审计 —— 用户会在模块界面上看到权限提示和一条「发送失败」记录。
 */
object ExpressNotificationPoster {

    private const val TAG = "ReaPress"

    /** 渠道 ID。与 reamicro 的做法一致：渠道懒创建，不依赖 Application 初始化。 */
    const val CHANNEL_ID = "reapress_express"
    const val CHANNEL_NAME = "快递通知"

    /** 通知 ID 基数。掩码后加它，避免与模块其他通知撞号。 */
    private const val BASE_ID = 5100

    /** 点击通知打开的界面。 */
    private val CONTENT_INTENT_CLASS = "io.github.YGHFv.ReaPressExtend.ui.ExpressMainActivity"

    fun post(context: Context, record: ExpressRecord): Boolean {
        if (!hasPermission(context)) {
            ModuleAndroidLog.error(
                TAG,
                "POST_NOTIFICATIONS not granted — replacement notification dropped " +
                    "for key=${record.dedupeKey}",
            )
            ExpressNotificationLog.record(context, record, delivered = false, detail = "未授予通知权限")
            return false
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        return runCatching {
            ensureChannel(manager)

            val title = ExpressFormatter.title(record)
            val body = ExpressFormatter.body(record)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder
                .setSmallIcon(R.drawable.ic_notification_express)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                // 同一个包裹反复更新时不要每次都响铃 —— 状态推进才值得提醒。
                .setOnlyAlertOnce(record.status != io.github.YGHFv.ReaPressExtend.core.ExpressStatus.READY_FOR_PICKUP)
                .setContentIntent(contentIntent(context, record))

            // 递归防护第一重：给通知打上模块来源标记。
            // system_server 侧的 hook 见到这个 extra 会直接放行，否则
            // 「拦截 → 重发 → 又被拦截」会无限循环。
            builder.extras.putBoolean(ExpressRelay.EXTRA_MODULE_ORIGIN, true)

            manager.notify(notificationId(record), builder.build())
            ModuleAndroidLog.legacy(TAG, "replacement notification posted key=${record.dedupeKey} title=$title")
            ExpressNotificationLog.record(context, record, delivered = true)
            true
        }.getOrElse {
            ModuleAndroidLog.error(TAG, "post replacement notification failed key=${record.dedupeKey}", it)
            ExpressNotificationLog.record(context, record, delivered = false, detail = it.message.orEmpty())
            false
        }
    }

    /**
     * 通知 ID。
     *
     * `dedupeKey.hashCode()` 可能为负，掩到 16 位保证非负；再加基数避开模块其他通知。
     */
    fun notificationId(record: ExpressRecord): Int =
        BASE_ID + (record.dedupeKey.hashCode() and 0xFFFF)

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 系统层面通知是否被关闭（渠道被用户关掉的情况）。 */
    fun areNotificationsEnabled(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.areNotificationsEnabled() == true
    }.getOrDefault(false)

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // createNotificationChannel 是幂等的，重复调用不会重置用户的渠道设置。
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // DEFAULT 而不是 HIGH：快递通知值得提醒，但不该像来电那样打断。
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun contentIntent(context: Context, record: ExpressRecord): PendingIntent? = runCatching {
        val intent = Intent().apply {
            setClassName(ExpressRelay.MODULE_PACKAGE, CONTENT_INTENT_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 带上运单号，界面可以定位到这一条。
            putExtra(ExpressRelay.EXTRA_TRACKING, record.trackingNumber)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        // requestCode 用通知 ID：每条通知有独立的 PendingIntent，不会互相覆盖 extra。
        PendingIntent.getActivity(context, notificationId(record), intent, flags)
    }.getOrNull()
}
