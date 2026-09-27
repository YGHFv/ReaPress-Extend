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
 * 从通知文本里抽取结构化字段。
 *
 * 全部是纯函数、只吃 String：system_server 里不能碰 Notification 对象做复杂操作
 * （那会在 NMS 的关键路径上加锁/耗时），所以调用方先把文本取出来，这里只做字符串处理。
 */
object ExpressParser {

    /**
     * ⛔ **本文件不许再用 `\b`**（2026-09-27 真机实证）。
     *
     * Android 的 `java.util.regex` 由 ICU 支撑，`\b` 是 **Unicode 词边界** —— 汉字算「词字符」。
     * 于是 `凭1-1-2001到…` 里 `凭` 与 `1` 之间**根本没有边界**，带 `\b` 的正则在真机上一条都配不上。
     * 而 JVM 的 `\b` 是 ASCII 的（汉字不算词字符），**单测全绿、真机全废** —— 这正是这个坑
     * 一直没被发现的原因。同一台设备实测：
     *
     * | 文本 | 结果 |
     * |---|---|
     * | `凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹` | 取件码抽不到，记录连强标识都没有（`upsert dropped`） |
     * | `凭 17-5-2644 到…`（两侧是空格） | 取件码抽得到 |
     * | `单号7903000000000已到驿站 取件码 3-2-2008` | 只有取件码抽到，**运单号也没抽到** |
     *
     * 所以「前后不是字母数字」一律用**显式的 ASCII 字符类断言**表达：字符类是明确的，
     * 两个运行时行为一致，单测才有代表性。
     */
    private const val NOT_ALNUM_BEFORE = "(?<![0-9A-Za-z])"
    private const val NOT_ALNUM_AFTER = "(?![0-9A-Za-z])"

    /**
     * 运单号候选。
     *
     * 顺序即优先级 —— 越靠前的越不容易误判：
     * 1. 字母前缀型（SF1234567890123、YT...）：前缀唯一，误判率极低
     * 2. 纯数字 12-15 位：主流国内快递的长度区间
     *
     * **刻意不收 11 位纯数字**：那是手机号。快递短信里手机号几乎必然出现，
     * 收进来会把手机号当运单号。宁可漏掉 11 位的运单号。
     */
    private val TRACKING_PATTERNS = listOf(
        Regex("$NOT_ALNUM_BEFORE([A-Z]{2,3}\\d{9,13})$NOT_ALNUM_AFTER"),
        Regex("$NOT_ALNUM_BEFORE(\\d{12,15})$NOT_ALNUM_AFTER"),
    )

    /**
     * 带国家码的手机号 —— 它是**发件号码**，不是运单号。
     *
     * 短信通知的标题就是发送方号码，形如 `+8613800138000`；剥掉 `+`/`00` 之后正好落进
     * 「12-15 位纯数字」这条运单号规则里。2026-09-27 真机：这条短信（`凭1-1-2001到…`）
     * 被记成了一单 `运单号 8613800138000` 的包裹 —— 首页上就是一串谁也不认识的数字，
     * 而真正的包裹因为「运单号不相等」永远配不上它。
     */
    private val PHONE_WITH_COUNTRY_CODE = Regex("""^(?:00)?86(1[3-9]\d{9})$""")

    /** 取件码：「取件码 8-2-3021」「取件码：1234」「提货码 A1234」「取件码-6-2-2003】」 */
    private val PICKUP_CODE_PATTERNS = listOf(
        Regex("""(?:取件码|提货码|取货码|提取码)\s*[-—:：]?\s*([0-9A-Za-z][0-9A-Za-z\-]{2,15})"""),
        // 菜鸟驿站的「货架-层-序号」格式，没有「取件码」前缀也认得出来
        Regex("$NOT_ALNUM_BEFORE(\\d{1,3}-\\d{1,2}-\\d{3,6})$NOT_ALNUM_AFTER"),
    )

    /**
     * 包裹尾号 —— 通知里那句「**取尾号1234**包裹」里的 `1234`。
     *
     * 驿站/快递柜的通知常常只有尾号能对上：文本里明说「取尾号 XXXX」，而宿主给的是一长串
     * 运单号。有了这个尾号，同一驿站里「运单号后四位 = 本尾号」的那件包裹就能被认出来
     * （见 `ExpressRecordStore.tailMatches`）—— 短信里唯一能用来认领包裹的东西就是它。
     *
     * 必须带「取」这个动词：**单独的「尾号1234」不认**。原因不只是「可能认错」——
     * `尾号1234的包裹` 这种写法在「手机尾号」句里同样出现（`您的手机尾号1234的包裹到了`），
     * 认了就会把**手机尾号**当成运单号尾段去匹配另一件包裹。宁可漏，不可错。
     * （手机尾号由 [PHONE_TAIL_PATTERNS] 判，两者别互相抢。）
     */
    private val PARCEL_TAIL_PATTERNS = listOf(
        Regex("""取尾号\s*[:：]?\s*(\d{3,6})(?!\d)"""),
    )

