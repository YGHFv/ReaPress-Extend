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
import io.github.YGHFv.ReaPressExtend.core.CainiaoTraceInfo
import io.github.YGHFv.ReaPressExtend.core.CainiaoTraceParser
import io.github.YGHFv.ReaPressExtend.core.MtopSign
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 调 `mtop.taobao.logisticstracedetailservice.queryalltrace` 拿全轨迹。三段式：预热拿 `_m_h5_tk`（必须与
 * `_m_h5_tk_enc` 成对带）→ 用 token 里 `_` 前 32 位算 sign → 正式请求。只用 JDK 自带 HttpURLConnection
 * （注入进程引第三方库会 NoClassDefFoundError）。风控真实存在（RGV587），不追加重试。
 */
internal object CainiaoTraceApi {

    private const val HOST = "https://acs.m.taobao.com/h5/"

    private const val TRACE_API = "mtop.taobao.logisticstracedetailservice.queryalltrace"
    private const val TRACE_VERSION = "1.0"

    private const val APP_KEY = MtopSign.TAOBAO_H5_APP_KEY

    internal const val UA_DEFAULT =
        "Mozilla/5.0 (Linux; Android 12; M2102K1C) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/105.0.0.0 Mobile Safari/537.36 EdgA/105.0.1343.48"

    /** 菜鸟 WebView 的真实 UA（宿主随 cookie 同步过来）；UA 必须与 cookie 画像一致，那正是风控特征。 */
    @Volatile var preferredUa: String? = null

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** 风控退避基线：按请求频率收网（连续 5 个成功后第 6 个被拦）；时长指数递增封顶 1 小时，只有成功才清零。 */
    internal const val RISK_BACKOFF_MS = 10 * 60_000L

    private const val RISK_BACKOFF_MAX_MS = 60 * 60_000L

    @Volatile private var backoffLevel = 0
    private val riskLock = Any()

    @Volatile
    var riskBlockedUntil = 0L
        private set

    /** 退避必须持久化：只存内存的话进程被杀就归零，下次点详情立刻再撞一次线。 */
    @Volatile internal var onRiskMarked: ((Long) -> Unit)? = null

    internal fun restoreRisk(until: Long): Unit = synchronized(riskLock) {
        if (until > riskBlockedUntil) riskBlockedUntil = until
    }

    internal fun riskBlocked(now: Long = System.currentTimeMillis()): Boolean =
        now < riskBlockedUntil

    private fun markRiskBlocked() {
        val until = synchronized(riskLock) {
            val backoff = (RISK_BACKOFF_MS shl backoffLevel).coerceAtMost(RISK_BACKOFF_MAX_MS)
            if (backoff < RISK_BACKOFF_MAX_MS) backoffLevel++
            riskBlockedUntil = maxOf(riskBlockedUntil, System.currentTimeMillis() + backoff)
            riskBlockedUntil
        }
        cachedTokens.clear()
        runCatching { onRiskMarked?.invoke(until) }
    }

    /** 预热拿到的 token 缓存，按 host 分开（不分开的失败与「没 token」同形、最容易查错方向）。 */
    private val cachedTokens = java.util.concurrent.ConcurrentHashMap<String, SessionToken>()

    internal fun clearCachedTokens() {
        cachedTokens.clear()
    }

    private data class Token(val raw: String, val enc: String?)
    private data class SessionToken(val cookie: String, val token: Token)

    @Volatile
    var lastError: String? = null
        private set

    private fun fail(reason: String): String? {
        lastError = reason
        return null
    }

    fun fetch(context: Context, trackingNumber: String): CainiaoTraceInfo? =
        fetch(HostCredentialSource.cookie(context), trackingNumber)

    fun fetch(cookie: String?, trackingNumber: String): CainiaoTraceInfo? {
        val body = callH5(TRACE_API, TRACE_VERSION, requestBody(trackingNumber), cookie)
            ?: return null
        val info = CainiaoTraceParser.parse(body)
        if (info == null) {
            lastError = "轨迹接口返回的内容解析不出结果"
            XposedBridge.logAlways(
                "cainiao trace: 解析不出结果 tn=${trackingNumber.take(6)}… resp=${body.take(120)}",
            )
            return null
        }
        synchronized(riskLock) { backoffLevel = 0 }
        lastError = null
        return info
    }

