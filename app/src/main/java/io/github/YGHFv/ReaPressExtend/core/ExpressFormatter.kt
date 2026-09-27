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

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 把 [ExpressRecord] 渲染成替换通知的标题与正文。纯函数，模块 App 进程与诊断界面共用。
 * 按固定字段重排而非照抄原文：用户要的是「哪个包裹、到哪了、取件码多少」。
 */
object ExpressFormatter {

    /** 通知标题，固定带前缀让用户认出是模块重发的；公司名用简称省空间，UNKNOWN.shortName 就是「快递」。 */
    fun title(record: ExpressRecord): String {
        val courier = record.courier.shortName
        return when (record.status) {
            ExpressStatus.READY_FOR_PICKUP -> "$courier · 待取件"
            ExpressStatus.ARRIVED_STATION -> "$courier · 已到站"
            ExpressStatus.DELIVERING -> "$courier · 派送中"
            ExpressStatus.SIGNED -> "$courier · 已签收"
            ExpressStatus.FAILED -> "$courier · 投递失败"
            ExpressStatus.IN_TRANSIT -> "$courier · 运输中"
            ExpressStatus.PICKED_UP -> "$courier · 已揽收"
            ExpressStatus.CREATED -> "$courier · 已下单"
            ExpressStatus.UNKNOWN -> courier
        }
    }

    /**
     * 通知收起态那句。分岔只有一个：要不要用户动手 —— 有取件码/驿站说「去哪取」，都没有说「现在在哪 + 哪一件」。
     * 只有「取件码」保留标签（光一串 `8-2-3021` 用户不知道是什么），地点/运单号/状态的标签全去掉。
     * 一个字段都没有时返回 null，由 [body] 退回原文首行。
     */
    fun summaryLine(record: ExpressRecord): String? {
        val pickup = record.pickupCode?.takeIf { it.isNotBlank() }
        // placeName 会丢掉说不出「哪一处」的串（如历史记录里的「代收点存放已超过24小时…」），写法本身不改。
        val station = ExpressStationName.placeName(record.station)
        if (pickup != null || station != null) {
            return listOfNotNull(pickup?.let { "取件码 $it" }, station).joinToString(" · ")
        }
        val detail = record.logisticsDetail?.takeIf { it.isNotBlank() }
        return listOfNotNull(detail, trackingLine(record))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
    }

    /** 「公司简称 运单号」必须连在一起才算完整；公司认不出只留运单号。 */
    fun trackingLine(record: ExpressRecord): String? =
        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { tracking ->
            if (record.courier == Courier.UNKNOWN) tracking else "${record.courier.shortName} $tracking"
        }

    /** 通知展开态正文，行序即重要性：摘要 → 哪一件 → 现在在哪 → 买的是什么；逐行去重。字段全空退回原文。 */
    fun body(record: ExpressRecord): String {
        val pickup = record.pickupCode?.takeIf { it.isNotBlank() }
        val station = ExpressStationName.placeName(record.station)
        val lines = mutableListOf<String>()
        summaryLine(record)?.let { lines += it }
        // 摘要行已吃下运单动态时（路上那些件）不再补运单号/动态，避免把同一句话拆开重说。
        if (pickup != null || station != null) {
            trackingLine(record)?.let { lines += it }
            record.logisticsDetail?.takeIf { it.isNotBlank() }?.let { lines += it }
        }
        // 商品行与首页卡片同一种写法，两界面来回看不会对不上；不带「商品：」前缀（卡片上也没有）。
        goodsSummary(record)?.takeIf { it !in lines }?.let { lines += it }
        if (lines.isEmpty()) {
            return record.rawText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return lines.joinToString("\n")
    }

    /**
     * 在站时长「N 天」，按自然日差算不是 24 小时整除（菜鸟口径，2026-09-26 实测：到站 24 小时后
     * 会从「今天」跳成「1天」，与用户数日历格子的直觉对不上）。不带「已入站」字样（抬头已说状态）；
     * 不足一天说「今天」。**只对到站件用**：arrivalAt 是最后一次状态变更时间，对运输中件算出来是错的。
     * `now` 由调用方注入（core 不碰系统时钟）；[zone] 属于运行环境，用户看的是自己手机上的日历。
     */
    fun inStationLabel(
        arrivalAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(arrivalAt).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        )
        // days <= 0 只可能来自时钟回拨或宿主给错时间，按「刚发生」处理。
        return if (days <= 0L) "今天" else "${days}天"
    }

    /**
     * 驿站营业时间压成「9:00-21:00」（宿主原样「周一至周日09点00分到21点00分」会把抬头挤到折行）。
     * 全周前缀丢掉（等于没限制）；非全周前缀保留（「周一至周五」丢掉用户周末白跑）；钟点统一 H:mm；
     * 抓到 4 个钟点按上午/下午两段输出。解析不出成对钟点就原样返回，宁难看也不编出不存在的营业时间。
     */
    fun stationHoursLabel(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val times = STATION_TIME.findAll(text).take(4).toList()
        // 奇数个钟点说不清怎么配对（要么宿主给了半句话，要么我们的正则太宽），退回原文。
        if (times.size < 2 || times.size % 2 != 0) return text
        val ranges = times.chunked(2).joinToString("，") { "${clockOf(it[0])}-${clockOf(it[1])}" }
        val prefix = text.substring(0, times.first().range.first).trimEnd(*PREFIX_TRIM)
        return if (prefix.isEmpty() || FULL_WEEK.containsMatchIn(prefix)) ranges else "$prefix · $ranges"
    }

