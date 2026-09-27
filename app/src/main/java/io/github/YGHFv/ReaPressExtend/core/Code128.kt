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
 * Code 128-B 条码编码器 —— 身份码弹窗里那根条码是模块自己画的（宿主有 zxing 但只在宿主进程，模块不引 zxing）。
 * 只支持 B 组（身份码是可打印 ASCII）。返回模块宽度序列：从条开始、条/空交替，第 0、2、4… 项是黑条，
 * 画多高多宽由 UI 决定 —— 这样留在 core 层不碰 Canvas，能单测。
 */
object Code128 {

    /**
     * 107 个符号的模块宽度表（Code 128 规格固定表，逐位比对过多处独立来源）。
     * 两条必须留的自检不变量（单测跑着）：每项模块宽度相加恒为 11（[STOP] 是 13）；
     * 107 项两两不同。表抄错了条码在屏幕上看不出任何异常，只有用户扫不出来 —— 最糟的失败形态。
     */
    private val PATTERNS = listOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
        "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
        "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
        "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
        "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
        "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
        "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
        "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
        "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
        "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
        "211214", "211232", "2331112",
    )

    private const val START_B = 104

    private const val STOP = 106

    private const val CHECK_MOD = 103

    private const val MIN_ASCII = 32
    private const val MAX_ASCII = 126

    /** 文本为空或含 B 组编不了的字符返回 null；不自动降级 A/C 组 —— 失败该显示数字而不是硬编一根扫不出的条码。 */
    fun encodeB(text: String): List<Int>? {
        if (text.isEmpty()) return null

        val symbols = ArrayList<Int>(text.length + 3)
        symbols += START_B
        for (ch in text) {
            val code = ch.code
            if (code < MIN_ASCII || code > MAX_ASCII) return null
            symbols += code - MIN_ASCII
        }
        symbols += checksum(symbols)
        symbols += STOP

        val widths = ArrayList<Int>(symbols.size * 6 + 1)
        for (symbol in symbols) {
            for (digit in PATTERNS[symbol]) widths += digit - '0'
        }
        return widths
    }

    /** 校验和：起始符不乘位置，第 i 个数据符号（从 1 开始数）乘以 i，最后模 103；位置写成 0 条码看着正常只是扫不出。 */
    private fun checksum(symbols: List<Int>): Int {
        var sum = symbols.first()
        for (index in 1 until symbols.size) {
            sum += symbols[index] * index
        }
        return sum % CHECK_MOD
    }
}
