package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.io.File

/**
 * 从菜鸟**自己的** WebView cookie 库里取淘宝登录态。
 *
 * ## 为什么不需要登录、也不需要淘宝 App
 *
 * 菜鸟是阿里系应用，它的 WebView 里早就带着一份完整的 `.taobao.com` 登录态。真机实测
 * （2026-09-26）该库里有 26 条 `.taobao.com` cookie：`sgcookie`(140B) · `cookie2` · `_tb_token_`
 * · `unb` · `cna` · `tfstk` · `sg` · `skt` · `dnk` …，值**明文未加密**。
 *
 * 所以本模块复用的是**宿主已有的登录态**，不是自己维护一套账号 —— 这与「只复用宿主已解析好的
 * 对象」是同一个原则，只是从「对象」延伸到了「凭据」。用户不需要做任何事，也不会多出一个
 * 要登录、要过期、要重新授权的东西。
 *
 * ## 为什么不走 `CookieManager`
 *
 * `android.webkit.CookieManager.getInstance().getCookie(url)` 是官方 API，但它要求调用线程
 * 有 Looper、且会**触发 WebView 初始化**（首次几十到上百毫秒，还可能改变宿主的 WebView 状态）。
 * hook 回调跑在宿主自己的查询线程上，这两条都是负担。直接只读打开 SQLite 更可控：
 * 同 uid 读自己的数据目录，不需要 root，也不惊动 WebView。
 *
 * 代价是**读到的可能比 WebView 内存里的旧一点**（未 checkpoint 的写入）。对这里的用途没有影响 ——
 * `sgcookie` 这类是月级有效期，晚几分钟读到完全一样的值。
 */
internal object CainiaoCookieSource {

    private const val TAG = "cainiao cookie"

    /** 只取这个域：`acs.m.taobao.com` 是它的子域，域名 cookie 直接生效。 */
    private const val DOMAIN = ".taobao.com"

    /** 缓存时长。凭据是月级的，缓存十分钟纯粹是为了避免每次请求都开一次数据库。 */
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    /** 拼串上限，防呆：真机实测全套约 1500 字符，超过这个数说明库里进了脏东西。 */
    private const val MAX_COOKIE_CHARS = 8 * 1024

    @Volatile private var cached: String? = null

    @Volatile private var cachedAt = 0L

    /**
     * @return `name=value; name=value` 形式的 Cookie 头值；库里没有（用户没在菜鸟里登录过
     *   淘宝系账号）或读取失败时返回 null。
     *
     * 读取失败时**退回上一次的缓存**而不是直接给 null：多一次机会比直接放弃好，而凭据过期与否
     * 最终由服务端说了算（它会回 `FAIL_SYS_TOKEN_EMPTY`）。
     */
    fun cookie(context: Context): String? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < MAX_AGE_MS) return it }

        val fresh = read(context)
        if (fresh.isNullOrEmpty()) return cached

        cached = fresh
        cachedAt = now
        return fresh
    }

    private fun read(context: Context): String? = runCatching {
        val file = File(context.dataDir, WEBVIEW_COOKIE_PATH)
        if (!file.exists()) {
            XposedBridge.logAlways("$TAG: 没找到 ${file.path}（菜鸟里可能从未开过 WebView）")
            return@runCatching null
        }

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT name, value FROM cookies WHERE host_key = ?",
                arrayOf(DOMAIN),
            ).use { cursor ->
                val parts = StringBuilder()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val value = cursor.getString(1)
                    if (name.isNullOrBlank() || value.isNullOrBlank()) continue
                    if (parts.isNotEmpty()) parts.append("; ")
                    parts.append(name).append('=').append(value)
                    if (parts.length >= MAX_COOKIE_CHARS) break
                }
                parts.toString()
            }
        }
    }.getOrElse {
        XposedBridge.logError("$TAG: 读取失败（退化用缓存）", it)
        null
    }

    private const val WEBVIEW_COOKIE_PATH = "app_webview/Default/Cookies"
}
