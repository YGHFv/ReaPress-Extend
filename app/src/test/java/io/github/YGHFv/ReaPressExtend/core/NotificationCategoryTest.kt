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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类映射与设置的读写往返。
 *
 * 这里钉的是**边界**而不是「映射对不对」—— 映射本身一眼能看，真正会出事的是两类：
 * 状态枚举加了值没人回来补映射（`when` 穷举会在编译期挡住，这里再兜一道运行期断言），
 * 以及不可拦截的分类从配置里挤进来。
 */
class NotificationCategoryTest {

    @Test
    fun `状态到分类的映射逐条钉住`() {
        // 逐条写死而不是拿 `of` 的返回值自我比对：这张表就是用户勾选开关时真正生效的东西
        // （「揽件通知」到底管不管「待发货」），改动必须是刻意的。
        val expected = mapOf(
            ExpressStatus.CREATED to NotificationCategory.ACQUISITION,
            ExpressStatus.PICKED_UP to NotificationCategory.ACQUISITION,
            ExpressStatus.IN_TRANSIT to NotificationCategory.TRANSIT,
            ExpressStatus.DELIVERING to NotificationCategory.DELIVERY,
            ExpressStatus.SIGNED to NotificationCategory.SIGNED,
            ExpressStatus.ARRIVED_STATION to NotificationCategory.ARRIVAL,
            ExpressStatus.READY_FOR_PICKUP to NotificationCategory.ARRIVAL,
            ExpressStatus.FAILED to NotificationCategory.EXCEPTION,
            ExpressStatus.UNKNOWN to NotificationCategory.UNKNOWN,
        )
        expected.forEach { (status, category) ->
            assertEquals("状态 $status 的分类", category, NotificationCategory.of(status))
        }
        // 每个状态都在表里：ExpressStatus 加了新值而没人回来补映射时，这里会红。
        assertEquals(ExpressStatus.entries.size, expected.size)
    }

    @Test
    fun `到站与投递异常不能拦`() {
        assertFalse(NotificationCategory.ARRIVAL.toggleable)
        assertFalse(NotificationCategory.EXCEPTION.toggleable)
    }

    @Test
    fun `可拦截的分类覆盖全部噪点类状态`() {
        // 揽件/运输/派送/签收/未知 —— 五个开关，缺一个就意味着用户有一类噪音清不掉。
        val toggleable = NotificationCategory.toggleable
        assertEquals(5, toggleable.size)
        assertTrue(NotificationCategory.ACQUISITION in toggleable)
        assertTrue(NotificationCategory.TRANSIT in toggleable)
        assertTrue(NotificationCategory.DELIVERY in toggleable)
        assertTrue(NotificationCategory.SIGNED in toggleable)
        assertTrue(NotificationCategory.UNKNOWN in toggleable)
    }

    @Test
    fun `解析会丢掉不可拦截的分类`() {
        // 「手改 XML 把 ARRIVAL 塞进来」是这条守卫存在的理由：到站通知被吞掉的后果
        // 不该由配置文件决定。
        val parsed = NotificationCategory.parse(
            setOf("ARRIVAL", "EXCEPTION", "ACQUISITION", "NOT_A_CATEGORY", "  TRANSIT  "),
        )
        assertEquals(
            setOf(NotificationCategory.ACQUISITION, NotificationCategory.TRANSIT),
            parsed,
        )
    }

    @Test
    fun `空配置解析为空集`() {
        assertTrue(NotificationCategory.parse(null).isEmpty())
        assertTrue(NotificationCategory.parse(emptySet()).isEmpty())
    }

    @Test
    fun `存出去的名字能原样读回来`() {
        val original = setOf(
            NotificationCategory.SIGNED,
            NotificationCategory.ACQUISITION,
            NotificationCategory.DELIVERY,
        )
        assertEquals(original, NotificationCategory.parse(NotificationCategory.names(original)))
    }

    @Test
    fun `名字已排序且不含不可拦截的分类`() {
        val names = NotificationCategory.names(
            setOf(NotificationCategory.TRANSIT, NotificationCategory.ARRIVAL),
        )
        // 排序是为了让 prefs 里的字符串稳定（同一个配置每次存出来都一样）；
        // 不含 ARRIVAL 是上一条守卫的写入侧对应物。
        assertEquals(listOf(NotificationCategory.TRANSIT.name), names)
    }
}
