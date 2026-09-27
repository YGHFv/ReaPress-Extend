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

/** 判定「这条通知是不是快递通知」。纯函数（system_server 里跑，必须快且不能抛）：关键词命中数 + 结构化字段是否抽到，两者都有才给高分 —— 只看词会拦营销推送，只看字段会漏无运单号的文案。 */
object ExpressClassifier {

    private const val KEYWORD_SCORE = 20
    private const val MAX_KEYWORD_HITS = 3

    private const val PICKUP_CODE_SCORE = 35
    private const val TRACKING_NUMBER_SCORE = 25
    private const val STATION_SCORE = 15
    private const val STATUS_SCORE = 10

    /**
     * 不值得拦截的状态：原通知照常放行、模块也不记。这是模块的默认规矩；用户的显式拦截走
     * [ExpressRule.interceptedCategories]（那个是吞掉），两者落在同一状态上重叠时听用户，
     * 所以 [classify] 里分类判定排在这条之前。只影响通知链路：宿主读到的同状态件照旧落库。
     * 判据取 [ExpressParser.parseStatus] 的有序结果，合并文案不会被这里误伤。
     */
    private val SILENT_STATUSES = setOf(ExpressStatus.PICKED_UP)

    fun classify(sourcePackage: String, text: String, rule: ExpressRule): ExpressVerdict {
        if (text.isBlank()) {
            return ExpressVerdict(isExpress = false, confidence = 0, matchedKeywords = emptyList())
        }

        // 排除词优先：命中即放行，且不看后面任何加分 —— 用户加个词就能让原通知照常出现。
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

        // 状态与取件码各只解一次：早退、分类判定与加分都要用；放在关键词命中之后省遍历。
        val status = ExpressParser.parseStatus(text)
        val pickupCode = ExpressParser.parsePickupCode(text)

        var score = minOf(matched.size, MAX_KEYWORD_HITS) * KEYWORD_SCORE

        if (pickupCode != null) score += PICKUP_CODE_SCORE
        if (ExpressParser.parseTrackingNumber(text) != null) score += TRACKING_NUMBER_SCORE
        if (ExpressParser.parseStation(text) != null) score += STATION_SCORE
        if (status != ExpressStatus.UNKNOWN) score += STATUS_SCORE

        val confidence = score.coerceIn(0, 100)
        val isExpress = confidence >= rule.confidenceThreshold

        // 分类判定必须排在 SILENT_STATUSES 之前：两者可能落在同一状态（都盯「已揽收」），
        // 重叠时听用户的显式选择。三个限定都是刻意的：
        // - toggleable：判定层自己不让到站/异常进来（设置侧 parse 已挡一道，这里是第二道防线）；
        // - isExpress：置信度不达标不吞 —— 宁可漏拦，不可误吞；
        // - pickupCode == null：取件码是取件凭据，不该被分类开关清掉。
        if (isExpress && pickupCode == null) {
            val category = NotificationCategory.of(status)
            if (category.toggleable && category in rule.interceptedCategories) {
                return ExpressVerdict(
                    isExpress = true,
                    confidence = confidence,
                    matchedKeywords = matched,
                    interceptedCategory = category,
                )
            }
        }

        if (status in SILENT_STATUSES) {
            return ExpressVerdict(
                isExpress = false,
                confidence = 0,
                matchedKeywords = matched,
                ignoredStatus = status,
            )
        }

        return ExpressVerdict(
            isExpress = isExpress,
            confidence = confidence,
            matchedKeywords = matched,
        )
    }

    /** 短信单独开关：短信里快递只占很小一部分，用户可能只想处理 App 推送。 */
    fun isSourceAllowed(sourcePackage: String, rule: ExpressRule): Boolean {
        if (sourcePackage in rule.extraAllowedSources) return true
        if (sourcePackage == SMS_PACKAGE) return rule.handleSms
        return sourcePackage in rule.sourcePackages
    }

    const val SMS_PACKAGE = "com.android.mms"
}
