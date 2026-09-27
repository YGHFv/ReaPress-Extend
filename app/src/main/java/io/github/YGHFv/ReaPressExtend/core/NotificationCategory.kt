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

/** 快递通知的分类，「设置 → 通知拦截」开关的键，由 [ExpressStatus] 折出。七类只有五个可拦：到站取件与投递异常是模块存在的理由，不给开关；被拦的原通知直接吞掉但留 kind = INTERCEPTED 审计。声明顺序 = 设置页显示顺序。 */
enum class NotificationCategory(
    val displayName: String,
    val summary: String,
    val toggleable: Boolean,
) {
    ACQUISITION("揽件通知", "已揽收 / 已收件 / 待发货", toggleable = true),

    TRANSIT("运输动态", "运输中 / 已发出 / 已到转运中心", toggleable = true),

    DELIVERY("派送通知", "派送中 / 正在派件", toggleable = true),

    SIGNED("签收通知", "已签收 / 已代收", toggleable = true),

    UNKNOWN("其他提醒", "认不出具体状态的快递提醒", toggleable = true),

    ARRIVAL("到站取件", "已到站 / 待取件（含取件码）", toggleable = false),

    EXCEPTION("投递异常", "投递失败 / 无人接收", toggleable = false),
    ;

    companion object {

        val toggleable: List<NotificationCategory> = entries.filter { it.toggleable }

        /** 审计里存的是枚举名而非序号；认不出返回 null，由调用方兜。 */
        fun byName(name: String?): NotificationCategory? =
            name?.trim()?.takeIf { it.isNotBlank() }?.let { byEnumName[it] }

        /** when 穷举（没有 else）：[ExpressStatus] 加值时编译器会挡住。 */
        fun of(status: ExpressStatus): NotificationCategory = when (status) {
            ExpressStatus.CREATED, ExpressStatus.PICKED_UP -> ACQUISITION
            ExpressStatus.IN_TRANSIT -> TRANSIT
            ExpressStatus.DELIVERING -> DELIVERY
            ExpressStatus.SIGNED -> SIGNED
            ExpressStatus.ARRIVED_STATION, ExpressStatus.READY_FOR_PICKUP -> ARRIVAL
            ExpressStatus.FAILED -> EXCEPTION
            ExpressStatus.UNKNOWN -> UNKNOWN
        }

        /** 只还原可拦的分类：设置来自 prefs，手改 XML 就能绕开界面，而「到站通知被吞掉」不该由配置文件决定。 */
        fun parse(names: Collection<String>?): Set<NotificationCategory> {
            val byName = entries.associateBy { it.name }
            return names.orEmpty()
                .mapNotNull { byName[it.trim()] }
                .filter { it.toggleable }
                .toSet()
        }

        fun names(categories: Set<NotificationCategory>): List<String> =
            categories.filter { it.toggleable }.map { it.name }.sorted()

        private val byEnumName: Map<String, NotificationCategory> = entries.associateBy { it.name }
    }
}
