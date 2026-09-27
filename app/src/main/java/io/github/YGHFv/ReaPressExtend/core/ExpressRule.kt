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
 * 判定规则；纯数据 + 纯函数，编辑入口在模块 UI，判定执行在 system_server，两边共用这一份定义。
 */
data class ExpressRule(
    val sourcePackages: Set<String> = DEFAULT_SOURCES,
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    val extraKeywords: Set<String> = emptySet(),
    /** 命中的排除词优先级高于 [keywords]，命中即放行。 */
    val excludeKeywords: Set<String> = emptySet(),
    /** 额外放行来源，不受 [sourcePackages] 与 [handleSms] 约束；真机验证用（debug 放 com.android.shell 以便 adb 造通知）。 */
    val extraAllowedSources: Set<String> = emptySet(),
    val confidenceThreshold: Int = DEFAULT_THRESHOLD,
    val handleSms: Boolean = true,
    /** 用户勾选拦截的分类：原通知直接吞掉但留一条拦截审计；文案含取件码的不吞；与 [ExpressClassifier.SILENT_STATUSES]（只不记、原通知放行）语义不同。 */
    val interceptedCategories: Set<NotificationCategory> = emptySet(),
) {
    val effectiveKeywords: Set<String> get() = keywords + extraKeywords

    companion object {
        val DEFAULT_SOURCES: Set<String> = setOf(
            "com.cainiao.wireless",
            "com.xunmeng.pinduoduo",
            "com.taobao.taobao",
            "com.android.mms",
        )

        val DEFAULT_KEYWORDS: Set<String> = setOf(
            "快递", "包裹", "运单", "快件", "邮包",
            "取件码", "取件", "驿站", "快递柜", "自提", "丰巢", "菜鸟驿站", "代收点",
            "已到站", "到站", "派送", "派件", "已揽收", "揽收", "签收", "待取件", "已到达",
            "顺丰", "圆通", "中通", "申通", "韵达", "京东物流", "德邦", "极兔", "百世", "邮政",
        )

        const val DEFAULT_THRESHOLD = 50
    }
}

data class ExpressVerdict(
    val isExpress: Boolean,
    val confidence: Int,
    val matchedKeywords: List<String>,
    val excludedBy: String? = null,
    val ignoredStatus: ExpressStatus? = null,
    /** 非空 = 原通知要原样消失、只留一条拦截审计；接收侧必须在投递之前先看它，否则会先按 [isExpress] 投递出去。 */
    val interceptedCategory: NotificationCategory? = null,
)
