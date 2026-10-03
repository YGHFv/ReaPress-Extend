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

import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.ReaPressExtend.core.CainiaoIdentity
import io.github.YGHFv.ReaPressExtend.core.CainiaoIdentityResult
import io.github.YGHFv.ReaPressExtend.core.Code128
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.GeoPoint
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationSpot
import io.github.YGHFv.ReaPressExtend.notification.SpotPick
import io.github.YGHFv.ReaPressExtend.notification.SpotPickReason
import io.github.YGHFv.ReaPressExtend.relay.IdentityCodeFetcher
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 身份码弹窗：菜鸟账号的身份码（条码 + 数字，由菜鸟用自己的会话取）为主，驿站取件码兜底。
 * 多驿站按直线距离取最近（位置来自系统最后已知位置 + 宿主驿站坐标），缺定位或缺坐标就退回
 * 「最近有来件的那个」，不硬猜。两者分开显示：身份码是账号级、取件码是包裹级。
 */
@Composable
internal fun IdentityCodeDialog(
    show: Boolean,
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    resumeTick: Int,
    onNeedLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<IdentityPhase>(IdentityPhase.Loading("正在获取…")) }

    // 位置只在这里读一次；授权 / 重试 / 回到前台都会重算。
    val position = remember(show, resumeTick, retry) {
        if (show) currentPosition(context) else null
    }

    val pick = remember(show, records, rules, position) {
        if (show) ExpressHomeGrouper.pickSpot(ExpressHomeGrouper.spots(records, rules), position) else null
    }

    LaunchedEffect(show, resumeTick) {
        if (!show) return@LaunchedEffect
        if (!hasLocationPermission(context)) onNeedLocation()
    }

    LaunchedEffect(show, retry) {
        if (!show) return@LaunchedEffect

        // 该平台未实现取码时如实说明，绝不换平台取（拿错码会白跑一趟）。
        val source = pick?.spot?.identitySource
        if (source != null && !source.supported) {
            phase = IdentityPhase.Failed(
                "${source.displayName}的身份码暂时取不到（它的签名在 native 层算，无法复刻）。" +
                    "可以在驿站管理里把这一站改成「菜鸟」，或者去${source.displayName}自己的取件页看。",
            )
            return@LaunchedEffect
        }

        // 身份码由菜鸟进程取（它自己的会话）—— 这一层只做「请一次 + 等回执」。
        phase = IdentityPhase.Loading("正在让菜鸟取身份码…")
        phase = when (val result = IdentityCodeFetcher.fetch(context)) {
            is CainiaoIdentityResult.Success -> IdentityPhase.Ready(result.identity)
            else -> IdentityPhase.Failed(IdentityCodeFetcher.describe(result))
        }
    }

    OverlayDialog(
        show = show,
        title = "身份码",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (val current = phase) {
                is IdentityPhase.Loading -> LoadingBlock(current.text)
                is IdentityPhase.Failed -> FailureBlock(current.text)
                is IdentityPhase.Ready -> IdentityBlock(current.identity)
            }

            if (pick != null) {
                Spacer(Modifier.height(12.dp))
                SpotBlock(pick = pick, emphasize = phase !is IdentityPhase.Ready)
            }

            // 必须用库里的 TextButton：私有版漏 clickable 时界面照常渲染但点了没反应（2026-09-26 真机实测）。
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "重试",
                    onClick = { retry++ },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 三种状态

private sealed interface IdentityPhase {
    data class Loading(val text: String) : IdentityPhase
    data class Ready(val identity: CainiaoIdentity) : IdentityPhase
    data class Failed(val text: String) : IdentityPhase
}

@Composable
private fun LoadingBlock(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        textAlign = TextAlign.Center,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 取不到身份码时的说明：照实说原因（等风控 / 打开一次菜鸟），不写「获取失败请重试」。 */
@Composable
private fun FailureBlock(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 身份码本体：大号数字 + 条码（自助机扫码、店员报数字，两者都要）。字号 24sp 为真机量得（34sp 时 20 字符溢出换行）。 */
@Composable
private fun IdentityBlock(identity: CainiaoIdentity) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = identity.code,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight(600),
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Barcode(identity.code)
        Spacer(Modifier.height(6.dp))
        Text(
            text = expiryLabel(identity),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 条码：背景恒白、条恒黑，不随主题 —— 深底上黑条等于没画，扫码要高对比度。 */
@Composable
private fun Barcode(code: String) {
    val widths = remember(code) { Code128.encodeB(code) } ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            // 静区：贴边会被扫码器判成无效。
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(BARCODE_HEIGHT),
        ) {
            val unit = size.width / widths.sum().toFloat()
            var cursor = 0f
            for ((index, modules) in widths.withIndex()) {
                val next = cursor + modules * unit
                // 只画偶数下标的「条」；边界外取整半像素防浮点白缝（扫不出来且肉眼难见）。
                if (index % 2 == 0) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(cursor, 0f),
                        size = Size((next - cursor).coerceAtLeast(1f), size.height),
                    )
                }
                cursor = next
            }
        }
    }
}

