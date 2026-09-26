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
 * 去重。
 *
 * 为什么必须有：快递推送的重复率极高 —— 同一条状态可能因为重推、多进程、系统重发而到达多次；
 * 菜鸟的「待取件」提醒还会每小时重复一次。不去重的话通知栏会被刷屏。
 *
 * 设计：
 * - 同一个包裹在 [ttlMillis] 内只放行一次
 * - **状态推进例外**：同一包裹从「运输中」变「待取件」必须放行，否则用户看不到关键进展
 * - 纯内存 + 惰性清理，不落盘（重启后重复一次无伤大雅，落盘反而要处理一致性问题）
 *
 * 「同一个包裹」走 [ExpressRecord.isSamePackageAs] 而不是主键字符串相等：同一个包裹的多次
 * 推送里驿站名详略可能不同、运单号可能时有时无，按主键字符串比会漏判成两个包裹。
 *
 * 线程安全：system_server 里 NMS 的调用可能来自多个 Binder 线程，必须加锁。
 * 锁粒度做到最小 —— 只包住 map 操作，不做任何耗时动作。
 */
class ExpressDedupe(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Entry(val at: Long, val record: ExpressRecord)

    private val lock = Any()
    private val seen = LinkedHashMap<String, Entry>()

    /**
     * @param now 当前时间，由调用方注入（core 层不碰系统时钟，方便单测）
     * @return true = 应当处理；false = 重复，丢弃
     */
    fun shouldAccept(record: ExpressRecord, now: Long): Boolean {
        val key = record.dedupeKey
        synchronized(lock) {
            evictExpired(now)
            // 先按主键直查；未命中再扫一遍找「同一个包裹」的旧记录。
            // 线性扫描看着糙，但表被 [maxEntries] 压在 200 条内，且只有主键发生变化时
            // （运单号从无到有之类）才会走到，实际开销可忽略。
            val matchedKey = if (seen.containsKey(key)) {
                key
            } else {
                seen.entries.firstOrNull { it.value.record.isSamePackageAs(record) }?.key
            }
            val previous = matchedKey?.let { seen[it] }
            val accept = when {
                previous == null -> true
                // 状态推进 → 放行。用户必须看到「运输中 → 待取件」这种关键进展。
                record.status.order > previous.record.status.order -> true
                // 状态倒退 → 丢弃。乱序推送时不该把「已签收」退回「运输中」。
                record.status.order < previous.record.status.order -> false
                // 同级：只有文案真的变了才放行。同状态同文案就是重复推送
                // （菜鸟的待取件提醒每小时重发一次，靠这条压掉）。
                else -> record.rawText != previous.record.rawText
            }
            // 主键可能变了（运单号从无到有），旧槽位要清掉，否则同一个包裹会占两格、
            // 各自独立计时，去重就漏了。
            if (matchedKey != null && matchedKey != key) seen.remove(matchedKey)
            // 无论放行与否都刷新时间戳：持续重复推送说明这个包裹仍活跃，
            // 不该因为「最后一次接受」太早而被清掉、导致下一轮又当成新的放行。
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

    /** 超出容量时按插入顺序淘汰最旧的（LinkedHashMap 的迭代顺序即插入顺序）。 */
    private fun trimToMax() {
        while (seen.size > maxEntries) {
            val oldest = seen.keys.firstOrNull() ?: return
            seen.remove(oldest)
        }
    }

    companion object {
        /** 6 小时。覆盖「同一状态反复推送」的典型周期，又不至于把昨天的记录压到今天。 */
        const val DEFAULT_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
