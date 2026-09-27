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
 * 快递推送去重：同一个包裹在 [ttlMillis] 内只放行一次，但**状态只推进不回退** ——
 * 「运输中 → 待取件」必须放行，倒退的乱序推送丢弃。纯内存、不落盘。
 * 「同一个包裹」走 [ExpressRecord.isSamePackageAs]（驿站名详略、运单号有无都会变），不能用主键字符串比。
 */
class ExpressDedupe(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Entry(val at: Long, val record: ExpressRecord)

    private val lock = Any()
    private val seen = LinkedHashMap<String, Entry>()

    /** true = 应当处理；false = 重复丢弃。now 由调用方注入。 */
    fun shouldAccept(record: ExpressRecord, now: Long): Boolean {
        val key = record.dedupeKey
        synchronized(lock) {
            evictExpired(now)
            // 先按主键直查，未命中再扫一遍找「同一个包裹」的旧记录。
            val matchedKey = if (seen.containsKey(key)) {
                key
            } else {
                seen.entries.firstOrNull { it.value.record.isSamePackageAs(record) }?.key
            }
            val previous = matchedKey?.let { seen[it] }
            val accept = when {
                previous == null -> true
                record.status.order > previous.record.status.order -> true
                record.status.order < previous.record.status.order -> false
                // 同级：只有文案变了才放行（菜鸟的待取件提醒每小时重发一次）。
                else -> record.rawText != previous.record.rawText
            }
            // 主键可能变了，旧槽位要清掉，否则同一个包裹占两格、去重漏判。
            if (matchedKey != null && matchedKey != key) seen.remove(matchedKey)
            // 无论放行与否都刷新时间戳：活跃包裹不该因「最后一次接受」太早被清掉。
            seen[key] = Entry(now, record)
            trimToMax()
            return accept
        }
    }

    fun clear() {
        synchronized(lock) { seen.clear() }
    }

    fun size(): Int = synchronized(lock) { seen.size }

    private fun evictExpired(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.at > ttlMillis) iterator.remove()
        }
    }

    private fun trimToMax() {
        while (seen.size > maxEntries) {
            val oldest = seen.keys.firstOrNull() ?: return
            seen.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
