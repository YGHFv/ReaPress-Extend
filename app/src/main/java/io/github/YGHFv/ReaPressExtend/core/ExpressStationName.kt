package io.github.YGHFv.ReaPressExtend.core

/**
 * 驿站名的身份判定 —— 判断两个写法是不是**同一个取件地点**。
 *
 * ## 为什么需要
 *
 * 首页按驿站名分组，可同一个驿站在两条记录里常有两种写法，而且**都不是脏数据**：
 *
 * - 通知文案里是简称 —— `阳光花园菜拼多多驿站`；
 * - 宿主 `packageStation.name` 把楼栋号一起带上 —— `阳光23号楼109阳光花园菜拼多多驿站`。
 *
 * （形态照 2026-09-26 真机 `reapress_records` 里的两条记录改写而来 —— 长度、后缀关系
 *   都按原样保留，只把地名和店名换成假名。仓库是公开的，别把真实取件地点写回去。）
 *
 * 于是同一个驿站裂成两张卡片，用户以为多了一个地方要跑。这个文件只解决这一件事：
 * 给每个名字算一个「身份」，同一个身份的名字在分组时相等。**纯函数、不碰 Android**，
 * 规则才能用单测钉住。
 *
 * ## 两条规则
 *
 * 1. [normalize] 抹掉「同一个名字的不同包装」：品牌前缀、外层括号、空白。
 *    `菜鸟驿站(杭州文一西路店)` 与 `杭州文一西路店` 归一后相等。
 * 2. [representatives] 处理「详略不同」：**短名是长名的后缀**时算同一个地点
 *    （`阳光花园菜拼多多驿站` 是 `阳光23号楼109阳光花园菜拼多多驿站` 的后缀）。
 *
 * 用后缀而不是「包含」：地址只会加在店名**前面**，加在后面的是另一个地点
 * （`A驿站` 与 `A驿站东门` 是两处，不能合）。
 *
 * ## 刻意保守
 *
 * 宁可不合，也不要把两个地点并成一个 —— 合错会让用户跑到错误的驿站白等一趟。
 * 所以后缀匹配要求短名至少 [MIN_SHARED_SUFFIX] 个字，而且必须是完整的后缀结尾
 * （不做模糊相似）。合不上最多退回今天的行为（两张卡），合错才是新引入的错误。
 */
object ExpressStationName {

    /**
     * 品牌前缀 —— 只是「谁家开的」，不是地名，也最容易两边写法不一致。
     *
     * 顺序有意义：长的排前面。`菜鸟驿站` 必须先于 `菜鸟` 匹配，否则
     * `菜鸟驿站(文一西路店)` 会被剥成 `驿站(文一西路店)`。
     */
    private val BRAND_PREFIXES = listOf("菜鸟驿站", "菜鸟裹裹", "菜鸟", "驿站")

    /**
     * 允许做后缀合并的最短名字长度。
     *
     * `花园店`（3 字）这种泛称「谁都能以它结尾」，拿它当身份会把无关的驿站串起来。
     * 4 字起步（`阳光花园店`）才有地名辨识度。
     */
    private const val MIN_SHARED_SUFFIX = 4

    /**
     * 只说了「哪一类地方」、没说是「哪一处」的词。
     *
     * 淘宝的通知只写「您购买的宝贝已送达代收点」，抽出来的就是这两个字 —— 它**不是地名**，
     * 拿它当驿站名会在首页凭空多出一个叫「代收点」的分组，和真正的「颍滨花园驿站」并列，
     * 用户以为要多跑一趟（2026-09-26 真机现场）。而且它还会**挡住富化的真名**：合并走
     * 「只填空不覆盖」，占位词非空，宿主给的驿站名就永远进不来（见
     * [ExpressRecord.mergeEnrichment] 里的让位规则）。
     *
     * 只认**整个名字恰好就是这个词**：`南门小区代收点` 带了地名，那是有效地点，不能误伤。
     */
    private val PLACEHOLDERS = setOf(
        "代收点",
        "快递代收点",
        "快递点",
        "快递柜",
        "自提柜",
        "智能快递柜",
        "智能柜",
    )

