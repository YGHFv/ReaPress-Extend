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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.core.NotificationIntentSnapshot
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.NotificationIntentLauncher
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
 * 一条通知记录的详情（「记录」页与「拦截记录」页共用）。
 *
 * ## 为什么要它
 *
 * 列表里那张卡片只印两行字：模块**识别之后**的标题与正文。于是「这条为什么被判成快递了」
 * 在界面上永远无从核对 —— 识别结果的措辞已经把线索洗掉了（取件码被拆成「取件码：…」、
 * 驿站名被截断、广告话术被删）。判错的现场只能靠原文复现。
 *
 * 所以这一页分上下两段：**原文**（用户本来会在通知栏看到的那条）与**识别结果**
 * （模块替换后的那两行），摆在一起对照。
 *
 * ## 两类记录共用这一页（[ExpressNotificationLog.Kind]）
 *
 * - [ExpressNotificationLog.Kind.DELIVERED]：模块发出去的那条 —— 这段结尾是「发出去了没有」；
 * - [ExpressNotificationLog.Kind.INTERCEPTED]：按设置被吞掉的那条 —— 结尾是「属于哪一类」，
 *   而**没有**投递可言（说它「未发出」是把主动行为说成失败）。
 *
 * ## 「打开原通知」（2026-09-27 修订）
 *
 * 拦截模式下原通知是被吞掉的，用户手里只剩模块这一条 —— 点开进的是本模块。想回到
 * 宿主那个具体页面（某个包裹的取件码页、订单详情）就得自己去 App 里翻。
 * 这里揣着原通知的点击跳转，一点就等价于点原通知。
 *
 * **两条路**（见 [NotificationIntentLauncher]）：内存里的 `PendingIntent` 令牌（最忠实）→
 * 记录里存的快照串（基础参数级）。所以模块进程重启后按钮**不再消失** ——
 * 之前只有令牌那一条路，重启就没了，界面上只能如实说「重启后失效」。
 */
@Composable
internal fun NotificationDetailPage(
    entry: ExpressNotificationLog.Entry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    // 取一次就够：这条记录在页面存续期间不会变，而读取本身是个 LRU 表查询 + 一次字符串判空。
    val canOpen = remember(entry) { NotificationIntentLauncher.canOpen(entry) }
    val targetApp = remember(entry) { targetLabel(entry) }
    var openError by remember(entry) { mutableStateOf<String?>(null) }

    val intercepted = entry.kind == ExpressNotificationLog.Kind.INTERCEPTED

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (intercepted) "拦截记录" else "通知记录",
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
            // ---------------------------------------------------------- 原文
            GroupTitle("通知原文")
            SettingsCard {
                InfoRow("来源", ExpressSettingsSnapshot.displayName(entry.sourcePackage))
                InfoRow("时间", ExpressNotificationLog.formatTime(entry.at))
                RowDivider()
                // 原文一行都不截：这一页的全部意义就是「原样看一遍」。
                entry.originTitle.takeIf { it.isNotBlank() }?.let { ParagraphText(it) }
                if (entry.originText.isNotBlank()) {
                    // 正文与标题往往有一句重复（`extractFullText` 是 title + subText + body 拼的），
                    // 重复的那段不排 —— 一屏里出现两遍同样的句子像是界面出了错。
                    ParagraphText(
                        entry.originText
                            .lineSequence()
                            .filter { it.isNotBlank() && it.trim() != entry.originTitle.trim() }
                            .joinToString("\n"),
                    )
                }
                if (entry.originTitle.isBlank() && entry.originText.isBlank()) {
                    HintText("这条记录里没有留下原文（模块升级前落盘的旧记录）。")
                }
                if (canOpen) {
                    CardDivider()
                    // 动作行只能走 CardActionRow（自写 Row 漏 clickable 是编译期看不出来的坑）。
                    // 认得出目标包名就带上它 —— 「打开原通知」不说到哪儿去，而这个按钮的
                    // 全部意义就是「回到那条通知本该去的页面」。见 targetLabel。
                    val label = if (intercepted) "打开被拦截的通知" else "打开原通知"
                    CardActionRow(label + (targetApp?.let { "（$it）" } ?: "")) {
                        openError = NotificationIntentLauncher.open(context, entry)
                    }
                }
            }
            if (!canOpen) {
                HintText("这条通知没有可用的跳转信息（原文里没有点击动作，或发送端是旧版本）。")
            }
            openError?.let { HintText("打不开原通知：$it", color = MiuixTheme.colorScheme.error) }

            // ---------------------------------------------------------- 识别结果
            GroupTitle("识别结果")
            SettingsCard {
                InfoRow("通知标题", entry.title)
                RowDivider()
                ParagraphText(entry.detail)
                if (intercepted) {
                    RowDivider()
                    // 「为什么它被吞了」的答案就是这个分类 —— 与设置页里那个开关同名。
                    InfoRow("拦截分类", categoryName(entry.category))
                } else {
                    RowDivider()
                    InfoRow(
                        "投递",
                        if (entry.delivered) {
                            "已发出"
                        } else {
                            "未发出 · ${entry.failureDetail.ifBlank { "未知原因" }}"
                        },
                        if (entry.delivered) null else MiuixTheme.colorScheme.error,
                    )
                }
            }
            HintText(
                if (intercepted) {
                    "这条通知按你的「通知拦截」设置被吞掉了，不会出现在通知栏，也不会被模块重发。"
                } else {
                    "这两行就是模块替换后的通知内容，也就是首页卡片与通知栏上显示的那份。"
                },
            )

            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 这条通知原本要跳到哪个应用，用来给动作行补一句「（菜鸟）」。
 *
 * 判据是记录里那份**快照串**（`Intent.toUri` 的输出），包名从里面纯字符串抠出来
 * （[NotificationIntentSnapshot.packageOf]，core 层有单测钉着）。
 * 两条「不写」的规则都是刻意的：
 * - 只揣着内存令牌、没有快照串的记录（老记录）→ 拿不到包名，不写；
 * - 认不出的包名（不在已知来源表里）→ 也不写，把 `com.foo.bar` 这种串贴进按钮没有信息量。
 */
private fun targetLabel(entry: ExpressNotificationLog.Entry): String? {
    val pkg = NotificationIntentSnapshot.packageOf(entry.intentUri) ?: return null
    val display = ExpressSettingsSnapshot.displayName(pkg)
    return display.takeIf { it != pkg }
}

/**
 * 分类的显示名。存的是枚举名（见 `ExpressNotificationLog.recordIntercepted`），
 * 认不出时**原样显示**而不是编一个默认值 —— 把旧版本残留的脏数据伪装成正常的「其他提醒」
 * 会让人以为拦截逻辑工作正常。
 */
private fun categoryName(name: String): String =
    name.takeIf { it.isNotBlank() }?.let { NotificationCategory.byName(it)?.displayName ?: it }
        ?: "未知"

/**
 * 一段可以换行的整段文字。
 *
 * 不走 [InfoRow]：那个是「标签 — 值」两栏结构，长文本会被挤在右栏里一行行折，
 * 一屏读下来左边一大片空白。原文与正文本来就是**整段的**东西，占整行宽才对。
 */
@Composable
private fun ParagraphText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
