package io.github.YGHFv.ReaPressExtend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeSection
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationGroup
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页。
 *
 * 布局对齐菜鸟首页：**到站包裹按取件地点聚合成卡片组**（那是用户真正要去取的东西），
 * 运输中的平铺在下面（用户只是「知道一下」）。
 *
 * 聚合这件事的意义在于：同一个驿站常有多个包裹，分开列会让用户在一个驿站和另一个驿站之间
 * 来回找；聚在一起则「去一趟驿站，这几件一起拿」。
 */
@Composable
internal fun HomePage(records: List<ExpressRecord>) {
    if (records.isEmpty()) {
        GroupTitle("我的包裹")
        SectionCard {
            InfoRow("暂无包裹", "拦截到快递通知后会自动出现在这里")
        }
        HintText(
            "收不到包裹？检查三件事：\n" +
                "1. LSPosed 作用域包含 system（关于页可确认）\n" +
                "2. 通知权限已授予\n" +
                "3. 对应来源的开关是打开的（设置页）",
        )
        return
    }

    val sections = ExpressHomeGrouper.group(records)

    for (section in sections) {
        GroupTitle("${section.title} · ${section.count}件")
        if (section.stationGroups.isNotEmpty()) {
            for (group in section.stationGroups) {
                StationGroupCard(group)
            }
        }
        for (record in section.records) {
            ParcelCard(record)
        }
    }
}

/** 一个取件地点下的所有包裹，合成一张卡片。 */
@Composable
private fun StationGroupCard(group: ExpressStationGroup) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        // 地点抬头：菜鸟首页把驿站名放在最显眼的位置，因为它决定了「去哪取」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stationTitle(group.station),
                fontSize = 16.sp,
                fontWeight = FontWeight(550),
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${group.records.size} 件",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        for ((index, record) in group.records.withIndex()) {
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                )
            }
            RecordBody(record, showStation = false)
        }
    }
}

/** 不按地点聚合时的单条卡片（运输中、已签收）。 */
@Composable
private fun ParcelCard(record: ExpressRecord) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        RecordBody(record, showStation = true)
    }
}

/**
 * 一条包裹的主体。
 *
 * 字段按「取件时要用到的顺序」排：**取件码最大最显眼**（到了驿站要念给店员听），
 * 其次是快递公司、运单号尾号、状态。这是照抄菜鸟首页的信息层级 ——
 * 用户在驿站门口看这一屏，第一眼必须能看到取件码。
 */
@Composable
private fun RecordBody(record: ExpressRecord, showStation: Boolean) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                val pickup = record.pickupCode
                if (!pickup.isNullOrBlank()) {
                    Text(
                        text = pickup,
                        fontSize = 24.sp,
                        fontWeight = FontWeight(600),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = courierLabel(record),
                        fontSize = 17.sp,
                        fontWeight = FontWeight(550),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle(record),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = record.status.displayName,
                fontSize = 13.sp,
                color = statusColor(record.status),
            )
        }

        if (showStation) {
            record.station?.takeIf { it.isNotBlank() }?.let { station ->
                Spacer(Modifier.height(6.dp))
                Text(
                    station,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }

        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { tracking ->
            Spacer(Modifier.height(6.dp))
            Text(
                "运单号 $tracking",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 驿站名。
 *
 * 原始串形如 `菜鸟驿站(阜阳颍滨花园店)`。抬头已经说明了这是取件地点，
 * 去掉「菜鸟驿站」前缀只留门店名，既省横向空间又更易读。
 *
 * 只剥**最外层**那一对括号：门店名本身可能带括号（`菜鸟驿站(A店(东门))`），
 * 用 removeSuffix 会把内层的右括号也吃掉。
 */
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

private fun courierLabel(record: ExpressRecord): String =
    if (record.courier == Courier.UNKNOWN) "快递包裹" else record.courier.displayName

/**
 * 副标题：快递公司 + 运单号尾号。
 *
 * 尾号而不是全号：菜鸟首页也是这么做的（「手机尾号1664的包裹」），因为取件时店员看的是尾号，
 * 全号太长反而干扰。
 *
 * 快递公司**只在标题没显示它时才重复**：有取件码时标题是取件码，需要公司名来区分；
 * 没有取件码时标题已经是公司名了，副标题再写一遍就是冗余。
 */
private fun subtitle(record: ExpressRecord): String = buildString {
    val titleShowsCourier = record.pickupCode.isNullOrBlank()
    if (record.courier != Courier.UNKNOWN && !titleShowsCourier) {
        append(record.courier.displayName)
    }
    record.trackingNumber?.takeIf { it.length >= 4 }?.let { tracking ->
        if (isNotEmpty()) append(" · ")
        append("尾号 ").append(tracking.takeLast(4))
    }
    if (isEmpty()) {
        // 什么都没抽到时退回原文首行 —— 有信息总比空着强。
        append(record.rawText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(40))
    }
}

@Composable
private fun statusColor(status: ExpressStatus) = when (status) {
    ExpressStatus.READY_FOR_PICKUP, ExpressStatus.ARRIVED_STATION -> MiuixTheme.colorScheme.primary
    ExpressStatus.FAILED -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
