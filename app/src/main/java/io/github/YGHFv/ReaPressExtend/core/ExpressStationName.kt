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
 * 驿站名的身份判定，纯函数、不碰 Android。短名是长名**后缀**才算同地点（地址只加在店名前面）。
 * 合错会让用户白跑一趟，宁可不合也不合错。
 */
object ExpressStationName {

    private val BRAND_PREFIXES = listOf("菜鸟驿站", "菜鸟裹裹", "菜鸟", "驿站")

    private const val MIN_SHARED_SUFFIX = 4

    /** 整个名字恰为它时才算；非空会占「只填空不覆盖」的名额，挡住宿主富化的真名（见 ExpressRecord.mergeEnrichment）。 */
    private val PLACEHOLDERS = setOf(
        "驿站",
        "代收点",
        "快递代收点",
        "快递点",
        "快递柜",
        "自提柜",
        "智能快递柜",
        "智能柜",
    )

    /** 「名字到这里就完了」的片段；历史记录里的截不掉，靠这里再截一次兜底。刻意不收「小时」（24小时便利店是真店名）。 */
    private val NARRATIVE_FRAGMENTS = listOf(
        "存放", "超过", "超时", "逾期", "未取",
        "取件码", "取货码", "提取码", "取尾号", "尾号",
        "验证码", "您", "请",
    )

    private val TRAILING_JUNK = charArrayOf(
        '（', '(', '【', '[', '「', '·', '、', '，', '。', '-', '~', '～',
    )

    /** 归一化：抹掉空白、品牌前缀与外层括号；空名 / 占位词返回空串。 */
    fun normalize(raw: String?): String {
        val compact = raw.orEmpty().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return ""
        val original = truncateAtNarrative(compact)
        if (original.isEmpty()) return ""
        if (original in PLACEHOLDERS) return ""
        var name = original
        var rounds = 0
        while (rounds++ < 3) {
            val next = stripBrandPrefix(stripOuterBrackets(name))
            if (next == name) break
            name = next
        }
        if (name in PLACEHOLDERS) return ""
        return name.ifEmpty { original }
    }

    private fun truncateAtNarrative(name: String): String {
        var cut = name.length
        for (fragment in NARRATIVE_FRAGMENTS) {
            val index = name.indexOf(fragment)
            if (index < 0) continue
            if (index == 0) return ""
            if (index < cut) cut = index
        }
        return if (cut == name.length) name else name.substring(0, cut).trimEnd(*TRAILING_JUNK)
    }

    fun hasLocation(raw: String?): Boolean = normalize(raw).isNotEmpty()

    fun isNarrativeArtifact(raw: String?): Boolean {
        val compact = raw.orEmpty().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return false
        return truncateAtNarrative(compact) != compact
    }

    /** 给显示用的取件地点，原样返回（剥品牌反而丢信息）；说不出「哪一处」时返回 null。 */
    fun placeName(raw: String?): String? {
        val compact = raw.orEmpty().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        return truncateAtNarrative(compact).takeIf { hasLocation(it) }
    }

    /** 把一批名字聚成类，返回「名字 → 代表名」；代表取类里最长的那个。 */
    fun representatives(names: Collection<String>): Map<String, String> {
        val result = HashMap<String, String>(names.size)
        val pinned = mutableListOf<String>()
        val ordered = names.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        for (name in ordered) {
            val owner = pinned.firstOrNull { isSameStation(it, name) } ?: name
            if (owner == name) pinned += name
            result[name] = owner
        }
        return result
    }

    private fun isSameStation(a: String, b: String): Boolean {
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        return short.length >= MIN_SHARED_SUFFIX && long.endsWith(short)
    }

    /** 剥一层品牌前缀；剥不空、也不剥成另一个品牌词。 */
    private fun stripBrandPrefix(name: String): String {
        val hit = BRAND_PREFIXES.firstOrNull { name.length > it.length && name.startsWith(it) }
            ?: return name
        val rest = name.substring(hit.length)
        return if (BRAND_PREFIXES.any { rest == it }) name else rest
    }

    /** 只在整串就是括号内容时才剥（`A区` 是另一个地点）。 */
    private fun stripOuterBrackets(name: String): String {
        if (name.length < 3) return name
        val inner = when {
            name.startsWith('(') && name.endsWith(')') -> name.substring(1, name.length - 1)
            name.startsWith('（') && name.endsWith('）') -> name.substring(1, name.length - 1)
            else -> return name
        }
        val nested = inner.any { it == '(' || it == ')' || it == '（' || it == '）' }
        return if (nested) name else inner
    }
}
