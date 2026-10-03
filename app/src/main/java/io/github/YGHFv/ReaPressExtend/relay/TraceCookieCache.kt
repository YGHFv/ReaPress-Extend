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

package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore

/**
 * 淘宝登录态 cookie 的模块进程缓存：cookie 只存在菜鸟私有目录（模块不同 uid 读不到），
 * 由 hook 侧经 `ACTION_COOKIE_SYNC` 送来落这里，落盘到模块私有目录（[TraceCookieStore]），
 * 不变的两条：不进日志、不离开模块。用 cookie 前先 [attach]（幂等）把落盘那份灌进内存，
 * 之后 [get] 只读内存，可放心在热路径上调。
 */
object TraceCookieCache {

    @Volatile private var cookie: String? = null

    @Volatile private var syncedAt = 0L

    @Volatile var hostUa: String? = null
        private set

    @Volatile private var store: Context? = null

    @Volatile private var restored = false

    /** 绑定落盘位置并把上次同步的登录态读回内存（幂等）；必须在 [get] 之前调用一次。存 applicationContext 防泄漏。 */
    fun attach(context: Context): Unit = ExpressRecordStore.withTransaction {
        val app = context.applicationContext
        store = app
        if (restored) return@withTransaction
        val stored = TraceCookieStore.read(app)
        restored = true
        if (stored == null) return@withTransaction
        if (cookie == null) {
            cookie = stored.cookie
            syncedAt = stored.syncedAt
            if (!stored.ua.isNullOrBlank()) hostUa = stored.ua
            CainiaoTraceApi.preferredUa = hostUa
        }
    }

    /** 当前可用的 cookie；从未同步过返回 null。 */
    fun get(): String? = ExpressRecordStore.withTransaction { cookie }

    internal fun reloadAfterRestore(context: Context): Unit = ExpressRecordStore.withTransaction {
        val app = context.applicationContext
        store = app
        restored = false
        cookie = null
        syncedAt = 0L
        hostUa = null
        CainiaoTraceApi.preferredUa = null
        CainiaoTraceApi.clearCachedTokens()
        val stored = TraceCookieStore.read(app)
        cookie = stored?.cookie
        syncedAt = stored?.syncedAt ?: 0L
        hostUa = stored?.ua
        CainiaoTraceApi.preferredUa = hostUa
        restored = true
    }

    /** 这份登录态是多久以前同步的；没有登录态返回 null。[now] 由调用方传（不依赖系统时钟）。 */
    fun ageMs(now: Long = System.currentTimeMillis()): Long? = ExpressRecordStore.withTransaction {
        val value = cookie ?: return@withTransaction null
        if (value.isBlank() || syncedAt <= 0L) return@withTransaction null
        (now - syncedAt).coerceAtLeast(0L)
    }

    fun put(value: String, ua: String? = null): Unit = ExpressRecordStore.withTransaction {
        if (value.isBlank()) return@withTransaction
        if (cookie != value) {
            CainiaoTraceApi.clearCachedTokens()
            hostUa = null
        }
        cookie = value
        if (!ua.isNullOrBlank()) hostUa = ua
        syncedAt = System.currentTimeMillis()
        // UA 由这里统一交给请求引擎：宿主同步与模块自登两条写入路径的口径必须一致，
        // 浏览器 UA 配 WebView 登录态本身就是风控眼里的异常组合。
        CainiaoTraceApi.preferredUa = hostUa
        store?.let { TraceCookieStore.write(it, value, hostUa) }
    }

    /**
     * 丢掉当前登录态（内存 + 磁盘）。
     *
     * 当前没有调用点，但别当死代码删：session 失败跟登录态无关（身份码接口在 H5 通道上就是关着的），
     * 风控时清 cookie 只会把下一轮也搭进去。该调的场景是确证过期 —— 轨迹与身份码同时被服务端
     * 以登录态为由拒绝、且重新索要来的那份也不管用。在那之前，留着旧的总比什么都没有强。
     */
    fun invalidate(context: Context): Unit = ExpressRecordStore.withTransaction {
        cookie = null
        syncedAt = 0L
        hostUa = null
        CainiaoTraceApi.preferredUa = null
        CainiaoTraceApi.clearCachedTokens()
        TraceCookieStore.clear(context.applicationContext)
    }

    /** 诊断用；绝不打内容，只打有没有与时刻。 */
    fun describe(): String = if (cookie != null) "cached(at=$syncedAt)" else "empty"
}
