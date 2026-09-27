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
 * 原通知那个 `PendingIntent` 的可落盘表示：binder 令牌本身不能落盘，但内容可以 ——
 * 把内部 `Intent` 用官方的 `Intent.toUri(URI_INTENT_SCHEME)` 编成字符串存下，需要时解回普通
 * `Intent` 去 `startActivity`，做到重启之后还能打开。代价：只编基础类型的 extras，
 * Parcelable / Serializable 的部分会丢、`FLAG_GRANT_*` 不还原，最坏丢一个参数。
 */
object NotificationIntentSnapshot {

    private const val KEY_COMPONENT = "component="

    private const val KEY_PACKAGE = "package="

    /** 从快照串里抠出目标包名（component= 取斜杠前，package= 直接取）；段以 `;` 结束，不截会连后面的参数一起当成包名。 */
    fun packageOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        segmentAfter(uri, KEY_COMPONENT)?.substringBefore('/')?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return segmentAfter(uri, KEY_PACKAGE)?.takeIf { it.isNotBlank() }
    }

    /** 这条记录属于哪个应用：「打开原通知」的目标。来源包名优先、快照串兜底 —— 按钮措辞与实际打开的目标必须同源，各算一遍迟早分叉。 */
    fun targetPackageOf(sourcePackage: String?, uri: String?): String? =
        sourcePackage?.takeIf { it.isNotBlank() } ?: packageOf(uri)

    /** 取 `key=` 之后到下一个 `;` 之间的内容；不走 removeSuffix("end") —— 会误伤含 "end" 的包名（com.foo.bender → com.foo.b）。 */
    private fun segmentAfter(uri: String, key: String): String? {
        val start = uri.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = uri.indexOf(';', from).takeIf { it >= 0 } ?: uri.length
        return uri.substring(from, end).takeIf { it.isNotBlank() }
    }
}
