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

/**
 * 原通知那个 `PendingIntent` 的**可落盘表示**。
 *
 * ## 为什么需要（2026-09-27 用户问：为什么别的通知记录软件过很久还能打开）
 *
 * `PendingIntent` 本身是个 binder 令牌，**不能落盘** —— 但它的**内容**可以。
 * `Intent` 有官方提供的字符串序列化（`Intent.toUri(Intent.URI_INTENT_SCHEME)`，
 * 配 `Intent.parseUri` 还原），这就是系统自己用来把 Intent 塞进 URI / 存进 `AlarmManager`
 * 的那套机制。把 `PendingIntent` 内部的 `Intent` 取出来编成这个字符串存下，
 * 需要时再解回一个普通 `Intent` 去 `startActivity` —— 就能做到「重启之后还能打开」。
 *
 * 代价要说清：`toUri` 只编**基础类型**的 extras（String / int / long / boolean / 各数组），
 * `Parcelable` / `Serializable` 的那部分会被丢掉，`FLAG_GRANT_*` 也不还原。
 * 但通知的 contentIntent 绝大多数就是「打开某个 Activity + 一两个基础类型的参数」，
 * 所以重建出来的跳转通常能到**同一个页面**，最坏情况是丢一个参数。
 *
 * ## 为什么包名要单独抠出来
 *
 * 界面要写「打开原通知（菜鸟）」这种东西，而 `toUri` 的输出是一整条 `intent://…#Intent;…;end`。
 * 从里面取 component 的包名是纯字符串处理 —— 放 core 层是为了能在 JVM 单测里钉住，
 * 不用起 Android 环境。
 */
object NotificationIntentSnapshot {

    /** `Intent.toUri` 里 component 段的键名（AOSP `Intent.URI_INTENT_SCHEME` 契约）。 */
    private const val KEY_COMPONENT = "component="

    /** 只声明 package、不声明 component 时（隐式 Intent）的键名。 */
    private const val KEY_PACKAGE = "package="

    /**
     * 从快照串里抠出目标包名；认不出返回 null。
     *
     * 取值规则（两种都按 AOSP 的写法）：
     * - `component=com.foo.bar/.MainActivity` → `com.foo.bar`（斜杠之前）
     * - `package=com.foo.bar` → `com.foo.bar`
     *
     * 段以 `;` 或串尾的 `end` 结束，所以要在这里截断 —— 不截的话会连
     * `;action=…;launchFlags=…` 一起当成包名。
     */
    fun packageOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        segmentAfter(uri, KEY_COMPONENT)?.substringBefore('/')?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return segmentAfter(uri, KEY_PACKAGE)?.takeIf { it.isNotBlank() }
    }

    /**
     * 取 `key=` 之后到下一个 `;` 之间的内容。
     *
     * **不走 `removeSuffix("end")`**：`Intent.toUri` 的输出一定以 `;end` 结尾，
     * 所以「下一段的分号」必然存在，截到分号就够了。而按后缀剪会误伤含 "end" 的包名
     * （`com.foo.bender` → `com.foo.b`），那是个只在畸形输入上暴露的静默错误。
     */
    private fun segmentAfter(uri: String, key: String): String? {
        val start = uri.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = uri.indexOf(';', from).takeIf { it >= 0 } ?: uri.length
        return uri.substring(from, end).takeIf { it.isNotBlank() }
    }
}
