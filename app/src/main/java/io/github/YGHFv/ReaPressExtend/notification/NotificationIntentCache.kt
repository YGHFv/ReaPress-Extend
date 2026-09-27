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

import android.app.PendingIntent

/**
 * 被拦通知的点击跳转令牌（[PendingIntent]），按审计记录 id 归口，模块进程内存 + LRU（上限 100）。
 *
 * PendingIntent 是 binder 句柄，没有可落盘的表示，只能存内存；但它寄存在 system_server 侧
 * （[io.github.YGHFv.ReaPressExtend.hook.IntentTokenStore]，句柄随记录落盘），模块进程重启后
 * 详情页会取回（IntentTokenFetcher），所以这张表的寿命 = 设备本次开机。
 * 另一条兜底路：令牌内的 Intent 可序列化（toUri/parseUri，见 ExpressNotificationLog.Entry.intentUri），
 * 快照永久但有损；两条路在 [NotificationIntentLauncher] 合流，令牌优先。
 */
object NotificationIntentCache {

    private const val MAX_ENTRIES = 100

    private val entries = object : LinkedHashMap<String, PendingIntent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean =
            size > MAX_ENTRIES
    }

    fun remember(id: String, intent: PendingIntent) {
        if (id.isBlank()) return
        synchronized(entries) { entries[id] = intent }
    }

    /** 取令牌；null = 此刻这里没有，不等于点不开（快照兜底与取回路仍在）；不消费，用户可能反复点。 */
    fun get(id: String): PendingIntent? {
        if (id.isBlank()) return null
        return synchronized(entries) { entries[id] }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
