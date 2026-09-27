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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.NoRootPlan
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.noroot.NoRootAccess
import io.github.YGHFv.ReaPressExtend.relay.CainiaoDirectFetcher
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「免 root 模式」二级页：顶部来源状态、能力清单、「立即同步」与「登录淘宝」两个动作。
 * 「授予通知使用权」在系统设置里完成，模块收不到任何回调，所以页面开着时按 [PERMISSION_POLL_MS]
 * 轮询重读授权状态。所有说明文字都来自 core / 动作方（[NoRootPlan.checklist] /
 * [NoRootAccess.sourceStatus] / [CainiaoDirectFetcher.describeOutcome]），这里不另写一套措辞。
 */
@Composable
internal fun NoRootPage(
    settings: ExpressSettingsSnapshot,
    update: ((ExpressSettingsSnapshot) -> ExpressSettingsSnapshot) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)

    // 授权状态、登录态、同步结果都可能在页面开着时变化，一个整数打掉所有 remember 缓存。
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PERMISSION_POLL_MS)
            tick++
        }
    }

    val status = remember(tick) { NoRootAccess.sourceStatus(context) }
    val capabilities = remember(tick) { NoRootAccess.capabilities(context) }
    val accessGranted = remember(tick) { NoRootAccess.isListenerAccessGranted(context) }
    val serviceLabel = remember(tick) { NoRootAccess.listenerServiceLabel() }
    val loginAge = remember(tick) { NoRootAccess.taobaoAgeMs(context) }
    val syncOutcome = remember(tick) { CainiaoDirectFetcher.describeOutcome() }
    val sourceSummary = remember(tick) { NoRootAccess.sourceSummary(context) }
    // 风控退避是「点了没反应」最常见的原因，它有自己的截止时刻，直接说出来。
    val riskMinutes = if (CainiaoTraceApi.riskBlocked()) {
        ((CainiaoTraceApi.riskBlockedUntil - System.currentTimeMillis()) / 60_000L)
            .coerceAtLeast(1L)
    } else {
        0L
    }

    var loginOpen by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "免 root 模式",
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
            GroupTitle("当前来源")
            SettingsCard {
                InfoRow("数据来源", status.mode.displayName)
                HintText(status.detail)
                if (settings.noRootListener) {
                    InfoRow("采集服务", serviceLabel)
                }
                if (settings.mode == ExpressSettingsKeys.MODE_OFF && settings.noRootListener) {
                    HintText(
                        "工作模式为「关闭」，免 root 采集不生效，请先到设置里选择模式。",
                        MiuixTheme.colorScheme.error,
                    )
                }
            }

            GroupTitle("免 root 采集")
            SettingsCard {
                SwitchPreference(
                    title = "免 root 采集",
                    summary = "用系统「通知使用权」读快递通知，不需要 root",
                    checked = settings.noRootListener,
                    onCheckedChange = { on ->
                        update { it.copy(noRootListener = on) }
                        tick++
                    },
                )
                if (accessGranted) {
                    InfoRow("通知使用权", "已授予")
                } else {
                    ArrowPreference(
                        title = "去授予通知使用权",
                        summary = "在系统设置的「通知使用权」里勾上本模块",
                        onClick = {
                            if (NoRootAccess.openListenerSettings(context)) {
                                syncNote = "授权后回到这一页，状态会自动刷新。"
                            } else {
                                syncNote = "这个 ROM 没打开设置页，请手动到系统设置的「通知使用权」里勾上本模块。"
                            }
                        },
                    )
                }
                HintText("只处理「设置 → 来源」里打开的应用：$sourceSummary。")
            }

            GroupTitle("登录淘宝（自己拉轨迹）")
            SettingsCard {
                InfoRow(
                    "登录状态",
                    loginAge?.let { "已登录 · ${NoRootPlan.describeAge(it)}" } ?: "未登录",
                    if (loginAge == null) null else MiuixTheme.colorScheme.primary,
                )
                ArrowPreference(
                    title = if (loginAge == null) "登录淘宝" else "重新登录",
                    summary = "登录后模块即可自动拉取订单与轨迹，无需打开菜鸟",
                    onClick = { loginOpen = true },
                )
                if (loginAge != null) {
                    ArrowPreference(
                        title = "清除登录态",
                        summary = "只删本机模块目录里的那份，不影响菜鸟或淘宝里的登录",
                        onClick = {
                            TraceCookieCache.invalidate(context)
                            tick++
                            syncNote = "已清除本机登录态。"
                        },
                    )
                }
                HintText(
                    "登录态只存本机私有目录，不进日志、不上传；与宿主 hook 同步的那份" +
                        "同落点，谁新用谁。",
                )
            }

            GroupTitle("同步")
            SettingsCard {
                InfoRow("上次同步", syncOutcome)
                if (riskMinutes > 0L) {
                    HintText("被淘宝风控拦截，约 $riskMinutes 分钟后可再试。", MiuixTheme.colorScheme.error)
                }
                TextButton(
                    text = "立即同步",
                    onClick = {
                        if (loginAge == null) {
                            syncNote = "先登录淘宝 —— 没有登录态就发不出请求。"
                        } else {
                            // 被闸门挡下时不能说「已发起」——那会让用户对着一个没动的数字等十几秒。
                            val started = CainiaoDirectFetcher.start(
                                context,
                                "免 root 页面手动同步",
                                force = true,
                            )
                            syncNote = if (started) {
                                "已发起。结果会出现在上面那一行（约十几秒）。"
                            } else {
                                "没有发起：${CainiaoDirectFetcher.describeOutcome()}"
                            }
                        }
                        tick++
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                syncNote?.let { HintText(it) }
                HintText(
                    "只覆盖淘宝/天猫订单；拼多多与他人寄件的接口签名在 native，" +
                        "免 root 下只能靠通知文案。",
                )
            }

            GroupTitle("能力")
            capabilities.forEach { capability ->
                RecordCard(
                    title = capability.title,
                    titleColor = if (capability.ok) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    description = capability.detail,
                    trailing = {
                        SecondaryText(if (capability.ok) "可用" else "不可用")
                    },
                )
            }
            HintText(
                "免 root 与 LSPosed 互补：hook 覆盖更全（含拼多多与身份码），免 root 不挑环境；" +
                    "两者同时处理同一条通知时只发一条提醒。",
            )

            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }

        // 登录弹窗必须放在 Scaffold 的 lambda 里：miuix 的 OverlayDialog 登记进
        // LocalRootDialogStates，宿主由最近一层 Scaffold 提供；放外面 root 为 null，
        // 弹窗静默不显示且没有任何报错。
        TaobaoLoginDialog(
            show = loginOpen,
            onPicked = {
                loginOpen = false
                tick++
                val started = CainiaoDirectFetcher.start(context, "登录淘宝后同步", force = true)
                syncNote = if (started) {
                    "登录态已保存，正在同步。"
                } else {
                    "登录态已保存，但这次没有发起同步：${CainiaoDirectFetcher.describeOutcome()}"
                }
            },
            onDismiss = {
                loginOpen = false
                tick++
            },
        )
    }
}

/** 授权在系统设置里勾完模块收不到任何回调，只能轮询重读；读的只是一个字符串字段，两秒一次无感。 */
private const val PERMISSION_POLL_MS = 2_000L
