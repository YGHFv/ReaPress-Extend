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

/** 把宿主富化的包裹数据配到已有通知记录上：无共同主键，靠多信号加权打分，阈值要求运单号或取件码级证据 —— 宁可漏配不可错配；纯函数，记录列表须按时间倒序，同分取最新。 */
object ExpressEnrichmentMatcher {

    const val MATCH_THRESHOLD = 50

    private const val SCORE_TRACKING_EXACT = 100

    private const val SCORE_TRACKING_TRUNCATED = 60

    private const val SCORE_PICKUP_CODE = 50

    private const val SCORE_TAIL_IN_TEXT = 50

    /** 驿站名单信号，**故意给低**，单独命中永远到不了阈值。 */
    private const val SCORE_STATION = 15

    private const val MIN_TRACKING_LENGTH = 6

    private const val TAIL_LENGTH = 4

    private const val LONG_TAIL_LENGTH = 6

    private val TAIL_MARKERS = listOf("尾号", "尾数", "末位", "末尾")

    /** 在 [records]（须按时间倒序）里找出应被 [enrichment] 补充的记录下标；没有够格的候选返回 -1。 */
    fun indexOfTarget(records: List<ExpressRecord>, enrichment: ExpressRecord): Int {
        var bestIndex = -1
        var bestScore = 0
        for ((index, candidate) in records.withIndex()) {
            val candidateScore = score(candidate, enrichment)
            if (candidateScore > bestScore) {
                bestScore = candidateScore
                bestIndex = index
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) bestIndex else -1
    }

    /** 公开是为了让调用方在没配上时能把分数打进日志。 */
    fun score(record: ExpressRecord, enrichment: ExpressRecord): Int {
        var score = 0
        val fromHost = enrichment.trackingNumber?.takeIf { it.isNotBlank() }
        val fromNotification = record.trackingNumber?.takeIf { it.isNotBlank() }

        if (fromHost != null && fromNotification != null) {
            score += when {
                fromHost == fromNotification -> SCORE_TRACKING_EXACT
                isTruncationOf(fromNotification, fromHost) -> SCORE_TRACKING_TRUNCATED
                isTruncationOf(fromHost, fromNotification) -> SCORE_TRACKING_TRUNCATED
                else -> 0
            }
        }

        if (fromHost != null && mentionsTailOf(record.rawText, fromHost)) {
            score += SCORE_TAIL_IN_TEXT
        }

        val pickupFromNotification = record.pickupCode?.takeIf { it.isNotBlank() }
        val pickupFromHost = enrichment.pickupCode?.takeIf { it.isNotBlank() }
        if (pickupFromNotification != null && pickupFromNotification == pickupFromHost) {
            score += SCORE_PICKUP_CODE
        }

        val stationFromNotification = stationCore(record.station)
        val stationFromHost = stationCore(enrichment.station)
        if (stationFromNotification != null && stationFromNotification == stationFromHost) {
            score += SCORE_STATION
        }

        return score
    }

    private fun isTruncationOf(short: String, long: String): Boolean =
        short.length >= MIN_TRACKING_LENGTH && long.length > short.length && long.endsWith(short)

    /** 有「尾号 / 尾数」类标记词时四位尾号即可，否则要六位。 */
    private fun mentionsTailOf(text: String, trackingNumber: String): Boolean {
        if (text.isBlank()) return false
        val upperText = text.uppercase()
        val upperTracking = trackingNumber.uppercase()
        val tail = upperTracking.takeLast(TAIL_LENGTH)
        if (!upperText.contains(tail)) return false
        if (TAIL_MARKERS.any { upperText.contains(it) }) return true
        return upperTracking.length > LONG_TAIL_LENGTH &&
            upperText.contains(upperTracking.takeLast(LONG_TAIL_LENGTH))
    }

    internal fun stationCore(station: String?): String? {
        val trimmed = station?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val core = trimmed
            .removePrefix("菜鸟驿站")
            .removePrefix("菜鸟")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "")
            .filterNot { it.isWhitespace() }
        return core.ifBlank { null }
    }
}
