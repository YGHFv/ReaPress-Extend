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

/**
 * 模块日志页 —— 关于页那一行「模块日志」点进来的**二级页面**。
 *
 * 为什么单独成页而不是摊在关于页里：日志一次几十上百行，直接排在关于页会把
 * 「模块状态 / 权限 / 看门狗」这些真正要一眼看到的信息全推到屏幕外。放在二级页里，
 * 关于页保持一屏读完，日志想翻多久翻多久。
 *
 * 版面**只用模块那套排版词汇**（见 `ExpressUiKit` 文件头）：`GroupTitle` + 一张卡片，
 * 卡片里每个条目 = 时刻行 + 正文行，条目之间用 [RowDivider]。没有为这一页新造字号或颜色。
 */
@Composable
internal fun LogPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    // 快照一次就够：这一页的寿命就是「看一眼」，不做实时刷新 ——
    // 每次重组都去读环形缓冲会让滚动中掉帧，而日志本来就是「现在这一刻的样子」。
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
                        // 顶栏左侧的返回不画胶囊底色：`IconButton` 默认铺一层
                        // secondaryContainer，在顶栏上会变成一个没由来的圆角块。
                        // 底色透明仍然保留它的按压反馈（miuix 自己的 indication）。
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
                        "暂无日志。日志先收在内存的环形缓冲里，模块进程重启后清空；" +
                            "「简洁日志」开着时，INFO / WARN 只进这里，不进 logcat。",
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

/**
 * 关于页那一行入口的摘要。
 *
 * 只给条数和错误数，不给「最近一条的时间」：这一行在 `ArrowPreference` 的副标题位置，
 * 要塞下「共 99 条，其中 12 条错误，最近 09-26 04:12:33.123」这种长度的写法就只能折行，
 * 而折行后的两行摘要会把整张卡片撑高，看着比一条还重。时间点开后第一行就是。
 */
internal fun logSummary(entries: List<ModuleLogEntry>): String {
    if (entries.isEmpty()) return "暂无日志"
    val errors = entries.count { it.level == LEVEL_ERROR }
    return buildString {
        append("共 ${entries.size} 条")
        if (errors > 0) append("，其中 $errors 条错误")
    }
}

/** 日志页的分组抬头：说清「列出来的是哪一段」。截断时要写出来，否则会以为日志丢了。 */
private fun logTitle(shown: List<ModuleLogEntry>, total: Int): String = buildString {
    append("最近 ${shown.size} 条")
    if (total > shown.size) append("（共 $total 条）")
    val errors = shown.count { it.level == LEVEL_ERROR }
    if (errors > 0) append(" · $errors 条错误")
}

/**
 * 一条日志：上行「级别（左）· 时刻（右）」，下行正文。
 *
 * 正文**不截断、不限行数**：日志的原因往往在后半段（异常类名、失败理由跟在前面那句后面），
 * 截成一行等于把最有用的部分吃掉。这一页就是拿来细看的，长一点没关系。
 *
 * 级别不另设徽章 / 底色：这一页的正文本来就长，再加色块会淹掉正文。
 * 级别只改**这一小段文字的颜色**（错误红、警告正常色、其余次要色），扫一列时足够分辨。
 */
@Composable
private fun LogRow(entry: ModuleLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // 级别靠左、时刻靠右：时刻是等宽的固定串，左对齐时每行的正文起点会被
        // 「ERROR」和「INFO」的宽度差推来推去，整列看着是锯齿。挤到右边当标尺，
        // 正文起点就统一了。
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

/**
 * 级别的颜色。miuix 的主题色里只有一个语义色（`error`），所以分级靠**明暗**而不是色相：
 * 错误用红；警告用正文色（比 INFO 的次要色重，眼睛能停在上面）；其余次要色。
 */
@Composable
private fun levelColor(level: String) = when (level) {
    LEVEL_ERROR -> MiuixTheme.colorScheme.error
    LEVEL_WARN -> MiuixTheme.colorScheme.onSurface
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

/**
 * 只留到秒。日志缓冲里的时间戳带毫秒（落盘那份用得上），但列表里三条挤在一起的毫秒
 * 是纯噪音，还把这行的宽度全占了。
 */
private fun clockLabel(at: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(at))

/**
 * 单页最多列出的条数。
 *
 * 缓冲里最多 500 条，但这一页是一次性组合出来的（没有用 `LazyColumn`：卡片的内边距和
 * 圆角要包住整列，改成懒加载就得放弃「一张卡包住全部条目」这个形状）。
 * 500 行 × 两行文字一次性组合会明显掉帧，而真正会有人读的也就是最近这一屏多一点。
 */
private const val MAX_LOG_ROWS = 200
