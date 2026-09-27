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

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Haversine 球面距离 —— 「哪个驿站离我最近」用它算。 */
object GeoDistance {

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        // 浮点误差会让 a 略微超过 1，asin 越界 —— 先夹住。
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    fun <T> nearest(
        lat: Double,
        lon: Double,
        items: List<T>,
        positionOf: (T) -> Pair<Double, Double>?,
    ): T? {
        var best: T? = null
        var bestDistance = Double.MAX_VALUE
        for (item in items) {
            val (itemLat, itemLon) = positionOf(item) ?: continue
            val distance = meters(lat, lon, itemLat, itemLon)
            if (distance < bestDistance) {
                bestDistance = distance
                best = item
            }
        }
        return best
    }
}
