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
 * 判定规则。
 *
 * 全部是纯数据 + 纯函数：规则的编辑入口在模块 UI，判定执行在 system_server，
 * 两边共用这一份定义，避免「UI 里改了关键词但 hook 里还是老规则」。
 */
data class ExpressRule(
    /** 来源包名白名单。不在名单里的包一律放行，不做任何判定。 */
    val sourcePackages: Set<String> = DEFAULT_SOURCES,
    /** 命中任一关键词才认为可能是快递通知。 */
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    /** 用户自定义的额外关键词（与 [keywords] 取并集）。 */
    val extraKeywords: Set<String> = emptySet(),
    /** 用户自定义的排除关键词：命中即放行，优先级高于 [keywords]。 */
    val excludeKeywords: Set<String> = emptySet(),
    /**
     * 额外的放行来源，不受 [sourcePackages] 与 [handleSms] 约束。
     *
     * 唯一的用途是**真机验证**：debug 构建把 `com.android.shell` 放进来，就能用
     * `adb shell cmd notification post` 造一条通知走完整链路，不必等真实快递推送。
     *
     * 放在这里而不是硬编码进 [ExpressClassifier]：core 层要保持无 Android 依赖
     * （它跑在 JVM 单测里），而「是否 debug 构建」是 BuildConfig 才知道的事 ——
     * 由 config 层填好传进来。
     */
    val extraAllowedSources: Set<String> = emptySet(),
    /** 置信度低于此值则放行原通知，不拦截。 */
    val confidenceThreshold: Int = DEFAULT_THRESHOLD,
    /** 是否处理短信（com.android.mms 的通知）。 */
    val handleSms: Boolean = true,
) {
    /** 实际生效的关键词集合。 */
    val effectiveKeywords: Set<String> get() = keywords + extraKeywords

    companion object {
        val DEFAULT_SOURCES: Set<String> = setOf(
            "com.cainiao.wireless",
            "com.xunmeng.pinduoduo",
            "com.taobao.taobao",
            "com.android.mms",
        )

        /**
         * 关键词表。
         *
         * 取舍标准：宁可漏判（放行原通知，用户照常看到）也不要误判（吞掉非快递通知）。
         * 所以只收**几乎只出现在快递语境**的词，像「订单」「物流」「发货」这种电商通用词
         * 不收 —— 它们会把「您的订单已发货」这类营销推送也吞掉。
         */
        val DEFAULT_KEYWORDS: Set<String> = setOf(
            // 包裹本体
            "快递", "包裹", "运单", "快件", "邮包",
            // 取件环节
            "取件码", "取件", "驿站", "快递柜", "自提", "丰巢", "菜鸟驿站", "代收点",
            // 状态
            "已到站", "到站", "派送", "派件", "已揽收", "揽收", "签收", "待取件", "已到达",
            // 公司名
            "顺丰", "圆通", "中通", "申通", "韵达", "京东物流", "德邦", "极兔", "百世", "邮政",
        )

        const val DEFAULT_THRESHOLD = 50
    }
}

/** 判定结果。 */
data class ExpressVerdict(
    /** 是否判定为快递通知。 */
    val isExpress: Boolean,
    /** 置信度 0..100。 */
    val confidence: Int,
    /** 命中的关键词（用于在界面上解释判定依据）。 */
    val matchedKeywords: List<String>,
    /** 命中的排除词（非空时 [isExpress] 必为 false）。 */
    val excludedBy: String? = null,
    /**
     * 因状态不值得拦截而放行时的那个状态（见 [ExpressClassifier.SILENT_STATUSES]）。
     *
     * 与 [excludedBy] 分开：那个是**用户配的词**，这里是模块自己的规则。
     * 混用一个字段的话，日志里看到「排除词：已揽件」会让人以为用户在设置页里配过它。
     */
    val ignoredStatus: ExpressStatus? = null,
)
