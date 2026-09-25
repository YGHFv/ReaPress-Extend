package io.github.YGHFv.ReaPressExtend.core

/**
 * 用户对驿站的手工调整（设置页「驿站管理」改的就是这张表）。
 *
 * ## 为什么只有一张表
 *
 * 「合并」和「改外显名」看着是两件事，其实是**同一件事**：把驿站 A 的名字换成另一个名字。
 *
 * - 换成「家附近的驿站」（一个不存在于任何记录里的名字）→ 就是改名；
 * - 换成驿站 B 的名字 → A 和 B 的记录在分组时算出同一个名字，自然并成一张卡 = 合并。
 *
 * 所以只需要 `归一化名 → 想显示的名字` 一张表。拆成两张（合并表 + 改名表）会立刻带来
 * 「先改名还是先合并」的优先级问题，而那个问题没有正确答案 —— 一张表就没有这个歧义。
 *
 * ## 自动归一化 + 手工规则，两道工序
 *
 * [ExpressStationName] 处理的是**机器能看出来**的同一驿站（品牌前缀、括号、地址详略）。
 * 这里处理的是**只有用户知道**的：两个名字看起来毫无关系，但其实是同一个取件点，或者
 * 用户就是想把某个驿站叫成别的。前者不猜，后者完全交给用户。
 *
 * ## 只影响本机显示
 *
 * 规则**不改** [ExpressRecord.station]，原始写法永远留在记录里。所以改错了、或者想换回来，
 * 删掉规则就回到原样 —— 「恢复默认」不需要备份任何东西，这也是不把新名字写回记录的原因。
 */
data class ExpressStationRules(
    /** 归一化驿站名 → 用户设定的名字。见类注释：合并与改名都长在这张表上。 */
    val renames: Map<String, String> = emptyMap(),
) {

    /**
     * 把一个驿站名换成它该显示的名字。
     *
     * ## 为什么是链式而不是查一次
     *
     * 规则可以叠起来用：A 合并到 B，之后又把 B 改名成「家门口」。这时候 A 的包裹也该跟着
     * 显示「家门口」—— 它们本来就在同一张卡片上，名字却分成两种是不可接受的。
     * 所以这里跟着映射一路走到底，而不是只查一跳。
     *
     * `MAX_HOPS` 是防死循环的：用户先把 A 并到 B、再反过来把 B 并到 A，就会走成一个环。
     * 走到上限就停在当前值上 —— 环是用户的输入错误，界面不该因此卡死。
     *
     * 空值 / 空白一律当「没有规则」处理而不是当成空名字：用户清空输入框时应该回到原样，
     * 而不是让整个驿站的名字变成空字符串（那会把它扔进「未知取件地点」，看着像数据丢了）。
     */
    fun apply(normalized: String): String {
        var current = normalized
        repeat(MAX_HOPS) {
            val next = renames[current]?.takeIf { it.isNotBlank() } ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    /** 这个驿站有没有被用户改过。UI 靠它决定要不要显示「恢复默认」。 */
    fun hasRule(normalized: String): Boolean = !renames[normalized].isNullOrBlank()

    val isEmpty: Boolean get() = renames.isEmpty()

    companion object {
        val EMPTY = ExpressStationRules()

        /** 链式解析的最大跳数。见 [apply]：纯粹用来兜住 A↔B 互相指的死环。 */
        private const val MAX_HOPS = 4
    }
}
