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
 * 「在模块里登录淘宝」：模块自己起 WebView，用户登一次，从本进程 CookieManager 取
 * .taobao.com 登录态落地——不依赖任何宿主 App 与作用域。负责富化（拉订单、全轨迹、商品图），
 * 与「免 root 采集」（读通知，负责发现）合成免 root 方案的全部。
 * 只解决轨迹：身份码接口在 H5 通道就没开过（真机实证），这条做不出身份码。
 * 登录态伪造不出来——sgcookie/cookie2 是登录成功时服务端 Set-Cookie 下发的，_m_h5_tk 是会话
 * 签发的临时令牌；能做的只有拿一份真的。命中判据收紧到 [LOGIN_MARKERS]：未登录时的匿名
 * cookie 拿去发 MTOP 只会换来 FAIL_SYS_TOKEN_EMPTY。
 */
@Composable
internal fun TaobaoLoginDialog(
    show: Boolean,
    onPicked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hint by remember(show) {
        mutableStateOf("在下面登录淘宝。登录完成后会自动识别；登录态只存在本机模块目录里。")
    }

    // 普通持有格而非 Compose state：只在 factory 写一次，放 state 每次赋值都触发无意义重组。
    val holder = remember { WebViewHolder() }

    // 轮询而不是 onPageFinished 判一次：登录跨好几个页面与重定向，sgcookie 与 cookie2
    // 也不是同一时刻到位；CookieManager 返回的就是「此刻的真实状态」。
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
                            // 淘宝登录页是前端应用，两样关掉连表单都渲染不出来。
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 默认 UA：与后面模块发 MTOP 请求用的那份保持一致——
                            // 改桌面 UA 会让风控把「登录」和「使用」判成两个环境。
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

private class WebViewHolder {
    var view: WebView? = null
}

/**
 * 把当前 WebView 的淘宝登录态取出来落地。走模块自己进程的 CookieManager，与宿主完全隔离；
 * 落地用 [TraceCookieCache.put]（内存 + 模块私有目录），下一个拉取入口 attach 完立刻能用。
 */
private fun capture(context: Context, view: WebView?): Boolean {
    val cookie = loginCookie() ?: return false
    // UA 跟着 WebView 走：模块发请求时用同一份，画像才一致。
    val ua = runCatching { view?.settings?.userAgentString }.getOrNull()
    TraceCookieCache.attach(context)
    TraceCookieCache.put(cookie, ua)
    return true
}

private fun loginCookie(): String? = runCatching {
    CookieManager.getInstance().getCookie(PROBE_URL)?.takeIf { value ->
        value.isNotBlank() && LOGIN_MARKERS.any { value.contains(it) }
    }
}.getOrNull()

/** 问哪个 URL 就是要哪个域的 cookie；后面真正要打的就是这个域。 */
private const val PROBE_URL = "https://acs.m.taobao.com"

/** 淘宝登录成功后必然出现的 cookie 名，只有它们能证明这是一份登录态。 */
private val LOGIN_MARKERS = listOf("sgcookie=", "cookie2=")

/** 登录页入口（比首页少绕一跳）。不做 URL 白名单——拦住用户去安全验证页只会让登录永远做不完。 */
private const val LOGIN_URL = "https://login.m.taobao.com/"

private val LOGIN_WEB_HEIGHT = 380.dp

private const val LOGIN_WAIT_MS = 180_000L
private const val LOGIN_POLL_MS = 1_000L

/** 命中后缓一拍再交接，让提示先渲染出来，不然界面显得「莫名其妙就关了」。 */
private const val HANDOFF_DELAY_MS = 400L
