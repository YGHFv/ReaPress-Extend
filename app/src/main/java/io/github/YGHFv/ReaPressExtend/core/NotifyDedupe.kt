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

package io.github.YGHFv.ReaPressExtend.core

/**
 * 带时间窗的进程内去重表（root 拦截与免 root 监听两条链路跑同一进程，用它压住第二条）。
 * 不落盘 —— 只挡通知不挡落库，进程被杀最多多一条通知。线程安全，时刻由调用方传入。
 */
class NotifyDedupe(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()

    /** true = 窗口内没人占过，你去处理。 */
    fun claim(key: String, now: Long): Boolean {
        // 空键放过：压住它只会丢数据。
        if (key.isBlank()) return true
        synchronized(lock) {
            val previous = seen[key]
            // now < previous（时钟被改回去）按过期处理，宁可多提醒一次。
            if (previous != null && now >= previous && now - previous < windowMs) return false
            seen[key] = now
            if (seen.size > maxEntries) {
                val iterator = seen.keys.iterator()
                while (seen.size > maxEntries && iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            return true
        }
    }

    /** 诊断用。 */
    fun size(): Int = synchronized(lock) { seen.size }

    fun clear() = synchronized(lock) { seen.clear() }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L

        const val DEFAULT_MAX_ENTRIES = 64
    }
}
