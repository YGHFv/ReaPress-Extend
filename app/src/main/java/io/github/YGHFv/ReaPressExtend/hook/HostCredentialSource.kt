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

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 从**当前被注入的宿主进程**里取淘宝登录态，供模块发 MTOP 请求使用。
 *
 * 菜鸟进程里跑就是取菜鸟的，淘宝进程里跑就是取淘宝的 —— 实现只认「本进程的 WebView
 * cookie」，不认包名（包名差异全部收在 [ExpressHookDispatcher] 的分派上）。
 *
 * ## 为什么要多渠道
 *
 * 2026-09-26 真机实测：只读 `app_webview/Default/Cookies` 这一条路时，模块侧永远拿不到
 * 登录态，日志停在「库里有 cookie、但没有 `.taobao.com` 域」。**单点依赖是这个问题的根源** ——
 * Chromium 从 96 起把 cookie 库挪到了 `Default/Network/Cookies`，多进程 WebView（
 * `:channel`、`_render_proc0`）各有自己的数据目录，X5 / 华为的容器目录名又是另一套。
 * 只要路径猜错一次，整条链路就静默断掉。
 *
 * 所以现在按「可靠性」排两条独立渠道，任一条命中就够：
 *
 * 1. **[fromCookieManager]（首选）**：`android.webkit.CookieManager#getCookie(url)` —— 这是
 *    AOSP 公开 API，**不猜路径、不解析文件格式**，拿到的是 WebView 内存里那份权威值
 *    （比磁盘新，过期由 WebView 自己管）。代价是要求调用线程有 Looper。
 * 2. **[fromDatabases]（兜底）**：遍历 `dataDir` 下所有 `*webview*` 目录，逐个试
 *    `Default/Cookies` / `Default/Network/Cookies` / `Cookies` 三种布局，
 *    用 `host_key LIKE '%taobao%'` 而不是 `= '.taobao.com'` 精确匹配
 *    （`acs.m.taobao.com`、`login.taobao.com` 这些 host cookie 也该收进来）。
 *
 * ## 为什么不只调 CookieManager
 *
 * [fromCookieManager] 有 Looper 与 WebView 初始化两个前提，而 hook 回调线程未必满足，
 * 首次初始化 WebView 也可能超出等待预算。它是**首选**，不能是**唯一**。
 *
 * ## 为什么不用 `ActivityThread` 之外的隐藏 API
 *
 * 整条链路只碰公开类：`CookieManager`、`SQLiteDatabase`、`Looper`。被注入进程里的铁律是
 * 「异常退化为不干预」—— 每个渠道都自包 `runCatching`，失败就往下一个走，绝不冒泡到宿主。
 */
internal object HostCredentialSource {

    private const val TAG = "host credential"

    /** 域名 cookie 直接对子域生效，所以「查哪个 URL」就等于「要哪个域的登录态」。 */
    private val PROBE_URLS = listOf(
        "https://acs.m.taobao.com",
        "https://h5.m.taobao.com",
    )

    /**
     * 按域取 cookie。
     *
     * `LIKE '%taobao%'` 是刻意的宽容：精确匹配 `.taobao.com` 会漏掉 host cookie
     * （`acs.m.taobao.com` 上的 `_m_h5_tk` 就是这一类）。
     * 排序让**域名 cookie 优先、长值优先** —— 同名 cookie 在多个域下都存在时，
     * `.taobao.com` 那份才是全站有效的那份。
     */
    private const val SQL_TAOBAO_COOKIES =
        "SELECT name, value FROM cookies WHERE host_key LIKE '%taobao%' " +
            "ORDER BY (host_key = '.taobao.com') DESC, length(value) DESC"

    /** 缓存时长。凭据是月级的，缓存十分钟纯粹是为了避免每次请求都开一次数据库。 */
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    /** 拼串上限，防呆：真机实测全套约 1500 字符，超过这个数说明库里进了脏东西。 */
    private const val MAX_COOKIE_CHARS = 8 * 1024

    /**
     * 等主线程回 CookieManager 结果的预算。
     *
     * 超时只意味着「退回 SQLite」，不意味着失败，所以取短一些：这条链路跑在宿主的查询
     * 回调线程上，**卡住宿主比读不到更严重**。
     */
    private const val WEBVIEW_WAIT_MS = 800L

    @Volatile private var cached: String? = null

    @Volatile private var cachedAt = 0L

