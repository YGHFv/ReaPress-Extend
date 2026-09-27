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
 * 拼多多 HTTP 响应缓存（`cache/pdd_cache` 目录下的响应文件）的**包裹发现**解析器 —— 纯函数，可单测。
 *
 * ## 数据源为什么在这里（2026-09-27 真机定性）
 *
 * 拼多多的「取快递」页整个是 H5（`NewPageActivity` + `meco.webkit.WebView`）：
 * 进页时 express 相关 HTTP 请求为零，列表数据来自宿主自己的 HTTP 响应缓存
 * `cache/pdd_cache` 的响应文件。缓存里是订单/快递卡的原始 JSON，快递页那份带
 * `express_tab` 分栏结构（真机样本核实，内容化名）：
 *
 * ```json
 * {"express_tab":{"tab_list":[
 *   {"tab_id":"sign","tab_name":"已签收","orders":[]},
 *   {"tab_id":"got","tab_name":"待取件",
 *    "items":[{"pick_up_info":{"company_name":"某驿站","address":"某楼栋",…},
 *              "orders":[{"pick_up_desc":[{"text":"取件码 1-1-2001"}],
 *                        "tracking_num":"中通快递: 770012340000005",
 *                        "order_sn":"260922-999000000000001",
 *                        "express_link_url":"goods_express.html?tracking_number=…",
 *                        "additional_desc":[{"text":"9月24日18:22送达，已超3天未取"}]}]}]}]}}
 * ```
 *
 * 订单列表那份没有分栏，但每个订单的「查看物流」按钮 URL（`goods_express.html?…`）
 * 前约 100~400 字符处贴着同一订单的 `chat_status_prompt`（历史件是「交易成功」）——
 * 这是把历史已签收件认出来的锚点（真机样本核实）。
 *
 * 挂点（parse 出口）抓不到它 —— H5 不经过宿主的对象解析层，所以「发现」只能扫缓存。
 *
 * ## 输入约定
 *
 * 调用方（`hook/PddCacheScanner`）按 **ISO_8859_1** 把缓存文件读成字符串再交进来
 * （缓存是 HTTP 报文原样落盘，字节级操作才稳）；因此**中文是 mojibake**，
 * 本解析器对抽出的文本字段逐个转回 UTF-8（[fixMojibake]）。
 *
 * ⚠️ 缓存里 JSON 的 `=` 被编码成 `\u003d`（真机第一版零命中的原因），
 * 所有带 `=` 的正则都要两种写法兼容。
 *
 * ## 抽取策略：事件扫描，不试图完整解析 JSON
 *
 * 缓存文件是**多个响应拼起来的**（HTTP 报文边界、gzip 后的二进制块混在一起），
 * 没有「整个文件是一份 JSON」这回事。能稳定依赖的只有**字段级的局部结构**：
 * 把若干锚点正则的全部命中按位置排序，当一条事件流走一遍，维护「当前分栏 /
 * 当前驿站 / 待挂的取件提示 / 最近一个包裹」四份上下文：
 *
 * - `tab_id` → 当前分栏的状态语义（got=待取件、send=派送中、sign=已签收）；
 * - `pick_up_info` → 驿站名/地址，作用于其后、下一份 `pick_up_info` 之前的单；
 * - `pick_up_desc` → 取件提示，挂在**其后 1500 字符内**的第一个运单锚点上
 *   （真机形状里它就在 `tracking_num` 前面一点；带过期窗口是防止没等到锚点
 *   就泄漏给下一个单）；
 * - `tracking_num` 值 / `express_link_url` → 运单锚点，产出包裹；
 * - `additional_desc` / 状态提示词 → 挂到**最近一个**锚点的窗口内。
 *
 * ## 状态映射的刻意留白
 *
 * `onroad`（运输中）**不映射** [ExpressStatus.IN_TRANSIT] —— 与轨迹拉取那条腿同一条
 * 理由（见 `CainiaoTraceFetcher`）：收下「运输中」会把富化合并里「CREATED 纠正
 * IN_TRANSIT」的修正原样盖回去。同理「待收货」这类提示词在到站/运输之间分不清，不猜。
 *
 * ## 取件信息的三种形态（优先级见 [Package.pickupCode] 的说明）
 *
 * - **连字符数字码**（`1-1-2001` 这种货架格号）→ [Package.pickupCode]；
 * - **纯数字码**（`123456`）→ [Package.pickupCode]（有「手机号/尾号」字样时**不收**，
 *   那更可能是手机尾号 —— 收错码比没有码糟，用户会照着念错）；
 * - **出示手机号**（`取件手机号 188****0000`）→ [Package.phoneTail]（后四位），
 *   **不进** [Package.pickupCode]：它不是码。
 */
