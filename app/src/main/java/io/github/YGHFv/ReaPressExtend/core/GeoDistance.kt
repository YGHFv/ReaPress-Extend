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

/**
 * 两点间的球面距离 —— 「哪个驿站离我最近」用它算。
 *
 * ## 为什么是 Haversine
 *
 * 驿站的坐标直接从宿主库里读（`packageStation.stationLat` / `stationLng`），量级是「同一个城市里
 * 几百米到几公里」，用等距圆柱（把经纬度当成平面直角坐标）在这个尺度上误差极小，但**换城市、
 * 换纬度**时经度方向的尺度会漂。Haversine 只有几行、无依赖、全纬度都准，没有省它的理由。
 *
 * ## 纯函数
 *
 * 不碰 Android、不碰时钟（现在的位置由调用方传进来）—— 「最近的那个」这种选择规则必须能用
 * 单测钉住，否则它只能靠真机试错。
 */
object GeoDistance {

    /** 地球平均半径（米）。取 WGS84 平均半径，误差量级远小于驿站定位本身的精度。 */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * 两个坐标之间的大圆距离（米）。
     *
     * 纬度先转弧度再进公式 —— 少了这一步算出来的数会差几十倍，而这种错在真机上表现为
     * 「最近驿站选错了」，看起来像数据问题，很难往回查到公式上。
     */
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        // 浮点误差会让 a 略微超过 1，asin 的定义域就爆了 —— 先夹住。
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    /**
     * 从候选里挑离 [lat]/[lon] 最近的那个。
     *
     * [positionOf] 返回 null 表示这一条没有坐标（宿主很多行确实没填 `stationLat`/`stationLng`）——
     * 这种候选**直接排除**，不参与比较：拿它们当「距离无穷远」也能得到同样的结果，但万一所有候选
     * 都没坐标，显式排除能让我们干净地返回 null，由调用方决定降级方案。
     *
     * 全部候选都没坐标时返回 null，**不瞎猜一个**：选错驿站会让用户白跑一趟，比「不知道选哪个」严重。
     */
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
