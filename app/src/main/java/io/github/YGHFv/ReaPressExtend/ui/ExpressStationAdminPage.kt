package io.github.YGHFv.ReaPressExtend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationSummary
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 驿站管理 —— 设置页那一行点进来的二级页面。
 *
 * ## 它解决的问题
 *
 * 首页按驿站分组，而同一个取件地点在通知和菜鸟里可能是两个名字。模块会先自动认一批
 * （地址详略、品牌前缀、括号，见 `ExpressStationName`），但**机器认不出来的只能人来**：
 * 「这个驿站通知里叫 A、菜鸟里叫 B，其实是同一处」这件事没有任何字符串规律，只有住那儿的人知道。
 *
 * 所以这一页只做两件事，且都落在同一张规则表上（见 `ExpressStationRules`）：
 * - **改外显名称** —— 显示成「家门口」这种只有用户自己看得懂的名字；
 * - **合并到另一个驿站** —— 把名称设置成对方的名称，两处的包裹就会显示在同一张卡片上。
 *
 * ## 为什么是「列表 → 详情」两层
 *
 * 一个驿站要改名字、要选合并对象、还要让用户看见「这站是哪几件」—— 三件事塞进列表里，
 * 每行都会变成一小片表单，一扫就分不清哪行对应哪个驿站。列表只回答「有哪些驿站」，
 * 操作全部收进详情页。
 *
 * 版面沿用模块那套词汇（`ExpressUiKit` 文件头）：`GroupTitle` + `SettingsCard`，
 * 没有为这一页新造字号或颜色。
 */
@Composable
internal fun StationAdminPage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    onRename: (key: String, display: String?) -> Unit,
    onBack: () -> Unit,
) {
    // 驿站列表按当前规则算：改完名字立刻能看到结果，不用退出去再进来。
    val stations = remember(records, rules) {
        ExpressHomeGrouper.stations(records, rules)
    }
    var openedKey by remember { mutableStateOf<String?>(null) }
    // 记住的是 key 而不是 summary 对象：summary 每次重组都是新实例，而 key 在改名前后不变
    // （改名改的是规则的值，不是键），所以保存之后详情页不会自己弹回列表。
    val opened = stations.firstOrNull { it.key == openedKey }

    if (opened == null) {
        StationListPage(stations = stations, onOpen = { openedKey = it }, onBack = onBack)
    } else {
        StationDetailPage(
            station = opened,
            allStations = stations,
            onRename = onRename,
            onBack = { openedKey = null },
        )
    }
}

// ---------------------------------------------------------------- 列表

@Composable
private fun StationListPage(
    stations: List<ExpressStationSummary>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)

    StationScaffold("驿站管理", scrollBehavior, onBack) {
        GroupTitle("怎么用")
        SettingsCard {
            HintText(
                "同一个取件地点在通知和菜鸟里可能写成两个名字。模块会自动认出「地址详略不同」" +
                    "的那些写法（比如多了楼栋号），认不出来的在这里手动处理。",
            )
            HintText(
                "合并：把某个驿站的名称设置成另一个驿站的名称，两处的包裹就显示在同一张卡片上。\n" +
                    "改名只影响本机显示，记录里的原始写法不会被改动，随时可以恢复。",
            )
        }

        if (stations.isEmpty()) {
            GroupTitle("驿站")
            SettingsCard {
                HintText("还没有任何包裹记录。收到快递通知或菜鸟同步过之后，这里会列出驿站。")
            }
        } else {
            GroupTitle("共 ${stations.size} 个驿站")
            SettingsCard {
                stations.forEachIndexed { index, station ->
                    if (index > 0) RowDivider()
                    ArrowPreference(
                        title = station.displayName,
                        summary = listSummary(station),
                        onClick = { onOpen(station.key) },
                    )
                }
            }
        }
    }
}

/**
 * 列表行的摘要。**必须短** —— `ArrowPreference` 的副标题塞不下两行，折行会把卡片撑得比正文还高。
 *
 * 原始写法具体是哪几种留给详情页说：那是「凭什么说这两个名字是一处」的解释，点进去才需要。
 */
