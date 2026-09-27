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
 * 快递公司识别：从运单号前缀反推公司，以及判断一串数字是不是运单号——短信里电话、订单号、
 * 验证码都是长数字，只有带已知前缀或符合长度规则的才当运单号。前缀表只收「前缀唯一不冲突」的；
 * 中通/圆通这类纯数字标 UNKNOWN 走长度规则——猜错比不猜更糟。
 */
enum class Courier(
    val displayName: String,
    /**
     * 界面简称。不能用 displayName.take(2) 截：EMS 的 displayName 是「中国邮政」，截出来是「中国」。
     * 同时被 [byCompanyKeyword] 用作识别关键字，一份定义两处共用。
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
        /** 前缀按长度倒序匹配，避免 "J" 抢在 "JD" 前面把京东判成极兔。 */
        private val byPrefix: List<Pair<String, Courier>> =
            entries
                .filter { it != UNKNOWN }
                .flatMap { courier -> courier.prefixes.map { it to courier } }
                .sortedByDescending { it.first.length }

        /**
         * shortName 之外还要认的写法：EMS 是宿主给的英文简称；「邮储」同属中国邮政
         * （用户上报过「邮储的快递只显示单号」，只认「邮政」时整条记录掉进 UNKNOWN）。
         */
        private val keywordAliases: List<Pair<String, Courier>> = listOf(
            "EMS" to EMS,
            "邮储" to EMS,
        )

        /**
         * 快递公司名 → 枚举，与 [prefixes] 刻意分开：前缀管「从运单号反推」，这里管「宿主已给
         * 公司名」。用短品牌词包含匹配——菜鸟的 tpName 有「邮政快递包裹」这类带后缀写法，
         * 拿 displayName 全等会漏掉一批；按词长倒序，「百世」不会被更短的词抢走。
         */
        private val byCompanyKeyword: List<Pair<String, Courier>> =
            (
                entries.filter { it != UNKNOWN }.map { it.shortName to it } + keywordAliases
                ).sortedByDescending { it.first.length }

        /**
         * 菜鸟 partnerCode → 枚举，只收有证据的（POSTB = 中国邮政快递包裹，9-26 三重证据钉死）。
         * 仍不收 HTKY：它的拉丁形式推不出中文名，认错公司名会让用户在驿站报错名字，留 UNKNOWN 更划算。
         * 宿主同一行的 partnerName 优先于本表，这里只兜「给了代码没给中文名」的场景。
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

        fun fromPartnerCode(code: String?): Courier? {
            val normalized = code?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return null
            return byPartnerCode[normalized]
        }

        /**
         * 按可靠度三级判定（宿主侧直接用）：中文公司名 > 公司代码 > 运单号前缀。
         * 不能写成 fromCompanyName(n) ?: ...——它认不出时返回 UNKNOWN（非空），Elvis 会把
         * 后两级全部跳过，宿主没给中文名的记录一个都认不出（编译器的 Elvis 警告就是它），必须显式比 UNKNOWN。
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

        /** 从公司名反查。认不出不猜，直接 UNKNOWN——猜错公司名会让用户在驿站报错名字。 */
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
