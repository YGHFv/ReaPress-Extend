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
 * 身份码来自哪个平台：它是各平台自己发的取件凭证、彼此不通用，所以归属跟着驿站走（驿站属性）。
 * PDD 的 anti_content 在 native 层算不出，取码未实现 —— supported=false 时如实说明，绝不回退到菜鸟码。
 */
enum class IdentitySource(val displayName: String) {

    CAINIAO("菜鸟"),

    PDD("拼多多"),
    ;

    /** false = 取码暂未支持，不是这一项没用。 */
    val supported: Boolean get() = this == CAINIAO

    companion object {
        /** 不认识的一律返回 null（当没设过）；刻意不猜成 [CAINIAO]。 */
        fun parse(raw: String?): IdentitySource? {
            if (raw.isNullOrBlank()) return null
            return values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}
