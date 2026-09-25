package io.github.YGHFv.ReaPressExtend.core

/**
 * 判定「这条通知是不是快递通知」。
 *
 * 纯函数，输入只有包名和文本 —— system_server 里跑，必须快且不能抛。
 *
 * 打分思路：关键词命中数 + 结构化字段是否抽到，两者都有才给高分。
 * 只看关键词的话，「您的快递已发货」这种营销推送也会被拦；只看结构化字段的话，
 * 「您的包裹已到菜鸟驿站」没有运单号就会被漏掉。两者结合最稳。
 */
object ExpressClassifier {

    /** 单条关键词的权重。命中越多分越高，但设上限避免长文本堆分。 */
    private const val KEYWORD_SCORE = 20
    private const val MAX_KEYWORD_HITS = 3

    /** 抽到结构化字段的加分。 */
    private const val PICKUP_CODE_SCORE = 35
    private const val TRACKING_NUMBER_SCORE = 25
    private const val STATION_SCORE = 15
    private const val STATUS_SCORE = 10

    fun classify(sourcePackage: String, text: String, rule: ExpressRule): ExpressVerdict {
        if (text.isBlank()) {
            return ExpressVerdict(isExpress = false, confidence = 0, matchedKeywords = emptyList())
        }

        // 排除词优先：命中即放行，且不看后面任何加分。
        // 用途：用户被某类推送烦到，加个词就能让原通知照常出现（而不是被模块吞掉）。
        val excluded = rule.excludeKeywords.firstOrNull { it.isNotBlank() && text.contains(it) }
        if (excluded != null) {
            return ExpressVerdict(
                isExpress = false,
                confidence = 0,
                matchedKeywords = emptyList(),
                excludedBy = excluded,
            )
        }

        val matched = rule.effectiveKeywords
            .filter { it.isNotBlank() && text.contains(it) }
            .sortedByDescending { it.length }
        if (matched.isEmpty()) {
            return ExpressVerdict(isExpress = false, confidence = 0, matchedKeywords = emptyList())
        }

        var score = minOf(matched.size, MAX_KEYWORD_HITS) * KEYWORD_SCORE

        if (ExpressParser.parsePickupCode(text) != null) score += PICKUP_CODE_SCORE
        if (ExpressParser.parseTrackingNumber(text) != null) score += TRACKING_NUMBER_SCORE
        if (ExpressParser.parseStation(text) != null) score += STATION_SCORE
        if (ExpressParser.parseStatus(text) != ExpressStatus.UNKNOWN) score += STATUS_SCORE

        val confidence = score.coerceIn(0, 100)
        return ExpressVerdict(
            isExpress = confidence >= rule.confidenceThreshold,
            confidence = confidence,
            matchedKeywords = matched,
        )
    }

    /**
     * 该包名是否在管辖范围内。
     *
     * 短信单独开关：短信通知里快递只占很小一部分，用户可能只想处理 App 推送。
     */
    fun isSourceAllowed(sourcePackage: String, rule: ExpressRule): Boolean {
        // 显式放行的来源优先于一切开关（用途见 ExpressRule.extraAllowedSources）。
        if (sourcePackage in rule.extraAllowedSources) return true
        if (sourcePackage == SMS_PACKAGE) return rule.handleSms
        return sourcePackage in rule.sourcePackages
    }

    const val SMS_PACKAGE = "com.android.mms"
}
