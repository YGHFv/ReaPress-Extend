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
import io.github.YGHFv.ReaPressExtend.core.IdentitySource
import io.github.YGHFv.ReaPressExtend.core.StationFingerprint
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
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
 * 驿站管理二级页：机器认不出的同址异名只能人来合并；外显名称、默认身份码来源、精确地址
 * 三类规则同页填。所有回调传 [ExpressStationSummary.ruleKeys] 整组 —— 只动一个键会让
 * 合并行当场裂回两行。地址不让用户手打，改为门口点一次「获取当前位置」记定位 + WiFi。
 * 「默认取件码」表仍在、仍被使用，只是入口让给了身份码，数据与回退逻辑不删。
 */
@Composable
internal fun StationAdminPage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    onRename: (keys: List<String>, display: String?) -> Unit,
    onIdentitySource: (keys: List<String>, source: IdentitySource?) -> Unit,
    onCaptureLocation: (keys: List<String>) -> Unit,
    onRestore: (keys: List<String>) -> Unit,
    /** 「获取当前位置」这一次动作的状态；null = 没有任何采集在跑或刚跑完。 */
    capture: StationCaptureState?,
    onBack: () -> Unit,
) {
    val stations = remember(records, rules) {
        ExpressHomeGrouper.stations(records, rules)
    }
    var openedKey by remember { mutableStateOf<String?>(null) }
    // 记 key 不记 summary 对象：改名前后 key 不变，保存后详情页不会自己弹回列表。
    val opened = stations.firstOrNull { it.key == openedKey }

    if (opened == null) {
        StationListPage(stations = stations, onOpen = { openedKey = it }, onBack = onBack)
    } else {
        StationDetailPage(
            station = opened,
            allStations = stations,
            rules = rules,
            onRename = onRename,
            onIdentitySource = onIdentitySource,
            onCaptureLocation = onCaptureLocation,
            onRestore = onRestore,
            // 采集状态按 key 归属，别站的提示不能跟过来。
            capture = capture?.takeIf { it.key == opened.key },
            onBack = { openedKey = null },
        )
    }
}

/**
 * 「获取当前位置」一次动作的状态。key 必须有：采集异步，等待期间用户可能已切到别的驿站。
 */
