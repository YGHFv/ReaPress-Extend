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

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.YGHFv.ReaPressExtend.backup.BackupScheduler
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay

/**
 * 「包裹数据变了」广播（[ExpressRelay.ACTION_RECORDS_CHANGED]）的发送侧唯一出口：
 * 任何直接写 [ExpressRecordStore] 的地方都必须经过这里发一次（写记录处共三处，跑在同一进程但分散在不同类）。
 *
 * 节流只用于富化链路（throttled=true，宿主自查一个循环连发十几条）：窗口量的是间隔，
 * 用 elapsedRealtime 与用户改没改系统时间无关；被丢掉的中间状态不会留在界面 ——
 * 自查末尾必有 [ExpressRelay.ACTION_HOST_QUERY_REPORT]，接收器收到后不节流地再发一次兜住最终状态。
 */
internal object ExpressChangeNotifier {

    private const val TAG = "ReaPress"

    /** 节流窗口：够把一个循环里连发的十几条压成一条，又不至于让用户点一下等半秒。 */
    private const val MIN_INTERVAL_MS = 600L

    private val lock = Any()
    private var lastAt = 0L

    fun notify(context: Context, throttled: Boolean = false) {
        if (throttled) {
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (now - lastAt < MIN_INTERVAL_MS) return
                lastAt = now
            }
        }
        runCatching {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_RECORDS_CHANGED).setPackage(context.packageName),
            )
        }.onFailure {
            // 发不出去只是界面晚一步更新，不是数据丢 —— 数据已在调用方落库。
            ModuleAndroidLog.error(TAG, "records-changed broadcast failed", it)
        }

        // 「有变更时自动备份」挂在这条信号上：这里是数据变更的唯一出口，
        // 挂到逐个调用方的话漏掉一处 = 那条链路的数据永远不会被自动备份。
        runCatching { BackupScheduler.onDataChanged(context) }
            .onFailure { ModuleAndroidLog.error(TAG, "backup trigger failed", it) }
    }
}
