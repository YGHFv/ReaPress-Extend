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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宿主驿站坐标的归一化。
 *
 * 值得钉住的原因：算错的后果是**「最近的驿站选错了」**，看起来像数据问题而不是单位问题 ——
 * 真机上用户只会说「显示不对」，而这两组样本就是从真机记录里抄出来的。
 */
class GeoPointTest {

    /** 真机样本一：合肥南湖春城华韵古筝隔壁（`stationLat=3176936, stationLng=11726814`）。 */
    @Test
    fun `宿主放大 1e5 的坐标被还原成度`() {
        val point = geoPointOf(3176936.0, 11726814.0)!!
        assertEquals(31.76936, point.lat, 1e-9)
        assertEquals(117.26814, point.lng, 1e-9)
    }

    /** 真机样本二：阜阳颍滨花园店。 */
    @Test
    fun `另一个真机样本`() {
        val point = geoPointOf(3294167.0, 11581004.0)!!
        assertEquals(32.94167, point.lat, 1e-9)
        assertEquals(115.81004, point.lng, 1e-9)
    }

    /**
     * 幂等 —— 这个性质是「写入侧与读取侧都过一遍」的前提。
     * 历史记录里存的是放大值、新写进来的是度值，两边得到同一个点才对得上。
     */
    @Test
    fun `已经归一的值原样返回`() {
        val point = geoPointOf(31.76936, 117.26814)!!
        assertEquals(31.76936, point.lat, 1e-9)
        assertEquals(117.26814, point.lng, 1e-9)

        // 已经是度值的合法边界，不该被再除一次（除两次会得到格陵兰附近的点）。
        assertEquals(31.7734, geoPointOf(31.7734, 117.26441)!!.lat, 1e-9)
    }

    @Test
    fun `没有坐标的几种情形`() {
        assertNull(geoPointOf(null, 117.0))
        assertNull(geoPointOf(31.0, null))
        assertNull(geoPointOf(null, null))
        // (0, 0) 是几内亚湾 —— 宿主用 0 表示「没给」的概率远大于真有用户站在那里。
        assertNull(geoPointOf(0.0, 0.0))
        assertNull(geoPointOf(Double.NaN, 117.0))
        assertNull(geoPointOf(Double.POSITIVE_INFINITY, 117.0))
    }

    /** 除完还是越界 = 认不出来。**宁可当没有**，也不硬猜一个坐标把用户指向几百公里外。 */
    @Test
    fun `认不出的坐标当没有`() {
        assertNull(geoPointOf(999_999_999.0, 999_999_999.0))
        assertNull(geoPointOf(31.0, 999_999_999.0))
    }

    /** 纬度合法但经度越界的放大值：整体当成没有（只归一化一半比不归一化更危险）。 */
    @Test
    fun `只越界一个分量时整体判为没有`() {
        // lat / 1e5 = 31.77 合法，lng / 1e5 = 1172.6411 越界 → 认不出。
        assertNull(geoPointOf(3_177_340.0, 117_264_110.0))
    }
}
