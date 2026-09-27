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
 * 拼多多 HTTP 响应缓存（`cache/pdd_cache`）的包裹发现，纯函数可单测。「取快递」页整个是 H5、
 * 不经过宿主对象解析层（真机 2026-09-27 定性），parse 出口抓不到，只能扫缓存。
 *
 * 输入约定：调用方（`hook/PddCacheScanner`）按 ISO_8859_1 读缓存交进来，中文是 mojibake，
 * 字段逐个经 [fixMojibake] 转回；JSON 的 `=` 被编码成 `\u003d`，带 `=` 的正则两种写法都要兼容。
 * 缓存文件是多个 HTTP 响应拼起来的，不存在「整文件是一份 JSON」，只做字段级锚点的事件扫描
 * （tab_id / pick_up_info / pick_up_desc / tracking_num / additional_desc 按位置排序，维护当前分栏、
 * 驿站、待挂提示、最近包裹四份上下文）；历史已签收件靠贴在「查看物流」URL 前约 100~400 字符的
 * `chat_status_prompt`（「交易成功」）认出。
 *
 * 状态映射刻意留白：`onroad` 不映射 [ExpressStatus.IN_TRANSIT] —— 收下会把富化合并里
 * 「CREATED 纠正 IN_TRANSIT」的修正盖回去。取件码只收码形（连字符数字 > 纯数字），
 * 「出示手机号」落 [Package.phoneTail] 不进 pickupCode：它不是码，收错码比没有码糟。
 */
object PddCacheDiscovery {

    /** 缓存文件只认这个目录标记（`libzstd.so` 里混着 `YT000…` 测试数据，别碰）。 */
    const val CACHE_PATH_MARK = "pdd_cache"

    /** 一个从缓存里发现的包裹。字段全可空 —— 抽不到就是 null，不编造。 */
    data class Package(
        val trackingNumber: String,
        val orderSn: String? = null,
        /** 取件码，只收码形（连字符数字优先，纯数字其次）；「出示手机号」落 [phoneTail]。 */
        val pickupCode: String? = null,
        val phoneTail: String? = null,
        val courier: Courier = Courier.UNKNOWN,
        val station: String? = null,
        val stationAddress: String? = null,
        val logisticsDetail: String? = null,
        /** 分栏/提示词推得的状态。运输中刻意不推，见类注释。 */
        val status: ExpressStatus = ExpressStatus.UNKNOWN,
    )

