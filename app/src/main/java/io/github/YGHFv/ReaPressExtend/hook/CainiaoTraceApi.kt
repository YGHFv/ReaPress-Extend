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
 * 调 `queryalltrace` 拿全轨迹 —— 宿主首页只给一句 `lastLogisticDetail`，这条路能给全部。
 *
 * ## 三段式（真机实测跑通，证据见 `express-source-research.md`）
 *
 * 1. **预热**：不带 sign 发一次，服务端回**两条** `Set-Cookie` —— `_m_h5_tk` + `_m_h5_tk_enc`。
 *    ⚠️ 必须成对带上。只带 `_m_h5_tk` 会得到 `FAIL_SYS_TOKEN_EMPTY`（第一次就栽在这里）。
 * 2. 用 `_m_h5_tk` 里 `_` 前面那 32 位算 `sign = md5(token & t & appKey & data)`。
 * 3. 正式请求带回 sign 与两个 token。
 *
 * ## 用 HttpURLConnection 而不是 OkHttp
 *
 * 这段代码会被注入到**菜鸟进程**，而模块的依赖不会一起进去（`implementation` 只是编进模块 APK）。
 * 引第三方 HTTP 库等于引入一个宿主里不存在的类 —— 加载时直接 `NoClassDefFoundError`。
 * JDK 自带的 `HttpURLConnection` 在所有进程里都有。
 *
 * ## 风控是真实存在的
 *
 * 带假 token 请求会吃到 `FAIL_SYS_USER_VALIDATE / RGV587_ERROR::SM` 加一个处罚跳转页。
 * 我们用的是菜鸟自己那份真实登录态，与菜鸟 App 的正常请求同源同 IP，风险低，但**不追加重试**：
 * 被拦了就这一次算了，不拿用户的账号去撞。
 */
internal object CainiaoTraceApi {

    private const val BASE =
        "https://acs.m.taobao.com/h5/mtop.taobao.logisticstracedetailservice.queryalltrace/1.0/"

    private const val APP_KEY = MtopSign.TAOBAO_H5_APP_KEY

    /** 与菜鸟 H5 页面同族的 Android 浏览器 UA（实测可用），也是 [preferredUa] 缺席时的兜底。 */
    private const val UA_DEFAULT =
        "Mozilla/5.0 (Linux; Android 12; M2102K1C) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/105.0.0.0 Mobile Safari/537.36 EdgA/105.0.1343.48"

    /**
     * 菜鸟 WebView 的真实 UA（宿主进程随 cookie 一起同步过来）。
     *
     * **为什么 UA 重要**：cookie 是菜鸟 App 内 WebView 写下的，服务端画像里这批登录态
     * 的「日常浏览器」就是菜鸟的 WebView —— 我们却拿一个 2022 年的老 Edge UA 去用它们，
     * 「cookie 画像说菜鸟、UA 说是三年前的浏览器」本身就是风控特征。换成宿主真实 UA
     * 让请求与 cookie 画像一致，是最便宜的伪装。拿不到（hook 没同步过）退回 [UA_DEFAULT]。
     */
    @Volatile var preferredUa: String? = null

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * 撞到风控后的**全局**退避基线。真机实证（2026-09-26）：连续拉 5 个单号都成功后，
     * 第 6、7 个的预热直接回 `FAIL_SYS_USER_VALIDATE / RGV587_ERROR::SM::哎哟喂,被挤爆啦` ——
     * 风控按**请求频率**收网，不是按单号。
     *
     * 退避时长**指数递增**（[RISK_BACKOFF_MS] · 2^level，封顶 1 小时）：
     * 14:33 撞线后 36 分钟内的下一次请求仍然被拦 —— 处罚窗口是小时级的余温，
     * 固定 10 分钟等于退避一结束就再撞一次，把窗口越撞越长。只有**成功**才清零。
     */
    internal const val RISK_BACKOFF_MS = 10 * 60_000L

