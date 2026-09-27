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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.core.WatchSchedule
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

private const val LOG_TAG = "ReaPress"

/**
 * 自动轮查的启停开关（[AutoWatchService] 的唯一入口）。
 *
 * ## 只能在「允许起前台服务」的时机调用
 *
 * Android 12 起后台应用不能随意启动前台服务。合规的入口只有两类：
 * - **界面里**（用户改了开关、或打开了模块）—— 应用此刻在前台；
 * - **开机广播**（见 [BootCompletedReceiver]）—— 系统给的豁免之一。
 *
 * 别的地方（比如 Application.onCreate —— 它可能是被一条广播拉起来的）一律不行，
 * 会抛 `ForegroundServiceStartNotAllowedException`。**不做静默降级重试**：
 * 真要起不来，用户下次打开模块时这条路自然又会走一遍。
 */
object AutoWatch {

    /**
     * 按用户的选择把服务摆到正确的状态。幂等，可以放心在每次 onResume / 开机时调。
     *
     * @return true 表示「开机自启 / 界面重进」时服务已经被拉起来了（供日志用）。
     */
    fun sync(context: Context, reason: String): Boolean {
        val enabled = ExpressSettings.read(context).autoWatch
        val intent = Intent(context, AutoWatchService::class.java)
        return if (enabled) {
            runCatching {
                // minSdk 26，startForegroundService 一定可用（不需要 ContextCompat）。
                context.startForegroundService(intent)
            }.onFailure {
                ModuleAndroidLog.error(LOG_TAG, "auto watch start failed ($reason)", it)
            }.isSuccess
        } else {
            context.stopService(intent)
            true
        }
    }

    /**
     * 用户在设置里改了间隔之后，把「下一次该动手」的时刻跟着调过来。
     *
     * ## 为什么需要它
     *
     * 「下次该动手」的时刻是**落盘**的（见 [WatchState]）—— 这正是「重启后不从头问一遍」
     * 得以成立的原因。但反过来它也带来一个副作用：服务正睡在一个长等待里时，用户改了间隔，
     * 那个等待不会自己醒。把「一轮间隔」从 6 小时改到 5 分钟、却还要把旧的 6 小时等完，
     * 在界面上就是**改了没用** —— 与用户 2026-09-27 报的「不走间隔」是同一类体感问题。
     *
     * ## 语义：只往前拉，绝不往后推
     *
     * 具体规则（含「到点了不动」）在 [WatchSchedule.pullDueEarlier]，那里是纯函数、有单测；
     * 这里只负责读出来、算一次、需要时才落盘。
     */
    fun reschedule(context: Context, intervalMillis: Long) {
        val due = WatchState.nextDueAt(context)
        // 「只往前拉、到点不动」那三条规则在 core 的纯函数里（带单测），这里只负责落盘。
        val next = WatchSchedule.pullDueEarlier(
            dueAtMillis = due,
            nowMillis = System.currentTimeMillis(),
            intervalMillis = intervalMillis,
        )
        if (next != due) {
            WatchState.setNextDueAt(context, next)
        }
    }
}

/**
 * 开机后把自动轮查接回去。
 *
 * 没有它的话，「开了轮查 → 重启设备」之后轮查就悄悄停了 —— 而用户不会每天进模块看一眼。
 * 开机广播是系统给的**允许启动前台服务**的豁免时机之一（见 [AutoWatch]）。
 *
 * `exported=false`：只收系统广播，别的应用发不进来越权用这条路径起服务。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AutoWatch.sync(context.applicationContext, "开机")
    }
}