    private val STATION_TIME = Regex("""(\d{1,2})\s*[:：点时]\s*(\d{1,2})?\s*分?""")

    private val PREFIX_TRIM = charArrayOf(' ', '，', ',', '、', '：', ':', '至', '-', '~', '到')

    private val FULL_WEEK = Regex("""(每[天日]|全周|一周[七7]天|每?周[一1]\s*[至\-~到]\s*周[日天七])""")

    private fun clockOf(match: MatchResult): String {
        val hour = match.groupValues[1].toIntOrNull() ?: return match.value
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        return "$hour:${minute.toString().padStart(2, '0')}"
    }

    /** 「x小时前」，起算点必须用 [statusSince] 而非 timestamp（gmt_modified 会停在旧值）；不足 1 分钟返回 null（整段不显示）。 */
    fun relativeAge(timestamp: Long, now: Long): String? {
        val elapsed = now - timestamp
        if (elapsed <= 0L) return null
        val minutes = elapsed / 60_000L
        return when {
            minutes < 1L -> null
            minutes < 60L -> "${minutes}分钟前"
            minutes < 24 * 60L -> "${minutes / 60L}小时前"
            else -> "${ChronoUnit.DAYS.between(Instant.ofEpochMilli(timestamp), Instant.ofEpochMilli(now))}天前"
        }
    }

    /** 轨迹点时间两种写法：秒级是接口原样，分钟级是个别快递公司的缩水版。 */
    private val TRACE_TIME_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val TRACE_TIME_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 轨迹时间串 → epoch 毫秒（排序可比字符串，但与 timestamp 比新旧、算「几小时前」必须真解析）。解析不出 null，不能编。 */
    fun tracePointTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(raw.trim(), TRACE_TIME_FULL).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Throwable) {
        try {
            LocalDateTime.parse(raw.trim(), TRACE_TIME_MINUTE).atZone(zone).toInstant().toEpochMilli()
        } catch (e: Throwable) {
            null
        }
    }

    /** 「x小时前」的起算点：轨迹最新节点与宿主 `logistics_gmt_modified` 取较新者。gmt_modified 会停在旧值（真机 2026-09-26：13:54 已派送的件显示「11小时前」）。 */
    fun statusSince(record: ExpressRecord, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val fromTrace = record.trace.lastOrNull()?.let { tracePointTime(it.time, zone) }
        val fromRecord = record.timestamp.takeIf { it > 0L }
        return listOfNotNull(fromTrace, fromRecord).maxOrNull()
    }

    /** 「平台 · 商品名」，首页卡片与通知正文共用。平台再过一次 [ExpressPlatform.normalize]：排版层的兜底，不出现把收件类型词（如「普通收件」）当平台的情况。 */
    fun goodsSummary(record: ExpressRecord): String? {
        val platform = ExpressPlatform.normalize(record.platform)
        val goods = record.goodsName?.takeIf { it.isNotBlank() }
        return when {
            platform != null && goods != null -> "$platform · $goods"
            goods != null -> goods
            platform != null -> platform
            else -> null
        }
    }

    fun goodsLine(record: ExpressRecord): String? =
        goodsSummary(record)?.let { "商品：$it" }

    /** 「手机尾号XXXX · 运单动态」。运单号尾号刻意不出现（全号已跟在标题后）；空间不够被截的是动态，动态下一小时就变而尾号不会。 */
    fun detailLine(record: ExpressRecord): String? {
        val parts = mutableListOf<String>()
        record.phoneTail?.takeIf { it.isNotBlank() }?.let { parts += "手机尾号$it" }
        record.logisticsDetail?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** 卡片右上角状态文案：用户标记优先于宿主状态（宿主可能隔天才扫签收），SIGNED 除外（外部更权威且结论一致）；只做文案不碰 [ExpressRecord.status]。 */
    fun statusLabel(record: ExpressRecord): String = when {
        !record.isPickedUp -> record.status.displayName
        record.status == ExpressStatus.SIGNED -> record.status.displayName
        else -> PICKED_UP_LABEL
    }

    const val PICKED_UP_LABEL = "已取件"

    fun summaryTitle(records: List<ExpressRecord>): String {
        val ready = records.count { it.status == ExpressStatus.READY_FOR_PICKUP }
        return when {
            records.isEmpty() -> "快递"
            ready > 0 -> "有 $ready 个包裹待取件"
            records.size == 1 -> title(records.first())
            else -> "${records.size} 个包裹动态"
        }
    }

    fun summaryBody(records: List<ExpressRecord>): String =
        records.joinToString("\n") { record ->
            buildString {
                append("· ")
                append(title(record))
                record.pickupCode?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            }
        }
}
