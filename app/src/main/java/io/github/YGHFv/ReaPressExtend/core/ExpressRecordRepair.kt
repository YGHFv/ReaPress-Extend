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
 * 用当前解析逻辑重扫记录里存下的原文，把旧解析器写错的字段改回来。解析只在通知到达那一刻
 * 跑一次，之后修好解析器，已落盘的脏值一个字不变，还会因 mergeEnrichment「只填空」挡住
 * 宿主富化的真值——所以修复挂在读路径（每次 [ExpressRecordStore.load] 重算，幂等，
 * 下一次写入把结果存回去），不需要版本号或迁移状态。
 *
 * 铁律：只改「能证明原来那一步做错了」的字段，绝不整条重解析——那会把宿主富化的字段
 * （带楼栋号的驿站名、商品图、轨迹）一起冲掉，且误改会被用户看到并随写入落盘、不可逆。
 * 逐字段准入：运单号只处理「其实是原文里带国家码的手机号」（19/20 开头 14 位被当日期挡掉
 * 不是错）；驿站名只处理「说不出地点」与「关键词+半句话」；取件码/包裹尾号/手机尾号只填空；
 * 快递公司只在 UNKNOWN 时按原文品牌名猜；状态只推进不认 UNKNOWN（否则富化的「已签收」
 * 会被原文里的「取件码」打回待取件）。
 */
object ExpressRecordRepair {

    private const val PDD_V23_DIAGNOSTIC_RAW = "拼多多取快递缓存"

    private val PDD_V23_DIAGNOSTIC_RAW_RE = Regex("""拼多多取快递缓存（订单 [0-9-]{8,45}）""")

    /** 订单号前 6 位是下单日期（见 [PddCacheDiscovery.orderDateMillis]）。 */
    private val PDD_V23_ORDER_SN_RE = Regex("""订单 (\d{6,10}-\d{8,32})""")

    /** 修复一条记录；没有可修的返回原对象（调用方可以直接用 `===` 判断有没有变）。 */
    fun repair(record: ExpressRecord, now: Long = System.currentTimeMillis()): ExpressRecord {
        val raw = record.rawText
        if (raw.isBlank()) return record
        var result = record

        // 旧版 PDD 扫描器的诊断副标题，不是任何通知的原文；精确匹配整串才清，误伤面为零。
        // 清空前先抢救订单号与订单日期：这批件的物流痕迹已被宿主淘汰，订单时刻是
        // 「这是什么时候的件」唯一证据，也是归档链唯一的凭据。只填空，不覆盖物流侧给的 arrivalAt。
        if (raw == PDD_V23_DIAGNOSTIC_RAW || raw.matches(PDD_V23_DIAGNOSTIC_RAW_RE)) {
            val orderSn = PDD_V23_ORDER_SN_RE.find(raw)?.groupValues?.get(1)
            val orderDate = orderSn?.let { PddCacheDiscovery.orderDateMillis(it, now) }
            return record.copy(
                rawText = "",
                orderSn = record.orderSn ?: orderSn,
                arrivalAt = record.arrivalAt ?: orderDate,
            )
        }

        // 运单号其实是个手机号（判据：原文里那串号带国家码）。
        val suspiciousTracking = result.trackingNumber
            ?.takeIf { it.isNotBlank() && isEchoedWithCountryCode(raw, it) }
        if (suspiciousTracking != null) {
            result = result.copy(trackingNumber = ExpressParser.parseTrackingNumber(raw))
        }

        // 驿站名：空的、或「关键词 + 半句话」的产物。说不出地点就清空，
        // 留着只会污染首页分组并挡住富化的真名。
        val station = result.station
        if (station.isNullOrBlank() || ExpressStationName.isNarrativeArtifact(station)) {
            val fresh = ExpressParser.parseStation(raw)
            if (fresh != station) result = result.copy(station = fresh)
        }

        // 只填空的取件码必须在这里补：旧解析抽不到它，这条记录的强标识就只剩那个
        // 会被上面清掉的假运单号，不补的话整条记录会失去身份被丢弃。
        if (result.pickupCode.isNullOrBlank()) {
            ExpressParser.parsePickupCode(raw)?.let { result = result.copy(pickupCode = it) }
        }
        if (result.parcelTail.isNullOrBlank()) {
            ExpressParser.parseParcelTail(raw)?.let { result = result.copy(parcelTail = it) }
        }
        if (result.phoneTail.isNullOrBlank()) {
            ExpressParser.parsePhoneTail(raw)?.let { result = result.copy(phoneTail = it) }
        }

        // 认不出公司时才按原文猜：品牌名常写在通知标题里，比「快递」更能说清是哪家。
        if (result.courier == Courier.UNKNOWN) {
            val byText = Courier.fromCompanyName(raw)
            if (byText != Courier.UNKNOWN) result = result.copy(courier = byText)
        }

        val fresh = ExpressParser.parseStatus(raw)
        if (fresh.order > result.status.order) result = result.copy(status = fresh)

        return result
    }

    /** 原文里这个「运单号」是不是带国家码写出来的。只认 + 号：不带国家码的 11 位本来就不会被当运单号。 */
    private fun isEchoedWithCountryCode(raw: String, trackingNumber: String): Boolean =
        raw.contains("+$trackingNumber") || raw.contains("+86$trackingNumber")
}
