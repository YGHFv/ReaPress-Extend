package io.github.YGHFv.ReaPressExtend.core

/**
 * 快递公司识别。
 *
 * 用途有两个：
 * 1. 从运单号前缀反推快递公司，补全通知里没写的信息（「您的包裹」→「顺丰」）
 * 2. 判断一串数字**是不是**运单号 —— 快递短信里电话号码、订单号、验证码都是长数字，
 *    只有带已知前缀或符合已知长度规则的才当运单号，否则会把手机号误当运单号
 *
 * 前缀表只收「前缀唯一且不会与其它公司冲突」的。像中通/圆通这类纯数字、靠长度和
 * 号段区分的，标 [Courier.UNKNOWN] 走长度规则，不做前缀猜测 —— 猜错比不猜更糟。
 */
enum class Courier(
    val displayName: String,
    /**
     * 界面上显示的简称（`中通` / `顺丰` / `邮政`）。
     *
     * **不能用 `displayName.take(2)` 截**：`EMS` 的 displayName 是「中国邮政」，
     * 截出来是「中国」—— 那不是快递公司名。简称在这里逐条写死。
     *
     * 这个值同时被 [byCompanyKeyword] 用作识别关键字，两处共用一份定义：
     * 它们本来就是同一批短词，分开维护迟早会漂。
     */
    val shortName: String,
    val prefixes: List<String>,
) {
    SHUNFENG("顺丰速运", "顺丰", listOf("SF")),
    YUANTONG("圆通速递", "圆通", listOf("YT")),
    ZHONGTONG("中通快递", "中通", listOf("ZT")),
    SHENTONG("申通快递", "申通", listOf("STO")),
    YUNDA("韵达速递", "韵达", listOf("YD")),
    JD("京东物流", "京东", listOf("JD", "JDL")),
    EMS("中国邮政", "邮政", listOf("EA", "EB", "EN", "EQ", "KA", "KB", "SA", "SB")),
    DEBANG("德邦快递", "德邦", listOf("DPK", "DBL")),
    JITU("极兔速递", "极兔", listOf("JT")),
    BAISHI("百世快递", "百世", listOf("BS", "KJ")),
    FENGHUANG("丰网速运", "丰网", listOf("FW")),
    UNKNOWN("快递", "快递", emptyList()),
    ;

    companion object {
        /** 前缀表按长度倒序匹配，避免 "J" 抢在 "JD" 前面把京东判成极兔。 */
        private val byPrefix: List<Pair<String, Courier>> =
            entries
                .filter { it != UNKNOWN }
                .flatMap { courier -> courier.prefixes.map { it to courier } }
                .sortedByDescending { it.first.length }

        /**
         * [Courier.shortName] 之外还要认的写法。
         *
         * 宿主的中文公司名里会出现 shortName 覆盖不到的写法，实测有两类：
         *
         * - `EMS`：宿主给的英文简称。
         * - `邮储`：2026-09-26 用户上报「邮储的快递只显示单号」。中国邮政集团下除了「邮政」
         *   还有邮储银行这条线（信用卡、纪念币这类寄件），宿主给的名字里是「邮储」而不是
         *   「邮政」—— 只有 shortName 的「邮政」时匹配不到，于是整条记录掉进 `UNKNOWN`。
         *   它同属中国邮政，简称用「邮政」没有歧义。
         */
        private val keywordAliases: List<Pair<String, Courier>> = listOf(
            "EMS" to EMS,
            "邮储" to EMS,
        )

        /**
         * 快递公司名 → 枚举。
         *
         * **刻意与 [prefixes] 分开**：前缀是「从运单号反推公司」，这里是「宿主已经给了公司名，
         * 只要认出来」。两边可用的线索完全不同 —— 宿主给的是中文品牌名（`中通快递`），
         * 而运单号只能给字母前缀（`ZT`）。
         *
         * 用**短品牌词**而不是 [displayName] 做包含匹配：菜鸟返回的 `tpName` 有
         * `邮政快递包裹`、`圆通速递`、`顺丰速运` 这类带后缀的写法，拿 displayName 全等会漏掉一批。
         * 按词长倒序匹配，「百世」不会被更短的词抢走。
         *
         * 关键字直接取自 [Courier.shortName] —— 界面简称和识别关键字是同一批词，
         * 只有一处定义可改。
         */
        private val byCompanyKeyword: List<Pair<String, Courier>> =
            (
                entries.filter { it != UNKNOWN }.map { it.shortName to it } + keywordAliases
                ).sortedByDescending { it.first.length }

        /**
         * 菜鸟 `partnerCode` → 枚举。
         *
         * 来源：菜鸟本地库 `package_list_v4_package_info.partnerCode`。**只收有证据的**：
         *
         * - 代码本身就是公司拉丁品牌名的（`ZTO` = ZTO Express = 中通快递，`YTO` = YTO Express =
         *   圆通速递，`STO` = STO Express = 申通快递……），这类对应关系不依赖任何猜测。
         * - `POSTB` = 中国邮政（快递包裹）。这条**不是从名字推的**，是 2026-09-26 的三重证据：
         *   (1) `log/run6` 里两行 `partnerCode=POSTB`，运单号 `9823xxxxxx04` / `9823xxxxxx07`
         *   （中段已掩去，仓库是公开的），都是 9 开头 13 位 —— 中国邮政快递包裹的号段；
         *   (2) 这两件在 `log/run10` 的 emit
         *   日志里都是 `cp=快递`（判成 `UNKNOWN`）；(3) 用户同一天上报「邮储的快递只显示单号」。
         *   三条指向同一批件。
         *
         * **仍不收 `HTKY`**：它的拉丁形式推不出中文名（常见说法是「汇通」，但没有任何一份
         * 真机数据能把 `HTKY` 和某个品牌绑上），认错公司名会让用户在驿站报错名字，
         * 留 `UNKNOWN` 退回运单号更划算。
         *
         * 另外还有一条更稳的路：宿主在**同一行**里给了 `partnerName`（中文名，见
         * `CainiaoPackageHook.FIELD_PARTNER_NAME`），它优先于本表。所以这里只要覆盖
         * 「宿主给了代码却没给中文名」的兜底场景。
         */
        private val byPartnerCode: Map<String, Courier> = mapOf(
            "SF" to SHUNFENG,
            "YTO" to YUANTONG,
            "ZTO" to ZHONGTONG,
            "STO" to SHENTONG,
            "YD" to YUNDA,
            "JD" to JD,
            "JTL" to JITU,
            "DBL" to DEBANG,
            "FW" to FENGHUANG,
            "POSTB" to EMS,
        )

        /** 宿主直接给了快递公司代码。认不出返回 null，由调用方决定要不要退回运单号前缀。 */
        fun fromPartnerCode(code: String?): Courier? {
            val normalized = code?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return null
            return byPartnerCode[normalized]
        }

        /**
         * 宿主一行数据上能拿到的三样线索，按可靠程度依次判定 —— 宿主侧（hook）直接用这个。
         *
         * 1. **中文公司名**（菜鸟的 `partnerName`）—— 最可靠：宿主已经映射过一次，
         *    模块不需要维护任何码表；`邮政快递包裹` 这种带后缀的写法由 [fromCompanyName]
         *    的包含匹配吃掉。
         * 2. **公司代码**（`partnerCode`）—— 有覆盖缺口（`POSTB` 这种要靠服务端码表）。
         * 3. **运单号前缀** —— 最后兜底，只对字母前缀的公司有效；纯数字号段（中通 / 邮政包裹）
         *    这里必然认不出，只能算 [UNKNOWN]，由 UI 退回显示运单号。
         *
         * ⚠️ **不能写成 `fromCompanyName(n) ?: fromPartnerCode(c) ?: ...`**：[fromCompanyName]
         * 认不出时返回的是 [UNKNOWN]（**非空**，因为它的调用方需要一个确定答案），
         * `?:` 会认为它有值、把后面两级全部跳过 —— 结果是「宿主没给中文名」的记录一个都认不出来。
         * 2026-09-26 这里踩过一次，编译器的 `Elvis operator always returns the left operand`
         * 警告就是它。所以必须显式跟 [UNKNOWN] 比。
         *
         * 三级都不猜：认不出就往下走，全认不出才是 [UNKNOWN]。
         */
        fun resolve(partnerName: String?, partnerCode: String?, trackingNumber: String?): Courier {
            val byName = fromCompanyName(partnerName)
            if (byName != UNKNOWN) return byName
            return fromPartnerCode(partnerCode) ?: fromTrackingNumber(trackingNumber)
        }

        fun fromTrackingNumber(trackingNumber: String?): Courier {
            val normalized = trackingNumber?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return UNKNOWN
            for ((prefix, courier) in byPrefix) {
                if (normalized.startsWith(prefix)) return courier
            }
            return UNKNOWN
        }

        /**
         * 从快递公司名反查。
         *
         * 认不出不猜 —— 直接 [UNKNOWN]，由调用方决定要不要退回 [fromTrackingNumber]。
         * 猜错公司名会让用户在驿站报错名字，比留空更糟。
         */
        fun fromCompanyName(name: String?): Courier {
            val normalized = name?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return UNKNOWN
            for ((keyword, courier) in byCompanyKeyword) {
                if (normalized.contains(keyword)) return courier
            }
            return UNKNOWN
        }
    }
}