    /** 通用 MTOP H5 GET（预热→sign→正式请求）。只对 H5 通道开放的接口有效：身份码接口预热不给 token，别往这里接。 */
    fun callH5(
        api: String,
        version: String,
        data: String,
        cookie: String?,
        host: String = HOST,
    ): String? {
        if (cookie.isNullOrBlank()) {
            XposedBridge.logAlways("cainiao h5: 没有可用 cookie，跳过 $api")
            return fail("没有可用的淘宝登录态（请在菜鸟里登录后重试）")
        }

        val base = "$host$api/$version/"
        // token 来源：cookie 里现成的 → 内存缓存 → 预热；cookie 那份只在默认 host 用（分不出属于谁）。
        val token = (if (host == HOST) tokenFromCookie(cookie) else null)
            ?: cachedTokens[host]?.takeIf { it.cookie == cookie }?.token
            ?: warmUp(base, cookie)?.also { cachedTokens[host] = SessionToken(cookie, it) }
            ?: run {
                XposedBridge.logAlways("cainiao h5: 预热没拿到 token，跳过 $api")
                return fail(lastError ?: "预热没拿到 token（网络不通或被风控拦下）")
            }

        val timestamp = System.currentTimeMillis().toString()
        val sign = MtopSign.wapSign(MtopSign.tokenOf(token.raw), timestamp, APP_KEY, data)

        val url = base + "?jsv=2.3.18&appKey=" + APP_KEY + "&t=" + timestamp + "&sign=" + sign +
            "&type=originaljson&data=" + URLEncoder.encode(data, "UTF-8")

        val body = get(url, cookieWithTokens(cookie, token)).first
        // 风控检查放这里而非「解析失败之后」：预热通过但正式请求被拦同样存在。
        if (isRiskResponse(body)) {
            markRiskBlocked()
            XposedBridge.logAlways("cainiao h5: 请求被风控，指数退避（档位=$backoffLevel）")
            lastError = "被淘宝风控拦下（按请求频率保护），稍后再试"
        }
        return body
    }

    private fun isRiskResponse(body: String): Boolean =
        body.contains("FAIL_SYS_USER_VALIDATE") || body.contains("RGV587")

    private fun tokenFromCookie(cookie: String): Token? {
        var raw: String? = null
        var enc: String? = null
        for (piece in cookie.split(';')) {
            H5_TOKEN.find(piece)?.let { raw = it.groupValues[1] }
            H5_TOKEN_ENC.find(piece)?.let { enc = it.groupValues[1] }
        }
        raw = raw?.takeIf { it.isNotBlank() }
        enc = enc?.takeIf { it.isNotBlank() }
        return if (raw != null && enc != null) Token(raw, enc) else null
    }

    /** 预热：不带 sign 的请求，只为让服务端下发 token；服务端有时把 token 放响应 `c` 字段，两条路都试。 */
    private fun warmUp(base: String, cookie: String): Token? {
        val (body, setCookies) = get("${base}?appKey=$APP_KEY", cookie)

        var raw: String? = null
        var enc: String? = null
        for (header in setCookies) {
            H5_TOKEN.find(header)?.let { raw = it.groupValues[1] }
            H5_TOKEN_ENC.find(header)?.let { enc = it.groupValues[1] }
        }

        // 服务端有时把 token 放在响应的 `c` 字段（`token_时间戳;enc`）。
        if (raw == null) {
            val combined = runCatching {
                JSONObject(body).optString("c").takeIf { it.isNotBlank() }
            }.getOrNull()
            val pieces = combined?.split(';')
            if (pieces != null && pieces.size >= 2) {
                raw = pieces[0]
                enc = pieces[1]
            }
        }

        if (raw.isNullOrBlank()) {
            if (isRiskResponse(body)) {
                markRiskBlocked()
                XposedBridge.logAlways("cainiao h5: 预热撞到风控，指数退避（档位=$backoffLevel）")
                fail("被淘宝风控拦下（按请求频率保护），稍后再试")
            } else {
                XposedBridge.logAlways("cainiao h5: 预热响应无 token，resp=${body.take(120)}")
                lastError = "预热响应里没有 token（接口形状可能变了）"
            }
            return null
        }
        return Token(raw = raw, enc = enc?.takeIf { it.isNotBlank() })
    }

    private fun requestBody(trackingNumber: String): String = JSONObject()
        .put("mailNo", trackingNumber)
        .put("appName", "GUOGUO")
        .put("actor", "RECEIVER")
        .put("isShowItem", true)
        .put("isShowConsignDetail", true)
        .put("isUnique", true)
        .put("isStandard", true)
        .toString()

    private fun cookieWithTokens(cookie: String, token: Token): String = buildString {
        append(cookie)
        append("; _m_h5_tk=").append(token.raw)
        token.enc?.let { append("; _m_h5_tk_enc=").append(it) }
    }

    /** 响应体 + 所有 Set-Cookie 头。不跟随重定向：风控 302 到处罚页，跟过去只会拿到 HTML 并假装成功。 */
    private fun get(url: String, cookie: String): Pair<String, List<String>> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", preferredUa?.takeIf { it.isNotBlank() } ?: UA_DEFAULT)
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Origin", "https://page.cainiao.com")
            setRequestProperty("Referer", "https://page.cainiao.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val setCookies = connection.headerFields.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value
                .orEmpty()
            body to setCookies
        } finally {
            connection.disconnect()
        }
    }

    private val H5_TOKEN = Regex("_m_h5_tk=([^;]+)")
    private val H5_TOKEN_ENC = Regex("_m_h5_tk_enc=([^;]+)")
}