    /**
     * 驿站关键词，**长的排前面**：同一个位置优先认长的，`菜鸟驿站` 不该被拆成 `驿站`。
     */
    private val STATION_KEYWORDS = listOf(
        "菜鸟驿站", "菜鸟裹裹", "服务中心", "快递柜", "自提柜", "代收点", "驿站", "丰巢",
    ).sortedByDescending { it.length }

    /**
     * 抓驿站名时**向前**（关键词之前）扫到这些词就停 —— 它们是句子的谓语/介词，不是店名的一部分。
     *
     * 加这一层之前的写法是「只从关键词往后啃」（见 `关键词后面跟着句子时只取到关键词` 那条测试
     * 的旧注释）：那时认为往前面啃会把 `已到` `在` 这类叙述词吃进来。方向没错，做法太糙 ——
     * 真机 2026-09-27 的短信 `凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹` 里，
     * 店名 `阳光花园菜鸟驿站` **整段在关键词前面**，只往后啃的结果是 `驿站取尾号1234包裹`
     * —— 首页上就是一个叫这串字的驿站分组，用户报的「快递站点识别不了」就是它。
     *
     * 所以现在的判据是「向前扫，遇到虚词才停」：`到` 停 → `阳光花园菜鸟` + `驿站`；
     * 而 `您的包裹已到菜鸟驿站(杭州文一西路店)` 同样在 `到` 处停 → 结果与旧行为一致。
     *
     * 「多字词排在单字前」由 [stationHead] 的扫描顺序保证（每退一个字都先把长词试一遍）。
     *
     * 取舍：`在` 是单字停止词，所以 `在水一方驿站` 这种名字会丢掉开头的 `在`。这样的句子
     * 本来就极少（要在通知里写「在在水一方驿站…」才触发），真误伤了用户在驿站管理里改名即可
     * —— 比「整段店名认不出来」轻得多。
     */
    private val STATION_HEAD_BREAK = listOf(
        // 多字：动词短语
        "放入", "存入", "寄入", "寄到", "送到", "已到", "到达", "送至", "送往", "投放", "放在", "存在",
        // 单字：真正不会出现在店名里的虚词
        "到", "至", "在", "从", "由", "往", "向", "于", "请", "凭", "的", "是", "您", "你",
    )

    /**
     * 关键词之后的**尾巴**遇到就停的词：它们是句子的谓语或凭据，不是店名的组成部分。
     *
     * `取` 是单字，它一并盖住了 `取件码` / `取货码` / `提取码` / `取尾号` 四种写法 ——
     * 真机原文 `…在代收点存放已超过24小时 【取件码-6-2-2003】` 曾抽出
     * `代收点存放已超过24小时 【取件码-6-2-6`（正好撞上 20 字上限），首页于是凭空多出一个
     * 叫这串字的驿站分组，还会挡住宿主富化的真名。
     *
     * 这一层只负责**在哪儿停**；「停下来之后剩下的还算不算地名」交给
     * [ExpressStationName.normalize]（`代收点` 会落进占位词表）。两层都必要：
     * 这里保的是「别把垃圾存进记录」，那边管的是「历史记录里已经存下的也要能纠正」。
     */
    private val STATION_TAIL_BREAK = listOf(
        "存放", "超过", "超时", "逾期", "未取", "提货码", "验证码", "尾号", "取", "您", "请",
    )

    /** 尾巴里允许出现的连接号（店名常带 `A区-1号` 这种写法）。其余标点一律是边界。 */
    private val STATION_TAIL_LINK_CHARS = charArrayOf('-', '—', '·', '~', '～', '&', '+', '_')

    /** 向前 / 向后各最多吃这么多字。真机名最长十几个字，超出基本就是啃到句子了。 */
    private const val MAX_STATION_HEAD = 20
    private const val MAX_STATION_TAIL = 20

    /**
     * 收件手机号尾号。
     *
     * **必须带明确措辞**才认 —— 这是本文件里唯一一处「宁可漏，不可错」的取舍说到极致的规则：
     * 通知里四位数字到处都是（取件码尾段、楼层货架号、验证码），而手机尾号本身**不是**取件凭据，
     * 认错了会引导用户去跟店员报一个错的号。所以要求「手机/电话」与「尾号/后四位」同时出现，
     * 两种语序都收：
     *
     * - `手机尾号1234` / `手机号后四位：1234`（手机名在前）
     * - `尾号1234的手机`（尾号在前）
     *
     * 不认单独的「尾号1234」：那是运单号尾号的常见写法。
     */
    private val PHONE_TAIL_PATTERNS = listOf(
        Regex("""(?:手机|电话|手机号|手机号码)\s*号?\s*(?:尾号|后四位|末四位|后4位|末4位)\s*[:：]?\s*(\d{4})(?!\d)"""),
        Regex("""(?:尾号|后四位|末四位)\s*[:：]?\s*(\d{4})(?!\d)\s*的?\s*(?:手机|电话)"""),
    )

