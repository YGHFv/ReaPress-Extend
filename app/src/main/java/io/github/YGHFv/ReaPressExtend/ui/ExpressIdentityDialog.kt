package io.github.YGHFv.ReaPressExtend.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * 首页右上角那个按钮点开的**身份码弹窗**。
 *
 * ## 里面是什么
 *
 * 一屏两件事，都是「站在驿站门口那一刻要念出来的东西」：
 *
 * 1. **菜鸟账号的身份码**（主）：在自助取件机上扫的条码 + 可直接报给店员的数字。
 *    由**菜鸟自己**用它自己的会话取来（见 [IdentityCodeFetcher]）—— 用户不需要在模块里登录
 *    任何东西，也不需要模块去复刻任何签名。取不到时照实说原因。
 * 2. **取件码**（兜底）：驿站那套「1-5-8644」的码。身份码取不到（连不上菜鸟 / 宿主自己也没取到 /
 *    服务端没下发）时，它就是用户唯一能用的东西，所以那一刻它要顶上来而不是藏在下面。
 *
 * ## 「最近的那个驿站」怎么选
 *
 * 唯一驿站时没有歧义，直接用它。多个驿站时按经纬度算直线距离取最近的（[GeoDistance]），
 * 位置来自系统最后已知位置 + 宿主给的驿站坐标。两条路都可能缺：
 * - **没有定位权限** → 发起一次申请；用户拒绝就退回「最近有来件的那一个」；
 * - **宿主没给驿站坐标**（真机很常见）→ 同样退回「最近有来件的那个」。
 *
 * 不硬猜一个：选错驿站会让人白跑一趟。退回「最近有来件的」至少是可解释的。
 *
 * ## 为什么身份码和取件码要分开显示
 *
 * 它们**不是一回事**：身份码是账号级的（在自助机上证明「我是这个账号」），取件码是包裹级的
 * （在货架上证明「这件是我的」）。混在一处会让用户以为随便是哪个都能取件。
 */
@Composable
internal fun IdentityCodeDialog(
    show: Boolean,
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    /** 「回到前台」计数器：用户从系统设置里授权回来后重算一次最近驿站。 */
    resumeTick: Int,
    /** 申请定位权限（没授权时调用）。由 Activity 发起 —— 只有它能弹系统授权框。 */
    onNeedLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 重试用的计数器。点「重试」自增，下面那个 effect 随之重跑。
    var retry by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<IdentityPhase>(IdentityPhase.Loading("正在获取…")) }

    // 当前位置。**只在这里读一次**，下面两处都用它：挑选取件点、以及显示「离我多远」。
    // 授权 / 重试 / 回到前台都会重算 —— 用户去系统设置里给了权限回来，这一屏要自己跟上。
    val position = remember(show, resumeTick, retry) {
        if (show) currentPosition(context) else null
    }

    // 最近取件点。规则在 [ExpressHomeGrouper.pickSpot] 里（纯函数、有单测）——
    // 界面只负责把「位置」喂进去，不在这一层再写一遍挑选逻辑。
    val pick = remember(show, records, rules, position) {
        if (show) ExpressHomeGrouper.pickSpot(ExpressHomeGrouper.spots(records, rules), position) else null
    }

    // 申请定位权限。放在 effect 里而不是组合期 —— 组合期有副作用是错的，
    // 而且申请动作本身会触发 Activity 重建。
    LaunchedEffect(show, resumeTick) {
        if (!show) return@LaunchedEffect
        if (!hasLocationPermission(context)) onNeedLocation()
    }

    LaunchedEffect(show, retry) {
        if (!show) return@LaunchedEffect

        // 这一站选的平台还没实现取码（`IdentitySource.supported` 为 false）——
        // **如实说明，绝不换一个平台去取**。
        //
        // 悄悄回退到菜鸟的码是这里最糟的做法：用户会拿着拼多多驿站根本不认的码站在柜台前，
        // 而取不到至少还能让他打开拼多多 App 自己的页面。
        val source = pick?.spot?.identitySource
        if (source != null && !source.supported) {
            phase = IdentityPhase.Failed(
                "${source.displayName}的身份码暂时取不到（它的签名在 native 层算，无法复刻）。" +
                    "可以在驿站管理里把这一站改成「菜鸟」，或者去${source.displayName}自己的取件页看。",
            )
            return@LaunchedEffect
        }

        // 身份码由**菜鸟进程**取（用它自己的会话）—— 这一层只做「请一次 + 等回执」。
        //
        // 模块不再自己发这个请求：那个接口在 H5 通道上根本打不动（预热连 token 都不给就回
        // `FAIL_SYS_SESSION_EXPIRED`，而同一时刻同一份 cookie 拉轨迹是成功的），
        // 完整证据在 `IdentityCodeFetcher` 与 `CainiaoIdentityBridge` 的类注释里。
        phase = IdentityPhase.Loading("正在让菜鸟取身份码…")
        phase = when (val result = IdentityCodeFetcher.fetch(context)) {
            is CainiaoIdentityResult.Success -> IdentityPhase.Ready(result.identity)
            else -> IdentityPhase.Failed(IdentityCodeFetcher.describe(result))
        }
    }

    // 弹窗**没有副标题**：原来那句「在驿站自助机上扫这根条码，或把数字报给店员」是废话 ——
    // 打开这个弹窗的人已经在驿站门口了，码和数字都在同一屏上，不需要再教一遍怎么用。
    // 少了它，标题下面直接就是码，视线落到该落的地方（2026-09-26 用户要求移除）。
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

            // ⚠️ 这里必须用 miuix 自己的按钮（[TextButton]）而不是自写的 Row + Text：
            // 私有版本漏写 `clickable` 时，界面照常渲染、点了毫无反应，编译期也看不出来
            // （2026-09-26 真机实测：三个键全是死的）。共用库里那一份就没有「另一份忘了」的机会。
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

