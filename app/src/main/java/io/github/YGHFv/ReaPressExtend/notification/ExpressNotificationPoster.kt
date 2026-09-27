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
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay

/** 发替换通知。通知 ID 用 [ExpressRecord.dedupeKey] 的哈希掩 16 位加 [BASE_ID] 派生，同一包裹的状态更新覆盖同一条而非堆叠；Android 13+ 没有 `POST_NOTIFICATIONS` 时只记日志和审计、不弹窗。 */
object ExpressNotificationPoster {

    private const val TAG = "ReaPress"

    const val CHANNEL_ID = "reapress_express"
    const val CHANNEL_NAME = "快递通知"

    private const val BASE_ID = 5100

    private val CONTENT_INTENT_CLASS = "io.github.YGHFv.ReaPressExtend.ui.ExpressMainActivity"

    /** intentUri 是可落盘快照（跨重启还能用）；intentToken 是 system_server 侧的令牌寄存句柄，只活到设备重启。 */
    fun post(
        context: Context,
        record: ExpressRecord,
        contentIntent: PendingIntent? = null,
        intentUri: String? = null,
        intentToken: String? = null,
    ): Boolean {
        if (!hasPermission(context)) {
            ModuleAndroidLog.error(
                TAG,
                "POST_NOTIFICATIONS not granted — replacement notification dropped " +
                    "for key=${record.dedupeKey}",
            )
            ExpressNotificationLog.record(
                context, record, delivered = false, detail = "未授予通知权限",
                contentIntent = contentIntent, intentUri = intentUri, intentToken = intentToken,
            )
            return false
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        return runCatching {
            ensureChannel(manager)

            val title = ExpressFormatter.title(record)
            val summary = ExpressFormatter.summaryLine(record) ?: ExpressFormatter.body(record)
            val body = ExpressFormatter.body(record)
            val focus = FocusNotificationCapability.probe(context)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder
                .setSmallIcon(R.drawable.ic_notification_express)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(record.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis())
                .setOnlyAlertOnce(record.status != ExpressStatus.READY_FOR_PICKUP)
                .setContentIntent(contentIntent(context, record))

            // 递归防护：给通知打上模块来源标记，system_server 的 hook 见到就放行，
            // 否则「拦截 → 重发 → 又被拦截」会无限循环。
            builder.extras.putBoolean(ExpressRelay.EXTRA_MODULE_ORIGIN, true)

            if (focus.canAttachFocusParam) {
                runCatching {
                    builder.extras.putString(
                        MiuiFocusPayload.EXTRA_PARAM,
                        MiuiFocusPayload.build(record, focus.protocol),
                    )
                }.onFailure {
                    // 参数有问题不该让整条通知发不出去：丢掉焦点参数，退回普通通知。
                    ModuleAndroidLog.error(TAG, "focus param build failed, fallback to plain", it)
                }
            }

            manager.notify(notificationId(record), builder.build())
            ModuleAndroidLog.legacy(
                TAG,
                "replacement notification posted key=${record.dedupeKey} title=$title " +
                    "focus=${focus.protocol}/${focus.canShowFocus}",
            )
            ExpressNotificationLog.record(
                context, record, delivered = true,
                contentIntent = contentIntent, intentUri = intentUri, intentToken = intentToken,
            )
            true
        }.getOrElse {
            ModuleAndroidLog.error(TAG, "post replacement notification failed key=${record.dedupeKey}", it)
            ExpressNotificationLog.record(
                context, record, delivered = false, detail = it.message.orEmpty(),
                contentIntent = contentIntent, intentUri = intentUri, intentToken = intentToken,
            )
            false
        }
    }

    fun notificationId(record: ExpressRecord): Int =
        BASE_ID + (record.dedupeKey.hashCode() and 0xFFFF)

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun areNotificationsEnabled(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.areNotificationsEnabled() == true
    }.getOrDefault(false)

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun contentIntent(context: Context, record: ExpressRecord): PendingIntent? = runCatching {
        val intent = Intent().apply {
            setClassName(ExpressRelay.MODULE_PACKAGE, CONTENT_INTENT_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ExpressRelay.EXTRA_TRACKING, record.trackingNumber)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        // requestCode 用通知 ID：每条通知有独立的 PendingIntent，不会互相覆盖 extra。
        PendingIntent.getActivity(context, notificationId(record), intent, flags)
    }.getOrNull()
}
