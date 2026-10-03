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

import io.github.YGHFv.ReaPressExtend.core.ExpressRecord

/** 两条链路共用的进程内账本：只去重通知，不挡落库；失败释放，只有成功才进入时间窗。 */
object ExpressDeliveryLedger {

    private val ledger = DeliveryLedger()

    fun deliver(record: ExpressRecord, post: () -> Boolean): DeliveryLedger.Result =
        ledger.deliver(keyOf(record), post)

    fun keyOf(record: ExpressRecord): String =
        record.dedupeKey + "|" + record.rawText.trim()

    fun size(): Int = ledger.size()
}

class DeliveryLedger(
    private val windowMs: Long = 60_000L,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(windowMs > 0L)
        require(maxEntries > 0)
    }

    enum class Result { POSTED, ALREADY_POSTED, IN_FLIGHT, FAILED }

    private val inFlight = mutableSetOf<String>()
    private val delivered = LinkedHashMap<String, Long>()

    fun deliver(key: String, post: () -> Boolean): Result {
        synchronized(this) {
            val now = clock()
            delivered.entries.removeAll { now < it.value || now - it.value >= windowMs }
            if (key in delivered) return Result.ALREADY_POSTED
            if (!inFlight.add(key)) return Result.IN_FLIGHT
        }
        var success = false
        try {
            success = post()
            return if (success) Result.POSTED else Result.FAILED
        } finally {
            synchronized(this) {
                inFlight.remove(key)
                if (success) {
                    delivered[key] = clock()
                    while (delivered.size > maxEntries) delivered.remove(delivered.keys.first())
                }
            }
        }
    }

    @Synchronized
    fun size(): Int = delivered.size + inFlight.size
}