    /**
     * 归一化：同一个名字的不同包装在这里被抹平。
     *
     * 空白也一并去掉 —— 它从来不是地名的组成部分，纯粹是两边录入的差异。
     * 空名返回空串，由调用方决定怎么兜底（分组那边归到「未知取件地点」）。
     */
    fun normalize(raw: String?): String {
        // 直接调 String?.orEmpty()，不要写成 `raw?.orEmpty()`：后者是 safe-call，
        // 编译器会去 String 上找 orEmpty（那里没有），是个一眼看不出的坑。
        val original = raw.orEmpty().filterNot { it.isWhitespace() }
        if (original.isEmpty()) return ""
        // 整个名字就是个「地方类型词」→ 等于没说在哪。返回空串，由调用方归到「未知取件地点」。
        // 放在剥品牌**之前**：`菜鸟驿站代收点` 剥完也还是占位词，两处都要判。
        if (original in PLACEHOLDERS) return ""
        var name = original
        // 括号和品牌前缀会互相遮住（`(菜鸟驿站A店)` 是先括号后前缀），剥到不动为止。
        // 3 轮只是防死循环，正常最多两轮。
        var rounds = 0
        while (rounds++ < 3) {
            val next = stripBrandPrefix(stripOuterBrackets(name))
            if (next == name) break
            name = next
        }
        if (name in PLACEHOLDERS) return ""
        // 整个名字就是品牌（`菜鸟驿站`）时退回原文：那至少还是用户看到的那串字，
        // 而且它确实没告诉我们「哪家店」，不该和别的驿站混为一谈。
        return name.ifEmpty { original }
    }

    /**
     * 这个串里有没有「地点信息」—— 等价于 [normalize] 之后还剩不剩东西。
     *
     * 给「合并两个来源的驿站名」用：通知侧的 `代收点` 没有地点信息，就该把位置让给
     * 宿主富化给的真名，而不是靠「非空」把它占住（见 [ExpressRecord.mergeEnrichment]）。
     */
    fun hasLocation(raw: String?): Boolean = normalize(raw).isNotEmpty()

    /**
     * 把一批名字聚成类，返回「名字 → 该类的代表名」（代表名也在返回值里，指向自己）。
     *
     * 代表名取类里**最长**的那个，两个理由：地址越全对「去哪取」越有帮助
     * （`阳光23号楼109…` 多给了楼栋号，能少问一次人）；长名通常来自宿主
     * `packageStation.name`，比通知文案权威。
     */
    fun representatives(names: Collection<String>): Map<String, String> {
        val result = HashMap<String, String>(names.size)
        val pinned = mutableListOf<String>()
        // 长度降序：只要按这个顺序处理，代表名天然就是类里最长的那个，后来者只需跟
        // 已有代表比一次。同长度再按字典序，保证结果与输入顺序无关 —— 否则同一批记录
        // 换个顺序就可能聚出不同的组，单测也就钉不住了。
        val ordered = names.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        for (name in ordered) {
            val owner = pinned.firstOrNull { isSameStation(it, name) } ?: name
            if (owner == name) pinned += name
            result[name] = owner
        }
        return result
    }

    /** 同一个地点：短名是长名的**后缀**，且短名本身够长。 */
    private fun isSameStation(a: String, b: String): Boolean {
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        // 等长且相等时 endsWith 为真 —— 重名的记录本来就该归一组，这里不用特判。
        return short.length >= MIN_SHARED_SUFFIX && long.endsWith(short)
    }

    /**
     * 剥一层品牌前缀。**剥不空、也不剥成另一个品牌词**：`菜鸟驿站` 剥掉 `菜鸟` 只剩下
     * `驿站`，那是个和原名一样没有门店信息的串，只是看着短了 —— 这种剥法只会凭空制造
     * 一个「新名字」，让本该相同的两个写法算不到一块去。
     */
    private fun stripBrandPrefix(name: String): String {
        val hit = BRAND_PREFIXES.firstOrNull { name.length > it.length && name.startsWith(it) }
            ?: return name
        val rest = name.substring(hit.length)
        return if (BRAND_PREFIXES.any { rest == it }) name else rest
    }

    /**
     * 剥掉「把整个名字包起来」的那一对括号：`菜鸟驿站(临河阳光花园店)` → `临河阳光花园店`。
     *
     * 只在**整串就是括号内容**时才剥。`菜鸟驿站(A区)东门` 里的括号是名字的一部分，
     * 剥了会把它错认成 `A区` —— 那是另一个地点，宁可不合。
     */
    private fun stripOuterBrackets(name: String): String {
        if (name.length < 3) return name
        val inner = when {
            name.startsWith('(') && name.endsWith(')') -> name.substring(1, name.length - 1)
            name.startsWith('（') && name.endsWith('）') -> name.substring(1, name.length - 1)
            else -> return name
        }
        // 里面还嵌着括号，说明这对括号不是包装层（`(A(B))`），别动。
        val nested = inner.any { it == '(' || it == ')' || it == '（' || it == '）' }
        return if (nested) name else inner
    }
}
