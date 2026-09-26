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
 * 包裹详情页 —— 首页卡片单击进来的**二级页面**。
 *
 * ## 为什么需要它
 *
 * 首页卡片受高度限制，每个字段都只留一行的位置（商品名截断、轨迹只显示最新一条）。
 * 但有两类信息天生就长，首页放不下：
 * - **全轨迹**：宿主首页只给一句「已发往【上海转运中心】」，用户想知道「到底卡在哪一步」时
 *   必须看到完整节点；
 * - **驿站完整地址**：首页只显示驿站名（参与地点聚类），而「照着去哪儿取」要的是门牌。
 *
 * 所以这一页的定位是「把卡片上省略掉的东西原样摊开」，**不新增交互** —— 没有按钮、
 * 没有可点区域（取件确认仍然在首页双击卡片上，一处入口，不用学两套）。
 *
 * ## 下拉刷新
 *
 * 轨迹 / 商品图是**异步**落库的（宿主进程发请求 → 广播回模块进程 → 存储），用户点进详情页的
 * 那一刻数据可能还没到 —— 打开页面时会自动重读一遍，但要是用户等不及（或者刚看完菜鸟回来），
 * 下拉就能再读一次。刷新**只重读本地存储**，不会（也没法）让宿主重新发请求：模块进程无权
 * 调起宿主的 hook，重新拉轨迹得回菜鸟里刷一遍首页。
 *
 * ## 版面
 *
 * 与 `LogPage` 同一条路子：只用 `ExpressUiKit` 那套排版词汇（[GroupTitle] + [SettingsCard] +
 * [InfoRow] + [RowDivider] + [SecondaryText]），**没有为这一页新造字号或颜色**。
 * 轨迹列表也照 `LogPage` 的形状做 —— 每一条 = 一行小字时刻 + 一行正文，条目之间 [RowDivider]，
 * 不画时间轴的点和竖线（那会引入本模块别处都没有的一套视觉符号）。
 *
 * @param stationLabel 该显示的驿站名（用户在驿站管理里改过就是改后的名字），未知传 null。
 *   **不是 `record.station`** —— 原始串过不了用户的改名 / 合并规则，两处显示会不一致。
 * @param isRefreshing 下拉刷新是否进行中。状态提升到 Activity：与首页 / 记录页同一套约定
 *   （`onRefresh` 里自己置 true、完事置 false）。
 * @param onRefresh 下拉触发。Activity 侧重读存储 —— 记录更新后本页会因入参变化自动重组，
 *   显示出刚落库的轨迹。
 * @param traceHint 轨迹空态时替换默认提示的一行字（如「风控退避中」）。null = 用默认提示。
 *   由 Activity 从 [io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi] 的退避状态算出来 ——
 *   那边才知道请求被挡的真实原因，干等超时再显示「确认菜鸟在后台」是误导。
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

    // 系统返回键等同于顶栏的返回箭头。
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "包裹详情",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 与日志页同一条规矩：顶栏上的返回不铺 secondaryContainer 底色，
                        // 保留透明（按压反馈仍在），否则顶栏会多出一个没由来的圆角块。
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        // 下拉刷新包住滚动内容：结构与主页的 RefreshablePage 相同 —— contentPadding
        // 只把指示器推到顶栏下面，内容自己的 padding 照旧。
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

/**
 * 「包裹」分组：商品图 + 商品名 + 全部标识字段。
 *
 * 顺序是按「用户想确认什么」排的：先「这是什么」（图 / 名 / 状态），再「怎么认它」
 * （取件码 → 手机尾号 → 运单号 → 公司），最后「哪买的」。取件码排在运单号前面是因为
 * 到驿站念的是它，而运单号更多是拿去核对或报客服。
 */
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
        // 与首页卡片同一条来源：记录自己的码优先，缺了用该站的默认码（用户在驿站管理里填的）。
        // 两处各读一遍 `record.pickupCode` 的话，就会出现「首页有码、详情页没有」的错位。
        ExpressHomeGrouper.pickupCodeOf(record, rules)?.let { InfoRow("取件码", it) }
        record.phoneTail?.takeIf { it.isNotBlank() }?.let { InfoRow("手机尾号", it) }
        // 来源也要过一遍归一化：宿主对非淘包裹给的 `pkgSourceDesc` 是「普通收件」这类
        // **收件类型词**，不是平台名（见 [ExpressPlatform]）。这一行显示「来源：普通收件」
        // 等于用一整行说了一件零信息量的事。
        ExpressPlatform.normalize(record.platform)?.let { InfoRow("来源", it) }
    }
}

