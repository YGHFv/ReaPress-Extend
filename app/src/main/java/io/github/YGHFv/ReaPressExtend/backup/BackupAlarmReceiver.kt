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

/** 广播只提交系统 Job；文件与 SAF IO 不占用广播的有限存活窗口。 */
class BackupAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackupScheduler.ACTION_BACKUP_ALARM) return
        if (!BackupJobService.schedule(context.applicationContext)) {
            io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog.error("ReaPress", "backup alarm job not scheduled")
        }
    }
}
