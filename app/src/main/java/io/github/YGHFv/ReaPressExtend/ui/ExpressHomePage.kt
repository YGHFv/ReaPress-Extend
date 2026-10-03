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

package io.github.YGHFv.ReaPressExtend.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeSection
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationGroup
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首页：到站件按取件地点聚合成卡片组，其余平铺；回调传 null 表示对应功能不可用。 */
@Composable
internal fun HomePage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules = ExpressStationRules.EMPTY,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)? = null,
    onOpenArchive: (() -> Unit)? = null,
    /** 归档窗口（毫秒）：设置选「签收后归档」传 0，「签收7天后归档」传 [ExpressHomeGrouper.ARCHIVE_RETENTION_MS]。 */
    archiveRetentionMs: Long = ExpressHomeGrouper.ARCHIVE_RETENTION_MS,
    packageSyncStatus: String? = null,
) {
    packageSyncStatus?.let { HintText(it) }
    if (records.isEmpty()) {
        EmptyHome()
        return
    }

    // now 按分钟取整并作为 remember 的 key：相对时间标签每分钟精度足够，
    // 而分组/排序/归档这三笔全量计算只在数据或分钟真的变了才重跑 ——
    // 原来写在组合体里，任何一处父级状态变化都会把整页 O(n log n) 重算一遍。
    val now = (System.currentTimeMillis() / 60_000L) * 60_000L
    val sections = remember(records, rules, now, archiveRetentionMs) {
        ExpressHomeGrouper.group(records, rules, now, archiveRetentionMs)
    }
    val stationLabels = remember(records, rules) {
        ExpressHomeGrouper.stationLabels(records, rules)
    }

    for (section in sections) {
        GroupTitle("${section.title} · ${section.count}件")
        if (section.stationGroups.isNotEmpty()) {
            for (group in section.stationGroups) {
                StationGroupCard(group, rules, now, onTogglePickup, onOpenDetail)
            }
        }
        for (record in section.records) {
            ParcelCard(
                record = record,
                stationLabel = ExpressHomeGrouper.stationLabelOf(record, stationLabels),
                rules = rules,
                now = now,
                onTogglePickup = onTogglePickup,
                onOpenDetail = onOpenDetail,
            )
        }
    }

    if (onOpenArchive != null) {
        val archivedCount = remember(records, now, archiveRetentionMs) {
            ExpressHomeGrouper.archive(records, now, archiveRetentionMs).size
        }
        GroupTitle("归档")
        SettingsCard {
            ArrowPreference(
                title = "归档快递",
                summary = if (archivedCount == 0) {
                    if (archiveRetentionMs == 0L) "暂无 · 物流签收后自动移到这里"
                    else "暂无 · 物流签收超过 7 天会自动移到这里"
                } else {
                    if (archiveRetentionMs == 0L) "$archivedCount 件 · 物流签收后归档"
                    else "$archivedCount 件 · 物流签收超过 7 天"
                },
                onClick = onOpenArchive,
            )
        }
    }
}

