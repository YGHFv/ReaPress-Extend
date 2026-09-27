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
 * 快递通知的**分类** —— 「设置 → 通知拦截」那一组开关的键。
 *
 * ## 分类是怎么来的
 *
 * 由 [ExpressStatus] 折出来，不另做一套判据：[ExpressParser.STATUS_RULES] 已经把各家文案的
 * 说法收干净了，分类只是把相邻的几个状态归成「用户眼里的一类事」——
 * 「已揽收」「已收件」「等快递员来取」在用户看来是同一件事：**东西还在路上，跟今天的我没关系**。
 *
 * ## 为什么只有一部分分类能拦
 *
 * [toggleable] 为 false 的两个（到站取件 / 投递异常）是这个模块存在的理由：一个是「去哪取、
 * 凭取件码」，一个是「没送到，得去处理」。它们不给开关 —— 用户真想让这些也别提醒，那该把
 * 工作模式设成「关闭」，而不是在一排分类里找一个会让模块失去意义的开关。
 *
 * ## 拦截的语义
 *
 * 见 [ExpressClassifier] 与 `SystemServerHook`：被拦下的分类**原通知直接吞掉，不发通知也不
 * 提醒**（纯噪音就该消失，而不是换个名字再出现）。2026-09-27 起会**留一条拦截审计**
 * （`ExpressNotificationLog`，kind = INTERCEPTED）—— 那是给用户事后核对的，不会产生任何提醒。
 * 与工作模式的「拦截并替换」不同 —— 那个是吞掉原通知后**自己再发一条**。
 *
 * ## 顺序
 *
 * 声明顺序 = 设置页里的显示顺序，按包裹从早到晚排：揽件 → 运输 → 派送 → 签收 → 其他。
 */
enum class NotificationCategory(
    /** 设置页里那一行的标题、以及日志里的名字。 */
    val displayName: String,
    /**
     * 设置页那一行的副标题。
     *
     * 举的是**宿主原话**（状态表里的词），用户对着自己的通知栏能直接对上号 ——
     * 只写「揽件类通知」的话，用户得先猜「我这算揽件还是运输」。
     */
    val summary: String,
    /** 能不能被拦截。false = 始终保留，界面不给开关（理由见类注释）。 */
    val toggleable: Boolean,
) {
    /** 揽件：包裹刚被快递员拿走，离用户还隔着好几天的转运 —— 最典型的「跟我没关系」。 */
    ACQUISITION("揽件通知", "已揽收 / 已收件 / 待发货", toggleable = true),

    /** 运输中：在转运中心之间走。用户能做的只有等。 */
    TRANSIT("运输动态", "运输中 / 已发出 / 已到转运中心", toggleable = true),

    /**
     * 派送中：就快到了，但还没到「能去取」的程度。
     *
     * 与 [TRANSIT] 分开而不是并成一类：这两个节点在用户眼里差别很大 —— 运输中的件可以完全
     * 不理，派送中的件往往意味着「今天在家等着」。并起来的话，想清掉转运噪音就必然连
     * 派送提醒一起清掉，那是最不该丢的一条。
     */
    DELIVERY("派送通知", "派送中 / 正在派件", toggleable = true),

    /** 已签收 / 已代收：包裹已经闭环，用户不需要为它做任何事。 */
    SIGNED("签收通知", "已签收 / 已代收", toggleable = true),

    /** 认不出具体状态的快递提醒（文案里没有状态词，只有「包裹」之类）。 */
    UNKNOWN("其他提醒", "认不出具体状态的快递提醒", toggleable = true),

    /** 到站 / 待取件 —— 核心价值，不给开关。 */
    ARRIVAL("到站取件", "已到站 / 待取件（含取件码）", toggleable = false),

    /** 投递异常 —— 要用户去处理的事，不给开关。 */
    EXCEPTION("投递异常", "投递失败 / 无人接收", toggleable = false),
    ;

    companion object {

        /** 设置页要画的那几个，顺序就是声明顺序。 */
        val toggleable: List<NotificationCategory> = entries.filter { it.toggleable }

        /**
         * 按枚举名找回分类。
         *
         * 用在**记录回放**上：拦截审计里存的是名字（不是序号 —— 序号会随枚举增删错位），
         * 界面要把它显示成用户看得懂的中文。认不出返回 null，由调用方决定怎么兜
         * （显示原始名字 / 干脆不显示那一行），不在这里编一个默认值。
         */
        fun byName(name: String?): NotificationCategory? =
            name?.trim()?.takeIf { it.isNotBlank() }?.let { byEnumName[it] }

        /** 一个状态属于哪一类。when 写穷举（没有 else）：[ExpressStatus] 加值时编译器会挡住。 */
        fun of(status: ExpressStatus): NotificationCategory = when (status) {
            ExpressStatus.CREATED, ExpressStatus.PICKED_UP -> ACQUISITION
            ExpressStatus.IN_TRANSIT -> TRANSIT
            ExpressStatus.DELIVERING -> DELIVERY
            ExpressStatus.SIGNED -> SIGNED
            ExpressStatus.ARRIVED_STATION, ExpressStatus.READY_FOR_PICKUP -> ARRIVAL
            ExpressStatus.FAILED -> EXCEPTION
            ExpressStatus.UNKNOWN -> UNKNOWN
        }

        /**
         * 从设置里存的字符串还原成分类集合。
         *
         * 两种值会被丢掉，都是**有意**的：
         * - 认不出的名字（旧版本留下的、手改过的 XML）；
         * - [toggleable] 为 false 的分类。
         *
         * 第二条是**第二道防线**，不能只靠界面不给开关：设置是从 prefs 读出来的，手改一份
         * XML（或将来某个 bug 把 ARRIVAL 写进去）就能绕开界面，而「到站通知被吞掉」这种后果
         * 不该由配置文件决定。
         */
        fun parse(names: Collection<String>?): Set<NotificationCategory> {
            val byName = entries.associateBy { it.name }
            return names.orEmpty()
                .mapNotNull { byName[it.trim()] }
                .filter { it.toggleable }
                .toSet()
        }

        /** 写回设置。同样只写可拦截的那几个（排序后写，让 prefs 里的字符串稳定、便于比对）。 */
        fun names(categories: Set<NotificationCategory>): List<String> =
            categories.filter { it.toggleable }.map { it.name }.sorted()

        /**
         * 名字 → 分类。**包含不可拦截的两个**（[byName] 用它查记录），与 [parse] 的口径不同：
         * 那个是「能不能被拦」的白名单，这个只是「认不认得这个名字」的字典。
         */
        private val byEnumName: Map<String, NotificationCategory> = entries.associateBy { it.name }
    }
}
