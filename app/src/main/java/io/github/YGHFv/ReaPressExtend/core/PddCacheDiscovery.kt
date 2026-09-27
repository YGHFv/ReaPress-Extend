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
 * `cache/pdd_cache` 的响应文件。缓存里是订单/快递卡的原始 JSON，每个单的形状
 * （真机窗口核实）：
 *
 * ```json
 * {"pick_up_desc":[{"text":"取件手机号 188****0000",…}],
 *  "tracking_num":"运单号: 770012340000005",
 *  "order_sn":"260922-999000000000001",
 *  "express_link_url":"goods_express.html?tracking_number=…&order_sn=…"}
 * ```
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
 * ## 抽取策略：锚点 + 窗口，不试图完整解析 JSON
 *
 * 缓存文件是**多个响应拼起来的**（HTTP 报文边界、gzip 后的二进制块混在一起），
 * 没有「整个文件是一份 JSON」这回事。能稳定依赖的只有**字段级的局部结构**：
 * 以 `pick_up_desc`（取件提示）为锚，向后 1200 字符窗口内找同单的
 * `tracking_num` / `order_sn` —— 三者的相对顺序与间距已用真机窗口核实。
 * 没有取件提示的单（历史件/非驿站件）从 `express_link_url` 正向取运单号兜底。
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
    )

    /**
     * 解析一批缓存文件。
     *
     * @param files 相对路径 → 文件全文（ISO_8859_1 读入的原始字节串）。
     *   路径不含 [CACHE_PATH_MARK 的一律忽略。
     * @return 按「首次发现顺序」去重合并后的包裹表（同一单号出现在多个缓存版本里是常态，
     *   字段按「只填空」合并 —— 与模块侧落库的合并哲学一致）。
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
                    )
                }
            }
        }
        return merged.values.toList()
    }

    /** 单个缓存文件 → 若干包裹（不去重，交给 [parse] 合并）。 */
    private fun parseFile(text: String): List<Package> {
        val found = mutableListOf<Package>()
        // 主路：以 pick_up_desc 为锚，向后 1200 字符内找同单的 tracking_num / order_sn
        //（结构顺序真机已核：pick_up_desc → tracking_num → order_sn → express_link_url）。
        for (m in PICK_RE.findAll(text)) {
            val tail = text.substring(m.range.last + 1, (m.range.last + 1200).coerceAtMost(text.length))
            val tn = TRACKING_VALUE_RE.find(tail)?.groupValues?.get(1)
                ?: TRACKING_URL_RE.find(tail)?.groupValues?.get(1)
                ?: continue
            found += Package(
                trackingNumber = tn,
                orderSn = ORDER_SN_RE.find(tail)?.groupValues?.get(1)?.trimStart('"'),
                pickupCode = pickupCodeOf(m.groupValues[1]),
                phoneTail = phoneTailOf(m.groupValues[1]),
            )
        }
        // 补路：没有 pick_up_desc 的单（历史/非驿站件）从 express_link_url 正向取，
        // order_sn 在运单号后面 ~500 字符。
        for (m in TRACKING_URL_RE.findAll(text)) {
            val ahead = text.substring(m.range.last + 1, (m.range.last + 500).coerceAtMost(text.length))
            val tn = m.groupValues[1]
            // 主路已经收过这一件的话（带取件提示那条更全），这里只补没有的单。
            if (found.any { it.trackingNumber == tn }) continue
            found += Package(
                trackingNumber = tn,
                orderSn = ORDER_SN_RE.find(ahead)?.groupValues?.get(1)?.trimStart('"'),
            )
        }
        return found.filter { isPlausibleTrackingNumber(it.trackingNumber) }
    }

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

    /**
     * 运单号的可信度过滤。缓存里混着测试数据（`libzstd.so` 的 `YT0000000000000`，
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

    // ⚠️ 以下正则一律用**显式 ASCII 断言**（`(?<![0-9A-Za-z])`），不用 `\b`：
    // Android 的 `\b` 是 Unicode 词边界（汉字算词字符），JVM 的是 ASCII 的 ——
    // 单测全绿、真机全废（项目教训）。

    /** 快递卡片 H5 链接里的运单号（`goods_express.html?tracking_number=…`）。 */
    private val TRACKING_URL_RE =
        Regex("""goods_express\.html\?tracking_number(?:=|\\u003d)([0-9A-Za-z]{8,25})""")

    /** `tracking_num` 字段值（`"运单号: 770012340000005"` —— 值里带中文前缀，取末段号码）。 */
    private val TRACKING_VALUE_RE = Regex(""""tracking_num":"[^"]*?([0-9A-Za-z]{8,25})"""")

    /** 订单号（`260922-999000000000001`）。 */
    private val ORDER_SN_RE = Regex("""order_sn(?:=|\\u003d)("?\d{6,10}-?\d{8,32})""")

    /** 取件提示数组（`"pick_up_desc":[ … ]`）。 */
    private val PICK_RE = Regex(""""pick_up_desc":\[(.{0,600}?)\]""")

    /** 提示里的文本元素（`"text":"取件手机号 188****0000"`）。 */
    private val TEXT_RE = Regex(""""text":"([^"]{1,80})"""")

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
}
