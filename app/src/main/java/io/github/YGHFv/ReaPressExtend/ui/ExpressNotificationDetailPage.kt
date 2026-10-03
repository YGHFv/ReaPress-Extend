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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.YGHFv.ReaPressExtend.notification.IntentTokenFetcher
import io.github.YGHFv.ReaPressExtend.notification.NotificationIntentLauncher
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
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
 * 一条通知记录的详情（记录页与拦截记录页共用）：「通知原文」与「识别结果」上下对照——
 * 识别措辞已把线索洗掉（取件码被拆行、驿站名被截断），判错现场只能靠原文复现。
 * DELIVERED 结尾是「发出去了没有」；INTERCEPTED 结尾是拦截分类，没有投递可言。
 * 打开原通知见 [NotificationIntentLauncher]：令牌（内存 / 从 system_server 取回，页面打开时
 * [IntentTokenFetcher] 预热）走 send()，重启后只剩快照能打开宿主 App。
 */
@Composable
internal fun NotificationDetailPage(
    entry: ExpressNotificationLog.Entry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    // 令牌是异步取回的，这两个判断不能只在进场时算一次：到货信号 +1，remember 跟着重算。
    var tokenVersion by remember(entry) { mutableIntStateOf(0) }
    val canOpen = remember(entry, tokenVersion) { NotificationIntentLauncher.canOpen(entry) }
    val faithful = remember(entry, tokenVersion) { NotificationIntentLauncher.isFaithful(entry) }
    val targetApp = remember(entry) { targetLabel(entry) }
    var openError by remember(entry) { mutableStateOf<String?>(null) }

    // 进页就预热令牌：点击那条路要同步返回结果，跨进程往返做不到同步。
    LaunchedEffect(entry) { IntentTokenFetcher.request(context, entry) }

    DisposableEffect(entry) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                tokenVersion++
            }
        }
        registerTokenReadyReceiver(context, receiver)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val intercepted = entry.kind == ExpressNotificationLog.Kind.INTERCEPTED

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (intercepted) "拦截记录" else "通知记录",
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 顶栏上的图标按钮不铺胶囊底色。
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
            GroupTitle("通知原文")
            SettingsCard {
                InfoRow("来源", ExpressSettingsSnapshot.displayName(entry.sourcePackage))
                InfoRow("时间", ExpressNotificationLog.formatTime(entry.at))
                RowDivider()
                entry.originTitle.takeIf { it.isNotBlank() }?.let { ParagraphText(it) }
                if (entry.originText.isNotBlank()) {
                    // 正文与标题常有一句重复（extractFullText 是拼接的），重复那段不排。
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
                    CardActionRow(actionLabel(intercepted, faithful, targetApp)) {
                        openError = NotificationIntentLauncher.open(context, entry)
                    }
                }
            }
            if (canOpen && !faithful) {
                HintText(
                    "跳转令牌只在本次开机内有效，重启后失效。" +
                        "因此这里只能打开${targetApp ?: "对应应用"}；令牌还在时能直接回到那条通知。",
                )
            }
            if (!canOpen) {
                HintText(
                    "该记录未留跳转信息：旧版本落盘时读不到跳转，或该通知本身无点击动作。" +
                        "新通知不受影响。",
                )
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
                    "该通知被「通知拦截」设置吞掉：不出现在通知栏，也不会重发。"
                } else {
                    "这两行是模块替换后的通知内容，即首页卡片与通知栏显示的那份。"
                },
            )

            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 这条通知属于哪个应用。判据与 [NotificationIntentLauncher] 实际打开的目标同源
 * （[NotificationIntentSnapshot.targetPackageOf]），不能各算一遍，否则按钮写着菜鸟打开的是别的。
 * 包名认不出就不写——把 com.foo.bar 贴进按钮没有信息量。
 */
private fun targetLabel(entry: ExpressNotificationLog.Entry): String? {
    val pkg = NotificationIntentSnapshot.targetPackageOf(entry.sourcePackage, entry.intentUri)
        ?: return null
    val display = ExpressSettingsSnapshot.displayName(pkg)
    return display.takeIf { it != pkg }
}

/** 动作行措辞跟着「这次真能做到什么」走：有令牌才能叫「打开原通知」，只剩快照就写「打开某 App」。 */
private fun actionLabel(intercepted: Boolean, faithful: Boolean, targetApp: String?): String {
    val base = if (intercepted) "打开被拦截的通知" else "打开原通知"
    return when {
        faithful -> targetApp?.let { "$base（$it）" } ?: base
        targetApp != null -> "打开$targetApp"
        else -> base
    }
}

/** 分类的显示名。认不出时原样显示——把旧版本残留的脏数据伪装成「其他提醒」会让人以为拦截正常。 */
private fun categoryName(name: String): String =
    name.takeIf { it.isNotBlank() }?.let { NotificationCategory.byName(it)?.displayName ?: it }
        ?: "未知"

/**
 * 注册「令牌到货」接收器。广播是自己 setPackage 发给自己的，用 RECEIVER_NOT_EXPORTED；
 * API 33 起不传 flag 直接抛 SecurityException，必须显式分支。
 */
private fun registerTokenReadyReceiver(context: Context, receiver: BroadcastReceiver) {
    val filter = IntentFilter(ExpressRelay.ACTION_INTENT_TOKEN_READY)
    if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
}

/** 一段可以换行的整段文字，占整行宽——InfoRow 的两栏结构会把长文本挤在右栏一行行折。 */
@Composable
private fun ParagraphText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
