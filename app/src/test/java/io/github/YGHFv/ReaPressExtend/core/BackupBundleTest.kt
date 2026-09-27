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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [BackupBundle] 的编解码与坏数据判定。 */
class BackupBundleTest {

    private fun payload(prefs: Map<String, Map<String, Any?>>) = BackupBundle.Payload(
        version = BackupBundle.FORMAT_VERSION,
        exportedAt = 1_790_518_055_307L,
        appVersion = "1.2.3",
        prefs = prefs,
    )

    @Test
    fun `六种类型的值都能原样读回`() {
        val original = payload(
            mapOf(
                "reapress_records" to linkedMapOf<String, Any?>(
                    "records" to """[{"tn":"1234"}]""",
                    "at" to 1_790_518_055_307L,
                    "count" to 42,
                    "flag" to true,
                    "ratio" to 0.25f,
                    "set" to linkedSetOf("菜鸟", "拼多多"),
                ),
            ),
        )

        val decoded = BackupBundle.decode(BackupBundle.encode(original))
        val values = decoded.prefs.getValue("reapress_records")

        assertEquals("""[{"tn":"1234"}]""", values["records"])
        assertEquals(1_790_518_055_307L, values["at"])
        assertEquals(42, values["count"])
        assertEquals(true, values["flag"])
        assertEquals(0.25f, values["ratio"])
        assertEquals(linkedSetOf("菜鸟", "拼多多"), values["set"])
        // 元信息也要留住，否则界面上的「备份于何时」就没法显示。
        assertEquals(1_790_518_055_307L, decoded.exportedAt)
        assertEquals("1.2.3", decoded.appVersion)
    }

    /**
     * 这条是**必须**有的：JSON 的数字只有一种，`Long` 被当成 `Int` 读回来时，
     * 13 位的时间戳会直接变成截断值 —— 而 SharedPreferences 的 `getLong` 拿到错的类型
     * 会抛 `ClassCastException`。当年 PR 里最容易漏的就是它。
     */
    @Test
    fun `Long 不会被当成 Int 读回`() {
        val original = payload(mapOf("p" to linkedMapOf<String, Any?>("at" to 1_790_518_055_307L)))
        val values = BackupBundle.decode(BackupBundle.encode(original)).prefs.getValue("p")
        val at = values["at"]
        assertTrue("读回来的应当是 Long 本身，实际是 ${at?.let { it::class.java.name }}", at is Long)
        assertEquals(1_790_518_055_307L, at)
    }

    @Test
    fun `一份空的 prefs 也能往返`() {
        val original = payload(mapOf("express_ui_prefs" to emptyMap()))
        val decoded = BackupBundle.decode(BackupBundle.encode(original))
        assertTrue(decoded.prefs.getValue("express_ui_prefs").isEmpty())
        assertEquals(0, decoded.entryCount)
    }

    @Test
    fun `entryCount 统计所有键`() {
        val original = payload(
            mapOf(
                "a" to linkedMapOf<String, Any?>("x" to "1", "y" to "2"),
                "b" to linkedMapOf<String, Any?>("z" to "3"),
            ),
        )
        assertEquals(3, original.entryCount)
    }

    @Test
    fun `版本高于当前时拒绝并说明原因`() {
        val json = BackupBundle.encode(payload(emptyMap()))
        val bumped = json.replace("\"format\":1", "\"format\":99")
        try {
            BackupBundle.decode(bumped)
            fail("应当拒绝更高版本的备份")
        } catch (e: BackupBundle.BackupFormatException) {
            assertTrue(e.message!!, e.message!!.contains("99"))
        }
    }

    @Test
    fun `不是 JSON 时明确报错`() {
        try {
            BackupBundle.decode("这不是 JSON")
            fail("应当报错")
        } catch (e: BackupBundle.BackupFormatException) {
            assertTrue(e.message!!, e.message!!.contains("JSON"))
        }
    }

    @Test
    fun `空文件报错`() {
        try {
            BackupBundle.decode("   ")
            fail("应当报错")
        } catch (e: BackupBundle.BackupFormatException) {
            assertTrue(e.message!!, e.message!!.contains("空"))
        }
    }

    @Test
    fun `缺少数据段报错`() {
        try {
            BackupBundle.decode("""{"format":1,"exportedAt":1,"appVersion":"x"}""")
            fail("应当报错")
        } catch (e: BackupBundle.BackupFormatException) {
            assertTrue(e.message!!, e.message!!.contains("数据段"))
        }
    }

    @Test
    fun `类型标记不认识时报错并指名道姓`() {
        val broken = """{"format":1,"exportedAt":1,"appVersion":"x","prefs":""" +
            """{"p":{"k":{"t":"zz","v":1}}}}"""
        try {
            BackupBundle.decode(broken)
            fail("应当报错")
        } catch (e: BackupBundle.BackupFormatException) {
            assertTrue(e.message!!, e.message!!.contains("zz"))
        }
    }

    @Test
    fun `无法备份的取值类型在编码阶段就被挡住`() {
        try {
            BackupBundle.encode(payload(mapOf("p" to linkedMapOf<String, Any?>("k" to Any()))))
            fail("应当报错")
        } catch (e: BackupBundle.BackupFormatException) {
            assertTrue(e.message!!, e.message!!.contains("k"))
        }
    }
}