object PddCacheDiscovery {

    /** 缓存文件只认这一个目录标记（`libzstd.so` 里混着 `YT000…` 测试数据，别碰）。 */
    const val CACHE_PATH_MARK = "pdd_cache"

    /** 一个从缓存里发现的包裹。字段全可空 —— 抽不到就是 null，不编造。 */
    data class Package(
        /** 运单号。强标识 —— 没有它整个单不成立（身份判定的最高一级）。 */
        val trackingNumber: String,
        /** 拼多多订单号（`260922-999000000000001` 形状），只用于诊断与去重。 */
        val orderSn: String? = null,
        /**
         * 取件码 —— 只收「码形」的值：连字符数字（`1-1-2001`）优先，纯数字其次。
         * 「出示手机号」这类提示**不是码**，落在 [phoneTail]。
         */
        val pickupCode: String? = null,
        /** 收件手机尾号（4 位，来自脱敏手机号 `188****0000` 的末段）。 */
        val phoneTail: String? = null,
        /** 快递公司。来自 `tracking_num` 值的中文前缀（`中通快递: …`），认不出是 UNKNOWN。 */
        val courier: Courier = Courier.UNKNOWN,
        /** 驿站名。来自待取件分栏的 `pick_up_info.company_name`。 */
        val station: String? = null,
        /** 驿站地址。来自同一处的 `address`。 */
        val stationAddress: String? = null,
        /** 取件/送达提示原文（`9月24日18:22送达，已超3天未取`），落库进 logisticsDetail。 */
        val logisticsDetail: String? = null,
        /** 分栏/提示词推得的状态。推不出是 UNKNOWN —— 运输中刻意不推，见类注释。 */
        val status: ExpressStatus = ExpressStatus.UNKNOWN,
    )

    /**
     * 解析一批缓存文件。
     *
     * @param files 相对路径 → 文件全文（ISO_8859_1 读入的原始字节串）。
     *   路径不含 [CACHE_PATH_MARK 的一律忽略。
     * @return 按「首次发现顺序」去重合并后的包裹表（同一单号出现在多个缓存版本里是常态，
     *   字段按「只填空」合并、状态只推进 —— 与模块侧落库的合并哲学一致）。
     */
    fun parse(files: Map<String, String>): List<Package> {
        val merged = LinkedHashMap<String, Package>()
        for ((path, text) in files) {
            if (CACHE_PATH_MARK !in path) continue
            for (found in parseFile(text)) {
                val existing = merged[found.trackingNumber]
                merged[found.trackingNumber] = if (existing == null) {
                    found
                } else {
                    existing.copy(
                        orderSn = existing.orderSn ?: found.orderSn,
                        pickupCode = existing.pickupCode ?: found.pickupCode,
                        phoneTail = existing.phoneTail ?: found.phoneTail,
                        courier = if (existing.courier != Courier.UNKNOWN) existing.courier else found.courier,
                        station = existing.station ?: found.station,
                        stationAddress = existing.stationAddress ?: found.stationAddress,
                        logisticsDetail = existing.logisticsDetail ?: found.logisticsDetail,
                        status = if (found.status.order > existing.status.order) found.status else existing.status,
                    )
                }
            }
        }
        return merged.values.toList()
    }

