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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogEntry
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 模块日志二级页：单独成页让关于页保持一屏读完；只用模块那套排版词汇，没有为这一页新造字号或颜色。 */
@Composable
internal fun LogPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    // 快照一次不做实时刷新：每次重组读环形缓冲会让滚动掉帧。
    val logs = remember { ModuleLogBuffer.snapshot() }
    val shown = remember { logs.take(MAX_LOG_ROWS) }

    // 系统返回键等同于顶栏的返回箭头。
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "模块日志",
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
            if (shown.isEmpty()) {
                GroupTitle("模块日志")
                SettingsCard {
                    HintText(
                        "暂无日志。日志存于内存环形缓冲，进程重启后清空；" +
                            "「简洁日志」开启时 INFO/WARN 只进这里。",
                    )
                }
            } else {
                GroupTitle(logTitle(shown, logs.size))
                SettingsCard {
                    shown.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        LogRow(entry)
                    }
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 关于页入口的摘要；只给条数和错误数（位置只容得下一行）。 */
internal fun logSummary(entries: List<ModuleLogEntry>): String {
    if (entries.isEmpty()) return "暂无日志"
    val errors = entries.count { it.level == LEVEL_ERROR }
    return buildString {
        append("共 ${entries.size} 条")
        if (errors > 0) append("，其中 $errors 条错误")
    }
}

/** 日志页的分组抬头：截断时要写出来，否则会以为日志丢了。 */
private fun logTitle(shown: List<ModuleLogEntry>, total: Int): String = buildString {
    append("最近 ${shown.size} 条")
    if (total > shown.size) append("（共 $total 条）")
    val errors = shown.count { it.level == LEVEL_ERROR }
    if (errors > 0) append(" · $errors 条错误")
}

/** 一条日志：上行「级别（左）· 时刻（右）」，下行正文；正文不截断 —— 日志原因常在后半段。 */
@Composable
private fun LogRow(entry: ModuleLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryText(entry.level, color = levelColor(entry.level))
            Spacer(Modifier.weight(1f))
            SecondaryText(clockLabel(entry.at))
        }
        Spacer(Modifier.height(3.dp))
        Text(entry.message)
    }
}

private const val LEVEL_ERROR = "ERROR"
private const val LEVEL_WARN = "WARN"

/** 级别的颜色：miuix 主题只有一个语义色，分级靠明暗而不是色相。 */
@Composable
private fun levelColor(level: String) = when (level) {
    LEVEL_ERROR -> MiuixTheme.colorScheme.error
    LEVEL_WARN -> MiuixTheme.colorScheme.onSurface
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

private fun clockLabel(at: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(at))

/** 单页上限：一次性组合（不用 LazyColumn 为保「一张卡包住全部条目」），500 行 × 两行文字会明显掉帧。 */
private const val MAX_LOG_ROWS = 200
