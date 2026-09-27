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
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 「归档快递」二级页：[ExpressHomeGrouper.archive] 挑出的物流签收后按归档设置过期的包裹，
 * 加上长期无动静的未知件；时间倒序平铺、不分档也不按驿站聚合。
 * 不删除任何记录 —— 归档只是换一页显示，仍可搜索、可在详情页看全轨迹。
 *
 * @param records 全量记录而非归档子集：驿站名聚类必须拿完整集合做，只喂归档件会让同一驿站在两页印出两个名字。
 */
@Composable
internal fun ExpressArchivePage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    onBack: () -> Unit,
    onOpenDetail: ((ExpressRecord) -> Unit)? = null,
    archiveRetentionMs: Long = ExpressHomeGrouper.ARCHIVE_RETENTION_MS,
) {
    val scrollBehavior = MiuixScrollBehavior()

    BackHandler(onBack = onBack)

    // 与首页同一个口径取 now：首页 group 与归档必须用同一时刻切分。
    val now = System.currentTimeMillis()
    val archived = ExpressHomeGrouper.archive(records, now, archiveRetentionMs)
    val stationLabels = ExpressHomeGrouper.stationLabels(records, rules)

    Scaffold(
        topBar = {
            TopAppBar(
                title = "归档快递",
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
            if (archived.isEmpty()) {
                GroupTitle("归档快递")
                SettingsCard {
                    HintText(
                        if (archiveRetentionMs == 0L) {
                            "暂无归档。物流签收后的包裹会自动移到这里，首页就不再显示它们。"
                        } else {
                            "暂无归档。物流签收超过 7 天的包裹会自动移到这里，首页就不再显示它们。"
                        },
                    )
                }
            } else {
                GroupTitle("归档快递 · ${archived.size}件")
                for (record in archived) {
                    ParcelCard(
                        record = record,
                        stationLabel = ExpressHomeGrouper.stationLabelOf(record, stationLabels),
                        rules = rules,
                        now = now,
                        // 归档件不支持双击撤销「已取件」：几个月前的记录不再有撤销语义，留着只会多出隐藏双击区。
                        onTogglePickup = null,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}
