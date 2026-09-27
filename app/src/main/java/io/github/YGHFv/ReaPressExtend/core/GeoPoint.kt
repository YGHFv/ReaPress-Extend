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

/** 一个经纬度点（WGS84 度）。 */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * 把宿主给的驿站坐标（放大 1e5 的整数）归一化成 WGS84 度；幂等 —— 已合法的分量原样返回。
 * 缺一半 / 非有限 / (0,0) / 除完仍越界一律返回 null，不硬猜。
 */
fun geoPointOf(lat: Double?, lng: Double?): GeoPoint? {
    if (lat == null || lng == null) return null
    if (!lat.isFinite() || !lng.isFinite()) return null
    if (lat == 0.0 && lng == 0.0) return null
    if (isValidLatitude(lat) && isValidLongitude(lng)) return GeoPoint(lat, lng)

    val scaled = GeoPoint(lat / HOST_COORDINATE_SCALE, lng / HOST_COORDINATE_SCALE)
    return scaled.takeIf { isValidLatitude(it.lat) && isValidLongitude(it.lng) }
}

/** 只认这一个系数，不反复除到合法为止。 */
private const val HOST_COORDINATE_SCALE = 100_000.0

private fun isValidLatitude(value: Double): Boolean = value in -90.0..90.0

private fun isValidLongitude(value: Double): Boolean = value in -180.0..180.0
