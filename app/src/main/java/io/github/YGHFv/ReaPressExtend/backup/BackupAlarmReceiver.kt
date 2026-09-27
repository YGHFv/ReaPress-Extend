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

package io.github.YGHFv.ReaPressExtend.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 定时备份的闹钟接收器：onReceive 跑在主线程，读全部 prefs 并序列化是几十毫秒 IO，用 goAsync() 把这条广播的存活期交给自己、做完再 finish()。 */
class BackupAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackupScheduler.ACTION_BACKUP_ALARM) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                BackupScheduler.maybeRunDue(app, "定时备份")
                BackupScheduler.ensureScheduled(app)
            } finally {
                // 必须在所有路径上调用 —— 漏了系统会一直以为这条广播没处理完。
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }
}