/**
 * 商品图 + 商品名。
 *
 * 两者都没有时**整段不排**：这一格存在的意义是「让用户认出是哪一单」，
 * 凑不出内容时留一块空图 or 一行空文字只会把状态行往下挤。
 */
@Composable
private fun PackageHeader(record: ExpressRecord) {
    val name = record.goodsName?.takeIf { it.isNotBlank() }
    val image = record.goodsImage?.takeIf { it.isNotBlank() }
    if (name == null && image == null) return

    if (name == null) {
        // 只有图：图片按自己的尺寸靠左排，不撑满整行 —— 把 240px 的位图拉满屏幕宽度
        // 会盖过下面所有的文字信息。
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
                // 图旁边就是同一件商品的标题，再念一遍是重复噪音。
                contentDescription = null,
                modifier = Modifier
                    .size(GOODS_IMAGE_SIZE)
                    .clip(RoundedCornerShape(GOODS_IMAGE_CORNER)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            // 首页卡片把商品名截成一行（卡片高度不能随标题长短乱跳），这一页就是来补这个的：
            // 给到 3 行，够放下绝大多数电商标题。
            Text(text = name, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 详情页的缩略图边长。比首页任何一格的图标都大，但它在这一页是「认件」的主角。 */
private val GOODS_IMAGE_SIZE = 64.dp
private val GOODS_IMAGE_CORNER = 10.dp

/**
 * 「取件地点」分组：驿站名、完整地址、营业时间。
 *
 * 三项都拿不到时整组不排 —— 空组会在页面上留下一句孤零零的抬头。
 */
@Composable
private fun StationSection(
    record: ExpressRecord,
    stationLabel: String?,
    rules: ExpressStationRules,
) {
    val name = stationLabel?.takeIf { it.isNotBlank() }
    // 宿主给的地址优先，缺了才用用户在驿站管理里填的精确地址 —— 宿主那个是快递公司自己给的，
    // 比手填的更权威；反过来只在「宿主什么都没有」时才补位（那正是用户填它的原因）。
    val address = ExpressHomeGrouper.stationAddressOf(record, rules)
    val hours = ExpressFormatter.stationHoursLabel(record.stationHours)
    if (name == null && address == null && hours == null) return

    GroupTitle("取件地点")
    SettingsCard {
        if (name != null) InfoRow("驿站", name)
        // 地址走 InfoRow 的「上下堆叠」那一档（超 24 字自动切），这里不截断：
        // 只看得到前半截的地址等于没有，而这正是用户抱怨「通知里拿不到取件地址」时要的那个串。
        if (address != null) InfoRow("地址", address)
        if (hours != null) InfoRow("营业时间", hours)
    }
}

/**
 * 「物流轨迹」分组。按**最新在上**排 —— [ExpressRecord.trace] 是「最早 → 最新」，
 * 这里反一次序。用户点进详情页想先看到的是「现在到哪了」，而不是三周前的揽件。
 *
 * 最新一条的时刻用主题色点一下：一屏轨迹里「哪一条是最新的」需要一眼看出来，
 * 而正文不能改色（那是全部文字的主体，改色就看不清层级了）。
 */
@Composable
private fun TraceSection(record: ExpressRecord, hint: String?) {
    val points = record.trace
    if (points.isEmpty()) {
        GroupTitle("物流轨迹")
        SettingsCard {
            HintText(
                hint
                    // 拉取被风控退避挡下时，Activity 会算出剩余时间传进来 —— 那时显示
                    // 「去确认菜鸟在后台」是误导：请求根本没发出去。
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

/**
 * 一条轨迹。形状跟着 `LogPage.LogRow` 走：小字时刻在上、正文在下，正文**不截断**。
 *
 * 正文不给 [TextOverflow.Ellipsis]：轨迹文案里「哪个网点 / 谁在派件」经常落在后半句，
 * 截成一行正好把有用的那半吃掉，而这一页本来就是拿来细看的。
 */
@Composable
private fun TraceRow(point: ExpressTracePoint, latest: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SecondaryText(
            // 极少数节点没有 time（接口偶发给空串）。宁可写一个明确的「时间未知」，
            // 也不要让这行空着 —— 空行会被读成「这一条没有内容」。
            text = point.time.takeIf { it.isNotBlank() } ?: "时间未知",
            color = if (latest) MiuixTheme.colorScheme.primary else null,
        )
        Spacer(Modifier.height(3.dp))
        Text(point.text)
    }
}