private fun listSummary(station: ExpressStationSummary): String = buildString {
    append("${station.count} 件")
    if (station.rawNames.size > 1) append(" · ${station.rawNames.size} 种写法")
    if (station.renamed) append(" · 已手动命名")
}

// ---------------------------------------------------------------- 详情

@Composable
private fun StationDetailPage(
    station: ExpressStationSummary,
    allStations: List<ExpressStationSummary>,
    onRename: (String, String?) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)
    // 草稿按 key 记：换一个驿站进来时要重置成那个驿站的名字，而不是留着上一个的。
    var draft by remember(station.key) { mutableStateOf(station.displayName) }
    val others = allStations.filter { it.key != station.key }

    StationScaffold("驿站详情", scrollBehavior, onBack) {
        GroupTitle("外显名称")
        SettingsCard {
            HintText("在首页卡片上显示成什么。只改本机显示，不会同步给菜鸟或快递公司。")
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 只在真的改了才出现「保存」：名字没变时挂一个按了没反应的按钮，比不挂更让人怀疑。
            if (draft != station.displayName) {
                CardDivider()
                CardActionRow(label = "保存名称") { onRename(station.key, draft) }
            }
            if (station.renamed) {
                CardDivider()
                CardActionRow(label = "恢复默认名称", danger = true) { onRename(station.key, null) }
            }
        }

        // 原始写法摊在这里而不是列表里：点进来的人就是想确认「我改的到底是哪几件」，
        // 而这几个原始串正是「凭什么说它们是一处」的证据。
        if (station.rawNames.size > 1 || station.rawNames.firstOrNull() != station.displayName) {
            GroupTitle("记录里的原始写法")
            SettingsCard {
                station.rawNames.forEachIndexed { index, raw ->
                    if (index > 0) RowDivider()
                    Text(
                        raw,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (others.isNotEmpty()) {
            GroupTitle("合并到其他驿站")
            SettingsCard {
                OverlayDropdownPreference(
                    title = "合并到",
                    summary = "和另一个驿站的包裹并成一张卡片",
                    items = listOf(NOT_MERGED) + others.map { it.displayName },
                    // 永远停在「不合并」：这一行是**动作**而不是状态 —— 合并成功后这个驿站就不存在了，
                    // 界面会回列表，没有「当前合并到谁」需要显示。
                    selectedIndex = 0,
                    onSelectedIndexChange = { index ->
                        if (index > 0) onRename(station.key, others[index - 1].key)
                    },
                )
                HintText(
                    "选定之后，这站的 ${station.count} 件包裹会显示在对方的卡片上并沿用对方的名称。" +
                        "想改回来，在对方那一页里恢复默认即可。",
                )
            }
        }

        GroupTitle("这站的包裹 · ${station.count} 件")
        SettingsCard {
            station.records.forEachIndexed { index, record ->
                if (index > 0) RowDivider()
                ParcelLine(record)
            }
        }
    }
}

/** 一行包裹：左边取件码（没有再退回运单号），右边状态。 */
@Composable
private fun ParcelLine(record: ExpressRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            record.pickupCode?.takeIf { it.isNotBlank() }
                ?: record.trackingNumber?.takeIf { it.isNotBlank() }
                ?: "—",
            modifier = Modifier.weight(1f),
        )
        SecondaryText(ExpressFormatter.statusLabel(record))
    }
}

// ---------------------------------------------------------------- 骨架

/** 二级页共用的外壳：顶栏 + 返回 + 整页滚动。与 `LogPage` 同一套写法。 */
@Composable
private fun StationScaffold(
    title: String,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 同 `LogPage`：顶栏上的返回不铺胶囊底色，否则顶栏会多出一个圆角块。
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(vertical = 4.dp),
        ) {
            content()
            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 「合并到」下拉里的第一项（= 保持独立）。 */
private const val NOT_MERGED = "不合并"