/**
 * 取不到身份码时的说明。**照实说原因**，不写「获取失败请重试」——
 * [IdentityCodeFetcher.describe] 给的那几句话对应的动作完全不同（等风控 / 打开一次菜鸟）。
 */
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

/**
 * 身份码本体：大号数字 + 条码。
 *
 * 数字和条码**都要给**：自助机扫码，而柜台里的店员是人 —— 报数字比让人找扫描口快。
 *
 * 字号 24sp —— 这是**量出来的**，不是拍的（2026-09-26 真机截图 1220×2656 / density 520 /
 * font_scale 1.0；量图脚本 `.workbuddy/tools/measure_shot.java`）：
 *
 * - 真实身份码是 20 个字符（`CS80393562638611541C`），34sp 时一行只塞得下 18 个，
 *   末尾两个被挤进第二行 —— 看上去像码被截断了；
 * - 每字符实测 0.479em（18 字符的墨迹宽 952px ÷ 18 ÷ 110.5px em）；弹窗内容宽 986px
 *   （条码黑条跨度 920px + 两侧各 10dp 静区，两条独立路径算出同一个值）；
 * - 34sp → 28sp 后用户仍反馈「太大」，所以**没有**停在 26sp（那一档只降 7%，
 *   观感上几乎看不出变化），直接落到 24sp：20 字符占 747px = 76% 宽
 *   （34sp 时是 1105px、溢出换行；28sp 时 871px = 88%），且比下面那根条码（920px）窄一截。
 *
 * 24sp 在本机约 3.8mm 字高，念给店员绰绰有余；再往下就该动「把码折成两行」了，
 * 那条路更糟 —— 报数时多一次换行记忆。若反过来觉得小，26sp 是唯一的回退档。
 */
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

/**
 * 条码。**背景永远是白的、条永远是黑的**，不跟随主题 ——
 * 深色主题下把黑条画在深底上等于没画，而且扫码器要的是高对比度，
 * 这里抄的是真实条码的物理形态，不是界面的配色方案。
 */
