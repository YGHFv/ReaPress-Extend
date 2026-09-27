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
 * 驿站名的身份判定 —— 判断两个写法是不是**同一个取件地点**。
 *
 * ## 为什么需要
 *
 * 首页按驿站名分组，可同一个驿站在两条记录里常有两种写法，而且**都不是脏数据**：
 *
 * - 通知文案里是简称 —— `阳光花园菜鸟驿站`；
 * - 宿主 `packageStation.name` 把楼栋号一起带上 —— `阳光23号楼109阳光花园菜鸟驿站`。
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
 *    （`阳光花园菜鸟驿站` 是 `阳光23号楼109阳光花园菜鸟驿站` 的后缀）。
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
     * 拿它当驿站名会在首页凭空多出一个叫「代收点」的分组，和真正的「阳光花园驿站」并列，
     * 用户以为要多跑一趟（2026-09-26 真机现场）。而且它还会**挡住富化的真名**：合并走
     * 「只填空不覆盖」，占位词非空，宿主给的驿站名就永远进不来（见
     * [ExpressRecord.mergeEnrichment] 里的让位规则）。
     *
     * 只认**整个名字恰好就是这个词**：`南门小区代收点` 带了地名，那是有效地点，不能误伤。
     */
    private val PLACEHOLDERS = setOf(
        // `驿站` / `菜鸟驿站` 剥完品牌前缀之后剩下的就是这个 —— 它和 `代收点` 一样是
        // 「哪一类地方」而不是「哪一处」。真机里它只以两种方式出现：句子里那句裸的
        // `…已到驿站…`（2026-09-27 探针通知），以及 [NARRATIVE_FRAGMENTS] 截断后的残留。
        // 注意**不影响** `菜鸟驿站` 本身：`stripBrandPrefix` 拒绝把名字剥成一个品牌词，
        // 所以 `菜鸟驿站` 原样返回、不落进这里。
        "驿站",
        "代收点",
        "快递代收点",
        "快递点",
        "快递柜",
        "自提柜",
        "智能快递柜",
        "智能柜",
    )

    /**
     * 出现即说明「名字到这里就完了」的片段 —— 它们是句子的谓语或凭据，不是店名的组成部分。
     *
     * 抓驿站名时只能顺着关键词往后啃一段字符，啃到店名后是运气，啃到句子后是常态。
     * 2026-09-27 真机：`代收点存放已超过24小时 【取件码-6-2-2003】` 被整段当成驿站名存了下来，
     * 首页多出一个同名分组。解析层（[ExpressParser.STATION_PATTERNS]）现在已经能在这些词处
     * 停下，但**历史记录里存下的那些改不掉** —— 分组、显示名都过 [normalize]，所以在这里
     * 再截一次才是最省事的那道兜底。
     *
     * 取「截断」而不是「整条作废」：`临河阳光花园代收点存放中` 的前半段仍是有效地点，丢掉可惜。
     * 截完如果是 [PLACEHOLDERS] 里的类型词，自然就落进「不知道在哪」，不需要另写判空。
     *
     * 刻意**不收**「小时」「分钟」：`24小时便利店代收点` 是真会出现的店名，收进来会误伤。
     */
    private val NARRATIVE_FRAGMENTS = listOf(
        "存放", "超过", "超时", "逾期", "未取",
        // 「取件凭据」的各种写法。2026-09-27 真机那条短信
        // `凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹` 被旧解析器存成
        // `驿站取尾号1234包裹`（从关键词往后啃，把凭据那半句一起吃了），
        // 首页于是多出一个叫这串字的分组。
        //
        // `取尾号` 必须**单独列出来**，不能只靠 `尾号`：那会切在 `尾` 上、留下 `驿站取`，
        // 而 `驿站取` 剥掉品牌前缀之后只剩一个 `取` 字 —— 一个说不出任何地点的残渣。
        // 同理也不收**裸的 `取`**：`取水楼驿站` 这种真会存在的名字会被整条切掉。
        "取件码", "取货码", "提取码", "取尾号", "尾号",
        "验证码", "您", "请",
    )

    /** 截断后可能剩下半个括号/顿号，一并刮掉：`A小区驿站（存放点）` → `A小区驿站`。 */
    private val TRAILING_JUNK = charArrayOf(
        '（', '(', '【', '[', '「', '·', '、', '，', '。', '-', '~', '～',
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
        val compact = raw.orEmpty().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return ""
        // 「关键词 + 半句话」在这里被截回名字本身（判据见 [NARRATIVE_FRAGMENTS]）。
        // 放在占位词判断**之前**：截完可能正好剩下「代收点」，两处都要判。
        val original = truncateAtNarrative(compact)
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
     * 在第一个 [NARRATIVE_FRAGMENTS] 处截断，并刮掉因此露在外面的标点。
     *
     * 片段出现在**开头**时返回空串 —— 那说明整串都是句子的其余部分，一点地名信息都没有
     * （`存放已超过24小时`）。不能返回原文：那会变成一个凭空冒出来的驿站名。
     */
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

    /**
     * 这个串里有没有「地点信息」—— 等价于 [normalize] 之后还剩不剩东西。
     *
     * 给「合并两个来源的驿站名」用：通知侧的 `代收点` 没有地点信息，就该把位置让给
     * 宿主富化给的真名，而不是靠「非空」把它占住（见 [ExpressRecord.mergeEnrichment]）。
     */
    fun hasLocation(raw: String?): Boolean = normalize(raw).isNotEmpty()

    /**
     * 这个串是不是「关键词 + 半句话」的抓取产物 —— 等价于 [truncateAtNarrative] 真的截掉了东西。
     *
     * 给**读路径的自我修复**用（[ExpressRecordRepair]）：历史记录里存的是旧解析器写下的值，
     * 光靠 [normalize] 只能在**显示**时把它遮住，记录里那串烂字还在，而且会继续占着
     * [ExpressRecord.mergeEnrichment] 的「只填空不覆盖」名额。要判断「这条该不该用现在的
     * 解析逻辑重扫一遍」，缺的就是这个谓词。
     *
     * 为什么用「截掉了东西」而不是「归一化后变了」：[normalize] 剥品牌前缀、剥括号也算变，
     * 那些是**正常包装**（`菜鸟驿站(临河店)`），不该触发重扫。只有句子的尾巴被切下来，
     * 才说明原来那次抽取是错的。
     */
    fun isNarrativeArtifact(raw: String?): Boolean {
        val compact = raw.orEmpty().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return false
        return truncateAtNarrative(compact) != compact
    }

    /**
     * 这串字给出的取件地点，**原样返回**（不剥品牌、不剥括号）；说不出「哪一处」时返回 null。
     *
     * 与 [normalize] 的分工：那个算的是**身份**（同一处的两种写法必须相等），为了相等它可以
     * 改写；这个给的是**显示**—— 替换通知的正文、驿站管理里那几行「原始写法」，用户要一眼
     * 认出是哪家店，把 `菜鸟驿站` 剥掉反而是丢信息。
     *
     * 共用的仍是同一套「这算不算一个地点」的判据（截掉句子 + 类型词不算），所以**历史记录里
     * 已经存下的脏名字在这里也会被丢掉** —— 解析层修好了只管新数据，旧记录改不掉，
     * 显示侧不能再漏一遍。
     */
    fun placeName(raw: String?): String? {
        val compact = raw.orEmpty().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        return truncateAtNarrative(compact).takeIf { hasLocation(it) }
    }

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
