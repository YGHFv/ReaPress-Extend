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

/** 从通知文本里抽取结构化字段。纯函数、只吃 String（system_server 里不能碰 Notification 对象）。 */
object ExpressParser {

    /** Android 的 `\b` 是 Unicode 词边界而 JVM 是 ASCII，断言一律显式写。 */
    private const val NOT_ALNUM_BEFORE = "(?<![0-9A-Za-z])"
    private const val NOT_ALNUM_AFTER = "(?![0-9A-Za-z])"

    /** 运单号候选，顺序即优先级；刻意不收 11 位纯数字（那是手机号）。 */
    private val TRACKING_PATTERNS = listOf(
        Regex("$NOT_ALNUM_BEFORE([A-Z]{2,3}\\d{9,13})$NOT_ALNUM_AFTER"),
        Regex("$NOT_ALNUM_BEFORE(\\d{12,15})$NOT_ALNUM_AFTER"),
    )

    private val PHONE_WITH_COUNTRY_CODE = Regex("""^(?:00)?86(1[3-9]\d{9})$""")

    /** 取件码示例：「取件码 8-2-3021」「提货码 A1234」；第二式认菜鸟的货架-层-序号（无前缀）。 */
    private val PICKUP_CODE_PATTERNS = listOf(
        Regex("""(?:取件码|提货码|取货码|提取码)\s*[-—:：]?\s*([0-9A-Za-z][0-9A-Za-z\-]{2,15})"""),
        Regex("$NOT_ALNUM_BEFORE(\\d{1,3}-\\d{1,2}-\\d{3,6})$NOT_ALNUM_AFTER"),
    )

    /** 包裹尾号「取尾号1234」的 1234；必须带「取」，单独的「尾号」可能是手机尾号（见 PHONE_TAIL_PATTERNS）。 */
    private val PARCEL_TAIL_PATTERNS = listOf(
        Regex("""取尾号\s*[:：]?\s*(\d{3,6})(?!\d)"""),
    )

    private val STATION_KEYWORDS = listOf(
        "菜鸟驿站", "菜鸟裹裹", "服务中心", "快递柜", "自提柜", "代收点", "驿站", "丰巢",
    ).sortedByDescending { it.length }

    private val STATION_HEAD_BREAK = listOf(
        "放入", "存入", "寄入", "寄到", "送到", "已到", "到达", "送至", "送往", "投放", "放在", "存在",
        "到", "至", "在", "从", "由", "往", "向", "于", "请", "凭", "的", "是", "您", "你",
    )

    private val STATION_TAIL_BREAK = listOf(
        "存放", "超过", "超时", "逾期", "未取", "提货码", "验证码", "尾号", "取", "您", "请",
    )

    private val STATION_TAIL_LINK_CHARS = charArrayOf('-', '—', '·', '~', '～', '&', '+', '_')

    private const val MAX_STATION_HEAD = 20
    private const val MAX_STATION_TAIL = 20

    private val PHONE_TAIL_PATTERNS = listOf(
        Regex("""(?:手机|电话|手机号|手机号码)\s*号?\s*(?:尾号|后四位|末四位|后4位|末4位)\s*[:：]?\s*(\d{4})(?!\d)"""),
        Regex("""(?:尾号|后四位|末四位)\s*[:：]?\s*(\d{4})(?!\d)\s*的?\s*(?:手机|电话)"""),
    )

    /** 状态关键词 → 状态。顺序敏感：越具体的越靠前（「已签收」必须在「签收」之前判）。 */
    private val STATUS_RULES = listOf(
        "投递失败" to ExpressStatus.FAILED,
        "派送失败" to ExpressStatus.FAILED,
        "无人接收" to ExpressStatus.FAILED,
        "已签收" to ExpressStatus.SIGNED,
        "已代收" to ExpressStatus.SIGNED,
        "本人签收" to ExpressStatus.SIGNED,
        "取件码" to ExpressStatus.READY_FOR_PICKUP,
        "待取件" to ExpressStatus.READY_FOR_PICKUP,
        "请及时取件" to ExpressStatus.READY_FOR_PICKUP,
        "取尾号" to ExpressStatus.READY_FOR_PICKUP,
        "已到站" to ExpressStatus.ARRIVED_STATION,
        "已到达" to ExpressStatus.ARRIVED_STATION,
        "已入柜" to ExpressStatus.ARRIVED_STATION,
        "派送中" to ExpressStatus.DELIVERING,
        "正在派送" to ExpressStatus.DELIVERING,
        "派件中" to ExpressStatus.DELIVERING,
        "运输中" to ExpressStatus.IN_TRANSIT,
        "已发出" to ExpressStatus.IN_TRANSIT,
        "已发货" to ExpressStatus.IN_TRANSIT,
        "已揽收" to ExpressStatus.PICKED_UP,
        "已揽件" to ExpressStatus.PICKED_UP,
        "已收件" to ExpressStatus.PICKED_UP,
        "已下单" to ExpressStatus.CREATED,
        "待发货" to ExpressStatus.CREATED,
        "等待揽收" to ExpressStatus.CREATED,
        "待揽收" to ExpressStatus.CREATED,
    )