@Composable
private fun Barcode(code: String) {
    val widths = remember(code) { Code128.encodeB(code) } ?: return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            // 静区：条码规范要求两侧留白，贴边会被扫码器判成无效。
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
                // 只在「条」（偶数下标）上画。左右边界往外取整半像素，避免相邻模块之间
                // 因为浮点累积留下一条白缝（那会让条码扫不出来，而且肉眼几乎看不见）。
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

/** 有效期文案。服务端没给有效期时不提这一句（宁可不写，也不要写一个假的剩余时间）。 */
private fun expiryLabel(identity: CainiaoIdentity): String {
    val expireAt = identity.expireAt ?: return if (identity.offline) "离线码" else ""
    val remain = expireAt - System.currentTimeMillis()
    if (remain <= 0L) return "已过期，请点「重试」重新获取"
    val minutes = remain / 60_000L
    val seconds = (remain % 60_000L) / 1000L
    return if (minutes > 0) "剩余约 $minutes 分钟" else "剩余 $seconds 秒"
}

// ---------------------------------------------------------------- 最近取件点

/**
 * 系统最后已知位置。
 *
 * 用 `getLastKnownLocation` 而不是注册监听等回调：等一次定位回调要十几秒到几十秒，
 * 而用户是站在驿站门口开着弹窗等 —— 「哪个驿站在八百米外」这件事在几百米精度上完全够用，
 * 最后一次定位通常就是几分钟前的。
 *
 * 没有权限 / 没有 provider / 全部 provider 都还没定过位时返回 null。**这不是错误**：
 * [ExpressHomeGrouper.pickSpot] 会因此改按「哪站待取的件多」，那在「人在家里点开弹窗」
 * 的场景下反而是更准的答案。
 */
private fun currentPosition(context: Context): GeoPoint? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    return runCatching {
        LOCATION_PROVIDERS.asSequence()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }.getOrNull()
}

/**
 * 最近取件点那一段。
 *
 * 距离不用在这里算：[ExpressHomeGrouper.pickSpot] 已经把「多远」随结果一起给了
 * （它本来就得算一遍才知道近不近），这一层只负责显示 —— 显示用的数就是决策用的数。
 *
 * @param emphasize 身份码没取到时为 true —— 那时取件码就是用户唯一能用的东西，
 *   它要顶着显示，而不是缩在下面当补充信息。
 */
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
        // 距离 + 件数：把「为什么是这一站」摆出来。少了这一行，用户只能看到结论。
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

/**
 * 那一段的标题 —— **必须与挑选依据一致**。
 *
 * 一律写「最近的取件点」是错的：没有定位（或最近的一个都在几公里外）时，这一站是按
 * 「哪站待取的件多」挑出来的，此时说它「最近」就是在解释一个并没有执行的规则。
 */
private fun spotPickTitle(reason: SpotPickReason): String = when (reason) {
    SpotPickReason.NEARBY -> "最近的取件点"
    SpotPickReason.READY_COUNT -> "待取件最多的取件点"
    SpotPickReason.ONLY -> "取件点"
}

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** 按「谁更可能现在就有值」排：被动定位最省电也最常有值，GPS 最准但常常是空的。 */
private val LOCATION_PROVIDERS = listOf(
    LocationManager.PASSIVE_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.GPS_PROVIDER,
)

/**
 * 取件点那一行的副信息：「约 400 米 · 3 件待取」。
 *
 * 两样都缺时返回 null（整行不排）—— 一个什么依据都说不出来的站名，正是用户说
 * 「显示不对」时看到的东西。
 */
private fun spotMeta(spot: ExpressStationSpot, distanceMeters: Double?): String? {
    val parts = buildList {
        distanceMeters?.let { add(distanceLabel(it)) }
        if (spot.readyCount > 0) add("${spot.readyCount} 件待取")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 距离文案。米级不写小数，公里级保留一位 —— 这个尺度上再精细也没意义。 */
private fun distanceLabel(meters: Double): String =
    if (meters < 1_000) {
        "约 ${meters.roundToInt()} 米"
    } else {
        "约 ${(meters / 100).roundToInt() / 10.0} 公里"
    }