    /**
     * 状态关键词 → 状态。**顺序敏感**：越具体的越靠前。
     *
     * 例如「已签收」必须在「签收」之前判，否则「已签收」会先命中「签收」；
     * 「投递失败」必须在「派送」之前判，因为失败通知里常常也写着「派送」。
     */
    private val STATUS_RULES = listOf(
        "投递失败" to ExpressStatus.FAILED,
        "派送失败" to ExpressStatus.FAILED,
        "无人接收" to ExpressStatus.FAILED,
        "已签收" to ExpressStatus.SIGNED,
        "已代收" to ExpressStatus.SIGNED,
        "本人签收" to ExpressStatus.SIGNED,
        "取件码" to ExpressStatus.READY_FOR_PICKUP,
        "待取件" to ExpressStatus.READY_FOR_PICKUP,
        "请及时取件" to ExpressStatus.READY_FOR_PICKUP,
        // 「取尾号1234包裹」是驿站/快递柜最常见的到站话术，整句里一个「取件码」都没有，
        // 不收它就只能是 UNKNOWN —— 真机那条短信于是掉进首页的「其他」档，
        // 而它明明已经躺在驿站里等着取了。语义上它和「凭取件码取件」是同一件事。
        "取尾号" to ExpressStatus.READY_FOR_PICKUP,
        "已到站" to ExpressStatus.ARRIVED_STATION,
        "已到达" to ExpressStatus.ARRIVED_STATION,
        "已入柜" to ExpressStatus.ARRIVED_STATION,
        "派送中" to ExpressStatus.DELIVERING,
        "正在派送" to ExpressStatus.DELIVERING,
        "派件中" to ExpressStatus.DELIVERING,
        "运输中" to ExpressStatus.IN_TRANSIT,
        "已发出" to ExpressStatus.IN_TRANSIT,
        "已发货" to ExpressStatus.IN_TRANSIT,
        "已揽收" to ExpressStatus.PICKED_UP,
        // 「已揽件」是**菜鸟宿主自己的状态名**（`logisticsStatusDesc` 的原话），
        // 通知文案里一般写「已揽收」。两个都不收的话，这类件在首页会掉进「其他」——
        // 用户看到的是「状态显示不出来」，而不是「我没写这个词」。
        "已揽件" to ExpressStatus.PICKED_UP,
        "已收件" to ExpressStatus.PICKED_UP,
        "已下单" to ExpressStatus.CREATED,
        // 同上，宿主用「待发货」表示还没交给快递。归到 CREATED（= 已下单）：
        // 它是**下单之后的第一个状态**，语义就是「还没上路」，不是运输中。
        "待发货" to ExpressStatus.CREATED,
        // 「包裹正在等待揽收」是宿主 lastLogisticDetail 的原话。注意它必须排在
        // 「已揽收」的前面判不到冲突（两者互不包含），放这里只是和 CREATED 组扎堆。
        // 不收它的话，这类件的状态全靠 statusDesc 兜着 —— 而宿主对它们笼统写「运输中」。
        "等待揽收" to ExpressStatus.CREATED,
        "待揽收" to ExpressStatus.CREATED,
    )

    fun parseTrackingNumber(text: String): String? {
        for (pattern in TRACKING_PATTERNS) {
            for (match in pattern.findAll(text.uppercase())) {
                val candidate = match.groupValues[1]
                if (candidate.all(Char::isDigit)) {
                    // 纯数字的再排两次「长得像号码而不是单号」的：
                    if (candidate.length >= 12 && PHONE_WITH_COUNTRY_CODE.matches(candidate)) continue
                    // 以 19/20 开头的 12 位可能是日期或时间戳
                    if (candidate.length >= 12 &&
                        (candidate.startsWith("19") || candidate.startsWith("20"))
                    ) {
                        continue
                    }
                }
                return candidate
            }
        }
        return null
    }

