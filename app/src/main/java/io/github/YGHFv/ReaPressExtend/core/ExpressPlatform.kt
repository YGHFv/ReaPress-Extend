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
 * 收件类型词（「普通收件」之类）不能当来源显示，否则会占掉商品名的位置。[normalize] 幂等，
 * 写入 / 读取 / 渲染三处都要过；表外值原样保留，不猜。
 */
object ExpressPlatform {

    private val CATEGORY_WORDS = setOf(
        "普通收件",
        "普通快递",
        "标准快递",
        "其他",
        "其他收件",
        "未知",
        "无",
        "暂无",
    )

    /** 空白或落在 [CATEGORY_WORDS] 里返回 null，调用方整项省略。 */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.takeIf { it !in CATEGORY_WORDS }
    }
}
