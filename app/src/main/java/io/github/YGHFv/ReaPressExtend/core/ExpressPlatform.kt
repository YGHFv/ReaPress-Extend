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
 * 电商来源（宿主 `pkgSourceDesc`）的清洗。
 *
 * ## 为什么必须洗
 *
 * `pkgSourceDesc` 在菜鸟里**不只装平台名**。淘系订单填的是真平台（真机证据：`pkgSource=TMALL`
 * 时 `pkgSourceDesc=天猫`），而非淘包裹填的是**收件类型词** —— 真机截图（2026-09-26，`17-5-8644`
 * 那件）那个位置写的是「普通收件」。当成平台显示，代价有两层：
 *
 * 1. 「来源：普通收件」零信息量 —— 它回答的是「哪一类收件」，不是「哪个平台」；
 * 2. 更实际的一层：[ExpressFormatter.goodsSummary] 是 `平台 · 商品名`，平台排在商品名**前面**。
 *    于是一个泛称把商品名的位置占住了：卡片上本该显示 `ZM-冰箱贴` 的那一行只剩下「普通收件」，
 *    看起来完全像「商品名读取不到」。用户报的第一条就是这个现象。
 *
 * 这与「代收点 / 快递柜不算地址」是同一条规矩（见 `MEMORY.md`）：
 * **只知道「哪一类」的字段，不能当「哪一个 / 哪一处」用。**
 *
 * ## 兜底词表故意很短，且不认识的一律保留
 *
 * 收录判据是「说得出一类收件、说不出一个平台」，不是「长得像不像」。表外的值原样返回 ——
 * 猜错方向的代价**不对称**：多显示一个没用的词只是难看，而吃掉一个真平台名（将来多一个
 * `抖音商城` 之类）是静默丢信息，用户永远不会察觉。
 *
 * ## 在哪几处调它（三处，都调同一个纯函数）
 *
 * 1. **写入**：`CainiaoPackageHook.buildRecord` —— 存进去的就是干净的；
 * 2. **读取**：`ExpressRecordStore` 反序列化 —— 洗掉历史数据（旧版本原样存过类型词，
 *    而合并是「只填空不覆盖」，不清读路径就永远洗不掉）；
 * 3. **渲染前**：`ExpressFormatter.goodsSummary` 与包裹详情页的「来源」行 —— 兜底。
 *
 * 函数是幂等的，重复过几遍都没有副作用；放在三处是因为这三处的**失效方式不同**：
 * 只做写入挡不住历史数据，只做读取挡不住旧的宿主 hook 经 relay 传进来的值，
 * 而渲染点是最应该「无论数据多脏都不会显示错」的那一层。
 */
object ExpressPlatform {

    /**
     * 已知的收件类型 / 兜底词。
     *
     * `普通收件` 有截图级真机证据；其余是同族兜底词（宿主拿不到平台信息时用的那几个词），
     * 语义上不存在歧义 —— 没有一个电商平台叫这两个字。
     */
    private val CATEGORY_WORDS = setOf(
        "普通收件",
        "普通快递",
        "标准快递",
        "其他",
        "其他收件",
        "未知",
        "无",
        "暂无",
    )

    /**
     * @return 能当「来源」显示的串；空白、或落在 [CATEGORY_WORDS] 里时返回 `null`
     *   （调用方整项省略，而不是显示一个空的或不存在的来源）。
     */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.takeIf { it !in CATEGORY_WORDS }
    }
}