    /**
     * 最近一次「为什么没拿到」的人话说明（成功时清空）。由 [ExpressRelaySender.sendCookieSync]
     * 放进回执，模块侧因此能直接读到原因，不必去翻 hook 日志（那边要 root 才看得到）。
     *
     * ⚠️ 加它的理由（2026-09-26 实测）：以前**库里没有 `.taobao.com` 域**这条路径是完全静默的
     * —— `read` 返回空串既不是异常也不是 null，一路走到「宿主没有登录态」，而**卡在哪一步
     * 一个字都没有**。用户报「这台设备怎么都获取不了」查了两轮都停在这里。
     */
    @Volatile var lastReason: String = ""
        private set

    /**
     * @param force 跳过 [MAX_AGE_MS] 缓存、直接重读。只在「模块明确索要一次登录态」时传 ——
     *   那种场景下模块手里的那份刚刚被判定为不可用，再给它一份十分钟前的缓存毫无意义。
     * @return `name=value; name=value` 形式的 Cookie 头值；两条渠道都没命中时返回 null
     *   （若曾有缓存则退化成缓存，由服务端决定它是否还有效）。
     */
    fun cookie(context: Context, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { if (now - cachedAt < MAX_AGE_MS) return it }
        }

        // 诊断按渠道累积：两条都空时，这段文字就是「卡在哪一步」的全部答案。
        val notes = ArrayList<String>()

        fromCookieManager(notes)?.let { fresh ->
            cached = fresh
            cachedAt = now
            lastReason = ""
            return fresh
        }

        fromDatabases(context, notes)?.let { fresh ->
            cached = fresh
            cachedAt = now
            lastReason = ""
            return fresh
        }

