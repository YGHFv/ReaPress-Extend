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

package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 淘宝 App 进程里的登录态采集：真机实测菜鸟 WebView cookie 库里没有 `.taobao.com` 登录态
 * （用户没绑过淘宝账号就没有），淘宝 App 只要登录过必然有；取到后经 [ExpressRelaySender.sendCookieSync]
 * 送回模块发 `acs.m.taobao.com` 请求。「主动同步」（冷启动延迟一次）覆盖常见路径，
 * 「反向索要」（[ExpressRelay.ACTION_COOKIE_REQUEST]）覆盖模块刚重启、节流不会放行旧节拍的场景。
 * 只做主进程：淘宝多进程而凭据读的是应用级 dataDir，任何进程读到同一份。这条路要求
 * LSPosed 作用域勾上淘宝，没勾就没有 `onPackageReady`，整个类不会执行。
 */
internal object TaobaoCredentialHook {

    private const val TAG = "taobao credential"

    /** `CookieManager.getInstance()` 首次调用会同步初始化 WebView，错峰不给宿主冷启动添负担。 */
    private const val FIRST_SYNC_DELAY_MS = 8_000L

    private const val ACQUIRE_RETRY_MS = 3_000L

    private const val MAX_ACQUIRE_ATTEMPTS = 10

    @Volatile private var installed = false

    @Volatile private var receiverRegistered = false

    @Volatile private var firstSyncScheduled = false

    fun install(classLoader: ClassLoader, isMainProcess: Boolean): Boolean {
        if (installed) return true
        installed = true

        HostContextHolder.installCapture(classLoader)

        if (!isMainProcess) {
            XposedBridge.logAlways("$TAG: 非主进程，只装 context 捕获（不采集凭据）")
            return true
        }

        // 此刻 Application 常常还没创建，acquire 会返回 null —— 交给重试。
        val context = HostContextHolder.acquire()
        if (context != null) {
            onContextReady(context)
        } else {
            retryAcquire(attempt = 0)
        }

        XposedBridge.logAlways("$TAG: 淘宝凭据采集已装上（主动同步 + 反向索要）")
        return true
    }

    /** 拿到 context 之后注册反向索要、排一次主动同步；retryAcquire 可能把它叫来多次，两件都只做一次。 */
    private fun onContextReady(context: Context) {
        synchronized(this) {
            if (!receiverRegistered) {
                receiverRegistered = HostReceiverRegistrar.register(
                    context,
                    cookieRequestReceiver,
                    ExpressRelay.ACTION_COOKIE_REQUEST,
                )
                XposedBridge.logAlways("$TAG: 反向索要接收器 registered=$receiverRegistered")
            }
            if (!firstSyncScheduled) {
                firstSyncScheduled = true
                scheduleFirstSync(context)
            }
        }
    }

    private fun retryAcquire(attempt: Int) {
        if (attempt >= MAX_ACQUIRE_ATTEMPTS) {
            XposedBridge.logAlways("$TAG: 等了 ${attempt * ACQUIRE_RETRY_MS}ms 仍拿不到 context，放弃采集")
            return
        }
        val posted = runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                val context = HostContextHolder.acquire()
                if (context != null) onContextReady(context) else retryAcquire(attempt + 1)
            }, ACQUIRE_RETRY_MS)
        }.isSuccess
        if (!posted) return
    }

    private fun scheduleFirstSync(context: Context) {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { ExpressRelaySender.sendCookieSync(context) }
                    .onFailure { XposedBridge.logError("$TAG: 主动同步失败", it) }
            }, FIRST_SYNC_DELAY_MS)
        }.onFailure { XposedBridge.logError("$TAG: 排主动同步失败", it) }
    }

    /** 模块索要一次登录态；这里只有 cookie 一件事（拉轨迹、富化都是菜鸟特有的），接收器只管这一个 action。 */
    private val cookieRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ExpressRelay.ACTION_COOKIE_REQUEST) return
            runCatching { ExpressRelaySender.sendCookieSync(context, force = true) }
                .onFailure { XposedBridge.logError("$TAG: 响应索要失败", it) }
        }
    }
}