    /** 解析一批缓存文件。路径不含 [CACHE_PATH_MARK] 的一律忽略；同一单号出现在多个缓存版本是常态，按「只填空」合并、状态只推进。 */
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
        // orderList 缓存里状态提示词在锚点前 ~100-400 字符 —— 先挂着待消费，见 PROMPT 事件。
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
                    // 状态只推进。
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
                    // 换栏必须清驿站上下文，不然下一栏的单会挂上上一栏的驿站名。
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
                    val courier = Courier.fromCompanyName(fixMojibake(value.substringBeforeLast(':').trim()))
                    emit(tn, pos, courier, orderSn = null)
                }
                Kind.TRACKING_URL -> {
                    val ahead = text.substring(m.range.last + 1, (m.range.last + 500).coerceAtMost(text.length))
                    emit(m.groupValues[1], pos, Courier.UNKNOWN, ORDER_SN_RE.find(ahead)?.groupValues?.get(1)?.trimStart('"'))
                }
                Kind.PROMPT -> {
                    val hint = promptStatusOf(m.groupValues[1])
                    // 提示词挂最近的锚点：orderList 形状在锚点前 ~100-400 字符（pending），
                    // order_status_prompt 在锚点后 ~2000 字符（挂 lastTn）。
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

    /** 从取件提示抽码：连字符数字 > 纯数字，手机号形态不是码。只在提示文本内部找，全局找必收错。 */
    private fun pickupCodeOf(pickJson: String): String? {
        val texts = TEXT_RE.findAll(pickJson).map { fixMojibake(it.groupValues[1]) }.toList()
        for (text in texts) {
            HYPHEN_CODE_RE.find(text)?.groupValues?.get(1)?.let { return it }
        }
        for (text in texts) {
            if (text.contains("手机") || text.contains("尾号")) continue
            DIGIT_CODE_RE.find(text)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun phoneTailOf(pickJson: String): String? =
        TEXT_RE.findAll(pickJson)
            .map { fixMojibake(it.groupValues[1]) }
            .mapNotNull { PHONE_RE.find(it)?.groupValues?.get(2) }
            .firstOrNull()

    /** `onroad`/`others` 刻意返回 null，见类注释。 */
    private fun tabStatusOf(tabId: String): ExpressStatus? = when (tabId) {
        "got" -> ExpressStatus.READY_FOR_PICKUP
        "send" -> ExpressStatus.DELIVERING
        "sign" -> ExpressStatus.SIGNED
        else -> null
    }

    private fun promptStatusOf(prompt: String): ExpressStatus? = when (fixMojibake(prompt)) {
        "交易成功", "已签收" -> ExpressStatus.SIGNED
        else -> null
    }

    /** 缓存混着测试数据（libzstd.so 的 YT000…，2026-09-27 真机踩过），全零号不可能是真单。 */
    private fun isPlausibleTrackingNumber(tn: String): Boolean = tn.any { it != '0' }

    /** 把 ISO_8859_1 读入的中文转回 UTF-8。只在实际出现 Latin-1 高位字符（mojibake 特征）时才转：无守卫的话本来就是中文的输入会被当场打成问号，「手机尾号」关键词守卫全部失效。 */
    private fun fixMojibake(s: String): String {
        if (s.none { it.code in 0x80..0xFF }) return s
        return runCatching { s.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8) }
            .getOrDefault(s)
    }

    // ------------------------------------------------------------------ 正则表

    // 以下正则一律用显式 ASCII 断言（`(?<![0-9A-Za-z])`）而不用 `\b`：
    // Android 的 `\b` 是 Unicode 词边界（汉字算词字符），JVM 是 ASCII 的 —— 单测全绿、真机全废（项目教训）。

    private val TRACKING_URL_RE =
        Regex("""goods_express\.html\?tracking_number(?:=|\\u003d)([0-9A-Za-z]{8,25})""")

    private val TRACKING_FIELD_RE = Regex(""""tracking_num":"([^"]{1,60})"""")

    /** 字段值末段的运单号，锚定行尾 —— 值里冒号前是快递公司名，不能混进来。 */
    private val TRACKING_VALUE_TN_RE = Regex("""([0-9A-Za-z]{8,25})\s*${'$'}""")

    private val ORDER_SN_RE = Regex("""order_sn(?:=|\\u003d)("?\d{6,10}-?\d{8,32})""")

    private val PICK_RE = Regex(""""pick_up_desc":\[(.{0,600}?)\]""")

    private val TEXT_RE = Regex(""""text":"([^"]{1,80})"""")

    /** `_degrade` 变体因冒号不匹配。 */
    private val ADDITIONAL_RE = Regex(""""additional_desc":\[\{"type":1,"text":"([^"]{1,80})"""")

    private val TAB_RE = Regex(""""tab_id":"(sign|got|send|onroad|others)"""")

    private val PICKUP_INFO_RE = Regex(""""pick_up_info":\{""")

    private val COMPANY_RE = Regex(""""company_name":"([^"]{1,40})"""")

    private val ADDRESS_RE = Regex(""""address":"([^"]{1,80})"""")

    /** 订单状态提示词。真机形状常嵌在转义的 JSON 参数里（按钮参数是字符串里的字符串），引号与冒号前允许 0-2 个反斜杠。 */
    private val PROMPT_RE =
        Regex("""\\{0,2}"(?:chat_status|order_status)_prompt\\{0,2}":\\{0,2}"([^"\\]{1,12})""")

    private val PHONE_RE = Regex("""(\d{3})\*{2,}(\d{4})""")

    /** 连字符数字码。前后断言带连字符：订单号 `260922-999…` 的段不能被当成码。 */
    private val HYPHEN_CODE_RE =
        Regex("""(?<![0-9A-Za-z-])(\d{1,3}-\d{1,3}-\d{3,6})(?![0-9A-Za-z-])""")

    /** 纯数字码。前断言带 `*`：脱敏手机号末四位前面贴着星号，不排除会被当成取件码（2026-09-27 真机形状）。 */
    private val DIGIT_CODE_RE = Regex("""(?<![0-9A-Za-z*])(\d{4,8})(?![0-9A-Za-z])""")

    // ---------------------------------------------------------------- 窗口常量

    /** 订单号前段：前 6 位是下单日期（YYMMDD，真机样本核实）。 */
    private val ORDER_DATE_RE = Regex("""^(\d{2})(\d{2})(\d{2})-\d{8,}$""")

    /**
     * 订单号里的下单日期 → epoch 毫秒（当天零点，系统时区）。缓存淘汰后这是老件唯一
     * 可证明的时间证据：订单号的日期段不会撒谎。
     * 形状不对 / 日期非法 / 落在未来 → null，判不了就不判；core 不碰系统时钟，[nowMillis] 必须注入。
     */
    fun orderDateMillis(orderSn: String, nowMillis: Long): Long? {
        val m = ORDER_DATE_RE.find(orderSn) ?: return null
        val (yy, mm, dd) = m.destructured
        return runCatching {
            val date = java.time.LocalDate.of(2000 + yy.toInt(), mm.toInt(), dd.toInt())
            val millis = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            millis.takeIf { it <= nowMillis }
        }.getOrNull()
    }

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
