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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 界面小件——全模块唯一一套排版词汇（GroupTitle + SettingsCard + InfoRow + HintText +
 * CardActionRow）。硬规矩：主文本用 miuix Text 默认字号、小字只用 [SecondaryText]（12sp + 次要色），
 * 层级靠颜色和位置表达，不要在组件外自己写 fontSize；[GroupTitle] 不传 insideMargin（默认 28dp
 * 才与卡片内文字对齐）；miuix 的 Text 不吃 Markdown 星号，强调用词序和分行。
 */

/** 副文本 / 说明文字的字号。全模块只有这一个「小字」。 */
private val SECONDARY = 12.sp

/** 全模块下拉刷新共用的四段文案（miuix 默认英文，必须覆盖），别各页面自持一份。 */
internal val REFRESH_TEXTS = listOf("下拉刷新", "松手刷新", "正在刷新…", "刷新成功")

private val CARD_MARGIN = 12.dp

/** 分组标题。不要传 insideMargin：默认的 28dp 才是与卡片内文字对齐的那个值。 */
@Composable
internal fun GroupTitle(text: String) {
    SmallTitle(text = text)
}

/**
 * 设置 / 关于页的标准卡片，把 12dp 外边距收在一处（十几处里写漏一处，整屏就是「乱」）。
 * insideMargin 默认 0 是故意的：行内边距由各行组件自给（16dp），miuix 原生偏好行才能撑满整行。
 * 只有「卡片里不是标准行」的空状态类内容才传。
 */
@Composable
internal fun SettingsCard(
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = CARD_MARGIN)
            .padding(bottom = CARD_MARGIN),
        insideMargin = insideMargin,
        content = content,
    )
}

/**
 * 卡片里的一行只读信息。值短左右并排，值长（超 [LONG_VALUE_THRESHOLD]）改上下堆叠——
 * 并排会把左侧标签挤成一列竖排的字。按长度而不是测量结果判断，让布局在组合期就确定。
 */
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val color = valueColor ?: secondaryColor()
    if (value.length <= LONG_VALUE_THRESHOLD) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, color = color)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(label)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = SECONDARY, color = color)
        }
    }
}

private const val LONG_VALUE_THRESHOLD = 24

/** 分段选择（miuix 没有这个形状的组件）；取色全走主题，别自己挑灰阶——夜间主题下会糊。 */
@Composable
internal fun SegmentedRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) {
            val active = value == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(value) }
                    .background(
                        if (active) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        },
                    )
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = if (active) Color.White else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 一条记录 / 一个条目的标准卡片（自带 16dp 内边距），与 [SettingsCard] 不要互相替代——
 * 设置行有自己的行高，塞进这个骨架会被压扁。onClick 为 null 时走无点击反馈的重载：
 * 按下去有涟漪、松手什么也不发生，比不能点更让人困惑。
 */
@Composable
internal fun RecordCard(
    title: String,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = titleColor,
                fontWeight = FontWeight.Medium,
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (description != null) {
            Text(
                description,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                fontSize = SECONDARY,
                color = secondaryColor(),
            )
        }
        if (actions != null) {
            CardDivider()
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
    val modifier = Modifier
        .padding(horizontal = CARD_MARGIN)
        .padding(bottom = CARD_MARGIN)
    if (onClick != null) {
        Card(modifier = modifier, insideMargin = PaddingValues(16.dp), onClick = onClick, content = content)
    } else {
        Card(modifier = modifier, insideMargin = PaddingValues(16.dp), content = content)
    }
}

/** 全模块唯一的小字（12sp + 次要色）。需要小字就找它，别另起一种字号。 */
@Composable
internal fun SecondaryText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, modifier = modifier, fontSize = SECONDARY, color = color ?: secondaryColor())
}

/** 记录卡片右上角的时刻。 */
@Composable
internal fun RecordTime(text: String) {
    SecondaryText(text)
}

/**
 * 卡片里的动作行：整行可点、文字居中——右侧小按钮会跟卡片里其它行的左边距对不齐。
 * [danger] 破坏性动作用错误色，与普通动作区分开。
 */
@Composable
internal fun CardActionRow(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (danger) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
        )
    }
}

/**
 * 卡片内的说明文字。color 只在「这条说明是个错误」时传 error 色（字号不变，错误靠颜色表达）。
 * horizontalPadding 默认 16dp 与卡片行内边距对齐；弹窗里传 0.dp——弹窗自己有 24dp 内边距，再缩进会错位。
 */
@Composable
internal fun HintText(text: String, color: Color? = null, horizontalPadding: Dp = 16.dp) {
    SecondaryText(
        text = text,
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp),
        color = color,
    )
}

/** 卡片内的分隔线：0.5dp、outline 50% 透明，上下留 8dp。 */
@Composable
internal fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
}

/**
 * 相邻同类条目之间的分隔线：两侧留 16dp、不留上下空白，密排列表用——
 * 每行塞 8dp 空白会把十几条撑成一屏半。与 [CardDivider]（段落之间的空档）分工。
 */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
    )
}

@Composable
private fun secondaryColor() = MiuixTheme.colorScheme.onSurfaceVariantSummary
