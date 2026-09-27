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
import io.github.YGHFv.ReaPressExtend.backup.BackupScheduler
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.core.WatchSchedule
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

private const val LOG_TAG = "ReaPress"

/**
 * 自动轮查的启停开关（[AutoWatchService] 的唯一入口）。只能在「允许起前台服务」的时机调
 * （界面里 / 开机广播），别处会抛 `ForegroundServiceStartNotAllowedException`；不做静默重试。
 */
object AutoWatch {

    /** 按用户选择把服务摆到正确状态；幂等。true = 这次调用把服务拉起来了。 */
    fun sync(context: Context, reason: String): Boolean {
        val enabled = ExpressSettings.read(context).autoWatch
        val intent = Intent(context, AutoWatchService::class.java)
        return if (enabled) {
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                ModuleAndroidLog.error(LOG_TAG, "auto watch start failed ($reason)", it)
            }.isSuccess
        } else {
            context.stopService(intent)
            true
        }
    }

    /** 用户改间隔后把落盘的下次时刻往前拉（否则旧的长等待不会自己醒）；规则在 [WatchSchedule.pullDueEarlier]。 */
    fun reschedule(context: Context, intervalMillis: Long) {
        val due = WatchState.nextDueAt(context)
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

/** 开机后把自动轮查接回去（开机广播是允许起前台服务的豁免时机之一）。`exported=false` 防止别的应用走这条路径。 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        AutoWatch.sync(app, "开机")
        // 备份闹钟同样不跨重启：顺手重排一次，并立刻判一次是否已到期。
        runCatching { BackupScheduler.reschedule(app) }
        runCatching { BackupScheduler.maybeRunDue(app, "开机") }
    }
}
