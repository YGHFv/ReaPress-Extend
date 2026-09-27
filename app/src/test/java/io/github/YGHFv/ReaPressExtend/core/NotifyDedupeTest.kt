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

/** [NotifyDedupe] 的窗口与淘汰语义。 */
class NotifyDedupeTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `窗口内同一个键只放第一条`() {
        val dedupe = NotifyDedupe(windowMs = 60_000L)
        assertTrue(dedupe.claim("k", t0))
        assertFalse(dedupe.claim("k", t0 + 1))
        assertFalse(dedupe.claim("k", t0 + 59_999))
        // 窗口到点：放行。
        assertTrue(dedupe.claim("k", t0 + 60_000))
    }

    @Test
    fun `不同键互不影响`() {
        val dedupe = NotifyDedupe(windowMs = 60_000L)
        assertTrue(dedupe.claim("a", t0))
        assertTrue(dedupe.claim("b", t0))
        assertFalse(dedupe.claim("a", t0 + 10))
        assertFalse(dedupe.claim("b", t0 + 10))
    }

    @Test
    fun `空键一律放过`() {
        val dedupe = NotifyDedupe()
        assertTrue(dedupe.claim("", t0))
        assertTrue(dedupe.claim("", t0))
        assertTrue(dedupe.claim("   ", t0))
        // 空键不进表。
        assertEquals(0, dedupe.size())
    }

    /**
     * 时钟被改回去（用户改系统时间 / NTP 回跳）时按「过期」处理：
     * 宁可多提醒一次，也不要把这一分钟内的通知全吞掉。
     */
    @Test
    fun `时钟回跳时不吞事件`() {
        val dedupe = NotifyDedupe(windowMs = 60_000L)
        assertTrue(dedupe.claim("k", t0))
        assertTrue(dedupe.claim("k", t0 - 10_000))
    }

    @Test
    fun `超过上限时最旧的先淘汰`() {
        val dedupe = NotifyDedupe(windowMs = 60_000L, maxEntries = 3)
        assertTrue(dedupe.claim("a", t0))
        assertTrue(dedupe.claim("b", t0))
        assertTrue(dedupe.claim("c", t0))
        assertEquals(3, dedupe.size())
        // 表里的键照旧被压住。
        assertFalse(dedupe.claim("a", t0 + 10))
        // 再来一个满出来：淘汰的是最旧的 a。
        assertTrue(dedupe.claim("d", t0))
        assertEquals(3, dedupe.size())
        // a 已被淘汰 —— 它的窗口判定重新从零开始（这就是「表有上限」的语义：
        // 极端的突发情形下宁可多一条通知，也不能让表无界增长）。
        assertTrue(dedupe.claim("a", t0 + 20))
        assertEquals(3, dedupe.size())
    }

    @Test
    fun `clear 之后重新开始`() {
        val dedupe = NotifyDedupe()
        assertTrue(dedupe.claim("k", t0))
        dedupe.clear()
        assertEquals(0, dedupe.size())
        assertTrue(dedupe.claim("k", t0 + 1))
    }
}
