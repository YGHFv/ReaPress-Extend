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

package io.github.YGHFv.ReaPressExtend.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * relay 契约测试：[ExpressRelay] 里那些字符串常量的**唯一性**。
 *
 * ## 为什么值得单独钉住
 *
 * relay 是跨进程契约 —— 一侧在 system_server / 宿主进程里（另一个 ClassLoader），
 * 另一侧在模块进程里。两边只靠字符串对齐，**编译器看不见任何一处写错**：
 *
 * - 两个 action 撞成同一个字符串：`when` 分支里先出现的那个会把另一个的事件吃掉，
 *   表现是「某个功能永远不触发」而日志上一切正常；
 * - 两个 extra 撞名：后写的那次 `putExtra` 覆盖前一次，表现是「字段偶尔是空的」。
 *
 * 2026-09-27 加「借 system_server 身份代发唤醒销」时一次添了 2 个 action + 2 个 extra，
 * 这类复制的代价就是这么低，所以顺手把它钉住。
 */
class ExpressRelayContractTest {

    @Test
    fun `所有 action 互不相同且都带模块前缀`() {
        val actions = constants("ACTION_")
        // 前缀是「别的应用发同一条广播」时的判据之一，也是排查时一眼认出归属的标记。
        actions.forEach { (name, value) ->
            assertTrue(
                "$name 必须以模块包名为前缀（实际 $value）",
                value.startsWith("${ExpressRelay.MODULE_PACKAGE}."),
            )
        }
        assertNoDuplicates(actions, "action")
        // 反向自检：一个都没扫到说明反射拿的不是这层结构（常量被挪走或改名了）。
        assertTrue("一个 ACTION_ 常量都没扫到，反射取法可能失效了", actions.size >= 5)
    }

    @Test
    fun `所有 extra 互不相同`() {
        val extras = constants("EXTRA_")
        assertNoDuplicates(extras, "extra")
        assertTrue("一个 EXTRA_ 常量都没扫到，反射取法可能失效了", extras.size >= 5)
    }

    @Test
    fun `action 与 extra 之间也不许撞`() {
        // 理论上不会撞（一个带包名前缀、一个是裸名），但「不许撞」是对契约的完整要求：
        // 将来谁给 extra 加上前缀就不再有这个巧合了。
        assertNoDuplicates(constants("ACTION_") + constants("EXTRA_"), "常量")
    }

    private fun assertNoDuplicates(fields: Map<String, String>, kind: String) {
        val byValue = fields.entries.groupBy({ it.value }, { it.key })
        val clashes = byValue.filterValues { it.size > 1 }
        assertEquals(
            "$kind 字符串必须两两不同，撞车的：$clashes",
            emptyMap<String, List<String>>(),
            clashes,
        )
    }

    /**
     * 反射取 `ExpressRelay` 里所有 `const val String`。
     *
     * Kotlin 的 `const val` 编成 `public static final String` 字段，所以 `get(null)`
     * 就能读到 —— 不用实例化这个 object。
     */
    private fun constants(prefix: String): Map<String, String> =
        ExpressRelay::class.java.declaredFields
            .filter { it.name.startsWith(prefix) && it.type == String::class.java }
            .associate { it.name to (it.get(null) as String) }
}
