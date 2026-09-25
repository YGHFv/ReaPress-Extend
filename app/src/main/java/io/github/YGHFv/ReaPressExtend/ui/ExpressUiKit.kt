package io.github.YGHFv.ReaPressExtend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 界面小件。
 *
 * 独立成一个文件而不是塞进 Activity：主界面有多页（快递 / 记录 / 设置 / 关于），
 * 这些行组件各页共用。
 */

/** 分组标题。SmallTitle 默认水平内边距 28dp，与卡片的 12dp 凑一起会明显错位，这里统一收窄。 */
@Composable
internal fun GroupTitle(text: String) {
    SmallTitle(
        text = text,
        insideMargin = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
    )
}

/**
 * 卡片里的一行：左侧标签，右侧值。
 *
 * 值短时（「已授予」「开启」）左右并排；值长时（设置摘要那种一整句话）**改为上下堆叠** ——
 * 并排会把左侧标签挤成一列竖排的字（实测「设置摘要」被压成四行），既难看又难读。
 * 用长度而不是测量结果来判断，是为了让布局在组合期就确定，不必等一帧。
 */
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val color = valueColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
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
            Text(value, fontSize = 13.sp, color = color)
        }
    }
}

/** 超过这个长度就认为「一句话」而不是「一个值」，改走上下堆叠。 */
private const val LONG_VALUE_THRESHOLD = 24

/** 带开关的一行。整行可点（不只是开关本体），符合 miuix 的交互惯例。 */
@Composable
internal fun SwitchRow(
    label: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/**
 * 可点的一行（用于导航、展开）。
 */
@Composable
internal fun ClickableRow(
    label: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 分段选择（模式切换用）。miuix 的 TabRow 用于页面切换，这个用于小范围二选一。 */
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
                    .background(
                        if (active) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        },
                    )
                    .clickable { onSelect(value) }
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

/** 只读的多行文本块（诊断信息、日志条目）。 */
@Composable
internal fun MonospaceBlock(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        fontSize = 12.sp,
        maxLines = maxLines,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 卡片包裹一个内容块，统一内边距。 */
@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** 卡片里的分隔线：0.5dp、outline 50% 透明。 */
@Composable
internal fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        thickness = 0.5.dp,
        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
}

/**
 * KSU 骨架卡片：标题行（左标题 / 右 trailing）+ 可选副信息 + 描述 + 可选操作行。
 *
 * 与上面的 [SectionCard] 并存：那个是「一个卡片里塞任意内容」（设置页用），
 * 这个是「一条记录/一个条目」的标准骨架（记录页、任务页用）。
 * 两者不要互相替代 —— 设置页的开关行有自己的行高与内边距，套这个骨架会把行距压扁。
 */
@Composable
internal fun RecordCard(
    title: String,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String? = null,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        // KSU 的卡片自带 12dp 水平边距，页面不用再给。
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = onClick != null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight(550), color = titleColor)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
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
                    .padding(top = 2.dp),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (actions != null) {
            CardDivider()
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** 记录卡片右上角的时刻。走标题行而不是副行：纵向一眼对齐，横向不占额外高度。 */
@Composable
internal fun RecordTime(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight(550),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 卡片里的次要动作按钮。 */
@Composable
internal fun CardActionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(text = label, onClick = onClick)
    }
}

/** 卡片内的说明文字。比正文小一号、用次要色，用来解释「这个开关是干什么的」。 */
@Composable
internal fun HintText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 关于页/诊断页里的分条说明。行距比 [HintText] 略紧，因为是并列条目而非一段话。 */
@Composable
internal fun DiagnosticNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
