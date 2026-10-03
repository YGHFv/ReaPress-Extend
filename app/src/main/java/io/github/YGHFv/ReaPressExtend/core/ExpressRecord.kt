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

/** 一条识别出的快递。字段可空：解析器不编造，拿不到就是 null，由展示层降级。 */
data class ExpressRecord(
    val sourcePackage: String,
    val rawText: String,
    val trackingNumber: String? = null,
    /** 拼多多订单号。只有发现腿给得出，[PddCacheDiscovery.orderDateMillis] 靠它推下单时刻 —— 缓存淘汰后老件唯一的时间证据。 */
    val orderSn: String? = null,
    val courier: Courier = Courier.UNKNOWN,
    val pickupCode: String? = null,
    val station: String? = null,
    /** 电商平台（如「淘宝」「天猫」），不是快递公司（那是 [courier]）。宿主字段 `pkgSourceDesc`。 */
    val platform: String? = null,
    val goodsName: String? = null,
    /** 到站/入站时间（毫秒，宿主最后一次状态变更）。只对到站类状态有「在站几天」语义。 */
    val arrivalAt: Long? = null,
    /** 通知里明写的收件手机尾号（4 位）。宿主 `encryReceiverTel` 是加密串读不出，不存在富化版本。 */
    val phoneTail: String? = null,
    /** 「取尾号1234」的尾号（3-6 位），通知侧唯一能认领包裹的东西。与 [phoneTail] 是两个字段，不要合并。 */
    val parcelTail: String? = null,
    /** 被尾号匹配换掉的原取件码，仅在真的换掉非空旧码时写入，供详情页对照。 */
    val previousPickupCode: String? = null,
    /** 运单动态（最新一条物流详情）。与 [trace] 是两个字段，原样收下不加工。 */
    val logisticsDetail: String? = null,
    /** 驿站营业时间，原样保留宿主串（key `packageStation.officeTime`），规范化在 [ExpressFormatter.stationHoursLabel]；空=不知道而非不营业。 */
    val stationHours: String? = null,
    /** 驿站完整地址。与 [station] 不要合并：塞进去会让归一化出另一个名字，同一驿站被劈成两组。 */
    val stationAddress: String? = null,
    /** 驿站坐标（WGS84 度，宿主 `packageStation.stationLat/stationLng`）。很多行是空，整条链路要把「无坐标」当正常；只填空不覆盖。 */
    val stationLat: Double? = null,
    val stationLng: Double? = null,
    /** 全轨迹（最早 → 最新）。唯一会变更的外部字段，合并规则见 [mergeEnrichment]。 */
    val trace: List<ExpressTracePoint> = emptyList(),
    val goodsImage: String? = null,
    /** 用户手动确认已取件的时间。与 [status] 互不覆盖；只在 UI 双击时写入，不进 relay 契约。 */
    val pickedUpAt: Long? = null,
    val status: ExpressStatus = ExpressStatus.UNKNOWN,
    val title: String? = null,
    val origin: ExpressOrigin = ExpressOrigin.NOTIFICATION,
    val matchedKeywords: List<String> = emptyList(),
    val confidence: Int = 0,
    val timestamp: Long = 0L,
    /** Code freshness established by a confirmed snapshot (or a later replacing notification); zero for legacy input. */
    val pickupCodeObservedAt: Long = 0L,
) {
    val isPickedUp: Boolean get() = pickedUpAt != null

    /** 有强标识（运单号/取件码至少其一）。存储层把它当准入条件：没有强标识的是噪音通知。 */
    val hasIdentity: Boolean
        get() = !trackingNumber.isNullOrBlank() || !pickupCode.isNullOrBlank()

    /** 主键：运单号 → 取件码 → 原文哈希。不含驿站名，判断同一包裹请用 [isSamePackageAs]。 */
    val dedupeKey: String
        get() = when {
            !trackingNumber.isNullOrBlank() -> "tn:$trackingNumber"
            !pickupCode.isNullOrBlank() -> "pc:$pickupCode"
            else -> "raw:${rawText.hashCode()}"
        }

    /** 同包裹判定：都有运单号按单号；都有取件码按取件码；刻意不比驿站名（详略不一致会拆成两条）；否则比原文。 */
    fun isSamePackageAs(other: ExpressRecord): Boolean {
        val thisTracking = trackingNumber?.takeIf { it.isNotBlank() }
        val otherTracking = other.trackingNumber?.takeIf { it.isNotBlank() }
        if (thisTracking != null && otherTracking != null) {
            return thisTracking == otherTracking
        }

        val thisPickup = pickupCode?.takeIf { it.isNotBlank() }
        val otherPickup = other.pickupCode?.takeIf { it.isNotBlank() }
        if (thisPickup != null && otherPickup != null) {
            return thisPickup == otherPickup
        }

        if (thisTracking != null || otherTracking != null ||
            thisPickup != null || otherPickup != null
        ) {
            return thisTracking == otherTracking && thisPickup == otherPickup
        }

        return rawText == other.rawText
    }

    /** 富化以补空为主；确认为较新的菜鸟联网快照可更换同一完整运单号的取件码并保留旧码。 */
    fun mergeEnrichment(other: ExpressRecord): ExpressRecord {
        val otherTracking = other.trackingNumber?.takeIf { it.isNotBlank() }
        val mergedTracking = when {
            otherTracking == null -> trackingNumber
            trackingNumber.isNullOrBlank() -> otherTracking
            trackingNumber == otherTracking -> trackingNumber
            otherTracking.endsWith(trackingNumber) -> otherTracking
            else -> trackingNumber
        }
        val mergedStatus = when {
            other.status.isAdvanceFrom(status) -> other.status
            // CREATED 纠正 IN_TRANSIT：只来自明确文案，是更具体证据；不带时间条件（两趟合并方向相反，带了会在第二趟反转回去）。
            status == ExpressStatus.IN_TRANSIT && other.status == ExpressStatus.CREATED ->
                ExpressStatus.CREATED
            else -> status
        }

        val replaceCode = other.sourcePackage == "com.cainiao.wireless" && other.origin == ExpressOrigin.ENRICHMENT &&
            !trackingNumber.isNullOrBlank() && trackingNumber == other.trackingNumber &&
            !other.pickupCode.isNullOrBlank() && other.pickupCodeObservedAt > pickupCodeObservedAt &&
            other.pickupCodeObservedAt > 0L &&
            (origin != ExpressOrigin.NOTIFICATION || timestamp <= other.pickupCodeObservedAt)
        val merged = copy(
            trackingNumber = mergedTracking,
            orderSn = orderSn ?: other.orderSn,
            courier = if (courier == Courier.UNKNOWN) other.courier else courier,
            // 驿站名没有地点信息的写法要让位，否则宿主真名被「已送达代收点」挡住。
            station = station?.takeIf { ExpressStationName.hasLocation(it) } ?: other.station,
            pickupCode = if (replaceCode) other.pickupCode else pickupCode ?: other.pickupCode,
            pickupCodeObservedAt = if (replaceCode) other.pickupCodeObservedAt else pickupCodeObservedAt,
            platform = platform ?: other.platform,
            goodsName = goodsName ?: other.goodsName,
            arrivalAt = arrivalAt ?: other.arrivalAt,
            // 时序字段：两边都有值按时间戳新者；本条为空必须无条件收下（通知记录天生没动态）。
            logisticsDetail = when {
                logisticsDetail == null -> other.logisticsDetail
                other.timestamp >= timestamp -> other.logisticsDetail ?: logisticsDetail
                else -> logisticsDetail
            },
            stationHours = stationHours ?: other.stationHours,
            stationAddress = stationAddress ?: other.stationAddress,
            stationLat = stationLat ?: other.stationLat,
            stationLng = stationLng ?: other.stationLng,
            goodsImage = goodsImage ?: other.goodsImage,
            // 唯一会更替的字段，但仍只增不减：接口偶发返回残缺列表时不能冲掉已攒下的轨迹。
            trace = if (other.trace.size >= trace.size) other.trace else trace,
            phoneTail = phoneTail ?: other.phoneTail,
            parcelTail = parcelTail ?: other.parcelTail,
            previousPickupCode = if (replaceCode && pickupCode != other.pickupCode && !pickupCode.isNullOrBlank()) pickupCode
                else previousPickupCode ?: other.previousPickupCode,
            pickedUpAt = pickedUpAt ?: other.pickedUpAt,
            title = title ?: other.title,
            // status 必须在这份 copy 里：底下的 merged == this 是全字段相等判定，放外面会吞掉状态纠正。
            status = mergedStatus,
        )
        return if (merged == this) this else merged
    }
}

enum class ExpressOrigin(val displayName: String) {
    NOTIFICATION("通知"),

    ENRICHMENT("宿主富化"),
}

/** 物流状态，顺序即推进程度；推送乱序时不能把状态往回退（[isAdvanceFrom]）。 */
enum class ExpressStatus(val displayName: String, val order: Int) {
    UNKNOWN("未知", 0),
    CREATED("已下单", 1),
    PICKED_UP("已揽收", 2),
    IN_TRANSIT("运输中", 3),
    ARRIVED_STATION("已到站", 4),
    DELIVERING("派送中", 5),
    READY_FOR_PICKUP("待取件", 6),
    SIGNED("已签收", 7),
    FAILED("投递失败", 8),
    ;

    fun isAdvanceFrom(previous: ExpressStatus): Boolean = order >= previous.order
}