    fun parseTrackingNumber(text: String): String? {
        for (pattern in TRACKING_PATTERNS) {
            for (match in pattern.findAll(text.uppercase())) {
                val candidate = match.groupValues[1]
                if (candidate.all(Char::isDigit)) {
                    if (candidate.length >= 12 && PHONE_WITH_COUNTRY_CODE.matches(candidate)) continue
                    if (candidate.length >= 12 &&
                        (candidate.startsWith("19") || candidate.startsWith("20"))
                    ) {
                        continue
                    }
                }
                return candidate
            }
        }
        return null
    }

    fun parsePickupCode(text: String): String? {
        for (pattern in PICKUP_CODE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }

    /** 驿站 / 快递柜名称：关键词 + 向前啃地名 + 向后啃门店后缀（含括号门店名）。 */
    fun parseStation(text: String): String? {
        val index = firstKeywordIndex(text) ?: return null
        val keyword = STATION_KEYWORDS.first { text.startsWith(it, index) }
        val head = stationHead(text, index)
        val tail = stationTail(text, index + keyword.length)
        return (head + keyword + tail).ifBlank { null }
    }

    /** 第一个命中的驿站关键词的下标，同位置取最长。 */
    private fun firstKeywordIndex(text: String): Int? {
        for (index in text.indices) {
            if (STATION_KEYWORDS.any { text.startsWith(it, index) }) return index
        }
        return null
    }

    /** 关键词之前那一段地名，遇到虚词或非文字字符就停。 */
    private fun stationHead(text: String, end: Int): String {
        var start = end
        while (start > 0 && end - start < MAX_STATION_HEAD) {
            val c = text[start - 1]
            if (!c.isLetterOrDigit()) break
            val endsWithBreak = STATION_HEAD_BREAK.any { word ->
                start - word.length >= 0 && text.regionMatches(start - word.length, word, 0, word.length)
            }
            if (endsWithBreak) break
            start--
        }
        return text.substring(start, end)
    }

    /** 关键词之后那一段；紧跟的括号门店名整段收下。 */
    private fun stationTail(text: String, from: Int): String {
        var end = from
        while (end < text.length && end - from < MAX_STATION_TAIL) {
            if (STATION_TAIL_BREAK.any { text.startsWith(it, end) }) break
            val c = text[end]
            if (!c.isLetterOrDigit() && c !in STATION_TAIL_LINK_CHARS) break
            end++
        }
        var probe = end
        while (probe < text.length && text[probe].isWhitespace()) probe++
        val close = when (text.getOrNull(probe)) {
            '(' -> ')'
            '（' -> '）'
            else -> return text.substring(from, end)
        }
        val closeAt = text.indexOf(close, probe + 1)
        if (closeAt < 0 || closeAt - probe > MAX_STATION_TAIL) return text.substring(from, end)
        return text.substring(from, closeAt + 1)
    }

    /** 收件手机尾号（4 位数字），抽不到返回 null。 */
    fun parsePhoneTail(text: String): String? {
        for (pattern in PHONE_TAIL_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    /** 包裹尾号；与 parsePhoneTail 是两回事（那个是报给店员的号），别互相顶掉。 */
    fun parseParcelTail(text: String): String? {
        for (pattern in PARCEL_TAIL_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    fun parseStatus(text: String): ExpressStatus {
        for ((keyword, status) in STATUS_RULES) {
            if (text.contains(keyword)) return status
        }
        return ExpressStatus.UNKNOWN
    }

    fun parse(
        sourcePackage: String,
        title: String?,
        text: String,
        verdict: ExpressVerdict,
        timestamp: Long = 0L,
    ): ExpressRecord {
        val trackingNumber = parseTrackingNumber(text)
        return ExpressRecord(
            sourcePackage = sourcePackage,
            rawText = text,
            trackingNumber = trackingNumber,
            courier = Courier.fromTrackingNumber(trackingNumber),
            pickupCode = parsePickupCode(text),
            station = parseStation(text),
            phoneTail = parsePhoneTail(text),
            parcelTail = parseParcelTail(text),
            status = parseStatus(text),
            title = title,
            matchedKeywords = verdict.matchedKeywords,
            confidence = verdict.confidence,
            timestamp = timestamp,
        )
    }
}
