package io.github.YGHFv.ReaPressExtend.core

/**
 * 从通知文本里抽取结构化字段。
 *
 * 全部是纯函数、只吃 String：system_server 里不能碰 Notification 对象做复杂操作
 * （那会在 NMS 的关键路径上加锁/耗时），所以调用方先把文本取出来，这里只做字符串处理。
 */
object ExpressParser {

    /**
     * 运单号候选。
     *
     * 顺序即优先级 —— 越靠前的越不容易误判：
     * 1. 字母前缀型（SF1234567890123、YT...）：前缀唯一，误判率极低
     * 2. 纯数字 12-15 位：主流国内快递的长度区间
     *
     * **刻意不收 11 位纯数字**：那是手机号。快递短信里手机号几乎必然出现，
     * 收进来会把手机号当运单号。宁可漏掉 11 位的运单号。
     */
    private val TRACKING_PATTERNS = listOf(
        Regex("""\b([A-Z]{2,3}\d{9,13})\b"""),
        Regex("""\b(\d{12,15})\b"""),
    )

    /** 取件码：「取件码 8-2-3021」「取件码：1234」「提货码 A1234」 */
    private val PICKUP_CODE_PATTERNS = listOf(
        Regex("""(?:取件码|提货码|取货码|提取码)\s*[:：]?\s*([0-9A-Za-z][0-9A-Za-z\-]{2,15})"""),
        // 菜鸟驿站的「货架-层-序号」格式，没有「取件码」前缀也认得出来
        Regex("""\b(\d{1,3}-\d{1,2}-\d{3,6})\b"""),
    )

    /** 驿站 / 快递柜名称。括号内容一起收，因为门店名常在括号里。 */
    private val STATION_PATTERNS = listOf(
        Regex("""((?:菜鸟驿站|丰巢|快递柜|驿站|代收点|自提柜|服务中心)[^，。！\n]{0,20}(?:\([^)）]{0,20}\)|（[^)）]{0,20}）)?)"""),
    )

    /**
     * 状态关键词 → 状态。**顺序敏感**：越具体的越靠前。
     *
     * 例如「已签收」必须在「签收」之前判，否则「已签收」会先命中「签收」；
     * 「投递失败」必须在「派送」之前判，因为失败通知里常常也写着「派送」。
     */
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
        "已到站" to ExpressStatus.ARRIVED_STATION,
        "已到达" to ExpressStatus.ARRIVED_STATION,
        "已入柜" to ExpressStatus.ARRIVED_STATION,
        "派送中" to ExpressStatus.DELIVERING,
        "正在派送" to ExpressStatus.DELIVERING,
        "派件中" to ExpressStatus.DELIVERING,
        "运输中" to ExpressStatus.IN_TRANSIT,
        "已发出" to ExpressStatus.IN_TRANSIT,
        "已揽收" to ExpressStatus.PICKED_UP,
        "已收件" to ExpressStatus.PICKED_UP,
        "已下单" to ExpressStatus.CREATED,
    )

    fun parseTrackingNumber(text: String): String? {
        for (pattern in TRACKING_PATTERNS) {
            val match = pattern.find(text.uppercase()) ?: continue
            val candidate = match.groupValues[1]
            // 纯数字的再排一次「看起来像年份/电话」的：以 19/20 开头的 12 位可能是时间戳。
            if (candidate.all(Char::isDigit) && candidate.length >= 12) {
                if (candidate.startsWith("19") || candidate.startsWith("20")) continue
            }
            return candidate
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

    fun parseStation(text: String): String? {
        for (pattern in STATION_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1].trim().ifBlank { null }
        }
        return null
    }

    fun parseStatus(text: String): ExpressStatus {
        for ((keyword, status) in STATUS_RULES) {
            if (text.contains(keyword)) return status
        }
        return ExpressStatus.UNKNOWN
    }

    /**
     * 一站式解析。
     *
     * @param text 标题与正文拼好的文本
     * @param verdict 判定结果（关键词命中情况会带进记录，供界面解释）
     */
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
            status = parseStatus(text),
            title = title,
            matchedKeywords = verdict.matchedKeywords,
            confidence = verdict.confidence,
            timestamp = timestamp,
        )
    }
}