internal data class StationCaptureState(
    val key: String,
    val busy: Boolean,
    val note: String? = null,
)

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
        if (stations.isEmpty()) {
            GroupTitle("驿站")
            SettingsCard {
                HintText("暂无包裹记录，收到通知或同步后这里会列出驿站。")
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

/** 列表行的摘要，必须短（折行会把卡片撑得比正文还高）。 */
private fun listSummary(station: ExpressStationSummary): String = buildString {
    append("${station.count} 件")
    if (station.rawNames.size > 1) append(" · ${station.rawNames.size} 种写法")
    if (station.renamed) append(" · 已自定义")
}

// ---------------------------------------------------------------- 详情

@Composable
private fun StationDetailPage(
    station: ExpressStationSummary,
    allStations: List<ExpressStationSummary>,
    rules: ExpressStationRules,
    onRename: (List<String>, String?) -> Unit,
    onIdentitySource: (List<String>, IdentitySource?) -> Unit,
    onCaptureLocation: (List<String>) -> Unit,
    onRestore: (List<String>) -> Unit,
    capture: StationCaptureState?,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)

    // 取「整行任一键上的那一个」：合并过来之前填的码可能落在链里别的键上。
    val currentName = station.displayName
    val currentSource = station.ruleKeys.firstNotNullOfOrNull { rules.identitySourceFor(it) }
        ?: IdentitySource.CAINIAO
    val currentAddress = station.ruleKeys.firstNotNullOfOrNull { rules.addressFor(it) }.orEmpty()
    val currentFingerprint = station.ruleKeys.firstNotNullOfOrNull { rules.fingerprintFor(it) }

    // 只有名称和身份码是「草稿 + 保存」；地址和指纹当场落库，不进草稿。
    var draftName by remember(station.key) { mutableStateOf(currentName) }
    var draftSource by remember(station.key) { mutableStateOf(currentSource) }
    val dirty = draftName != currentName || draftSource != currentSource

    val others = allStations.filter { it.key != station.key }

    StationScaffold("驿站详情", scrollBehavior, onBack) {
        GroupTitle("外显名称")
        SettingsCard {
            HintText("首页卡片的显示名，只改本机显示，不同步给菜鸟或快递公司。")
            DraftField(draftName) { draftName = it }
        }

        GroupTitle("默认身份码")
        SettingsCard {
            HintText(
                "这一站取件出示哪个平台的码（菜鸟叫「出库码」）。" +
                    "各平台互不通用，按驿站各选一次。",
            )
            OverlayDropdownPreference(
                title = "身份码来源",
                summary = if (draftSource.supported) {
                    "取件时出示${draftSource.displayName}的码"
                } else {
                    "可以选，但${draftSource.displayName}的码暂时取不到" +
                        "（它的签名在 native 层算，无法复刻）"
                },
                items = IDENTITY_SOURCE_OPTIONS.map { it.displayName },
                selectedIndex = IDENTITY_SOURCE_OPTIONS.indexOf(draftSource),
                onSelectedIndexChange = { draftSource = IDENTITY_SOURCE_OPTIONS[it] },
            )
        }

        GroupTitle("精确地址")
        SettingsCard {
            HintText(
                "在驿站门口点下面按钮，记录当前定位与附近 WiFi：地址显示在详情页，" +
                    "并用于「到站附近有件可取」提醒。人在别处点，记下的就是别处坐标。",
            )
            InfoRow("地址", currentAddress.ifBlank { "未记录" })
            InfoRow("定位", positionLabel(currentFingerprint))
            InfoRow("附近 WiFi", wifiLabel(currentFingerprint))
            capturedLabel(currentFingerprint)?.let { InfoRow("记录时间", it) }
            CardActionRow(
                label = if (capture?.busy == true) "正在获取…" else "获取当前位置",
                onClick = { if (capture?.busy != true) onCaptureLocation(station.ruleKeys) },
            )
            capture?.note?.let { HintText(it) }
        }

        // 保存 / 恢复默认合成一张卡片：两个输入框共用一个「保存」。
        if (dirty || station.renamed) {
            SettingsCard {
                if (dirty) {
                    CardActionRow("保存") {
                        onRename(station.ruleKeys, draftName)
                        onIdentitySource(station.ruleKeys, draftSource)
                    }
                }
                if (station.renamed) {
                    if (dirty) CardDivider()
                    CardActionRow("恢复默认", danger = true) { onRestore(station.ruleKeys) }
                }
            }
        }

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
                    // 永远停在「不合并」：这是动作不是状态，合并成功后界面回列表。
                    selectedIndex = 0,
                    onSelectedIndexChange = { index ->
                        if (index > 0) onRename(station.ruleKeys, others[index - 1].key)
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

@Composable
private fun DraftField(value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

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

// ---------------------------------------------------------------- 指纹的只读呈现

/** 坐标 5 位小数（约 1 米）；精度半径必须一起给，只印坐标会让用户以为记得很准。 */
private fun positionLabel(fingerprint: StationFingerprint?): String {
    val position = fingerprint?.position ?: return "未记录"
    val coordinates = "%.5f, %.5f".format(Locale.US, position.lat, position.lng)
    val accuracy = fingerprint.accuracyMeters?.takeIf { it >= 0f }
    return if (accuracy == null) coordinates else "$coordinates（±${accuracy.roundToInt()} 米）"
}

private fun wifiLabel(fingerprint: StationFingerprint?): String {
    val count = fingerprint?.wifi?.size ?: 0
    return if (count == 0) "未记录" else "已记录 $count 个"
}

private fun capturedLabel(fingerprint: StationFingerprint?): String? {
    val at = fingerprint?.capturedAt ?: return null
    if (at <= 0L) return null
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))
}

/** 选项即枚举声明顺序。取不到码的平台也在列：归属要先记下来，缺项会让用户以为选错了地方。 */
private val IDENTITY_SOURCE_OPTIONS: List<IdentitySource> = IdentitySource.values().toList()