@Composable
private fun EmptyHome() {
    GroupTitle("我的包裹")
    SettingsCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 32.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("暂无包裹", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "拦截到快递通知后会自动出现在这里",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    GroupTitle("收不到包裹？")
    SettingsCard {
        HintText("1. LSPosed 作用域包含 system（关于页可确认）")
        HintText("2. 通知权限已授予")
        HintText("3. 对应来源的开关是打开的（设置页）")
    }
}

@Composable
private fun StationGroupCard(
    group: ExpressStationGroup,
    rules: ExpressStationRules,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    SettingsCard {
        val hours = group.records
            .firstNotNullOfOrNull { ExpressFormatter.stationHoursLabel(it.stationHours) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.station,
                    fontSize = 16.sp,
                    fontWeight = FontWeight(550),
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hours != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = hours,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                "${group.records.size} 件",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        for ((index, record) in group.records.withIndex()) {
            if (index > 0) RowDivider()
            PickupCardBody(record, rules, now, onTogglePickup, onOpenDetail)
        }
    }
}

/** 不按地点聚合时的单条卡片（归档页也用）。[stationLabel] 必须由调用方经 [ExpressHomeGrouper.stationLabels] 算好传入，不能读 `record.station`（原始串过不了改名规则）。 */
@Composable
internal fun ParcelCard(
    record: ExpressRecord,
    stationLabel: String?,
    rules: ExpressStationRules,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    val target = if (record.isPickedUp) onTogglePickup else null
    SettingsCard {
        ParcelCardBody(record, stationLabel, rules, now, target, onOpenDetail)
    }
}

/** 取件码列宽。真机截图量出：28sp 时 `1-5-2644` 在 104dp 里会折行，取 124dp。 */
private val PICKUP_COLUMN_WIDTH = 124.dp

/** 到站包裹主体。取件码一律走 [ExpressHomeGrouper.pickupCodeOf]（缺码时用该站默认码），不能直接读 `record.pickupCode`；手势挂整行（见 [cardTapTarget]）。 */
@Composable
private fun PickupCardBody(
    record: ExpressRecord,
    rules: ExpressStationRules,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    val pickup = ExpressHomeGrouper.pickupCodeOf(record, rules)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 手势挂在 padding 之前，点击区域才含留白。
            .cardTapTarget(record, onOpenDetail, onTogglePickup)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickupColumn(record, pickup)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val left = courierLine(record, pickup)
            val tail = stationTail(record, now)
            if (left.isNotEmpty() || tail != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (left.isNotEmpty()) {
                        Text(
                            text = left,
                            fontSize = 12.sp,
                            fontWeight = FontWeight(500),
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (tail != null) {
                        // 前段为空时不带分隔符，否则行首多出「 · 」。
                        Text(
                            text = if (left.isNotEmpty()) " · $tail" else tail,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
            ExpressFormatter.goodsSummary(record)?.let { goods ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = goods,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 运输中/已签收的主体（上下结构）。[stationLabel] 不是 `record.station`；取件码同 [PickupCardBody]，不能读 `record.pickupCode`。 */
@Composable
private fun ParcelCardBody(
    record: ExpressRecord,
    stationLabel: String?,
    rules: ExpressStationRules,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    val pickup = ExpressHomeGrouper.pickupCodeOf(record, rules)
    Column(
        modifier = Modifier
            .cardTapTarget(record, onOpenDetail, onTogglePickup)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                ParcelTitleRow(record, stationLabel, pickup)
            }
            Text(
                text = transitStatusLabel(record, now),
                fontSize = 13.sp,
                color = statusColor(record),
            )
        }
        parcelSubtitle(record, pickup)?.let { subtitle ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ExpressFormatter.goodsSummary(record)?.let { goods ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = goods,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 状态文案（相对时间+状态），只对还在路上的件显示。起算点必须用 [ExpressFormatter.statusSince]：宿主 gmt_modified 会停在旧值（真机 2026-09-26），轨迹最新节点才是状态的开始时刻。 */
private fun transitStatusLabel(record: ExpressRecord, now: Long): String {
    val status = ExpressFormatter.statusLabel(record)
    val onTheWay = record.status == ExpressStatus.IN_TRANSIT ||
        record.status == ExpressStatus.PICKED_UP ||
        record.status == ExpressStatus.DELIVERING
    if (!onTheWay || record.isPickedUp) return status
    val since = ExpressFormatter.statusSince(record) ?: return status
    val age = ExpressFormatter.relativeAge(since, now) ?: return status
    return "$age $status"
}

/** 标题行：主标识 + 运单号全号 + 驿站名。[stationLabel] 不是 `record.station`；[pickup] 由调用方算好，不能读 `record.pickupCode`。 */
@Composable
private fun ParcelTitleRow(record: ExpressRecord, stationLabel: String?, pickup: String?) {
    val code = pickup?.takeIf { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = code ?: courierLabel(record),
            fontSize = if (code != null) 24.sp else 17.sp,
            fontWeight = if (code != null) FontWeight(600) else FontWeight(550),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() }
        if (tracking != null && !titleShowsTracking(record, pickup)) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = tracking,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (stationLabel != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "·",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stationLabel,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 运输中卡片的副行：公司简称（仅当标题被取件码占了）+ 手机尾号 + 运单动态，凑不出退回原文首行。 */
private fun parcelSubtitle(record: ExpressRecord, pickup: String?): String? {
    val courier = if (!pickup.isNullOrBlank() && record.courier != Courier.UNKNOWN) {
        record.courier.shortName
    } else {
        null
    }
    val parts = listOfNotNull(courier, ExpressFormatter.detailLine(record))
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return record.rawText.lineSequence().firstOrNull { it.isNotBlank() }?.take(40)
}

/**
 * 单击/双击落点挂整行。必须用 `detectTapGestures` 而非 `combinedClickable`：单击要等双击窗口过期再触发。
 * key 是 [ExpressRecord.dedupeKey] 加两个「开没开」布尔：拿 lambda 当 key 会不停重启手势块，只按 dedupeKey 又会留旧回调。
 */
private fun Modifier.cardTapTarget(
    record: ExpressRecord,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
): Modifier = if (onOpenDetail == null && onTogglePickup == null) {
    this
} else {
    pointerInput(record.dedupeKey, onOpenDetail != null, onTogglePickup != null) {
        detectTapGestures(
            // 必须显式标注 Offset 参数，否则 lambda 被推成 () -> Unit 对不上 onTap。
            onTap = onOpenDetail?.let { detail -> { _: Offset -> detail(record) } },
            onDoubleTap = onTogglePickup?.let { toggle -> { _: Offset -> toggle(record) } },
        )
    }
}

/** 到站卡片左列：有码 26sp，退回公司名/运单号 17sp，已取件变灰。[pickup] 由调用方算好，不能读 `record.pickupCode`。 */
@Composable
private fun PickupColumn(record: ExpressRecord, pickup: String?) {
    val code = pickup?.takeIf { it.isNotBlank() }
    val color = if (record.isPickedUp) {
        MiuixTheme.colorScheme.disabledOnSurface
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.width(PICKUP_COLUMN_WIDTH)) {
        if (code != null) {
            Text(
                text = code,
                fontSize = 26.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight(600),
                color = color,
                maxLines = 2,
            )
        } else {
            Text(
                text = courierLabel(record),
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight(550),
                color = color,
                maxLines = 2,
            )
        }
    }
}

/** 在站时长尾段，拿不到到站时间整段不显示；不参与收缩，宁可运单号被截。 */
private fun stationTail(record: ExpressRecord, now: Long): String? {
    if (record.status !in STATION_STATUSES) return null
    return record.arrivalAt?.let { ExpressFormatter.inStationLabel(it, now) }
}

/** 剥掉「菜鸟驿站」前缀与最外层括号（removeSuffix 会连内层右括号一起吃掉）。 */
private fun stationTitle(station: String): String {
    val trimmed = station.trim()
    val withoutBrand = trimmed.removePrefix("菜鸟驿站")
    if (withoutBrand.length >= 2 &&
        ((withoutBrand.startsWith("(") && withoutBrand.endsWith(")")) ||
            (withoutBrand.startsWith("（") && withoutBrand.endsWith("）")))
    ) {
        return withoutBrand.substring(1, withoutBrand.length - 1).ifBlank { trimmed }
    }
    return withoutBrand.ifBlank { trimmed }
}

/** 无取件码时的主标识：公司简称 → 运单号 → 「快递包裹」占位。 */
private fun courierLabel(record: ExpressRecord): String = when {
    record.courier != Courier.UNKNOWN -> record.courier.shortName
    !record.trackingNumber.isNullOrBlank() -> record.trackingNumber
    else -> "快递包裹"
}

/** 主标识位是否已在显示运单号（避免单号出现两次）。判据用最终显示的取件码，不读 `record.pickupCode`。 */
private fun titleShowsTracking(record: ExpressRecord, pickup: String?): Boolean =
    pickup.isNullOrBlank() &&
        record.courier == Courier.UNKNOWN &&
        !record.trackingNumber.isNullOrBlank()

/** 在站时长只对这两个状态显示：arrivalAt 是最后一次状态变更时间，对运输中件算出假停留天数。 */
private val STATION_STATUSES = setOf(
    ExpressStatus.ARRIVED_STATION,
    ExpressStatus.READY_FOR_PICKUP,
)

/** 公司简称 + 运单号全号，不重复左列内容，返回值可能是空串。判断用传入的 [pickup]，不读 `record.pickupCode`。 */
private fun courierLine(record: ExpressRecord, pickup: String?): String = buildString {
    val hasPickup = !pickup.isNullOrBlank()
    if (hasPickup && record.courier != Courier.UNKNOWN) {
        append(record.courier.shortName)
    }
    if (!titleShowsTracking(record, pickup)) {
        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { tracking ->
            if (isNotEmpty()) append(" · ")
            append(tracking)
        }
    }
}

@Composable
private fun statusColor(record: ExpressRecord) = when {
    record.isPickedUp -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    record.status == ExpressStatus.FAILED -> MiuixTheme.colorScheme.error
    record.status == ExpressStatus.READY_FOR_PICKUP ||
        record.status == ExpressStatus.ARRIVED_STATION -> MiuixTheme.colorScheme.primary
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