    /** 退避封顶 1 小时：再长就该提示用户「稍后再看」，而不是无限憋着。 */
    private const val RISK_BACKOFF_MAX_MS = 60 * 60_000L

    /** 当前退避档位（指数的指数）。@Volatile：hook / 模块两个进程各自一份，进程内并发读。 */
    @Volatile private var backoffLevel = 0

    /** 风控退避的截止时刻；0 = 从未触发。只在检测到风控响应时由 [fetch] 自己写。 */
    @Volatile
    var riskBlockedUntil = 0L
        private set

    /**
     * 记录风控时的回调（模块进程注入，把截止时刻写进 prefs）。
     *
     * **为什么要持久化**：退避状态本来只存内存 —— 模块进程被杀（安装新包 / 用户从最近任务
     * 划掉 / 系统回收）就归零，下次点详情立刻再撞一次线。15:32 的日志抓到实证：上一进程
     * 15:16 撞线进了 20 分钟退避，新进程 15:32（退避期内！）照发不误。风控处罚是小时级
     * 余温，进程一重启退避就清零等于退避是装饰。
     */
    @Volatile internal var onRiskMarked: ((Long) -> Unit)? = null

    /** 进程重启后由持久化层调用：把上次记下的截止时刻恢复进来（只许推后，不许提前）。 */
    internal fun restoreRisk(until: Long) {
        if (until > riskBlockedUntil) riskBlockedUntil = until
    }

    /** 现在是否还在风控退避期内。调用方（[CainiaoTraceFetcher]）在此期间**连坑都不该占**。 */
    internal fun riskBlocked(now: Long = System.currentTimeMillis()): Boolean =
        now < riskBlockedUntil

    /** 记一次风控：退避时长翻倍、截止时刻后推、通知持久化、token 缓存清掉。 */
    private fun markRiskBlocked() {
        val backoff = (RISK_BACKOFF_MS shl backoffLevel).coerceAtMost(RISK_BACKOFF_MAX_MS)
        if (backoff < RISK_BACKOFF_MAX_MS) backoffLevel++
        riskBlockedUntil = System.currentTimeMillis() + backoff
        cachedToken = null
        runCatching { onRiskMarked?.invoke(riskBlockedUntil) }
    }

    /**
     * 预热拿到的 token 缓存。`_m_h5_tk` 的有效期是天级的，一次预热能用一整天 ——
     * 每次 fetch 都预热等于**请求数翻倍**（预热那发也会被风控计数），是纯浪费。
     * 风控 / 解析失败时清掉，下次重新预热。
     */
    @Volatile private var cachedToken: Token? = null

    /** 预热时服务端下发的两个 token。 */
    private data class Token(val raw: String, val enc: String?)

    /**
     * 菜鸟进程入口：cookie 从菜鸟自己的 WebView 库里读（[CainiaoCookieSource]）。
     * 模块进程走 [fetch] 的 cookie 直传重载 —— 那边读不到这个文件。
     */
    fun fetch(context: Context, trackingNumber: String): CainiaoTraceInfo? =
        fetch(CainiaoCookieSource.cookie(context), trackingNumber)

