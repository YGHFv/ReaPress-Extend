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
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「在模块里登录淘宝」——**完全不依赖任何宿主 App** 的凭据获取路径。
 *
 * ## ⚠️ 当前没有任何入口（2026-09-26 用户决定先不做）
 *
 * 这份实现是**完整的，但未接进界面**：入口原本挂在身份码弹窗的失败态（一个「登录淘宝」按钮），
 * 用户看过之后说「先不放在这，后面再构建这个方案」。所以这里保持现状、逻辑一字未动，
 * 等那个方案定下来再接 —— **不要因为「搜不到引用」就把它当死代码删掉**。
 *
 * 注意它**从未在真机上验证过**：WebView 登录流程、cookie 落地时机都只是按接口写出来的。
 * 真要启用，先确认 `IdentityCodeFetcher` / `TraceCookieCache` 的接口仍然和它对得上。
 *
 * ⚠️ 这条路**只解决轨迹**，解决不了身份码：`_m_h5_tk` 在手也打不动身份码那个接口，
 * 它在 H5 通道上就没打开过（2026-09-26 两轮真机实证，见 `CainiaoIdentityBridge`）。
 * 别指望「模块自己登录淘宝」能把身份码也一起做出来。
 *
 * ## 为什么要有这条路
 *
 * 前两条路都要赌一件事：**用户手机里得有一个已经登录过淘宝的 App，而且我们读得到它**。
 *
 * - 菜鸟那条：2026-09-26 真机实测失败（用户在菜鸟里没有淘宝登录态）；
 * - 淘宝 App 那条：要求用户装了淘宝，并在 LSPosed 里把本模块的作用域勾上它。
 *
 * 这条路把两个前提都去掉：模块自己起一个 WebView，用户在里面登录一次，我们从**自己进程**
 * 的 `CookieManager` 里把 `.taobao.com` 的登录态取出来落地。之后它是模块自己的凭据，
 * 与宿主在不在、勾没勾作用域都无关。
 *
 * ## 为什么这不是「伪造登录」
 *
 * 登录态**伪造不出来**：`sgcookie` / `cookie2` 是 `login.taobao.com` 在登录成功时用
 * `Set-Cookie` 下发的，`_m_h5_tk` 更是服务端按会话签发的临时令牌 —— 客户端凭空造一个值，
 * 服务端第一件事就是校签并回 `FAIL_SYS_TOKEN_*`。能做的只有「**拿一份真的**」，
 * 区别只在从哪儿拿：宿主的 WebView（前两条路），或者这里 —— 用户自己登一次。
 *
 * 好处也是实在的：这条路拿到的凭据**由用户自己维护时效**。过期了就在这儿重新登一次，
 * 不必等某个 App 更新、也不必重新适配谁的私有 cookie 库。
 *
 * ## 只认「登录过」的标志
 *
 * 未登录时 WebView 里也会有一堆匿名 cookie（`cna`、`t` 之类），拿它们去发 MTOP 请求只会
 * 换来 `FAIL_SYS_TOKEN_EMPTY`。所以命中判据收紧到 [LOGIN_MARKERS]。
 *
 * ## 什么时候不放 WebView
 *
 * `if (show)` 那一层是必须的：WebView 一旦创建就开始加载、占用渲染进程。配合
 * [DisposableEffect] 在离开组合时 `destroy()` —— 不销毁的 WebView 会拖着渲染进程不放。
 */
