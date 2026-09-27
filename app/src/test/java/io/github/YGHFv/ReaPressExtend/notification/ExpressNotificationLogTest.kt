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

package io.github.YGHFv.ReaPressExtend.notification

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知审计的**序列化契约**。
 *
 * ## 为什么值得钉住
 *
 * 这份 JSON 在用户设备的 prefs 里长期存在、随版本升级一路读下来 —— 加一个字段忘了写进
 * `parse` 的表现是「重启后这个能力悄悄消失」，而现场没有任何报错。项目约定里那条
 * 「新字段同步 4 处、旧 JSON 必须读得出」就是为它写的。
 *
 * 这里只钉两件事：新键（`tk`）能往返、**没有这个键的旧记录照样读得出**。
 */
class ExpressNotificationLogTest {

    private fun entry(
        id: String = "entry-1",
        tokenId: String = "",
        intentUri: String = "",
        kind: ExpressNotificationLog.Kind = ExpressNotificationLog.Kind.DELIVERED,
    ) = ExpressNotificationLog.Entry(
        at = 1_790_000_000_000L,
        sourcePackage = "com.example.app",
        title = "快递通知",
        detail = "取件码：1-2-3456",
        delivered = true,
        originTitle = "标题",
        originText = "正文",
        id = id,
        kind = kind,
        intentUri = intentUri,
        tokenId = tokenId,
    )

    @Test
    fun `令牌句柄随序列化往返`() {
        val original = listOf(entry(tokenId = "0f8fad5b-d9cb-469f-a165-70867728950e"))
        val restored = ExpressNotificationLog.parse(ExpressNotificationLog.serialize(original))

        assertEquals(1, restored.size)
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", restored[0].tokenId)
    }

    @Test
    fun `没有令牌句柄的记录往返后仍是空串`() {
        val restored = ExpressNotificationLog.parse(ExpressNotificationLog.serialize(listOf(entry())))
        assertEquals("", restored[0].tokenId)
    }

    /**
     * 旧版本落盘的那份 JSON —— 整个 `tk` 键都不存在。
     *
     * 这是最要紧的一条：读取侧一旦从 `optString` 改成 `getString`（或给字段加个非空默认），
     * 用户升级之后**整份记录**都会解析失败变成空列表，而界面上只是「记录都没了」。
     */
    @Test
    fun `旧记录缺 tk 键也能读出来`() {
        val legacy = JSONArray()
            .put(
                org.json.JSONObject().apply {
                    put("at", 1_790_000_000_000L)
                    put("pkg", "com.example.app")
                    put("title", "快递通知")
                    put("detail", "取件码：1-2-3456")
                    put("delivered", true)
                    put("otitle", "标题")
                    put("otext", "正文")
                    put("id", "legacy-id")
                    put("kind", "DELIVERED")
                    put("cat", "")
                    put("iuri", "intent:#Intent;end")
                },
            )
            .toString()

        val restored = ExpressNotificationLog.parse(legacy)

        assertEquals(1, restored.size)
        assertEquals("legacy-id", restored[0].id)
        // 「这条取不回令牌」是旧记录的正确表现，不是解析失败。
        assertEquals("", restored[0].tokenId)
        assertTrue(restored[0].intentUri.isNotBlank())
    }
}
