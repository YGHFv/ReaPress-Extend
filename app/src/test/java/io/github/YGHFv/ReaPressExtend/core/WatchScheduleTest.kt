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

import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动轮查排程的单测。
 *
 * 这一批钉的全是**边界**：夜间窗口含不含起止整点、跨零点怎么算、`start == end` 算不算全天、
 * 抖动会不会抖出负数。这些在真机上要等一整天才能观察一次，写成纯函数就能逐条钉。
 */
class WatchScheduleTest {

    /** 固定时区，避免在不同地区的开发机上跑出不同结果。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, 27, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------ 哪些件值得问

    @Test
    fun `在途件永远值得问`() {
        for (status in listOf(ExpressStatus.IN_TRANSIT, ExpressStatus.DELIVERING)) {
            assertTrue(status.name, WatchSchedule.isWatchable(status, pickedUp = false, allUnfinished = false))
            assertTrue(status.name, WatchSchedule.isWatchable(status, pickedUp = false, allUnfinished = true))
        }
    }

    @Test
    fun `到站待取件只在放宽到未完成时才问`() {
        for (status in listOf(
            ExpressStatus.CREATED,
            ExpressStatus.PICKED_UP,
            ExpressStatus.ARRIVED_STATION,
            ExpressStatus.READY_FOR_PICKUP,
        )) {
            assertFalse(status.name, WatchSchedule.isWatchable(status, pickedUp = false, allUnfinished = false))
            assertTrue(status.name, WatchSchedule.isWatchable(status, pickedUp = false, allUnfinished = true))
        }
    }

    @Test
    fun `闭环与未知状态两种范围下都不问`() {
        for (status in listOf(ExpressStatus.SIGNED, ExpressStatus.FAILED, ExpressStatus.UNKNOWN)) {
            assertFalse(status.name, WatchSchedule.isWatchable(status, pickedUp = false, allUnfinished = false))
            assertFalse(status.name, WatchSchedule.isWatchable(status, pickedUp = false, allUnfinished = true))
        }
    }

    @Test
    fun `用户确认取走的件不问`() {
        assertFalse(WatchSchedule.isWatchable(ExpressStatus.READY_FOR_PICKUP, pickedUp = true, allUnfinished = true))
        assertFalse(WatchSchedule.isWatchable(ExpressStatus.IN_TRANSIT, pickedUp = true, allUnfinished = true))
    }

    // ------------------------------------------------------------ 夜间窗口

    @Test
    fun `跨零点的窗口含起点_不含终点`() {
        // 22:00 开始停，8:00 恢复：22 点这一记要停，8 点那一记要恢复（否则每天白等一小时）。
        assertFalse(WatchSchedule.inQuietHours(21, 22, 8))
        assertTrue(WatchSchedule.inQuietHours(22, 22, 8))
        assertTrue(WatchSchedule.inQuietHours(23, 22, 8))
        assertTrue(WatchSchedule.inQuietHours(0, 22, 8))
        assertTrue(WatchSchedule.inQuietHours(7, 22, 8))
        assertFalse(WatchSchedule.inQuietHours(8, 22, 8))
        assertFalse(WatchSchedule.inQuietHours(12, 22, 8))
    }

    @Test
    fun `不跨零点的窗口是普通区间`() {
        assertFalse(WatchSchedule.inQuietHours(12, 13, 15))
        assertTrue(WatchSchedule.inQuietHours(13, 13, 15))
        assertTrue(WatchSchedule.inQuietHours(14, 13, 15))
        assertFalse(WatchSchedule.inQuietHours(15, 13, 15))
    }

    @Test
    fun `起止相同等于没有窗口_不是全天`() {
        for (hour in 0..23) {
            assertFalse(hour.toString(), WatchSchedule.inQuietHours(hour, 22, 22))
        }
    }

    @Test
    fun `关掉夜间暂停后任何时刻都不算窗口内`() {
        assertFalse(WatchSchedule.isQuietAt(at(3), 22, 8, quiet = false, zone = zone))
        assertTrue(WatchSchedule.isQuietAt(at(3), 22, 8, quiet = true, zone = zone))
    }

    // ------------------------------------------------------------ 还要等多久

    @Test
    fun `不在窗口里不用等`() {
        assertEquals(0L, WatchSchedule.millisUntilQuietEnd(at(15), 22, 8, zone = zone))
    }

    @Test
    fun `窗口内等到当天的结束整点`() {
        // 凌晨 3:00 → 早上 8:00，5 小时。
        assertEquals(
            5 * 60 * 60_000L,
            WatchSchedule.millisUntilQuietEnd(at(3), 22, 8, zone = zone),
        )
    }

    @Test
    fun `跨零点时算到次日而不是当天已过去的那个点`() {
        // 23:30 → 次日 8:00 = 8.5 小时。拿当天的 8:00 减会得到负的 15.5 小时。
        assertEquals(
            (8 * 60 + 30) * 60_000L,
            WatchSchedule.millisUntilQuietEnd(at(23, 30), 22, 8, zone = zone),
        )
    }

    @Test
    fun `不跨零点的窗口等当天那一次`() {
        // 13:30 → 15:00 = 1.5 小时。
        assertEquals(
            90 * 60_000L,
            WatchSchedule.millisUntilQuietEnd(at(13, 30), 13, 15, zone = zone),
        )
    }

    // ------------------------------------------------------------ 抖动

    @Test
    fun `抖动把间隔摊在正负一分钟内`() {
        assertEquals(
            WatchSchedule.DEFAULT_BASE_GAP_MS - WatchSchedule.DEFAULT_GAP_JITTER_MS,
            WatchSchedule.nextGapMillis(-1f),
        )
        assertEquals(WatchSchedule.DEFAULT_BASE_GAP_MS, WatchSchedule.nextGapMillis(0f))
        assertEquals(
            WatchSchedule.DEFAULT_BASE_GAP_MS + WatchSchedule.DEFAULT_GAP_JITTER_MS,
            WatchSchedule.nextGapMillis(1f),
        )
        assertEquals(WatchSchedule.DEFAULT_BASE_GAP_MS + 30_000L, WatchSchedule.nextGapMillis(0.5f))
    }

    @Test
    fun `越界抖动被夹回区间且永远不会是负数`() {
        assertEquals(
            WatchSchedule.DEFAULT_BASE_GAP_MS - WatchSchedule.DEFAULT_GAP_JITTER_MS,
            WatchSchedule.nextGapMillis(-9f),
        )
        assertEquals(
            WatchSchedule.DEFAULT_BASE_GAP_MS + WatchSchedule.DEFAULT_GAP_JITTER_MS,
            WatchSchedule.nextGapMillis(9f),
        )
        assertTrue(WatchSchedule.nextGapMillis(-100f) > 0L)
    }

    // ------------------------------------------------------------ 间隔可由用户调（2026-09-27）

    @Test
    fun `间隔基准可传入_不再固定三分钟`() {
        val tenMinutes = 10 * 60_000L
        assertEquals(tenMinutes, WatchSchedule.nextGapMillis(0f, baseMs = tenMinutes))
        assertEquals(
            tenMinutes - WatchSchedule.jitterFor(tenMinutes),
            WatchSchedule.nextGapMillis(-1f, baseMs = tenMinutes),
        )
    }

    @Test
    fun `抖动不超过基准的一半`() {
        // 默认 3 分钟：±1 分钟（60s）没超过一半（90s），原样保留。
        assertEquals(60_000L, WatchSchedule.jitterFor(3 * 60_000L))
        // 基准降到 1 分钟：±1 分钟会让一半取样落到 0 或负数（夹成 1 秒 = 连发），
        // 所以抖动缩到基准的一半。
        assertEquals(30_000L, WatchSchedule.jitterFor(60_000L))
        assertEquals(20_000L, WatchSchedule.jitterFor(40_000L))
    }

    @Test
    fun `最短基准配上抖动也不会抖成负数或零`() {
        val oneMinute = 60_000L
        val min = WatchSchedule.nextGapMillis(-1f, baseMs = oneMinute, jitterMs = WatchSchedule.jitterFor(oneMinute))
        assertEquals(30_000L, min)
        assertTrue(min > 0L)
    }

    // ------------------------------------------------------------ 下次何时动手

    @Test
    fun `到期时间算到点返回零_没到返回剩余`() {
        assertEquals(0L, WatchSchedule.millisUntilDue(dueAtMillis = 1_000L, nowMillis = 5_000L))
        assertEquals(4_000L, WatchSchedule.millisUntilDue(dueAtMillis = 9_000L, nowMillis = 5_000L))
        assertEquals(0L, WatchSchedule.millisUntilDue(dueAtMillis = 5_000L, nowMillis = 5_000L))
    }

    // ------------------------------------------------------------ 改间隔后把已排的时刻往前拉

    @Test
    fun `改小间隔时到期时刻被拉近`() {
        // 现在 0.0s，原来排到 30 分钟后（一轮间隔），用户把间隔改成 2 分钟。
        val now = 1_000_000L
        val due = now + 30 * 60_000L
        assertEquals(
            now + 2 * 60_000L,
            WatchSchedule.pullDueEarlier(due, now, intervalMillis = 2 * 60_000L),
        )
    }

    @Test
    fun `改大间隔时不把已排的时刻往后推`() {
        // 已经排到 2 分钟后，用户把间隔改成 30 分钟 —— 那次仍应 2 分钟后动手，
        // 否则「调大了间隔」等于把到点该问的件无端推迟。
        val now = 1_000_000L
        val due = now + 2 * 60_000L
        assertEquals(due, WatchSchedule.pullDueEarlier(due, now, intervalMillis = 30 * 60_000L))
    }

    @Test
    fun `没排过或已经到点时原样返回不推迟`() {
        val now = 1_000_000L
        assertEquals(0L, WatchSchedule.pullDueEarlier(0L, now, intervalMillis = 60_000L))
        assertEquals(-5L, WatchSchedule.pullDueEarlier(-5L, now, intervalMillis = 60_000L))
        // 已经到点（过去时刻）也不该被改写成「现在 + 间隔」。
        val past = now - 10_000L
        assertEquals(past, WatchSchedule.pullDueEarlier(past, now, intervalMillis = 60_000L))
    }

    @Test
    fun `负间隔按零处理_结果是立刻可动手`() {
        val now = 1_000_000L
        val due = now + 10 * 60_000L
        assertEquals(now, WatchSchedule.pullDueEarlier(due, now, intervalMillis = -999L))
    }

    // ------------------------------------------------------------ 设置值夹取

    @Test
    fun `件间隔夹进合法区间`() {
        assertEquals(WatchSchedule.MIN_GAP_MINUTES, WatchSchedule.clampGapMinutes(0))
        assertEquals(WatchSchedule.MIN_GAP_MINUTES, WatchSchedule.clampGapMinutes(-5))
        assertEquals(3, WatchSchedule.clampGapMinutes(3))
        assertEquals(WatchSchedule.MAX_GAP_MINUTES, WatchSchedule.clampGapMinutes(999))
    }

    @Test
    fun `轮间隔夹进合法区间`() {
        assertEquals(WatchSchedule.MIN_CYCLE_MINUTES, WatchSchedule.clampCycleMinutes(0))
        assertEquals(WatchSchedule.MIN_CYCLE_MINUTES, WatchSchedule.clampCycleMinutes(-5))
        assertEquals(30, WatchSchedule.clampCycleMinutes(30))
        assertEquals(WatchSchedule.MAX_CYCLE_MINUTES, WatchSchedule.clampCycleMinutes(9999))
    }

    @Test
    fun `设置默认值与排程默认值不漂移`() {
        // 两边漂了会让「没动过设置」的用户在界面上看到与实际不符的节奏
        // （界面写 3 分钟、实际按 5 分钟跑）。const val 在编译期内联，
        // 任一边被改这个断言就会红 —— 这正是它该做的事。
        assertEquals(
            ExpressSettingsKeys.DEFAULT_WATCH_GAP_MIN * 60_000L,
            WatchSchedule.DEFAULT_BASE_GAP_MS,
        )
        assertEquals(
            ExpressSettingsKeys.DEFAULT_WATCH_CYCLE_MIN * 60_000L,
            WatchSchedule.DEFAULT_CYCLE_WAIT_MS,
        )
        // 默认值必须在选项表里：不在的话下拉会因为 indexOfFirst 落空而显示成第一项，
        // 用户看到的是「1 分钟」，而实际跑的是 3 分钟。
        assertTrue(
            ExpressSettingsKeys.WATCH_GAP_OPTIONS.any {
                it.first == ExpressSettingsKeys.DEFAULT_WATCH_GAP_MIN
            },
        )
        assertTrue(
            ExpressSettingsKeys.WATCH_CYCLE_OPTIONS.any {
                it.first == ExpressSettingsKeys.DEFAULT_WATCH_CYCLE_MIN
            },
        )
        // 选项表里的每一档都得落在夹取区间内，否则手选一个超界的值会被静默改掉。
        for ((minutes, _) in ExpressSettingsKeys.WATCH_GAP_OPTIONS) {
            assertEquals(minutes, WatchSchedule.clampGapMinutes(minutes))
        }
        for ((minutes, _) in ExpressSettingsKeys.WATCH_CYCLE_OPTIONS) {
            assertEquals(minutes, WatchSchedule.clampCycleMinutes(minutes))
        }
    }
}