private val BARCODE_HEIGHT = 56.dp

/** 有效期文案：没给有效期时不提这句（宁可不写，不写假的剩余时间）。 */
private fun expiryLabel(identity: CainiaoIdentity): String {
    val expireAt = identity.expireAt ?: return if (identity.offline) "离线码" else ""
    val remain = expireAt - System.currentTimeMillis()
    if (remain <= 0L) return "已过期，请点「重试」重新获取"
    val minutes = remain / 60_000L
    val seconds = (remain % 60_000L) / 1000L
    return if (minutes > 0) "剩余约 $minutes 分钟" else "剩余 $seconds 秒"
}

// ---------------------------------------------------------------- 最近取件点

/** 用 getLastKnownLocation 而非等回调：用户站在驿站门口等不起；null 不是错误，pickSpot 会改按件数挑。 */
internal fun currentPosition(context: Context): GeoPoint? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    return runCatching {
        LocationAccess.lastKnown(context, manager, LOCATION_PROVIDERS)
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }.getOrNull()
}

/** 距离由 pickSpot 算好直接显示（显示用的数就是决策用的数）；emphasize=身份码没取到，取件码顶上。 */
@Composable
private fun SpotBlock(pick: SpotPick, emphasize: Boolean) {
    val spot = pick.spot
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = spotPickTitle(pick.reason),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(4.dp))
        Text(spot.displayName, fontWeight = FontWeight.Medium)
        spotMeta(spot, pick.distanceMeters)?.let { meta ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        spot.pickupCode?.takeIf { it.isNotBlank() }?.let { code ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = code,
                    fontSize = if (emphasize) 28.sp else 20.sp,
                    fontWeight = FontWeight(600),
                    color = if (emphasize) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = "  取件码",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (spot.pickupCode.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这一站还没有已知的取件码（宿主没下发，可在「驿站管理」里给它填一个默认码）。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 标题必须与挑选依据一致，否则是在解释一个并没有执行的规则。 */
private fun spotPickTitle(reason: SpotPickReason): String = when (reason) {
    SpotPickReason.NEARBY -> "最近的取件点"
    SpotPickReason.READY_COUNT -> "待取件最多的取件点"
    SpotPickReason.ONLY -> "取件点"
}

private fun hasLocationPermission(context: Context): Boolean =
    LocationAccess.hasPermission(context)

private val LOCATION_PROVIDERS = listOf(
    LocationManager.PASSIVE_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.GPS_PROVIDER,
)

/** 两样都缺时返回 null（整行不排）。 */
private fun spotMeta(spot: ExpressStationSpot, distanceMeters: Double?): String? {
    val parts = buildList {
        distanceMeters?.let { add(distanceLabel(it)) }
        if (spot.readyCount > 0) add("${spot.readyCount} 件待取")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 米级不写小数，公里级保留一位。 */
private fun distanceLabel(meters: Double): String =
    if (meters < 1_000) {
        "约 ${meters.roundToInt()} 米"
    } else {
        "约 ${(meters / 100).roundToInt() / 10.0} 公里"
    }
