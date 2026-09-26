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
 * 把宿主进程富化出的包裹数据配到已有的通知记录上。
 *
 * ## 为什么需要「配」
 *
 * 富化数据（宿主自己库里躺着的包裹）和通知记录是**两条独立到达的流**，各自带的字段不一样：
 *
 * | | 通知记录 | 富化记录 |
 * |---|---|---|
 * | 运单号 | 常被截断成尾号，或者干脆没有 | 全号 |
 * | 取件码 | 有（用户要念给店员听） | 有，但受宿主的显示开关控制 |
 * | 驿站名 | 有，但详略和通知文案一致 | 有，是宿主自己的写法 |
 *
 * 所以两边**没有一个是共同的主键**，必须靠多个弱信号加权判断。这里用打分而不是
 * 「按顺序匹配第一条命中的规则」，是因为信号之间会互相印证：单独一个驿站名不足以确认
 * （同一驿站常常同时有好几个包裹），但「驿站名对得上 + 通知里提到了这个运单号的尾号」
 * 合起来就足够可信了。
 *
 * ## 阈值是在防什么
 *
 * 配错的代价不是「信息没补上」，而是**把全号写到另一个包裹上** —— 用户照着它去驿站报号会取错件。
 * 所以阈值卡在「必须有运单号或取件码级别的证据」，驿站名单独命中只给 [SCORE_STATION] 分，
 * 永远到不了阈值。宁可漏配，不可错配。
 *
 * 纯函数，可单测；不碰系统时钟（排序由调用方保证）。
 */
object ExpressEnrichmentMatcher {

    /** 达到这个分数才算确认是同一个包裹。 */
    const val MATCH_THRESHOLD = 50

    /** 双方运单号完全一致。唯一一个可以单独越过阈值之外的强证据（其实它本身就够）。 */
    private const val SCORE_TRACKING_EXACT = 100

    /** 一方运单号是另一方的后缀之一 —— 通知里写了尾号、富化给了全号。 */
    private const val SCORE_TRACKING_TRUNCATED = 60

    /** 双方取件码一致。取件码在同一个驿站内唯一，是很强的证据。 */
    private const val SCORE_PICKUP_CODE = 50

    /** 通知原文里提到了富化运单号的尾号。 */
    private const val SCORE_TAIL_IN_TEXT = 50

    /** 归一化后的驿站名一致。**故意给得低**，理由见类注释。 */
    private const val SCORE_STATION = 15

    /**
     * 算「截断」关系时要求短串的最短长度。
     *
     * 低于这个长度时后缀匹配会退化成巧合：`1234` 这种四位串在任意两个运单号之间都可能撞上。
     */
    private const val MIN_TRACKING_LENGTH = 6

    /** 「尾号」的判定长度。 */
    private const val TAIL_LENGTH = 4

    /** 没有「尾号」这类标记时，改用这个更长的片段当证据。 */
    private const val LONG_TAIL_LENGTH = 6

    /** 出现这些词说明通知在刻意提示「这是运单号的尾号」，而不是碰巧出现的四位数字。 */
    private val TAIL_MARKERS = listOf("尾号", "尾数", "末位", "末尾")

    /**
     * 在 [records] 里找出应该被 [enrichment] 补充的那条记录。
     *
     * @param records 已有记录，**必须按时间倒序**（最新在前，[io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore.load]
     *   就是这个顺序）。同分时取靠前的，也就是取最新的那条 —— 同一个包裹的多次推送里，
     *   最新的一条才是用户在看的。
     * @return 命中记录的下标；没有够格的候选时返回 -1
     */
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

    /**
     * 单条候选的置信分。
     *
     * 公开出来是为了让调用方在**没配上**时能把分数打进日志 —— 排查「富化明明生效了但卡片没变」
     * 时，看得到「候选都在 15 分上下」和「压根没有候选」是两件完全不同的事。
     */
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

    /** [short] 是 [long] 的一段后缀，且长度够长，不算巧合。 */
    private fun isTruncationOf(short: String, long: String): Boolean =
        short.length >= MIN_TRACKING_LENGTH && long.length > short.length && long.endsWith(short)

    /**
     * 通知原文里是否提到了这个运单号的尾号。
     *
     * 分两级，正是为了压住误报：
     * - 有「尾号 / 尾数 / 末位 / 末尾」这类词时，四位尾号就够了 —— 通知自己说清了这是运单号，
     *   此时撞上其它数字的概率可以忽略
     * - 没有这类词时，改用六位尾号 —— 纯文本里随机出现六位相同数字的概率极低，够格当证据
     *
     * 之所以两级都需要：菜鸟不同模板的措辞差别很大，有的写「运单号尾号1234」，
     * 有的直接把尾号贴在单号后面。
     */
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

    /**
     * 驿站名的比较核心。
     *
     * 同一次推送里两边对同一个驿站的写法经常不同：
     * `菜鸟驿站(合肥南湖新城华韵古筝店)` / `合肥南湖新城华韵古筝店` / `菜鸟驿站合肥南湖新城华韵古筝店`。
     * 去掉品牌前缀（品牌名不是门店标识）、括号和空白之后就能对上。
     *
     * 注意这**不足以单独确认包裹身份**（同一驿站常有多个包裹），只是打分里的一个信号。
     */
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
