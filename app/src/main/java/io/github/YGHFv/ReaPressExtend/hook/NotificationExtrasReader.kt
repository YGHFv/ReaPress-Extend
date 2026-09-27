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

/** 把 Notification.extras 拆成纯 Map<String, Any?> 交给 core（core 不能碰 android.app.Notification）；只读已知键、不遍历 Bundle，并容忍 extras 为 null 或单个键取值失败。 */
internal object NotificationExtrasReader {

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
            val value = runCatching { extras.getCharSequence(key) }.getOrNull()
            if (value != null) result[key] = value
        }
        val lines = runCatching { extras.getCharSequenceArray(KEY_TEXT_LINES) }.getOrNull()
        if (lines != null) result[KEY_TEXT_LINES] = lines
        return result
    }

    /** 模块自己发的通知：不排除会「拦截 → 重发 → 又被拦截」无限循环。 */
    fun isModuleOrigin(notification: Notification?): Boolean =
        runCatching {
            notification?.extras?.getBoolean(
                io.github.YGHFv.ReaPressExtend.relay.ExpressRelay.EXTRA_MODULE_ORIGIN,
                false,
            ) == true
        }.getOrDefault(false)
}
