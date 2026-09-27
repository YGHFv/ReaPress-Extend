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

package io.github.YGHFv.ReaPressExtend.hook

import android.app.PendingIntent
import java.util.UUID

/**
 * 原通知跳转令牌在 system_server 侧的寄存处（模块侧是 NotificationIntentCache，两个进程里同名不同命的两份表）。
 * 令牌是 binder 句柄、没有可落盘的表示，寄存寿命 = 设备本次开机（重启后 system_server 与 AMS 记录一起重建，不是永久）；
 * 上限 LRU 200，量级与 ExpressNotificationLog.MAX_RECORDS 对齐。
 */
internal object IntentTokenStore {

    private const val MAX_ENTRIES = 200

    private val entries = object : LinkedHashMap<String, PendingIntent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 寄存令牌，返回记录里要存的句柄（[ExpressRelay.EXTRA_INTENT_TOKEN]）；null = 通知没有
     * contentIntent，调用方不发这个 extra、记录里留空。句柄用随机 UUID 求不可猜，不要求跨投递稳定。
     */
    fun stash(intent: PendingIntent?): String? {
        if (intent == null) return null
        val id = UUID.randomUUID().toString()
        synchronized(entries) { entries[id] = intent }
        return id
    }

    /** null = 这里没有（LRU 淘汰 / 重启过 / 句柄伪造），不等于点不开：记录里的快照串仍能打开宿主 App；不消费。 */
    fun get(id: String?): PendingIntent? {
        if (id.isNullOrBlank()) return null
        return synchronized(entries) { entries[id] }
    }
}
