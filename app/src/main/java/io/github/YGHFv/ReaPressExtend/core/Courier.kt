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
enum class Courier(val displayName: String, val prefixes: List<String>) {
    SHUNFENG("顺丰速运", listOf("SF")),
    YUANTONG("圆通速递", listOf("YT")),
    ZHONGTONG("中通快递", listOf("ZT")),
    SHENTONG("申通快递", listOf("STO")),
    YUNDA("韵达速递", listOf("YD")),
    JD("京东物流", listOf("JD", "JDL")),
    EMS("中国邮政", listOf("EA", "EB", "EN", "EQ", "KA", "KB", "SA", "SB")),
    DEBANG("德邦快递", listOf("DPK", "DBL")),
    JITU("极兔速递", listOf("JT")),
    BAISHI("百世快递", listOf("BS", "KJ")),
    FENGHUANG("丰网速运", listOf("FW")),
    UNKNOWN("快递", emptyList()),
    ;

    companion object {
        /** 前缀表按长度倒序匹配，避免 "J" 抢在 "JD" 前面把京东判成极兔。 */
        private val byPrefix: List<Pair<String, Courier>> =
            entries
                .filter { it != UNKNOWN }
                .flatMap { courier -> courier.prefixes.map { it to courier } }
                .sortedByDescending { it.first.length }

        fun fromTrackingNumber(trackingNumber: String?): Courier {
            val normalized = trackingNumber?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return UNKNOWN
            for ((prefix, courier) in byPrefix) {
                if (normalized.startsWith(prefix)) return courier
            }
            return UNKNOWN
        }
    }
}
