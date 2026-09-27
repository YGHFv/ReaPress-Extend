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
 * **三条来路、两条执行路**（见 [NotificationIntentLauncher]）：内存令牌 / 从 system_server
 * 取回的令牌（两者都落进同一张表，`send()` 出去）→ 记录里那份快照串（有损，但永久有效）。
 * 所以模块进程被回收之后按钮**不再退化**：令牌寄存在跟设备同寿的 system_server 里
 * （[IntentTokenFetcher] 在页面打开时取回），上限是**设备本次开机**；真到了重启之后，
 * 也还有快照那条路能打开宿主 App。
 */
@Composable
internal fun NotificationDetailPage(
    entry: ExpressNotificationLog.Entry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    // 令牌是**异步**取回来的（要过 system_server 一趟），所以这两个判断不能只在进场时算一次：
    // 到货信号一来就 +1，下面两个 remember 跟着重算，措辞与说明文字一起切到「能回原页面」。
    var tokenVersion by remember(entry) { mutableStateOf(0) }
    val canOpen = remember(entry, tokenVersion) { NotificationIntentLauncher.canOpen(entry) }
    // 令牌还在内存里 = 这次点击能真正回到那条通知的页面；只剩快照 = 只能打开宿主 App。
    val faithful = remember(entry, tokenVersion) { NotificationIntentLauncher.isFaithful(entry) }
    val targetApp = remember(entry) { targetLabel(entry) }
    var openError by remember(entry) { mutableStateOf<String?>(null) }

    // 进这一页就悄悄把令牌从 system_server 取回来（还没拿到的话）。
    // 放在这里而不是点击时：点击那条路要**同步**返回结果（见 NotificationIntentLauncher），
    // 而跨进程往返做不到同步。预热之后点击走的还是「内存里那块令牌」这条普通路径。
    LaunchedEffect(entry) { IntentTokenFetcher.request(context, entry) }

    // 令牌到货 → 重算。用进程内广播而不是状态容器：令牌表（NotificationIntentCache）是个
    // 普通 object，它自己不知道谁在看；而 Compose 这一层本来就在监听各种 relay 信号。
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
                    // 措辞跟着「这次真能做到什么」走，见 actionLabel。
                    CardActionRow(actionLabel(intercepted, faithful, targetApp)) {
                        openError = NotificationIntentLauncher.open(context, entry)
                    }
                }
            }
            if (canOpen && !faithful) {
                // 说清「为什么这次只能开首页」：令牌是内存里的东西，而它的寿命上限是**设备本次开机**
                // —— 系统替我们寄存着（system_server 侧），可设备一重启就一起归零。
                // 只剩快照时宿主内部页面复刻不了（推送落地页会被外部启动卡死）。
                HintText(
                    "原通知的跳转令牌只在设备本次开机内有效（系统替我们寄存着，重启后一起失效）。" +
                        "所以这里只能打开${targetApp ?: "对应应用"}；令牌还在时能直接回到那条通知的页面。",
                )
            }
            if (!canOpen) {
                // 说清两种成因，并明确「新收到的通知不受影响」——
                // 旧记录是**补不回来**的（跳转只有落盘那一刻能拿到），不说清会让用户以为功能还是坏的。
                HintText(
                    "这条记录没有留下跳转信息：落盘时模块还读不到原通知的跳转（旧版本），" +
                        "或者这条通知本身就没有点击动作。新收到的通知不受影响。",
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
 * 这条通知属于哪个应用，用来给动作行补一句「（菜鸟）」，或者直接写成「打开菜鸟」。
 *
 * 判据走 [NotificationIntentSnapshot.targetPackageOf]（来源包名优先、快照串兜底，
 * core 层有单测钉着）—— 与 [NotificationIntentLauncher] 实际打开的目标**同源**，
 * 不能各算一遍，否则会出现「按钮写着菜鸟、打开的是别的」。
 *
 * 两条「不写」的规则都是刻意的：
 * - 包名认不出（不在已知来源表里）→ 不写，把 `com.foo.bar` 这种串贴进按钮没有信息量；
 * - 老记录既没有快照串、来源也在表外 → 同样不写。
 */
private fun targetLabel(entry: ExpressNotificationLog.Entry): String? {
    val pkg = NotificationIntentSnapshot.targetPackageOf(entry.sourcePackage, entry.intentUri)
        ?: return null
    val display = ExpressSettingsSnapshot.displayName(pkg)
    return display.takeIf { it != pkg }
}

/**
 * 动作行的措辞。分开说清楚 —— 别让「打开原通知」这四个字许诺一个做不到的页面：
 * - 揣着令牌（[faithful]）：真的能回到那条通知的页面 → 「打开原通知（菜鸟）」；
 * - 只剩快照：只能开 App（宿主内部页面复刻不了，见 [NotificationIntentLauncher] 的类注释）
 *   → 「打开菜鸟」；
 * - 连包名都认不出（罕见）：退回中性措辞，不编一个应用名。
 */
private fun actionLabel(intercepted: Boolean, faithful: Boolean, targetApp: String?): String {
    val base = if (intercepted) "打开被拦截的通知" else "打开原通知"
    return when {
        faithful -> targetApp?.let { "$base（$it）" } ?: base
        targetApp != null -> "打开$targetApp"
        else -> base
    }
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
 * 注册「令牌到货」的接收器（[ExpressRelay.ACTION_INTENT_TOKEN_READY]）。
 *
 * 那条广播是本模块**自己发给自己**的（`setPackage` 到本包），收不到任何外部来源，
 * 所以用 `RECEIVER_NOT_EXPORTED` —— 不需要 Android 13+ 那套导出性讨论，本来就该是私有的。
 *
 * 版本分支必须显式写：API 33 起 `registerReceiver` 不传 flag 直接抛 `SecurityException`；
 * 而低版本没有那个重载的重载参数（`RECEIVER_NOT_EXPORTED` 是 API 33 的常量）。
 * 项目里另一处（`HostReceiverRegistrar`，那个要跨进程收，用 `EXPORTED`）也是同一个写法。
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
