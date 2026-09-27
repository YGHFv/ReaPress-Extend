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
 * 「拦截记录」二级页：被「通知拦截」开关吞掉的原通知（[ExpressNotificationLog.Kind.INTERCEPTED]），
 * 按设计不产生任何提醒，页面用于事后核对「勾了某分类到底拦掉了哪些」。
 * 与「记录」栏分开：那边是模块发出通知的投递审计，这边是主动吞掉、没有投递可言。
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

            // 只画最近 30 条，与「记录」页同一上限；存储本身另有总上限。
            entries.take(30).forEach { entry ->
                RecordCard(
                    title = "${ExpressSettingsSnapshot.displayName(entry.sourcePackage)} · " +
                        entry.originTitle.ifBlank { entry.title },
                    description = buildString {
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
                    // 时间到秒、不复用记录页的 clockLabel：核对场景下「拦在哪一刻」本身就是信息。
                    trailing = { RecordTime(ExpressNotificationLog.formatTime(entry.at)) },
                    onClick = { onOpen(entry) },
                )
            }

            HintText("本页只记录被拦的通知，不产生提醒；点开可看原文并跳回原应用。")

            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 分类显示名：记录里存的是枚举名，这里转成中文；认不出时显示原始名字而不伪装成「其他提醒」，避免把旧版本残留的脏数据伪装成正常数据。 */
private fun categoryLabel(name: String): String {
    if (name.isBlank()) return "已拦截"
    val display = NotificationCategory.byName(name)?.displayName ?: name
    return "已拦截 · $display"
}
