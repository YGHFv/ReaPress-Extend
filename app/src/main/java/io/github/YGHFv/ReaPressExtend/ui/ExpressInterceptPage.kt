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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「拦截记录」二级页（设置 → 通知拦截 → 拦截记录）。
 *
 * ## 它记的是什么
 *
 * 被「通知拦截」那组开关**吞掉**的原通知（[ExpressNotificationLog.Kind.INTERCEPTED]）。
 * 这些通知按设计不产生任何提醒 —— 页面存在的意义是让用户能**事后核对**：
 * 「我勾了运输动态，到底拦掉了哪些」。
 *
 * 之前它们是**纯静默**的：模块侧一行记录都没有，唯一判据是 LSPosed 日志里的
 * `EXPRESS DROPPED`（2026-09-27 用户要求补上）。
 *
 * ## 为什么与「记录」那一栏分开
 *
 * 那一栏是「模块**发出去**的通知有没有真的发出」（投递审计，`delivered` 有意义）；
 * 这里是「被我**吞掉**的通知」（主动行为，没有投递可言）。两页的渲染与文案都不同，
 * 混在一页里会让统计和措辞互相打架。
 *
 * @param onOpen 点开某一条（看原文 + 跳原通知）。二级页状态由 `ExpressApp` 管，
 *   这里只把「点了哪一条」报上去。
 */
@Composable
internal fun ExpressInterceptPage(
    entries: List<ExpressNotificationLog.Entry>,
    onBack: () -> Unit,
    onOpen: (ExpressNotificationLog.Entry) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "拦截记录",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 与日志页同一条规矩：顶栏上的图标按钮不铺胶囊底色。
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
            if (entries.isEmpty()) {
                GroupTitle("拦截记录")
                RecordCard(
                    title = "暂无记录",
                    description = "在设置里勾上「拦截…」的分类之后，被吞掉的通知会记在这里。" +
                        "到站取件、投递异常与含取件码的通知始终保留，不会出现在这里。",
                )
                Spacer(Modifier.height(padding.calculateBottomPadding()))
                Spacer(Modifier.height(4.dp))
                return@Column
            }

            GroupTitle("拦截记录 · ${entries.size} 条")

            // 与「记录」页同一个上限（只画最近 30 条）：再多也没有翻的必要，
            // 存储本身另有总上限（ExpressNotificationLog.MAX_RECORDS）。
            entries.take(30).forEach { entry ->
                RecordCard(
                    title = "${ExpressSettingsSnapshot.displayName(entry.sourcePackage)} · " +
                        entry.originTitle.ifBlank { entry.title },
                    description = buildString {
                        // 先说「为什么被拦」，再说内容 —— 用户点进来首先想知道的就是这个。
                        append(categoryLabel(entry.category))
                        val origin = entry.originText
                            .lineSequence()
                            .filter { it.isNotBlank() && it.trim() != entry.originTitle.trim() }
                            .joinToString(" ")
                        if (origin.isNotBlank()) {
                            append(" · ")
                            append(origin)
                        }
                    },
                    // 时间到秒（不复用记录页那个「只到时分」的 clockLabel）：这一页是核对用的，
                    // 「拦在哪一刻」本身就是信息，而卡片数量少、宽度够。
                    trailing = { RecordTime(ExpressNotificationLog.formatTime(entry.at)) },
                    onClick = { onOpen(entry) },
                )
            }

            HintText("这一页只记录被拦下的通知，不会产生任何提醒。点开可以看到原文并跳回原应用。")

            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 分类的显示名。
 *
 * 记录里存的是**枚举名**（见 `ExpressNotificationLog.recordIntercepted` 的说明），这里转成中文。
 * 认不出时不编一个默认值，直接把原始名字显示出来 —— 那至少能说明「存了个我不认识的东西」，
 * 而假装它是「其他提醒」会把一个旧版本残留的脏数据伪装成正常数据。
 */
private fun categoryLabel(name: String): String {
    if (name.isBlank()) return "已拦截"
    val display = NotificationCategory.byName(name)?.displayName ?: name
    return "已拦截 · $display"
}
