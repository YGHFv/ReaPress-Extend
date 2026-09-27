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
 * 一个取件地点的现场指纹（当前只采集、不判定）。纯数据：不碰 Android、不碰时钟。
 */
data class StationFingerprint(
    val position: GeoPoint? = null,
    val accuracyMeters: Float? = null,
    /** BSSID 小写、已去重；顺序即优先级，当前连接的那个排最前。空列表不是错误。 */
    val wifi: List<String> = emptyList(),
    val capturedAt: Long = 0L,
) {
    /** 写入侧据此拒绝落库空指纹。 */
    val isEmpty: Boolean get() = position == null && wifi.isEmpty()
}
