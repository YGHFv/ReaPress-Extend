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
 * 「打开原通知」的包名抠取。
 *
 * 样例是 `Intent.toUri(Intent.URI_INTENT_SCHEME)` 的**真实输出形状**（把真实 uri 抄进来，
 * 不是凭印象编 —— 编出来的样例只能证明我的假设自洽）。
 */
class NotificationIntentSnapshotTest {

    @Test
    fun `component 优先_package 兜底`() {
        // 显式 Intent：toUri 写 component=pkg/cls
        assertEquals(
            "com.cainiao.wireless",
            NotificationIntentSnapshot.packageOf(
                "intent://#Intent;action=android.intent.action.VIEW;" +
                    "component=com.cainiao.wireless/.ui.MainActivity;end",
            ),
        )
        // 隐式 Intent：只有 package（没有 component）
        assertEquals(
            "com.taobao.taobao",
            NotificationIntentSnapshot.packageOf(
                "intent://detail/#Intent;package=com.taobao.taobao;end",
            ),
        )
    }

    @Test
    fun `取到分号为止_不会把后面的段当包名`() {
        // 这一条钉的是最容易犯的错：不截断的话会拿回
        // "com.foo/.A;launchFlags=0x10000000;end"，界面上就是一行垃圾。
        assertEquals(
            "com.foo",
            NotificationIntentSnapshot.packageOf(
                "intent://#Intent;component=com.foo/.A;launchFlags=0x10000000;end",
            ),
        )
    }

    @Test
    fun `包名含 end 不被剪坏`() {
        // 曾经的写法是 removeSuffix("end")，会把 bender 剪成 b。这条钉住它不会再回来。
        assertEquals(
            "com.foo.bender",
            NotificationIntentSnapshot.packageOf("intent://#Intent;package=com.foo.bender;end"),
        )
    }

    @Test
    fun `认不出时返回 null`() {
        assertNull(NotificationIntentSnapshot.packageOf(null))
        assertNull(NotificationIntentSnapshot.packageOf(""))
        assertNull(NotificationIntentSnapshot.packageOf("   "))
        // 只有 data、没有 component / package 的（浏览器打开链接那种）。
        assertNull(NotificationIntentSnapshot.packageOf("https://example.com/x"))
    }
}