    /** [fetch] 的 cookie 直传入口（模块进程经 `TraceCookieCache` 调这里）。 */
    fun fetch(cookie: String?, trackingNumber: String): CainiaoTraceInfo? {
        if (cookie.isNullOrBlank()) {
            // 用户没在菜鸟里登录过淘宝系账号 —— 这不是错误，只是这条路走不通。
            XposedBridge.logAlways("cainiao trace: 没有可用 cookie，跳过 ${trackingNumber.take(6)}…")
            return null
        }

        // token 的三级来源：**cookie 里现成的**（菜鸟 WebView 跑过 H5 页面时写进 Cookies 库的，
        // 随 cookie 一起同步过来）→ 内存缓存（上次预热拿的）→ 预热。每次 fetch 都预热等于
        // 请求数翻倍（预热那发也会被风控计数），能省则省。
        // cookie 里拿的不进缓存：它会随菜鸟的使用自己更新，缓存反而可能钉死一份过期的。
        val token = tokenFromCookie(cookie) ?: cachedToken
            ?: warmUp(cookie)?.also { cachedToken = it } ?: run {
                XposedBridge.logAlways("cainiao trace: 预热没拿到 token，跳过 ${trackingNumber.take(6)}…")
                return null
            }

        val data = requestBody(trackingNumber)
        val timestamp = System.currentTimeMillis().toString()
        val sign = MtopSign.wapSign(MtopSign.tokenOf(token.raw), timestamp, APP_KEY, data)

        val url = BASE + "?jsv=2.3.18&appKey=" + APP_KEY + "&t=" + timestamp + "&sign=" + sign +
            "&type=originaljson&data=" + URLEncoder.encode(data, "UTF-8")

        val body = get(url, cookieWithTokens(cookie, token)).first
        val info = CainiaoTraceParser.parse(body)
        if (info == null) {
            // 把响应开头打出来 —— 失败原因是「被风控拦了」还是「接口形状变了」，一眼可分。
            // 预热通过了但正式请求被拦的情况同样存在（token 拿到了、请求节奏太密），
            // 这里也要认风控，别让退避只覆盖一半链路。
            if (isRiskResponse(body)) {
                markRiskBlocked()
                XposedBridge.logAlways("cainiao trace: 正式请求被风控，指数退避（档位=$backoffLevel）")
            }
            XposedBridge.logAlways(
                "cainiao trace: 解析不出结果 tn=${trackingNumber.take(6)}… resp=${body.take(120)}",
            )
        } else {
            // 成功是退避档位唯一归零的时刻 —— 说明当前节奏服务端能接受。
            backoffLevel = 0
        }
        return info
    }

    /** 风控响应的指纹：`ret` 数组里的这两个词。命中任何一处都算。 */
    private fun isRiskResponse(body: String): Boolean =
        body.contains("FAIL_SYS_USER_VALIDATE") || body.contains("RGV587")

    /**
     * 从 cookie 串里直接取 token（菜鸟 WebView 写进 Cookies 库的那份）。
     * **必须成对**（`_m_h5_tk` + `_m_h5_tk_enc`）才返回 —— 只有一半连 `FAIL_SYS_TOKEN_EMPTY`
     * 都省了服务端的口水，直接算失败（第一次实现时就栽在「只带一半」上）。
     */
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

    /** 预热：一次不带 sign 的请求，目的只是让服务端把 `_m_h5_tk` / `_m_h5_tk_enc` 发下来。 */
    private fun warmUp(cookie: String): Token? {
        val (body, setCookies) = get("$BASE?appKey=$APP_KEY", cookie)

        var raw: String? = null
        var enc: String? = null
        for (header in setCookies) {
            H5_TOKEN.find(header)?.let { raw = it.groupValues[1] }
            H5_TOKEN_ENC.find(header)?.let { enc = it.groupValues[1] }
        }

        // 服务端有时把 token 放在响应的 `c` 字段（`token_时间戳;enc`）而不是 Set-Cookie 里。
        // 两条路都试 —— 实测走 Set-Cookie，但这里多一层不花什么代价。
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
                XposedBridge.logAlways("cainiao trace: 撞到风控，指数退避（档位=$backoffLevel）")
            } else {
                XposedBridge.logAlways("cainiao trace: 预热响应无 token，resp=${body.take(120)}")
            }
            return null
        }
        return Token(raw = raw, enc = enc?.takeIf { it.isNotBlank() })
    }

    /** 请求体。字段名照抄菜鸟裹裹 H5 自己的那套（`appName=GUOGUO`）。 */
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

    /**
     * @return 响应体 + 所有 `Set-Cookie` 头。
     *
     * **不跟随重定向**：被风控时服务端会 302 到处罚页，跟过去只会拿到一坨 HTML 并假装成功，
     * 不如让 `parse` 直接失败。
     */
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