@Composable
internal fun TaobaoLoginDialog(
    show: Boolean,
    /** 已经拿到并落地登录态。调用方据此关闭弹窗并重试取码。 */
    onPicked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hint by remember(show) {
        mutableStateOf("在下面登录淘宝。登录完成后会自动识别；登录态只存在本机模块目录里。")
    }

    // WebView 实例的持有格。用普通持有格而不是 Compose state：它只在 factory 里被写一次，
    // 之后只读 —— 放进 state 会让每次赋值都触发一次无意义的重组。
    val holder = remember { WebViewHolder() }

    // 轮询而不是「在 onPageFinished 里判定一次」：登录流程会跨好几个页面与重定向
    // （登录页 → 安全验证 → 回跳），`sgcookie` 与 `cookie2` 也不是同一时刻到位的。
    // 每秒问一次 CookieManager 最省事，而且它返回的本来就是「此刻的真实状态」。
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        var waited = 0L
        while (waited < LOGIN_WAIT_MS) {
            delay(LOGIN_POLL_MS)
            waited += LOGIN_POLL_MS
            if (capture(context, holder.view)) {
                hint = "已拿到淘宝登录态，正在继续取码…"
                delay(HANDOFF_DELAY_MS)
                onPicked()
                return@LaunchedEffect
            }
        }
        hint = "还没检测到登录态。如果已经登录，点下面的「我已登录」再试一次。"
    }

    DisposableEffect(Unit) {
        onDispose {
            holder.view?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.destroy()
                }
            }
            holder.view = null
        }
    }

    OverlayDialog(
        show = show,
        title = "登录淘宝",
        summary = "登录一次之后，模块自己就能取轨迹，不必再打开菜鸟",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = hint,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            if (show) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LOGIN_WEB_HEIGHT),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // 淘宝登录页是前端应用，两样关掉的话它连表单都渲染不出来。
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 默认 UA —— 与后面模块发 MTOP 请求时用的那份保持一致。改成桌面 UA
                            // 会让风控把「登录」和「使用」判成两个环境。
                            // 用默认的 WebViewClient：链接就在本 WebView 里打开，不跳出到浏览器。
                            webViewClient = WebViewClient()
                            loadUrl(LOGIN_URL)
                            holder.view = this
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "我已登录",
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clickable {
                            if (capture(context, holder.view)) {
                                onPicked()
                            } else {
                                hint = "还是没检测到登录态。确认页面里已显示登录成功后再点。"
                            }
                        },
                )
                Text(
                    text = "关闭",
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** WebView 的持有格。见上面「用普通持有格而不是 Compose state」的说明。 */
private class WebViewHolder {
    var view: WebView? = null
}

/**
 * 把当前 WebView 的淘宝登录态取出来落地。
 *
 * 走的是**模块自己进程**的 `CookieManager` —— 与宿主的完全隔离，互不覆盖。
 * 落地用 [TraceCookieCache.put]，它同时写内存与模块私有目录
 * （[io.github.YGHFv.ReaPressExtend.relay.TraceCookieStore]），
 * 这样下一个拉取 / 取码入口 `attach` 完立刻就能用上。
 *
 * @return true 表示拿到并写入了。
 */
private fun capture(context: Context, view: WebView?): Boolean {
    val cookie = loginCookie() ?: return false
    // UA 跟着 WebView 走：模块发请求时用同一份，画像才一致。
    val ua = runCatching { view?.settings?.userAgentString }.getOrNull()
    TraceCookieCache.attach(context)
    TraceCookieCache.put(cookie, ua)
    return true
}

/**
 * 模块自己 WebView 里的淘宝登录态；**没真正登录过就返回 null**。
 *
 * WebView 未初始化时 `CookieManager.getInstance()` 会抛 `IllegalStateException`，
 * 这里吞掉当「还没有」—— 那正是轮询早期的正常状态。
 */
private fun loginCookie(): String? = runCatching {
    CookieManager.getInstance().getCookie(PROBE_URL)?.takeIf { value ->
        value.isNotBlank() && LOGIN_MARKERS.any { value.contains(it) }
    }
}.getOrNull()

/**
 * 问哪个 URL，就等于要哪个域的 cookie（域名 cookie 对子域生效）。
 *
 * 用 `acs.m.taobao.com` —— 后面真正要打的就是这个域，问它拿到的就是「打这个域时会带的东西」。
 */
private const val PROBE_URL = "https://acs.m.taobao.com"

/** 淘宝登录成功后必然出现的 cookie 名。只有它们能证明「这是一份登录态」。 */
private val LOGIN_MARKERS = listOf("sgcookie=", "cookie2=")

/**
 * 登录页入口。
 *
 * 用 `login.m.taobao.com` 而不是淘宝首页：少绕一跳，登录完就停在原地。
 * 万一这个地址将来变了，用户可以在 WebView 里自己导航 —— 不做 URL 白名单限制，
 * 因为拦住用户去安全验证页只会让登录永远做不完。
 */
private const val LOGIN_URL = "https://login.m.taobao.com/"

private val LOGIN_WEB_HEIGHT = 380.dp

/** 自动检测的上限。登录含短信 / 滑块验证，给足时间，但也不无限等。 */
private const val LOGIN_WAIT_MS = 180_000L
private const val LOGIN_POLL_MS = 1_000L

/** 命中后缓一拍再交接，让上面那句提示先渲染出来，不然界面会显得「莫名其妙就关了」。 */
private const val HANDOFF_DELAY_MS = 400L