    // ---------------------------------------------------------------- 事件扫描

    private enum class Kind { TAB, PICKUP_INFO, PICK, TRACKING_FIELD, TRACKING_URL, PROMPT, ADDITIONAL }

    private data class Event(val pos: Int, val kind: Kind, val match: MatchResult)

    /** 单个缓存文件 → 若干包裹（不去重，交给 [parse] 合并）。 */
    private fun parseFile(text: String): List<Package> {
        val events = buildList {
            TAB_RE.findAll(text).forEach { add(Event(it.range.first, Kind.TAB, it)) }
            PICKUP_INFO_RE.findAll(text).forEach { add(Event(it.range.first, Kind.PICKUP_INFO, it)) }
            PICK_RE.findAll(text).forEach { add(Event(it.range.first, Kind.PICK, it)) }
            TRACKING_FIELD_RE.findAll(text).forEach { add(Event(it.range.first, Kind.TRACKING_FIELD, it)) }
            TRACKING_URL_RE.findAll(text).forEach { add(Event(it.range.first, Kind.TRACKING_URL, it)) }
            PROMPT_RE.findAll(text).forEach { add(Event(it.range.first, Kind.PROMPT, it)) }
            ADDITIONAL_RE.findAll(text).forEach { add(Event(it.range.first, Kind.ADDITIONAL, it)) }
        }.sortedBy { it.pos }

        val acc = LinkedHashMap<String, Package>()
        var tabStatus: ExpressStatus? = null
        var station: String? = null
        var stationAddress: String? = null
        var pendingPickup: String? = null
        var pendingPhoneTail: String? = null
        var pendingPickPos = -1
        // orderList 缓存里状态提示词在锚点**前** ~100-400 字符 —— 待挂的提示，见 PROMPT 事件。
        var pendingPrompt: ExpressStatus? = null
        var pendingPromptPos = -1
        var lastTn: String? = null
        var lastAnchor = -1

        fun emit(tn: String, anchor: Int, courier: Courier, orderSn: String?) {
            // 取件提示只认「锚点前 1500 字符内」的那份 —— 过期作废，绝不挂给下一个单。
            val pickup = pendingPickup?.takeIf { anchor - pendingPickPos <= PICK_WINDOW }
            val phoneTail = pendingPhoneTail?.takeIf { anchor - pendingPickPos <= PICK_WINDOW }
            if (anchor - pendingPickPos <= PICK_WINDOW) {
                pendingPickup = null
                pendingPhoneTail = null
            }
            // 锚点前贴近的提示词属于本单（orderList 形状），消费掉；过期的作废。
            val prompt = pendingPrompt?.takeIf { anchor - pendingPromptPos <= PROMPT_BEFORE }
            pendingPrompt = null
            val base = tabStatus ?: ExpressStatus.UNKNOWN
            val status = if (prompt != null && prompt.order > base.order) prompt else base
            val existing = acc[tn]
            acc[tn] = if (existing == null) {
                Package(
                    trackingNumber = tn,
                    orderSn = orderSn,
                    pickupCode = pickup,
                    phoneTail = phoneTail,
                    courier = courier,
                    station = station,
                    stationAddress = stationAddress,
                    status = status,
                )
            } else {
                existing.copy(
                    orderSn = existing.orderSn ?: orderSn,
                    pickupCode = existing.pickupCode ?: pickup,
                    phoneTail = existing.phoneTail ?: phoneTail,
                    courier = if (existing.courier != Courier.UNKNOWN) existing.courier else courier,
                    station = existing.station ?: station,
                    stationAddress = existing.stationAddress ?: stationAddress,
                    // 状态只推进：同一单先见到「待取件」栏再见「交易成功」提示词时取更高档。
                    status = maxOf(existing.status, status),
                )
            }
            lastTn = tn
            lastAnchor = anchor
        }

        for ((pos, kind, m) in events) {
            when (kind) {
                Kind.TAB -> {
                    tabStatus = tabStatusOf(m.groupValues[1])
                    // 驿站上下文属于「待取件」栏的分组结构，换栏必须清掉 ——
                    // 不然下一栏的单会挂上上一栏的驿站名。
                    station = null
                    stationAddress = null
                }
                Kind.PICKUP_INFO -> {
                    val window = text.substring(pos, (pos + PICKUP_INFO_WINDOW).coerceAtMost(text.length))
                    station = COMPANY_RE.find(window)?.groupValues?.get(1)?.let(::fixMojibake)
                    stationAddress = ADDRESS_RE.find(window)?.groupValues?.get(1)?.let(::fixMojibake)
                }
                Kind.PICK -> {
                    pendingPickup = pickupCodeOf(m.groupValues[1])
                    pendingPhoneTail = phoneTailOf(m.groupValues[1])
                    pendingPickPos = pos
                }
                Kind.TRACKING_FIELD -> {
                    val value = m.groupValues[1]
                    val tn = TRACKING_VALUE_TN_RE.find(value)?.groupValues?.get(1) ?: continue
                    // 值里的公司名前缀是 mojibake（ISO 读入），先转回再认公司。
                    val courier = Courier.fromCompanyName(fixMojibake(value.substringBeforeLast(':').trim()))
                    emit(tn, pos, courier, orderSn = null)
                }
                Kind.TRACKING_URL -> {
                    val ahead = text.substring(m.range.last + 1, (m.range.last + 500).coerceAtMost(text.length))
                    emit(m.groupValues[1], pos, Courier.UNKNOWN, ORDER_SN_RE.find(ahead)?.groupValues?.get(1)?.trimStart('"'))
                }
                Kind.PROMPT -> {
                    val hint = promptStatusOf(m.groupValues[1])
                    // 提示词挂在**最近的锚点**上：真机形状里它要么贴在本单锚点前
                    // ~100-400 字符（orderList 的按钮 JSON），要么在锚点后 ~2000 字符
                    // （本单的 order_status_prompt）—— 窗口 [−800 前挂 pending, +2500 挂 last]。
                    if (hint != null) {
                        if (lastTn != null && pos >= lastAnchor && pos <= lastAnchor + PROMPT_AFTER) {
                            val cur = acc[lastTn]!!
                            if (hint.order > cur.status.order) acc[lastTn] = cur.copy(status = hint)
                        } else {
                            pendingPrompt = hint
                            pendingPromptPos = pos
                        }
                    }
                }
                Kind.ADDITIONAL -> {
                    if (lastTn != null && pos >= lastAnchor && pos <= lastAnchor + ADDITIONAL_AFTER) {
                        val detail = fixMojibake(m.groupValues[1])
                        val cur = acc[lastTn]!!
                        if (cur.logisticsDetail == null) acc[lastTn] = cur.copy(logisticsDetail = detail)
                    }
                }
            }
        }
        return acc.values.filter { isPlausibleTrackingNumber(it.trackingNumber) }
    }

