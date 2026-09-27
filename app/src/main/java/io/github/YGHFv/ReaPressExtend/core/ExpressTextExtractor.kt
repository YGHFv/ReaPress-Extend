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

/** 通知文本抽取：只吃 Map<String, Any?>（core 要能在 JVM 单测跑，碰 android.jar 桩类会 "not mocked"），key 用 AOSP 稳定契约的 `android.*` 字面量。 */
object ExpressTextExtractor {

    const val EXTRA_TITLE = "android.title"
    const val EXTRA_TEXT = "android.text"
    const val EXTRA_BIG_TEXT = "android.bigText"
    const val EXTRA_SUB_TEXT = "android.subText"
    const val EXTRA_TICKER_TEXT = "android.tickerText"
    const val EXTRA_TEXT_LINES = "android.textLines"
    const val EXTRA_INFO_TEXT = "android.infoText"

    fun extractBody(extras: Map<String, Any?>): String {
        val candidates = listOf(
            EXTRA_BIG_TEXT,
            EXTRA_TEXT_LINES,
            EXTRA_TEXT,
            EXTRA_INFO_TEXT,
            EXTRA_TICKER_TEXT,
        )
        for (key in candidates) {
            val value = flatten(extras[key])
            if (value.isNotBlank()) return value
        }
        return ""
    }

    fun extractTitle(extras: Map<String, Any?>): String = flatten(extras[EXTRA_TITLE]).trim()

    /** 标题 + 副标题 + 正文；去重是因为很多 App 把标题原样塞进正文开头，会让关键词重复命中。 */
    fun extractFullText(extras: Map<String, Any?>): String {
        val parts = listOf(
            extractTitle(extras),
            flatten(extras[EXTRA_SUB_TEXT]).trim(),
            extractBody(extras),
        ).filter { it.isNotBlank() }.distinct()

        return parts.joinToString("\n")
    }

    private fun flatten(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is CharSequence -> value.toString()
        is Array<*> -> value.joinToString("\n") { flatten(it) }
        is Iterable<*> -> value.joinToString("\n") { flatten(it) }
        else -> value.toString()
    }
}
