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
import android.os.Build
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动轮查。
 *
 * ## 为什么必须是前台服务
 *
 * 用户要的是「**包括在后台时**」—— 关掉界面、把模块划掉、放几个小时，回来时数据是新的。
 * Android 上只有三种可能的载体，逐一看过：
 *
 * | 方案 | 为什么不行 |
 * |---|---|
 * | `WorkManager` / `JobScheduler` | 周期任务的最小间隔是 15 分钟，做不到「件与件之间 3 分钟」；而且在 Doze 里会被整体推迟到维护窗口 |
 * | `AlarmManager` 精确闹钟链 | Android 12+ 要 `SCHEDULE_EXACT_ALARM` 特殊权限，Android 14 起不再自动授予；进 Doze 后 `setExactAndAllowWhileIdle` 也被限到 9 分钟一次 —— 三分钟的节奏直接作废 |
 * | 普通后台 Service | Android 8 起后台服务活不过一两分钟 |
 *
 * 所以只剩前台服务：**代价是一条撤不掉的通知**。这正是这个功能默认关闭的原因
 * （见 `ExpressSettingsKeys.KEY_AUTO_WATCH`）—— 关着的时候通知栏里什么都没有。
 *
 * ## 节奏
 *
 * 规则在 [WatchSchedule]（纯函数、有单测），**间隔本身来自设置**（件间隔 / 轮间隔，
 * 2026-09-27 起用户可调），这里只负责等：一件问完等「件间隔 ± 抖动」→ 一轮跑完等「轮间隔」
 * → 循环。夜间暂停期间整段挂起，且在**轮次中途**到点也会停下来（见 [runLoop] 里那段）。
 *
 * 进度（下次该动手的时刻、本轮问过哪些单号）落在 [WatchState] 里 —— 进程重启不重置节奏。
 *
 * ## 为什么每轮都重读设置
 *
 * 用户随时可能在界面上关掉它（或改范围 / 改间隔 / 改夜间时段）。长 `delay` 会让改动最多等
 * 一小时才生效，所以等待被切成 [SETTING_POLL_MS] 的小段：醒来一次、重读一次设置、
 * 还该继续就接着等。开销是「每小时几次 prefs 读取」。
 *
 * ## 不做的事
 *
 * **不负责拉新包裹**。包裹表在菜鸟的本地库里，模块拿不到（见 `ExpressRelay` 里
 * 「H5 通道没开」那段）—— 轮查能推进的只有「已经在本模块里的件」的轨迹与状态。
 */
class AutoWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ModuleLogBuffer.attach(this)
        // 轮查靠 cookie 直连（不经宿主进程），所以进程一起就要把落盘的那份登录态读回来。
        TraceCookieCache.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须在 5 秒内调用，否则系统直接抛 ANR 级的异常。
        runCatching { startForeground(NOTIFICATION_ID, buildNotification("正在准备")) }
            .onFailure { ModuleAndroidLog.error(TAG, "startForeground failed", it) }
        if (loop?.isActive != true) {
            loop = scope.launch { runLoop() }
        }
        // START_STICKY：被系统回收后自己回来。回来时会重读设置，用户已经关掉就什么都不做。
        return START_STICKY
    }

    override fun onDestroy() {
        ModuleAndroidLog.legacy(TAG, "auto watch service stopped")
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        ModuleAndroidLog.legacy(TAG, "auto watch service started（${WatchState.describe(this)}）")
        while (scope.isActive) {
            val settings = ExpressSettings.read(this)
            if (!settings.autoWatch) {
                // 用户在界面上关掉了：收摊。通知也跟着消失（stopSelf → onDestroy）。
                stopSelf()
                return
            }

            if (CainiaoTraceApi.riskBlocked()) {
                // 撞过风控就整轮不跑：这时候再发只会延长处罚窗口。退避还剩多久**读不到**
                // （接口只回答「还在不在退避」），所以按一个轮询切片反复醒来重问，
                // 直到它自然过期。
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
                // 夜间暂停：整段挂起。窗口是设置里的那两个整点 —— 用户改了它也能及时生效
                // （`waitFor` 每段醒来都重算剩余时间）。
                if (!waitFor(
                        describe = { "夜间暂停 · ${ExpressSettings.read(this).quietWindowLabel()}" },
                        remaining = { s -> quietRemaining(s) },
                    )
                ) {
                    return
                }
                continue
            }

            // 「下次该动手」是**落盘**的：进程重启（装包 / 被系统回收 / 重启手机）之后接着等
            // 剩下的那一段，而不是从头再问一遍。用户 2026-09-27 报的「每次更新等操作都会
            // 导致轮查重新开始，而没有走间隔」缺的就是这一步 —— 以前它只是协程里的一个 delay，
            // 进程一换就归零，服务起来立刻把所有件重问一遍。
            //
            // 这里同样每段醒来重读一次落盘的时刻：用户在设置里把间隔改短时，
            // 界面会把到期时刻往前挪（见 `AutoWatch.reschedule`），必须读到那个新值。
            if (!waitFor(
                    describe = { left -> "等下一件 · 还有约 ${left / 60_000 + 1} 分钟" },
                    remaining = { _ -> WatchSchedule.millisUntilDue(WatchState.nextDueAt(this), System.currentTimeMillis()) },
                )
            ) {
                return
            }

            // 本轮**已经问过**的单号要跳过：一轮的语义是「把手里每个在途件都问一遍」，
            // 重启后该接着问剩下的，而不是从第一个重新问（那会让排在前面的件被问两遍）。
            val done = WatchState.roundDone(this)
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
                if (!ExpressSettings.read(this).autoWatch) {
                    stopSelf()
                    return
                }
                // 夜间窗口可能在**轮次中途**开始（一轮几十件要走一个多小时）。
                // 停在这里，本轮剩下的留到窗口结束后接着问 —— roundDone 已经落盘，
                // 问过的那几个不会被重问。少这一步的话「22:00 到 8:00 不轮查」是假承诺。
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
                WatchState.markRoundDone(this, tracking, WatchState.roundDone(this))
                // 下一个什么时候可以问：**先落盘再等**。这样即使在等待途中进程没了，
                // 重启后也是接着把这剩下的间隔等完。
                val gap = WatchSchedule.nextGapMillis(
                    jitter = Random.nextFloat() * 2f - 1f,
                    baseMs = settings.watchGapMillis,
                    jitterMs = settings.watchJitterMillis,
                )
                WatchState.setNextDueAt(this, System.currentTimeMillis() + gap)
                if (index < targets.lastIndex) {
                    // 等下一件。走同一个「重读落盘时刻」的等待，这样用户把件间隔改小
                    // 也能在**轮次中途**立刻生效。
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
            // 夜间中断：本轮没跑完，别清 roundDone、也别记「本轮完成」——
            // 回到顶层等窗口结束，然后继续问剩下的那几件。
            if (interrupted) continue
            val cycle = settings.watchCycleMillis
            WatchState.clearRoundDone(this)
            WatchState.setNextDueAt(this, System.currentTimeMillis() + cycle)
        }
    }

    /** 距离走出夜间暂停窗口还有多少毫秒（不在窗口里 = 0）。 */
    private fun quietRemaining(settings: ExpressSettingsSnapshot): Long =
        WatchSchedule.millisUntilQuietEnd(
            atMillis = System.currentTimeMillis(),
            startHour = settings.watchQuietStart,
            endHour = settings.watchQuietEnd,
            quiet = settings.watchQuiet,
        )

    /**
     * 等到剩余时间为 0。每 [SETTING_POLL_MS] 醒一次、**重读设置并重算剩余时间**。
     *
     * 两件事都靠这个「重算」实现：
     *
     * 1. 用户改设置（改间隔 / 改夜间时段 / 直接关掉轮查）能及时生效，最长等一个切片。
     *    固定 `delay(一整个长时段)` 的话，改完最多要等一小时（那条「一轮间隔」最长 6 小时）——
     *    用户体验就是「改了没用」，而这一族问题用户已经报过两次了。
     * 2. 通知上那一行倒计时跟着实际剩余时间走，而不是停在按旧参数算出的那个数上。
     *
     * @param remaining 按**最新的**设置算出还剩多少毫秒；<= 0 表示可以继续往下走。
     * @return false 表示等待期间用户关掉了轮查（调用方直接 return，别再往下做任何事）。
     */
    private suspend fun waitFor(
        describe: (leftMs: Long) -> String,
        remaining: (ExpressSettingsSnapshot) -> Long,
    ): Boolean {
        var label: String? = null
        while (scope.isActive) {
            val settings = ExpressSettings.read(this)
            if (!settings.autoWatch) {
                stopSelf()
                return false
            }
            val left = remaining(settings)
            if (left <= 0L) return true
            val text = describe(left)
            // 只有文案真的变了才去刷通知：等待以十分钟为切片，一直重发同一条没有意义。
            if (text != label) {
                label = text
                updateNotification(text)
            }
            delay(minOf(left, SETTING_POLL_MS))
        }
        return false
    }

    /** 这一轮要问的单号：按范围筛出可轮查的件，同号只留一个（多张卡片同一单的情况真实存在）。 */
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

    // ------------------------------------------------------------ 通知

    private fun buildNotification(text: String): Notification {
        // 用户可在设置里把这条压成「静默最低优先级」（见 KEY_WATCH_NOTIFICATION）。
        // **不能真的不发** —— 前台服务没有通知会被系统直接杀掉，这里读设置是为了选渠道。
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
            // 常驻且安静：它是「服务在跑」的状态条，不是提醒。点不掉（ongoing）、
            // 不响铃（渠道本身就是 LOW / MIN 重要性，无声无振动）。
            .setOngoing(true)
            .setShowWhen(false)
            // 进度文案每件都会变，同一条通知不该每次都重新提醒一次（免打扰模式下尤其明显）。
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    /**
     * 两个渠道，按设置二选一。
     *
     * 为什么必须是**两个渠道**而不是改一个渠道的重要性：`createNotificationChannel` 只在
     * 首次创建时决定重要性，之后再调用不会改（用户的渠道设置有更高优先级，系统不允许应用
     * 自己升级）。所以「显示 / 静默」只能靠两条渠道 + 换 id 切换。
     *
     * 静默那条用 `IMPORTANCE_MIN`：不出声、不弹横幅、**不占状态栏图标**（只在通知栏里
     * 折叠在最下面）。这就是「关闭轮查通知」在 Android 上能达到的极限 —— 前台服务的通知
     * 撤不掉，系统不允许。
     */
    private fun ensureChannel(channelId: String, quiet: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // createNotificationChannel 是幂等的，重复调用不会重置用户的渠道设置。
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

        /** 通知 ID 与快递通知的基数（5100）离得很远，不会互相覆盖。 */
        const val NOTIFICATION_ID = 1001

        const val CHANNEL_ID = "reapress_autowatch"
        const val CHANNEL_NAME = "自动轮查"

        /** 用户关掉「轮查通知」之后走的那条最低优先级渠道（见 [ensureChannel]）。 */
        const val CHANNEL_ID_QUIET = "reapress_autowatch_quiet"
        const val CHANNEL_NAME_QUIET = "自动轮查（静默）"

        /**
         * 长等待的切片长度。取自「改完设置多久生效」的容忍度 —— 10 分钟内一定生效，
         * 而每小时只多几次 prefs 读取。
         */
        private const val SETTING_POLL_MS = 10 * 60_000L
    }
}
