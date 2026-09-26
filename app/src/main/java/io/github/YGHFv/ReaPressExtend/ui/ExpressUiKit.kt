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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 界面小件 —— **全模块唯一一套排版词汇**。
 *
 * ## 页面结构（每页都长这样）
 *
 * ```
 * GroupTitle("分组")        // miuix SmallTitle
 * SettingsCard {            // miuix Card，水平 12dp / 底部 12dp
 *     SwitchPreference(...) // miuix 自带的偏好行（开关 / 下拉）
 *     InfoRow(...)          // 本文件：只读的「标签 — 值」
 *     HintText(...)         // 本文件：一句说明
 *     CardActionRow(...)    // 本文件：卡片里的动作
 * }
 * ```
 *
 * ## 三条硬规矩
 *
 * 1. **只有两级字号**：主文本一律用 miuix [Text] 的默认字号（不写 `fontSize`），
 *    副文本 / 说明一律走 [SecondaryText]（12sp + 次要色）。
 *    之前设置的 11/13/14/17sp 混用是「文本乱七八糟」的主因 —— 层级靠**颜色和位置**表达，
 *    不靠字号微调。**不要在组件外自己写 `fontSize`**，那是把这条规矩又拆开一次。
 * 2. **对齐**：卡片水平外边距 12dp + 行内边距 16dp = 28dp，正好等于 miuix [SmallTitle]
 *    的默认水平内边距。所以 [GroupTitle] **不传 insideMargin**，传了就会错位。
 * 3. **不写 Markdown**：miuix 的 [Text] 不吃 `**粗体**` 这类标记，星号会原样显示出来。
 *    强调用词序和分行，不用符号。
 */

/** 副文本 / 说明文字的字号。全模块只有这一个「小字」。 */
private val SECONDARY = 12.sp

/**
 * 刷新指示器的四段文案（下拉 / 松手 / 刷新中 / 完成）。miuix 默认是英文，必须覆盖。
 *
 * 放在 [ExpressUiKit] 而不是各页面自持一份：这是「模块里所有下拉刷新共用的一套话」，
 * 分开写迟早有一处被改得跟别处不一样。
 */
internal val REFRESH_TEXTS = listOf("下拉刷新", "松手刷新", "正在刷新…", "刷新成功")

/** 卡片水平外边距。与 [GroupTitle] 的对齐关系见文件头。 */
private val CARD_MARGIN = 12.dp

/** 分组标题。**不要传 insideMargin**：默认的 28dp 才是与卡片内文字对齐的那个值。 */
@Composable
internal fun GroupTitle(text: String) {
    SmallTitle(text = text)
}

/**
 * 设置 / 关于页的标准卡片。
 *
 * 存在的意义是**把 12dp + 12dp 这套边距收在一处**：以前每个分组都手写一遍
 * `Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
 * insideMargin = PaddingValues(0.dp))`，十几处里只要有一处写漏，那一组卡片就会比别的
 * 宽一点或贴得紧一点 —— 单看没问题，一屏扫下来就是「乱」。
 *
 * `insideMargin` 默认 `0` 是**故意的**：行内边距由各行的组件自己给（16dp），卡片只负责外框。
 * 这样 [SwitchPreference] 这类 miuix 原生偏好行才能撑满整行、按下反馈铺满卡片宽度。
 *
 * @param insideMargin 只有「卡片里不是标准行」时（空状态那种整块居中的内容）才传，
 *   传了就要自己保证与 16dp 的行内边距是一路的，别再引入第三套数值。
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
 * 卡片里的一行只读信息：左侧标签，右侧值。
 *
 * 值短时（「已授予」「开启」）左右并排；值长时（设置摘要那种一整句话）改成上下堆叠 ——
 * 并排会把左侧标签挤成一列竖排的字（实测「设置摘要」被压成四行）。
 * 用长度而不是测量结果判断，是为了让布局在组合期就确定，不必等一帧。
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

/** 超过这个长度就认为「一句话」而不是「一个值」，改走上下堆叠。 */
private const val LONG_VALUE_THRESHOLD = 24

/**
 * 分段选择（模式、阈值这种三选一）。
 *
 * miuix 没有这个形状的组件（它只有 TabRow，那是给页面切换用的），所以自己画一个，
 * 但**取色全部走主题**：选中 `primary` + 白字，未选中 `secondaryContainer`。
 * 不自己挑灰阶 —— 那样夜间主题下就糊了。
 */
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
 * 一条记录 / 一个条目的标准卡片（记录页用）。
 *
 * 与 [SettingsCard] 的分工：那个是「若干设置行拼成的组」，这个是「一条独立的东西」——
 * 自带 16dp 内边距，标题行右侧放时间。**两者不要互相替代**：设置行有自己的行高，
 * 塞进这个骨架会被压扁。
 */
@Composable
internal fun RecordCard(
    title: String,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = CARD_MARGIN)
            .padding(bottom = CARD_MARGIN),
        insideMargin = PaddingValues(16.dp),
    ) {
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
}

/**
 * 副文本。**全模块唯一的一种小字**（12sp + 次要色），不要再另起一种字号。
 *
 * 单独成组件而不是各处自己写 `Text(fontSize = 12.sp, ...)`：字号一旦散落开，
 * 「只有两级字号」这条规矩就守不住了 —— 之前设置 / 关于页的乱正是这么来的
 * （11/12/13/17sp 各写各的）。需要小字就找它。
 */
@Composable
internal fun SecondaryText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(text, modifier = modifier, fontSize = SECONDARY, color = color ?: secondaryColor())
}

/** 记录卡片右上角的时刻。尺寸与其它副文本一致，只是位置在标题行。 */
@Composable
internal fun RecordTime(text: String) {
    SecondaryText(text)
}

/**
 * 卡片里的动作行：整行可点、文字居中。
 *
 * 不放在右侧做成小按钮：miuix 的偏好行动作是**整行**的，右侧小按钮会跟卡片里其它
 * 行的左边距对不齐，看着像临时拼上去的。
 *
 * @param danger 破坏性动作（删数据、复位 hook 这类）用错误色，与普通动作区分开
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
 * 卡片内的说明文字。**全模块唯一的一种小字**，不要再另起一种。
 *
 * @param color 只有「这条说明是个错误」（看门狗失败原因、投递失败原因）才传，
 *   传 [MiuixTheme.colorScheme.error]。字号不变 —— 错误靠颜色表达，不靠字号。
 */
@Composable
internal fun HintText(text: String, color: Color? = null) {
    SecondaryText(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = color,
    )
}

/** 卡片里的分隔线：0.5dp、outline 50% 透明，上下留 8dp。 */
@Composable
internal fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
}

/**
 * 卡片内**相邻同类条目**之间的分隔线：0.5dp、两侧留 16dp（与行内边距对齐），**不留上下空白**。
 *
 * 与 [CardDivider] 的分工：那个是「一行一段话」之间的空档，自带 8dp 留白；这个用在
 * 「同一张卡里连续排十来个条目」的场合（驿站下的多件包裹、日志列表）——
 * 每行都塞 8dp 空白的话，十几条会被撑成一屏半，扫读时反而更乱。
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
