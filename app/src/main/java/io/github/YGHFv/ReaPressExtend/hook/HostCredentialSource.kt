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
 * 从当前被注入的宿主进程取淘宝登录态，实现只认「本进程的 WebView cookie」、不认包名。
 * 两条独立渠道任一命中即够：[fromCookieManager]（首选，AOSP 公开 API、拿到的是 WebView
 * 内存里的权威值，要求调用线程有 Looper 且 WebView 已初始化）；[fromDatabases]（兜底，
 * 遍历 dataDir 下所有名字带 webview 的目录、试 Default/Cookies、Default/Network/Cookies、
 * Cookies 三种布局——Chromium 96 起 cookie 挪了位置，多进程/容器目录各一套，路径猜错一次
 * 整条链路就静默断掉）。每个渠道自包 runCatching，异常退化为不干预，绝不冒泡到宿主。
 */
internal object HostCredentialSource {

    private const val TAG = "host credential"

    /** 域名 cookie 对子域生效，查哪个 URL 就等于要哪个域的登录态。 */
    private val PROBE_URLS = listOf(
        "https://acs.m.taobao.com",
        "https://h5.m.taobao.com",
    )

    /**
     * LIKE '%taobao%' 是刻意的宽容：精确匹配会漏掉 acs.m.taobao.com 这类 host cookie
     * （`_m_h5_tk` 就是这一类）。域名 cookie 优先、长值优先——同名 cookie 在多个域下都存在时，
     * .taobao.com 那份才是全站有效的。
     */
    private const val SQL_TAOBAO_COOKIES =
        "SELECT name, value FROM cookies WHERE host_key LIKE '%taobao%' " +
            "ORDER BY (host_key = '.taobao.com') DESC, length(value) DESC"

    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private const val MAX_COOKIE_CHARS = 8 * 1024

    /** 超时只意味着退回 SQLite；这条链路跑在宿主的查询回调线程上，卡住宿主比读不到更严重，所以取短。 */
    private const val WEBVIEW_WAIT_MS = 800L

    @Volatile private var cached: String? = null

    @Volatile private var cachedAt = 0L

    /**
     * 最近一次「为什么没拿到」的人话说明（成功时清空），由 [ExpressRelaySender.sendCookieSync]
     * 放进回执——模块侧能直接读到原因，不必去翻 hook 日志（要 root 才看得到）。
     * 以前「库里没有 .taobao.com 域」这条路径完全静默，用户报「怎么都获取不了」查两轮都停在这里。
     */
    @Volatile var lastReason: String = ""
        private set

    /**
     * force 跳过缓存直接重读：只在「模块明确索要登录态」时传——那种场景下手里那份刚被判定
     * 不可用。两条渠道都没命中时返回缓存（若曾有），由服务端决定它是否还有效。
     */
    fun cookie(context: Context, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { if (now - cachedAt < MAX_AGE_MS) return it }
        }

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
     * 直接问 WebView 要。CookieManager.getInstance() 要求 WebView 已初始化（Android 8+ 抛
     * IllegalStateException），getCookie 要求调用线程有 Looper——拿不到就走下一条渠道。
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

    /** 在主线程上取值并等结果。已在主线程就直接调——post 给自己会自己等自己，必然超时。 */
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

        // 逐个记「试了哪个、结果如何」——路径猜错时这就是唯一的分界证据。
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

        // 全部落空才做汇总：装了别的域才是「用户真没登录淘宝」，装了 taobao 域却没命中才是「读错了库」，处置不同。
        val summary = files.take(3).joinToString(" | ") { "${name(context, it)}=[${hostSummary(it)}]" }
        notes += "候选库都没命中 taobao 域：${probe.joinToString(", ")}。库内域名：$summary"
        return null
    }

    /**
     * 所有值得一试的 cookie 库。三种布局都试（Chromium 96 前后）；目录名不做白名单——
     * 只要名字带 webview 就进去看，猜名字的代价我们已经付过一次了。
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
                // 同名 cookie 可能跨域重复，请求头里只能留一个；SQL 已让域名 cookie 排前，保留先到的。
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
        XposedBridge.logError("$TAG: 打开 ${file.name} 失败，换下一个候选", error)
        null
    }

    /** 库里按条数排前几名的 host_key，诊断的关键，只在全部落空时才查。 */
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

    /** 只取相对 dataDir 的路径：回执里更好读，也不泄露绝对路径。 */
    private fun name(context: Context, file: File): String =
        runCatching { file.relativeTo(context.dataDir).path }.getOrDefault(file.name)

    private const val MAX_REASON_CHARS = 400
}
