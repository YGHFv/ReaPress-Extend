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

    /**
     * **不值得拦截**的状态：命中这些状态的通知一律放行（原通知照常出现，模块不记一条）。
     *
     * 为什么是「已揽收」：这一刻包裹刚被快递员收走，离用户还隔着好几天的转运，
     * 用户在这个节点唯一能做的就是等 —— 拦下来记一条，只会在首页堆一张几天都不会变的卡片，
     * 而真正的节点（到站、派送、签收）反而被这些噪音挤下去。
     *
     * ⚠️ 这里只影响**通知链路**（[ExpressClassifier] 只在 system_server 里跑）。
     * 菜鸟宿主读到的同一状态的件**照旧会作为记录落库**（见 `CainiaoPackageHook`）——
     * 那是「我的包裹」页该有的完整清单，两者不矛盾：通知不打扰，清单不失真。
     *
     * 判据取 [ExpressParser.parseStatus] 的结果而不是文本包含：状态表是**有序**的，
     * 「已到站…已揽收」这种合并文案会先命中「已到站」，不会被这里误伤。
     */
    private val SILENT_STATUSES = setOf(ExpressStatus.PICKED_UP)

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

        // 状态只解一次：下面既要用它做「不值得拦」的早退，也要拿它加分。
        // 放在关键词命中之后 —— 一条关键词都没命中的通知压根不是候选，不必为它遍历状态表。
        val status = ExpressParser.parseStatus(text)
        if (status in SILENT_STATUSES) {
            return ExpressVerdict(
                isExpress = false,
                confidence = 0,
                // 保留命中词：日志里能看到「它本来是会被拦的，是状态规则放行的」。
                matchedKeywords = matched,
                ignoredStatus = status,
            )
        }

        var score = minOf(matched.size, MAX_KEYWORD_HITS) * KEYWORD_SCORE

        if (ExpressParser.parsePickupCode(text) != null) score += PICKUP_CODE_SCORE
        if (ExpressParser.parseTrackingNumber(text) != null) score += TRACKING_NUMBER_SCORE
        if (ExpressParser.parseStation(text) != null) score += STATION_SCORE
        if (status != ExpressStatus.UNKNOWN) score += STATUS_SCORE

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
