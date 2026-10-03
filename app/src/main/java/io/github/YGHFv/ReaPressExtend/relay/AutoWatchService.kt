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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.YGHFv.ReaPressExtend.R
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.WatchSchedule
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.ui.ExpressMainActivity
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自动轮查。默认关——常驻前台通知是代价。节奏规则在 [WatchSchedule]（件间默认 3min±1、
 * 一轮完等 30min、夜间 22:00–08:00 暂停）；等待切成切片并每片重读设置，下次动手时刻与本轮
 * 已问单号落盘在 [WatchState]，跨进程重启成立。不拉新包裹（包裹表在菜鸟本地库里，模块拿不到）；
 * 免 root 下每轮起点顺带跑一次 [CainiaoDirectFetcher]，只覆盖淘宝/天猫在途订单，
 * 拼多多与别人寄来的件只能靠通知文案。
 */
class AutoWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val loopMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ModuleLogBuffer.attach(this)
        // 轮查靠 cookie 直连（不经宿主进程），进程一起来就得把落盘的登录态读回来。
        TraceCookieCache.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须在 5 秒内调用，否则系统直接抛异常。
        runCatching { startForeground(NOTIFICATION_ID, buildNotification("正在准备")) }
            .onFailure { ModuleAndroidLog.error(TAG, "startForeground failed", it) }
        if (intent?.action == ACTION_RELOAD || loop?.isActive != true) {
            loop?.cancel()
            loop = scope.launch { loopMutex.withLock { runLoop() } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ModuleAndroidLog.legacy(TAG, "auto watch service stopped")
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        ModuleAndroidLog.legacy(TAG, "auto watch service started（${WatchState.describe(this)}）")
        while (currentCoroutineContext().isActive) {
            val settings = ExpressSettings.read(this)
            if (!settings.autoWatch) {
                stopSelf()
                return
            }

            if (CainiaoTraceApi.riskBlocked()) {
                // 撞过风控就整轮不跑（再发只会延长处罚窗口）；按切片反复醒来重问直到自然过期。
                if (!waitFor(
                        describe = { "风控退避中，暂停轮查" },
                        remaining = { if (CainiaoTraceApi.riskBlocked()) SETTING_POLL_MS else 0L },
                    )
                ) {
                    return
                }
                continue
            }

            if (quietRemaining(settings) > 0L) {
                if (!waitFor(
                        describe = { "夜间暂停 · ${ExpressSettings.read(this).quietWindowLabel()}" },
                        remaining = { s -> quietRemaining(s) },
                    )
                ) {
                    return
                }
                continue
            }

            // 「下次该动手」是落盘的：进程重启后接着等剩下那段，而不是从头重问；
            // 每片醒来重读落盘时刻，改短间隔能立刻生效。
            if (!waitFor(
                    describe = { left -> "等下一件 · 还有约 ${left / 60_000 + 1} 分钟" },
                    remaining = { _ -> WatchSchedule.millisUntilDue(WatchState.nextDueAt(this), System.currentTimeMillis()) },
                )
            ) {
                return
            }

            // 本轮已问过的单号要跳过：一轮的语义是「每个在途件问一遍」，重启后接着问剩下的。
            // 免 root 下新件只在一轮真正开始（done 空）时顺带发现一次——逐件发现会把请求数翻几倍。
            val done = WatchState.roundDone(this)
            if (done.isEmpty()) HostRefreshRequester.request(this)
            if (done.isEmpty() && settings.noRootListener) {
                CainiaoDirectFetcher.start(this, "轮查发现")
            }
            val targets = watchTargets().filterNot { it in done }
            if (targets.isEmpty()) {
                val cycle = settings.watchCycleMillis
                WatchState.clearRoundDone(this)
                WatchState.setNextDueAt(this, System.currentTimeMillis() + cycle)
                continue
            }

            ModuleAndroidLog.legacy(
                TAG,
                "auto watch round: ${targets.size} 件待问（本轮已问 ${done.size} 件 · " +
                    "件间隔 ${settings.watchGapMinutes} 分钟）",
            )
            var interrupted = false
            for ((index, tracking) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!ExpressSettings.read(this).autoWatch) {
                    stopSelf()
                    return
                }
                // 夜间窗口可能在轮次中途开始：停在这里，roundDone 已落盘，窗口结束后接着问剩下的。
                if (WatchSchedule.isQuietAt(
                        System.currentTimeMillis(),
                        settings.watchQuietStart,
                        settings.watchQuietEnd,
                        settings.watchQuiet,
                    )
                ) {
                    ModuleAndroidLog.legacy(TAG, "夜间暂停开始，本轮中断（${done.size + index} 件已问）")
                    interrupted = true
                    break
                }
                updateNotification("轮查中 ${index + 1}/${targets.size} · ${tracking.takeLast(4)}")
                ModuleTraceFetcher.watchFetch(this, tracking)
                WatchState.markRoundDone(this, tracking)
                // 先落盘再等：等待途中进程没了，重启后接着把这段间隔等完。
                val gap = WatchSchedule.nextGapMillis(
                    jitter = Random.nextFloat() * 2f - 1f,
                    baseMs = settings.watchGapMillis,
                    jitterMs = settings.watchJitterMillis,
                )
                WatchState.setNextDueAt(this, System.currentTimeMillis() + gap)
                if (index < targets.lastIndex) {
                    if (!waitFor(
                            describe = { left ->
                                "轮查中 ${index + 2}/${targets.size} · 等下一件（约 ${left / 60_000 + 1} 分钟）"
                            },
                            remaining = { _ ->
                                WatchSchedule.millisUntilDue(WatchState.nextDueAt(this), System.currentTimeMillis())
                            },
                        )
                    ) {
                        return
                    }
                }
            }
            if (interrupted) continue
            val cycle = settings.watchCycleMillis
            WatchState.clearRoundDone(this)
            WatchState.setNextDueAt(this, System.currentTimeMillis() + cycle)
        }
    }

    private fun quietRemaining(settings: ExpressSettingsSnapshot): Long =
        WatchSchedule.millisUntilQuietEnd(
            atMillis = System.currentTimeMillis(),
            startHour = settings.watchQuietStart,
            endHour = settings.watchQuietEnd,
            quiet = settings.watchQuiet,
        )

    /** 等到剩余时间为 0；每 [SETTING_POLL_MS] 醒一次并重读设置重算，改设置一个切片内生效。返回 false 表示用户关掉了轮查。 */
    private suspend fun waitFor(
        describe: (leftMs: Long) -> String,
        remaining: (ExpressSettingsSnapshot) -> Long,
    ): Boolean {
        var label: String? = null
        while (currentCoroutineContext().isActive) {
            val settings = ExpressSettings.read(this)
            if (!settings.autoWatch) {
                stopSelf()
                return false
            }
            val left = remaining(settings)
            if (left <= 0L) return true
            val text = describe(left)
            if (text != label) {
                label = text
                updateNotification(text)
            }
            delay(minOf(left, SETTING_POLL_MS))
        }
        return false
    }

    private fun watchTargets(): List<String> {
        val settings = ExpressSettings.read(this)
        return ExpressRecordStore.load(this)
            .filter {
                WatchSchedule.isWatchable(
                    status = it.status,
                    pickedUp = it.isPickedUp,
                    allUnfinished = settings.isWatchUnfinished,
                )
            }
            .mapNotNull { it.trackingNumber?.takeIf { tn -> tn.isNotBlank() } }
            .distinct()
    }

    private fun buildNotification(text: String): Notification {
        // 用户可把这条压成静默最低优先级；不能真的不发 —— 前台服务没通知会被系统杀掉，读设置只为选渠道。
        val quietChannel = !ExpressSettings.read(this).watchNotification
        val channelId = if (quietChannel) CHANNEL_ID_QUIET else CHANNEL_ID
        ensureChannel(channelId, quietChannel)
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ExpressMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_express)
            .setContentTitle("快递补全计划 · 自动轮查")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    /** 渠道重要性只在首次创建时生效、之后改不了，只能换 id 切换；静默渠道用 IMPORTANCE_MIN（前台服务通知的下限）。 */
    private fun ensureChannel(channelId: String, quiet: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                if (quiet) CHANNEL_NAME_QUIET else CHANNEL_NAME,
                if (quiet) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "ReaPress"

        internal const val ACTION_RELOAD = "io.github.YGHFv.ReaPressExtend.action.RELOAD_AUTO_WATCH"

        const val NOTIFICATION_ID = 1001

        const val CHANNEL_ID = "reapress_autowatch"
        const val CHANNEL_NAME = "自动轮查"

        const val CHANNEL_ID_QUIET = "reapress_autowatch_quiet"
        const val CHANNEL_NAME_QUIET = "自动轮查（静默）"

        /** 长等待的切片长度：改完设置十分钟内生效。 */
        private const val SETTING_POLL_MS = 10 * 60_000L
    }
}