    fun parsePickupCode(text: String): String? {
        for (pattern in PICKUP_CODE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }

    /**
     * 驿站 / 快递柜名称。
     *
     * 找关键词 → 向前啃地名 → 向后啃门店后缀（含括号门店名），三块拼起来。
     * 为什么要向前啃、以及向前啃在哪儿停，见 [STATION_HEAD_BREAK] 的注释。
     */
    fun parseStation(text: String): String? {
        val index = firstKeywordIndex(text) ?: return null
        val keyword = STATION_KEYWORDS.first { text.startsWith(it, index) }
        val head = stationHead(text, index)
        val tail = stationTail(text, index + keyword.length)
        return (head + keyword + tail).ifBlank { null }
    }

    /** 第一个命中的驿站关键词的下标；没有返回 null。同一位置上取**最长**的那个词。 */
    private fun firstKeywordIndex(text: String): Int? {
        for (index in text.indices) {
            if (STATION_KEYWORDS.any { text.startsWith(it, index) }) return index
        }
        return null
    }

    /**
     * 关键词**之前**那一段地名。从关键词位置往前扫，遇到虚词或非文字字符就停。
     *
     * 只吃「汉字 + ASCII 字母数字」：[isLetterOrDigit] 对汉字返回 true，所以标点、空格、
     * 换行天然就是边界，不必再列一张标点表。
     */
    private fun stationHead(text: String, end: Int): String {
        var start = end
        while (start > 0 && end - start < MAX_STATION_HEAD) {
            val c = text[start - 1]
            if (!c.isLetterOrDigit()) break
            // 停止词判的是「文字串以这个词**结尾**」——`已放入丰巢` 里的 `放入` 占 3、4 两位，
            // 当前 cursor 正好停在 5（= `丰` 的位置），所以拿 [start - len, start) 这段比。
            val endsWithBreak = STATION_HEAD_BREAK.any { word ->
                start - word.length >= 0 && text.regionMatches(start - word.length, word, 0, word.length)
            }
            if (endsWithBreak) break
            start--
        }
        return text.substring(start, end)
    }

    /**
     * 关键词**之后**那一段。停词见 [STATION_TAIL_BREAK]；紧跟着的括号门店名整段收下
     * （`菜鸟驿站(杭州文一西路店)`）。
     *
     * 括号只在「长度合理且闭合」时才认：半句话里冒出一个左括号时，宁可不收，
     * 也别把后面一大段叙述塞进驿站名。
     */
    private fun stationTail(text: String, from: Int): String {
        var end = from
        while (end < text.length && end - from < MAX_STATION_TAIL) {
            if (STATION_TAIL_BREAK.any { text.startsWith(it, end) }) break
            val c = text[end]
            if (!c.isLetterOrDigit() && c !in STATION_TAIL_LINK_CHARS) break
            end++
        }
        // 括号前允许有空格（`菜鸟驿站 (杭州文一西路店)`），空格随括号一起收进结果里。
        var probe = end
        while (probe < text.length && text[probe].isWhitespace()) probe++
        val close = when (text.getOrNull(probe)) {
            '(' -> ')'
            '（' -> '）'
            else -> return text.substring(from, end)
        }
        val closeAt = text.indexOf(close, probe + 1)
        if (closeAt < 0 || closeAt - probe > MAX_STATION_TAIL) return text.substring(from, end)
        return text.substring(from, closeAt + 1)
    }

    /** 收件手机尾号（4 位数字）。判据见 [PHONE_TAIL_PATTERNS]，抽不到返回 null。 */
    fun parsePhoneTail(text: String): String? {
        for (pattern in PHONE_TAIL_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    /**
     * 包裹尾号（`取尾号1234包裹` 里的 `1234`）。判据见 [PARCEL_TAIL_PATTERNS]，抽不到返回 null。
     *
     * 它与 [parsePhoneTail] 是两回事：那个是**凭手机号取件**时要说出口的号，这个是
     * **用来认领包裹**的运单号尾段。两者都可能出现在同一条通知里，别互相顶掉。
     */
    fun parseParcelTail(text: String): String? {
        for (pattern in PARCEL_TAIL_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    fun parseStatus(text: String): ExpressStatus {
        for ((keyword, status) in STATUS_RULES) {
            if (text.contains(keyword)) return status
        }
        return ExpressStatus.UNKNOWN
    }

    /**
     * 一站式解析。
     *
     * @param text 标题与正文拼好的文本
     * @param verdict 判定结果（关键词命中情况会带进记录，供界面解释）
     */
    fun parse(
        sourcePackage: String,
        title: String?,
        text: String,
        verdict: ExpressVerdict,
        timestamp: Long = 0L,
    ): ExpressRecord {
        val trackingNumber = parseTrackingNumber(text)
        return ExpressRecord(
            sourcePackage = sourcePackage,
            rawText = text,
            trackingNumber = trackingNumber,
            courier = Courier.fromTrackingNumber(trackingNumber),
            pickupCode = parsePickupCode(text),
            station = parseStation(text),
            phoneTail = parsePhoneTail(text),
            parcelTail = parseParcelTail(text),
            status = parseStatus(text),
            title = title,
            matchedKeywords = verdict.matchedKeywords,
            confidence = verdict.confidence,
            timestamp = timestamp,
        )
    }
}
