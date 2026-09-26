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

/**
 * 淘宝登录态 cookie 的模块进程缓存。
 *
 * ## 为什么存在
 *
 * 轨迹拉取收拢到模块进程后，模块要能自己发 MTOP 请求 —— 但 cookie 只存在
 * 菜鸟的私有目录里，模块（不同 uid）读不到，只能由 hook 侧经 `ACTION_COOKIE_SYNC` 送进来。
 * 这里就是落点。（身份码不用它：那个接口在 H5 通道上打不动，改由菜鸟用自己的会话取。）
 *
 * ## 边界（2026-09-26 修订过一次）
 *
 * 最初写着「只进内存，不落盘、不进日志」。真机证明那样模块**永远拿不到 cookie**
 * （hook 侧 30 分钟节流 + 模块进程一天重启六次），于是放宽为「落模块私有目录」
 * （[TraceCookieStore]，用户拍板）。不变的两条：**不进日志、不离开模块**。
 *
 * ## 怎么用
 *
 * 用到 cookie 之前先 [attach] 一次（幂等）—— 它把落盘的那份灌进内存。之后 [get] 只读内存，
 * 可以放心在热路径上调。宿主再来同步时 [put] 会顺手写回磁盘。
 */
object TraceCookieCache {

    @Volatile private var cookie: String? = null

    @Volatile private var syncedAt = 0L

    /** 菜鸟 WebView 的真实 UA（与 cookie 同批同步）。null = 还没同步过。 */
    @Volatile var hostUa: String? = null
        private set

    /** 落盘位置。没 [attach] 过时为 null —— 那种情况下 [put] 只写内存（不报错）。 */
    @Volatile private var store: Context? = null

    @Volatile private var restored = false

    /**
     * 绑定落盘位置并把上次同步的登录态读回内存（幂等）。
     *
     * **必须在 [get] 之前调用一次**。三个入口都调了它：广播接收器（宿主同步到达时）、
     * [ModuleTraceFetcher] 的三个拉取入口（都是模块自己发起的）。
     *
     * 用 `applicationContext` 存着：这是进程级单例，攥着 Activity 会漏。
     */
    fun attach(context: Context) {
        val app = context.applicationContext
        store = app
        if (restored) return
        restored = true
        val stored = TraceCookieStore.read(app) ?: return
        if (cookie == null) {
            cookie = stored.cookie
            syncedAt = stored.syncedAt
            if (!stored.ua.isNullOrBlank()) hostUa = stored.ua
            // cookie 是本机登录态，落盘是本机的取舍 —— 但**值不进日志**，只记有没有。
            CainiaoTraceApi.preferredUa = hostUa
        }
    }

    /** 当前可用的 cookie；从未同步过（内存与磁盘都没有）返回 null。 */
    fun get(): String? = cookie

    fun put(value: String, ua: String? = null) {
        if (value.isBlank()) return
        cookie = value
        if (!ua.isNullOrBlank()) hostUa = ua
        syncedAt = System.currentTimeMillis()
        // 只有模块进程会调到这里（接收器在模块进程里跑），所以 store 正常情况下非空。
        store?.let { TraceCookieStore.write(it, value, hostUa) }
    }

    /**
     * 丢掉当前登录态（内存 + 磁盘）。
     *
     * ⚠️ **当前没有调用点，但别当死代码删**。历史上两处都用过它、两处都被真机否定：
     * - 身份码那条路曾经一收到 `FAIL_SYS_SESSION_EXPIRED` 就清 —— 而实证表明那个失败跟登录态
     *   无关（那条接口在 H5 通道上就是关着的），清掉只是把轨迹链路一起弄断。身份码现在改为
     *   请菜鸟用自己的会话取，**彻底不再用 cookie**。
     * - 轨迹那条路的失败原因里，「被风控」占绝大多数，那时清 cookie 只会把下一轮也搭进去。
     *
     * 它真正该被调用的场景是**确证过期**：轨迹与身份码同时被服务端以登录态为由拒绝、
     * 且重新索要来的那份也不管用。在那之前，留着旧的总比手里什么都没有强。
     */
    fun invalidate(context: Context) {
        cookie = null
        syncedAt = 0L
        TraceCookieStore.clear(context.applicationContext)
    }

    /** 诊断用（日志只打「有没有」与时刻，**绝不打内容**）。 */
    fun describe(): String = if (cookie != null) "cached(at=$syncedAt)" else "empty"
}
