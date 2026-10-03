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

package io.github.YGHFv.ReaPressExtend.noroot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.core.ExpressClassifier
import io.github.YGHFv.ReaPressExtend.core.ExpressParser
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.ExpressTextExtractor
import io.github.YGHFv.ReaPressExtend.core.NoRootPlan
import io.github.YGHFv.ReaPressExtend.hook.NotificationExtrasReader
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier
import io.github.YGHFv.ReaPressExtend.notification.ExpressDeliveryLedger
import io.github.YGHFv.ReaPressExtend.notification.DeliveryLedger
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.relay.ModuleTraceFetcher

/**
 * 免 root 方案的通知来源（通知使用权），与 hook 走同一条链路；双链路同过 [ExpressDeliveryLedger] 去重。
 * 「拦截并替换」在这里退化为先出现再撤销，失败只记审计、绝不假装拦住了。身份码与宿主自查在这条路上做不到。
 */
class ExpressNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 最外层 runCatching 是硬要求：抛异常会变成监听服务不断被重启。
        runCatching { handle(sbn) }.onFailure {
            ModuleAndroidLog.error(TAG, "listener handle failed", it)
        }
    }

    private fun handle(sbn: StatusBarNotification?) {
        NoRootListenerState.markCallback()

        val notification = sbn?.notification ?: return
        if (NotificationExtrasReader.isModuleOrigin(notification)) return

        val app = applicationContext
        val pkg = sbn.packageName ?: return
        ModuleLogBuffer.attach(app)

        val settings = ExpressSettings.read(app)
        if (!NoRootPlan.isListenerCollecting(
                listenerEnabled = settings.noRootListener,
                accessGranted = true,
                modeOff = !settings.isEnabled,
            )
        ) {
            return
        }

        val rule = settings.toRule()
        if (!ExpressClassifier.isSourceAllowed(pkg, rule)) return

        val extras = NotificationExtrasReader.read(notification)
        val title = ExpressTextExtractor.extractTitle(extras)
        val text = ExpressTextExtractor.extractFullText(extras)
        if (text.isBlank()) return

        val verdict = ExpressClassifier.classify(pkg, text, rule)
        // 时刻用通知自己的 postTime：重连时系统重放已有通知，用 now 会把历史件记成刚刚发生。
        val record = ExpressParser.parse(pkg, title, text, verdict, sbn.postTime)

        if (BuildConfig.OBSERVE_ONLY) {
            audit(
                app,
                if (verdict.isExpress) "observe-hit" else "observe-miss",
                pkg,
                record,
                "conf=${verdict.confidence}",
            )
            return
        }

        // 用户勾了「直接吞掉这一类」：只留一条拦截审计，与 hook 侧同一条规矩。
        val category = verdict.interceptedCategory
        if (category != null) {
            ExpressNotificationLog.recordIntercepted(
                app,
                record,
                category = category.name,
                contentIntent = contentIntentOf(notification),
                intentUri = intentUriOf(notification),
            )
            audit(app, "captured", pkg, record, "intercepted=${category.name}")
            suppress(sbn)
            return
        }

        if (!verdict.isExpress) return

        val changed = ExpressRecordStore.upsert(app, record)
        audit(
            app,
            "captured",
            pkg,
            record,
            "conf=${record.confidence} changed=$changed",
        )
        // 免 root 下没有别的东西会替我们发这条广播。
        if (changed) ExpressChangeNotifier.notify(app)

        // 双链路去重：hook 可能已经为同一条通知发过一次（反过来也一样）。
        val delivery = ExpressDeliveryLedger.deliver(record) {
            ExpressNotificationPoster.post(
                app,
                record,
                contentIntent = contentIntentOf(notification),
                intentUri = intentUriOf(notification),
            )
        }
        if (settings.isInterceptMode) {
            // 旧成功可能已经被用户清除；重复事件只在替代通知仍存在时撤原通知。
            if (delivery == DeliveryLedger.Result.POSTED ||
                (delivery == DeliveryLedger.Result.ALREADY_POSTED &&
                    ExpressNotificationPoster.isReplacementActive(app, record))
            ) suppress(sbn)
        }

        if (changed) {
            if (settings.isTraceAutoFetch) {
                ModuleTraceFetcher.maybeAutoFetch(app, record)
            } else {
                ModuleTraceFetcher.maybeBackstopFetch(app, record)
            }
        }
    }

    /** 撤掉原通知（免 root 版「拦截」）。失败只记日志：ROM 拒撤或通知已被清掉都不是错误状态。 */
    private fun suppress(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        runCatching { cancelNotification(key) }.onFailure {
            ModuleAndroidLog.error(TAG, "cancelNotification failed pkg=${sbn.packageName}", it)
        }
    }

    /** 原通知的点击跳转令牌（只进审计记录）。 */
    private fun contentIntentOf(notification: android.app.Notification): PendingIntent? =
        runCatching { notification.contentIntent }.getOrNull()

    /** 可落盘快照。不用 hook 侧读法：getIntent() 要 signature 级权限必被拒，只取公开 API 的 creatorPackage。 */
    private fun intentUriOf(notification: android.app.Notification): String? = runCatching {
        contentIntentOf(notification)?.creatorPackage
            ?.takeIf { it.isNotBlank() }
            ?.let { Intent().setPackage(it).toUri(Intent.URI_INTENT_SCHEME) }
    }.getOrNull()

    /** 采集留痕：只有包名 / 置信度 / 状态，没有原文。 */
    private fun audit(
        app: Context,
        action: String,
        pkg: String,
        record: ExpressRecord,
        extra: String,
    ) {
        ModuleAndroidLog.legacy(
            TAG,
            "noroot $action pkg=$pkg key=${record.dedupeKey} " +
                "status=${record.status.takeIf { it != ExpressStatus.UNKNOWN } ?: "-"} $extra",
        )
    }

    /** 连上时记一行（也是被动失联后重连的观测点）；重放的所有写入均幂等。 */
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            ModuleLogBuffer.attach(this)
            ModuleAndroidLog.legacy(TAG, "noroot listener connected")
        }
        NoRootListenerState.markCallback()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NoRootListenerState.markDisconnected()
        runCatching { ModuleAndroidLog.legacy(TAG, "noroot listener disconnected") }
    }

    private companion object {
        const val TAG = "ReaPress"
    }
}