        lastReason = notes.joinToString("；").take(MAX_REASON_CHARS)
        XposedBridge.logAlways("$TAG: $lastReason")
        return cached
    }

    // ------------------------------------------------------------------ 渠道 1

    /**
     * 直接问 WebView 要。
     *
     * `CookieManager.getInstance()` 要求 WebView 已初始化（否则 Android 8+ 抛
     * `IllegalStateException`），`getCookie` 还要求调用线程有 Looper —— 两条都由
     * [onMainThread] 与 `runCatching` 兜住：**拿不到就走下一条渠道**。
     */
    private fun fromCookieManager(notes: MutableList<String>): String? {
        val failure = ArrayList<String>()
        val value = onMainThread {
            runCatching {
                val manager = CookieManager.getInstance()
                PROBE_URLS.firstNotNullOfOrNull { url ->
                    manager.getCookie(url)?.takeIf { it.isNotBlank() }
                }
            }.getOrElse { error ->
                // 最常见的是 WebView 还没初始化 —— 这不是异常情况，只是这条渠道此刻不可用。
                failure += "${error.javaClass.simpleName}: ${error.message}".take(80)
                null
            }
        }

        if (value.isNullOrEmpty()) {
            notes += "WebView CookieManager 没给出 cookie" +
                failure.firstOrNull()?.let { "（$it）" }.orEmpty()
        }
        return value
    }

    /**
     * 在**主线程**上取值并等结果。
     *
     * 已经在主线程就直接调（不能再 post 给自己 —— 那会自己等自己，必然超时）。
     * 用 [CountDownLatch] 而不是 `Future.get`：这里只是「等一个同步结果」，
     * 没有取消、没有异常传递需求，latch 的 happens-before 就够保证可见性。
     */
    private fun <T> onMainThread(block: () -> T): T? {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            return runCatching(block).getOrNull()
        }
        val latch = CountDownLatch(1)
        var result: T? = null
        val posted = runCatching {
            Handler(Looper.getMainLooper()).post {
                result = runCatching(block).getOrNull()
                latch.countDown()
            }
        }.getOrDefault(false)
        if (!posted) return null
        val finished = runCatching { latch.await(WEBVIEW_WAIT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        return if (finished) result else null
    }

    // ------------------------------------------------------------------ 渠道 2

    private fun fromDatabases(context: Context, notes: MutableList<String>): String? {
        val files = candidateDatabases(context)
        if (files.isEmpty()) {
            notes += "dataDir 下没有任何 webview cookie 库；${webviewDirs(context)}"
            return null
        }

        // 逐个记「试了哪个、结果如何」—— 路径猜错时，这就是唯一的分界证据。
        val probe = ArrayList<String>(files.size)
        for (file in files) {
            val cookie = readDatabase(file)
            if (!cookie.isNullOrEmpty()) {
                probe += "${name(context, file)}=命中${cookie.length}字符"
                notes += probe.joinToString(", ")
                return cookie
            }
            probe += "${name(context, file)}=空"
        }

        // 全部落空才做这次汇总（正常路径上零开销）：里面装了**别的域**才是「用户真没登录
        // 淘宝」，装了 taobao 域却没命中才是「我们读错了库」。两者处置完全不同。
        val summary = files.take(3).joinToString(" | ") { "${name(context, it)}=[${hostSummary(it)}]" }
        notes += "候选库都没命中 taobao 域：${probe.joinToString(", ")}。库内域名：$summary"
        return null
    }

    /**
     * 所有值得一试的 cookie 库。
     *
     * 三种布局都列出来的依据：Chromium 96 起 cookie 挪到 `Default/Network/Cookies`，
     * 而 `Cookies` 是更早期 / 其他容器的布局。目录名不做白名单 —— 只要名字带 `webview`
     * 就进去看（`app_webview`、`app_webview_<进程名>`、`app_hws_webview` … 都是这么命名的），
     * 猜名字的代价我们已经付过一次了。
     */
    private fun candidateDatabases(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        roots += File(context.dataDir, "app_webview")
        runCatching {
            context.dataDir.listFiles()
                ?.filter { it.isDirectory && it.name.contains("webview", ignoreCase = true) }
                ?.let { roots.addAll(it) }
        }

        val out = ArrayList<File>(roots.size * 3)
        for (root in roots) {
            out += File(root, "Default/Cookies")
            out += File(root, "Default/Network/Cookies")
            out += File(root, "Cookies")
        }
        // 只留真实存在的：不存在的文件走一遍 openDatabase 会抛异常，白白刷日志。
        return out.distinct().filter { it.isFile }
    }

    private fun readDatabase(file: File): String? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(SQL_TAOBAO_COOKIES, null).use { cursor ->
                // 同名 cookie 可能跨域重复（`.taobao.com` 与 `acs.m.taobao.com` 各有一份），
                // 而请求头里同名只能留一个 —— SQL 已让域名 cookie 排在前面，这里保留先到的。
                val seen = HashSet<String>()
                val sb = StringBuilder()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val value = cursor.getString(1)
                    if (name.isNullOrBlank() || value.isNullOrBlank()) continue
                    if (!seen.add(name)) continue
                    if (sb.isNotEmpty()) sb.append("; ")
                    sb.append(name).append('=').append(value)
                    if (sb.length >= MAX_COOKIE_CHARS) break
                }
                sb.toString()
            }
        }
    }.getOrElse { error ->
        // 打不开很正常（正在被 WebView 写、格式不是 SQLite）—— 记一笔就换下一个候选。
        XposedBridge.logError("$TAG: 打开 ${file.name} 失败，换下一个候选", error)
        null
    }

    /** 库里按条数排前几名的 `host_key`。**这是诊断的关键**，只在全部落空时才查。 */
    private fun hostSummary(file: File): String = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT host_key, COUNT(*) AS n FROM cookies GROUP BY host_key ORDER BY n DESC LIMIT 6",
                null,
            ).use { cursor ->
                val out = ArrayList<String>()
                while (cursor.moveToNext()) out += "${cursor.getString(0)}(${cursor.getInt(1)})"
                if (out.isEmpty()) "（库是空的）" else out.joinToString(", ")
            }
        }
    }.getOrDefault("（查询失败）")

    /** `dataDir` 下真实存在的 webview 相关目录，用于「路径变了」的情况。 */
    private fun webviewDirs(context: Context): String = runCatching {
        val names = context.dataDir.listFiles()
            ?.filter { it.isDirectory && it.name.contains("webview", ignoreCase = true) }
            ?.map { it.name }
            .orEmpty()
        if (names.isEmpty()) {
            "dataDir 下没有任何名字带 webview 的目录"
        } else {
            "dataDir 下有：${names.joinToString(", ")}"
        }
    }.getOrDefault("（列目录失败）")

    /** 只取相对 dataDir 的路径，回执里更好读，也不泄露绝对路径。 */
    private fun name(context: Context, file: File): String =
        runCatching { file.relativeTo(context.dataDir).path }.getOrDefault(file.name)

    /** 回执要走广播，太长没必要 —— 诊断信息取前 400 字符。 */
    private const val MAX_REASON_CHARS = 400
}
