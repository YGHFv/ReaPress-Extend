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

package io.github.YGHFv.ReaPressExtend.hook

import android.app.Notification

/**
 * 把 `Notification.extras` 拆成纯 `Map<String, Any?>`，交给 core 层处理。
 *
 * 为什么要有这一层：core 层要能在 JVM 单测里跑（本工程没有 Robolectric，`android.jar` 是桩），
 * 所以它不能碰 `android.app.Notification`。这里做一次转换，把 Android 类型挡在 core 之外。
 *
 * 只读**已知的**那几个键，不遍历整个 Bundle：
 * - `Bundle.get(String)` 在 API 33 起被废弃，而 `getCharSequence` / `getCharSequenceArray`
 *   没有替代品问题
 * - 遍历未知键还会把 App 塞进去的自定义 Parcelable 拉出来，那些对象在 system_server 里
 *   反序列化有额外开销和风险
 *
 * 安全性：`extras` 在极端情况下可能是 null（构造异常的通知），所有访问都要能容忍。
 */
internal object NotificationExtrasReader {

    /**
     * 需要读取的键。
     *
     * 用字面量而不是 `android.app.Notification.EXTRA_*`：常量值是 AOSP 稳定契约
     * （`android.title` 等自 API 1 起未变），比引一个在单测里是桩的类更可靠。
     */
    private val CHAR_SEQUENCE_KEYS = listOf(
        "android.title",
        "android.text",
        "android.bigText",
        "android.subText",
        "android.tickerText",
        "android.infoText",
    )

    private const val KEY_TEXT_LINES = "android.textLines"

    fun read(notification: Notification?): Map<String, Any?> {
        val extras = runCatching { notification?.extras }.getOrNull() ?: return emptyMap()
        val result = HashMap<String, Any?>(CHAR_SEQUENCE_KEYS.size + 1)
        for (key in CHAR_SEQUENCE_KEYS) {
            // 单个 key 取值失败不该让整条通知读不出来 —— 这是在 system_server 的关键路径上。
            val value = runCatching { extras.getCharSequence(key) }.getOrNull()
            if (value != null) result[key] = value
        }
        val lines = runCatching { extras.getCharSequenceArray(KEY_TEXT_LINES) }.getOrNull()
        if (lines != null) result[KEY_TEXT_LINES] = lines
        return result
    }

    /**
     * 是否是模块自己发出的通知。
     *
     * 递归防护的第一重。模块重发的通知也会经过 NMS，不排除就会
     * 「拦截 → 重发 → 又被拦截」无限循环。
     */
    fun isModuleOrigin(notification: Notification?): Boolean =
        runCatching {
            notification?.extras?.getBoolean(
                io.github.YGHFv.ReaPressExtend.relay.ExpressRelay.EXTRA_MODULE_ORIGIN,
                false,
            ) == true
        }.getOrDefault(false)
}
