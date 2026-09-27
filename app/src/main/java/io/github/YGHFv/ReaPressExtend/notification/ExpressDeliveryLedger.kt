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
import io.github.YGHFv.ReaPressExtend.core.NotifyDedupe

/** 两条链路（system_server hook / NotificationListenerService）的公共去重账本：先到者发通知，后到者只落库不发通知，去重只挡通知、不挡落库；键 = dedupeKey + 正文哈希，只压同一条事件的重复投递，不压同一包裹的下一次更新。进程级内存，不落盘，重启即失效 —— 代价是多一条通知。 */
object ExpressDeliveryLedger {

    private val dedupe = NotifyDedupe()

    /** 占一次坑：true = 该你投递；false = 已有人投递过，落库即可。 */
    fun claim(record: ExpressRecord, now: Long = System.currentTimeMillis()): Boolean =
        dedupe.claim(keyOf(record), now)

    fun keyOf(record: ExpressRecord): String =
        record.dedupeKey + "|" + record.rawText.trim().hashCode()

    fun size(): Int = dedupe.size()
}
