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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressPlatform
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.ExpressTracePoint
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 包裹详情页：把首页卡片放不下的东西摊开（全轨迹、驿站完整地址），不新增交互。
 * 下拉刷新只重读本地存储——模块进程无权调起宿主 hook，重新拉轨迹得回菜鸟里刷一遍首页。
 * [stationLabel] 不是 record.station：原始串过不了用户的改名/合并规则，两处显示会不一致。
 * [traceHint] 由 Activity 从 CainiaoTraceApi 的退避状态算出——干等超时显示「确认菜鸟在后台」是误导。
 */
@Composable
internal fun ExpressDetailPage(
    record: ExpressRecord,
    stationLabel: String?,
    rules: ExpressStationRules,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    traceHint: String? = null,
) {
    val scrollBehavior = MiuixScrollBehavior()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "包裹详情",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 顶栏返回不铺 secondaryContainer 底色，否则多出一个没由来的圆角块。
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            refreshTexts = REFRESH_TEXTS,
            contentPadding = PaddingValues(top = padding.calculateTopPadding()),
            topAppBarScrollBehavior = scrollBehavior,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding())
                    .padding(vertical = 4.dp),
            ) {
                PackageSection(record, rules)
                StationSection(record, stationLabel, rules)
                TraceSection(record, traceHint)
                Spacer(Modifier.height(padding.calculateBottomPadding()))
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** 「包裹」分组。顺序按「用户想确认什么」排：取件码排在运单号前——到驿站念的是它。 */
@Composable
private fun PackageSection(record: ExpressRecord, rules: ExpressStationRules) {
    GroupTitle("包裹")
    SettingsCard {
        PackageHeader(record)
        InfoRow("当前状态", ExpressFormatter.statusLabel(record))
        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { InfoRow("运单号", it) }
        if (record.courier != Courier.UNKNOWN) {
            InfoRow("快递公司", record.courier.displayName)
        }
        // 与首页卡片同源：记录的码优先，缺了用该站默认码——各读各的会出现「首页有码详情页没有」。
        ExpressHomeGrouper.pickupCodeOf(record, rules)?.let { InfoRow("取件码", it) }
        // 尾号只有 4 位可能认错件：原取件码紧跟取件码摆出来，用户在驿站念之前一眼能看出对不上。
        record.previousPickupCode?.takeIf { it.isNotBlank() }?.let { InfoRow("原取件码", it) }
        record.phoneTail?.takeIf { it.isNotBlank() }?.let { InfoRow("手机尾号", it) }
        // 宿主给非淘包裹的 pkgSourceDesc 是「普通收件」这类收件类型词不是平台名，归一化后再显示。
        ExpressPlatform.normalize(record.platform)?.let { InfoRow("来源", it) }
        record.orderSn?.takeIf { it.isNotBlank() }?.let { InfoRow("订单号", it) }
    }
}

/** 商品图 + 商品名，两者都没有时整段不排——留空图空字只会把状态行往下挤。 */
@Composable
private fun PackageHeader(record: ExpressRecord) {
    val name = record.goodsName?.takeIf { it.isNotBlank() }
    val image = record.goodsImage?.takeIf { it.isNotBlank() }
    if (name == null && image == null) return

    if (name == null) {
        if (image != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                RemoteImage(
                    url = image,
                    contentDescription = null,
                    modifier = Modifier
                        .size(GOODS_IMAGE_SIZE)
                        .clip(RoundedCornerShape(GOODS_IMAGE_CORNER)),
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image != null) {
            RemoteImage(
                url = image,
                contentDescription = null,
                modifier = Modifier
                    .size(GOODS_IMAGE_SIZE)
                    .clip(RoundedCornerShape(GOODS_IMAGE_CORNER)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val GOODS_IMAGE_SIZE = 64.dp
private val GOODS_IMAGE_CORNER = 10.dp

/** 「取件地点」分组，三项都拿不到时整组不排——空组会留一句孤零零的抬头。 */
@Composable
private fun StationSection(
    record: ExpressRecord,
    stationLabel: String?,
    rules: ExpressStationRules,
) {
    val name = stationLabel?.takeIf { it.isNotBlank() }
    // 宿主给的地址优先（快递公司给的比手填权威），缺了才用用户在驿站管理里填的精确地址。
    val address = ExpressHomeGrouper.stationAddressOf(record, rules)
    val hours = ExpressFormatter.stationHoursLabel(record.stationHours)
    if (name == null && address == null && hours == null) return

    GroupTitle("取件地点")
    SettingsCard {
        if (name != null) InfoRow("驿站", name)
        if (address != null) InfoRow("地址", address)
        if (hours != null) InfoRow("营业时间", hours)
    }
}

/** 轨迹最新在上（record.trace 是最早→最新，这里反一次序）；最新一条的时刻用主题色点出。 */
@Composable
private fun TraceSection(record: ExpressRecord, hint: String?) {
    val points = record.trace
    if (points.isEmpty()) {
        GroupTitle("物流轨迹")
        SettingsCard {
            HintText(
                hint
                    ?: "暂无物流轨迹。点开详情页时会自动拉取当前包裹（页面会等几秒）；" +
                    "若一直没有，请确认菜鸟在后台运行后下拉重试。",
            )
        }
        return
    }

    GroupTitle("物流轨迹 · ${points.size} 条")
    SettingsCard {
        val newestFirst = points.asReversed()
        for ((index, point) in newestFirst.withIndex()) {
            if (index > 0) RowDivider()
            TraceRow(point, latest = index == 0)
        }
    }
}

/** 一条轨迹：小字时刻在上、正文不截断——「哪个网点/谁在派件」常落在后半句，截一行正好吃掉有用的那半。 */
@Composable
private fun TraceRow(point: ExpressTracePoint, latest: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SecondaryText(
            // 极少数节点没有 time（接口偶给空串）；空行会被读成「这一条没有内容」。
            text = point.time.takeIf { it.isNotBlank() } ?: "时间未知",
            color = if (latest) MiuixTheme.colorScheme.primary else null,
        )
        Spacer(Modifier.height(3.dp))
        Text(point.text)
    }
}
