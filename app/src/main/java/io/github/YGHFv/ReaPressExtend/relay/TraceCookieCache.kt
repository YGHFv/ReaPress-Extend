package io.github.YGHFv.ReaPressExtend.relay

/**
 * 淘宝登录态 cookie 的模块进程内存缓存。
 *
 * ## 为什么存在
 *
 * 轨迹拉取收拢到模块进程后（`ExpressRelay.ACTION_TRACE_REQUEST` 的注释），模块进程要能
 * 自己发 MTOP 请求 —— 但 cookie 只存在菜鸟的私有目录里，模块（不同 uid）读不到，
 * 只能由 hook 侧经 `ACTION_COOKIE_SYNC` 送进来。这里就是落点。
 *
 * ## 边界（刻意收得很窄）
 *
 * - **只进内存，不落盘、不进日志**：进程被杀就丢，等宿主下次投递再同步。
 *   cookie 是登录态，任何落盘都会把它从「运行时凭证」变成「静态资产」，泄露面完全不同。
 * - 空串不缓存：宿主侧读不到 cookie 时会跳过同步，但防御性起见这里也拦一道。
 */
object TraceCookieCache {

    @Volatile private var cookie: String? = null

    @Volatile private var syncedAt = 0L

    /** 菜鸟 WebView 的真实 UA（与 cookie 同批同步）。null = 还没同步过。 */
    @Volatile var hostUa: String? = null
        private set

    /** 当前缓存的 cookie；从未同步过返回 null。 */
    fun get(): String? = cookie

    fun put(value: String, ua: String? = null) {
        if (value.isBlank()) return
        cookie = value
        if (!ua.isNullOrBlank()) hostUa = ua
        syncedAt = System.currentTimeMillis()
    }

    /** 诊断用（日志只打「有没有」与时刻，**绝不打内容**）。 */
    fun describe(): String = if (cookie != null) "cached(at=$syncedAt)" else "empty"
}