    // ------------------------------------------------------------ 字段小解析器

    /**
     * 从一段取件提示 JSON（`pick_up_desc` 数组内部）里抽**取件码**。
     *
     * 优先级（用户定的规则，与菜鸟侧同）：连字符数字 ＞ 纯数字；手机号形态不是码。
     * 只在提示文本内部找，不扫整个文件 —— 文件里全是运单号和订单号，
     * 全局找数字必然收错。
     */
    private fun pickupCodeOf(pickJson: String): String? {
        val texts = TEXT_RE.findAll(pickJson).map { fixMojibake(it.groupValues[1]) }.toList()
        for (text in texts) {
            HYPHEN_CODE_RE.find(text)?.groupValues?.get(1)?.let { return it }
        }
        for (text in texts) {
            // 「手机尾号1664」这类提示里的数字不是取件码 —— 收错比缺更糟。
            if (text.contains("手机") || text.contains("尾号")) continue
            DIGIT_CODE_RE.find(text)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /** 从取件提示里抽**手机尾号**（脱敏手机号的末四位）。 */
    private fun phoneTailOf(pickJson: String): String? =
        TEXT_RE.findAll(pickJson)
            .map { fixMojibake(it.groupValues[1]) }
            .mapNotNull { PHONE_RE.find(it)?.groupValues?.get(2) }
            .firstOrNull()

    /** 分栏 id → 状态。`onroad`/`others` 刻意返回 null，理由见类注释「状态映射的刻意留白」。 */
    private fun tabStatusOf(tabId: String): ExpressStatus? = when (tabId) {
        "got" -> ExpressStatus.READY_FOR_PICKUP
        "send" -> ExpressStatus.DELIVERING
        "sign" -> ExpressStatus.SIGNED
        else -> null
    }

    /** 订单状态提示词 → 状态。分不清的一律 null（不猜）。 */
    private fun promptStatusOf(prompt: String): ExpressStatus? = when (fixMojibake(prompt)) {
        "交易成功", "已签收" -> ExpressStatus.SIGNED
        else -> null
    }

    /**
     * 运单号的可信度过滤。缓存里混着测试数据（`libzstd.so` 的 `YT000…`，
     * 2026-09-27 真机踩过）—— 全零的号不可能是真单。
     */
    private fun isPlausibleTrackingNumber(tn: String): Boolean = tn.any { it != '0' }

    /**
     * 把按 ISO_8859_1 读入的中文转回 UTF-8。缓存是字节流，UTF-8 的中文在
     * ISO_8859_1 视角下是两三个「Latin 扩展字符」。
     *
     * ⚠️ 只在**确实出现了 Latin-1 高位字符**（mojibake 的特征）时才转：
     * `toByteArray(ISO_8859_1)` 对超出 ISO_8859_1 的字符（真汉字）一律替换成 `?`，
     * 无守卫的话一段本来就是中文的输入会被当场打成问号（「手机尾号」→「??尾号」，
     * 关键词守卫全部失效 —— 单测里第一个暴露）。真机输入恒为 ISO 读入，
     * 必带高位字符，守卫不挡正常路。
     */
    private fun fixMojibake(s: String): String {
        if (s.none { it.code in 0x80..0xFF }) return s
        return runCatching { s.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8) }
            .getOrDefault(s)
    }

    // ------------------------------------------------------------------ 正则表

    // ⚠️ 以下正则一律用**显式 ASCII 断言**（`(?<![0-9A-Za-z])`），不用 `\b`：
    // Android 的 `\b` 是 Unicode 词边界（汉字算词字符），JVM 的是 ASCII 的 ——
    // 单测全绿、真机全废（项目教训）。

    /** 快递卡片 H5 链接里的运单号（`goods_express.html?tracking_number=…`）。 */
    private val TRACKING_URL_RE =
        Regex("""goods_express\.html\?tracking_number(?:=|\\u003d)([0-9A-Za-z]{8,25})""")

    /** `tracking_num` 字段整值（`中通快递: 770012340000005` / `运单号: …`）。 */
    private val TRACKING_FIELD_RE = Regex(""""tracking_num":"([^"]{1,60})"""")

    /** 字段值末段的运单号（锚定行尾 —— 值里冒号前是快递公司名，不能混进来）。 */
    private val TRACKING_VALUE_TN_RE = Regex("""([0-9A-Za-z]{8,25})\s*${'$'}""")

    /** 订单号（`260922-999000000000001`）。 */
    private val ORDER_SN_RE = Regex("""order_sn(?:=|\\u003d)("?\d{6,10}-?\d{8,32})""")

    /** 取件提示数组（`"pick_up_desc":[ … ]`）。 */
    private val PICK_RE = Regex(""""pick_up_desc":\[(.{0,600}?)\]""")

    /** 提示里的文本元素（`"text":"取件手机号 188****0000"`）。 */
    private val TEXT_RE = Regex(""""text":"([^"]{1,80})"""")

    /** 送达/超时提示（`"additional_desc":[{"type":1,"text":"…"}]`；`_degrade` 变体因冒号不匹配）。 */
    private val ADDITIONAL_RE = Regex(""""additional_desc":\[\{"type":1,"text":"([^"]{1,80})"""")

    /** 分栏 id（已签收/待取件/派件中/运输中/其他）。 */
    private val TAB_RE = Regex(""""tab_id":"(sign|got|send|onroad|others)"""")

    /** 驿站信息块（`pick_up_info:{…}` —— 结构里无嵌套大括号，惰性取到块尾）。 */
    private val PICKUP_INFO_RE = Regex(""""pick_up_info":\{""")

    /** 驿站名 / 地址（只在 [PICKUP_INFO_RE] 命中后的窗口内找，不扫全文）。 */
    private val COMPANY_RE = Regex(""""company_name":"([^"]{1,40})"""")

    private val ADDRESS_RE = Regex(""""address":"([^"]{1,80})"""")

    /**
     * 订单状态提示词（`chat_status_prompt` / `order_status_prompt`）。
     *
     * ⚠️ 真机形状里它经常嵌在**转义的 JSON 参数**里（`\"chat_status_prompt\":\"交易成功\"`，
     * 按钮参数是字符串里的字符串），所以引号与冒号前允许 0-2 个反斜杠。
     */
    private val PROMPT_RE =
        Regex("""\\{0,2}"(?:chat_status|order_status)_prompt\\{0,2}":\\{0,2}"([^"\\]{1,12})""")

    /** 脱敏手机号（`188****0000`）→ 第 2 组是末四位。 */
    private val PHONE_RE = Regex("""(\d{3})\*{2,}(\d{4})""")

    /**
     * 连字符数字码（`1-1-2001`、`3-2-4013`）。
     *
     * ⚠️ 前后断言**带连字符**：`260922-999000000000001` 这种订单号的段
     * 不能被当成码收进来 —— 码的前后都不能贴着字母数字或连字符。
     */
    private val HYPHEN_CODE_RE =
        Regex("""(?<![0-9A-Za-z-])(\d{1,3}-\d{1,3}-\d{3,6})(?![0-9A-Za-z-])""")

    /**
     * 纯数字码（`123456`，4-8 位）。
     *
     * ⚠️ 前断言**带 `*`**：脱敏手机号 `188****0000` 的末四位前面贴着星号，
     * 不排除的话它会被当成取件码（2026-09-27 真机数据形状）。
     */
    private val DIGIT_CODE_RE = Regex("""(?<![0-9A-Za-z*])(\d{4,8})(?![0-9A-Za-z])""")

    // ---------------------------------------------------------------- 窗口常量

    /** 取件提示 → 运单锚点的最大距离（真机形状 ~100 字符，放宽留余量）。 */
    private const val PICK_WINDOW = 1500

    /** `pick_up_info` 块内找 company/address 的窗口。 */
    private const val PICKUP_INFO_WINDOW = 800

    /** 状态提示词相对最近锚点的前向窗口（真机形状 ~100–400 字符）。 */
    private const val PROMPT_BEFORE = 800

    /** 状态提示词相对最近锚点的后向窗口（`order_status_prompt` 在锚点后 ~2000 字符）。 */
    private const val PROMPT_AFTER = 2500

    /** `additional_desc` 相对锚点的后向窗口（真机形状 ~200 字符）。 */
    private const val ADDITIONAL_AFTER = 1500
}
