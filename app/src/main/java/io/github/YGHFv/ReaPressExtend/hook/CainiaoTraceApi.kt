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

    /**
     * MTOP 的 H5 通道入口（轨迹这条路的默认 host）。
     *
     * ⚠️ **host 不止一个**：淘宝的 H5 接口按业务分在 `acs.m.taobao.com` 与 `h5api.m.taobao.com`
     * 两个域名下（订单列表就在后者），签名与 appKey 一样，但 token 是**按域名下发**的
     * （见 [cachedTokens]）。所以 [callH5] 的 host 是可传的。
     */
    private const val HOST = "https://acs.m.taobao.com/h5/"

    /** 全轨迹接口。 */
    private const val TRACE_API = "mtop.taobao.logisticstracedetailservice.queryalltrace"
    private const val TRACE_VERSION = "1.0"

    private const val APP_KEY = MtopSign.TAOBAO_H5_APP_KEY

    /**
     * 与菜鸟 H5 页面同族的 Android 浏览器 UA（实测可用），也是 [preferredUa] 缺席时的兜底。
     *
     * `internal` 是因为 `TaobaoOrderApi` 的 SSR 页请求也要它 —— 两份 UA 常量迟早会漂。
     */
    internal const val UA_DEFAULT =
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
        cachedTokens.clear()
        runCatching { onRiskMarked?.invoke(riskBlockedUntil) }
    }

    /**
     * 预热拿到的 token 缓存，**按 host 分开**。
     *
     * `_m_h5_tk` 的有效期是天级的，一次预热能用一整天 —— 每次 fetch 都预热等于
     * **请求数翻倍**（预热那发也会被风控计数），是纯浪费。
     *
     * key 是 host 而不是 api：同一域名下换接口不该重新预热（`_m_h5_tk` 是按 appKey 签发的，
     * 与具体接口无关）。但**换 host 时按 host 分开存**是保守做法 —— 我们不确知
     * `acs.m.taobao.com` 预热来的那份在 `h5api.m.taobao.com` 上认不认（两者同属 `.taobao.com`
     * cookie 域，大概率认），而不认的表现是 `FAIL_SYS_TOKEN_EMPTY`，与「没有 token」同形，
     * 最容易查错方向。分开存最多多一次预热，错了却能让两个域各自持有自己那份。
     *
     * 风控命中时整体清掉（见 [markRiskBlocked]）—— 被处罚的是这份登录态，不是某一条 URL。
     */
    private val cachedTokens = java.util.concurrent.ConcurrentHashMap<String, Token>()

    /** 预热时服务端下发的两个 token。 */
    private data class Token(val raw: String, val enc: String?)

    /**
     * **最近一次失败的原因**（人话，可直接展示），成功时清空。
     *
     * 为什么要把它留着：以前所有失败都只写进 hook 日志，而模块侧能看到的只有「详情页转了
     * 几秒然后显示暂无轨迹」—— 用户报的「怎么都获取不了」和「风控退避中」在界面上长得一模一样。
     * 现在详情页 / 身份码弹窗可以直接把这句话显示出来，`ExpressRelay` 那条链路之外也就不用再猜。
     */
    @Volatile
    var lastError: String? = null
        private set

    private fun fail(reason: String): String? {
        lastError = reason
        return null
    }

    /**
     * 菜鸟进程入口：cookie 从菜鸟自己的 WebView 库里读（[HostCredentialSource]）。
     * 模块进程走 [fetch] 的 cookie 直传重载 —— 那边读不到这个文件。
     */
    fun fetch(context: Context, trackingNumber: String): CainiaoTraceInfo? =
        fetch(HostCredentialSource.cookie(context), trackingNumber)

    /** [fetch] 的 cookie 直传入口（模块进程经 `TraceCookieCache` 调这里）。 */
    fun fetch(cookie: String?, trackingNumber: String): CainiaoTraceInfo? {
        val body = callH5(TRACE_API, TRACE_VERSION, requestBody(trackingNumber), cookie)
            ?: return null
        val info = CainiaoTraceParser.parse(body)
        if (info == null) {
            // 把响应开头打出来 —— 失败原因是「被风控拦了」还是「接口形状变了」，一眼可分。
            lastError = "轨迹接口返回的内容解析不出结果"
            XposedBridge.logAlways(
                "cainiao trace: 解析不出结果 tn=${trackingNumber.take(6)}… resp=${body.take(120)}",
            )
            return null
        }
        // 成功是退避档位唯一归零的时刻 —— 说明当前节奏服务端能接受。
        backoffLevel = 0
        lastError = null
        return info
    }

    /**
     * 通用 MTOP H5 GET —— 三段式（预热拿 token → 算 sign → 正式请求）从 [fetch] 里抽出来。
     *
     * ⚠️ **它只对 H5 通道上开放的那些接口有效**。身份码（`mtop.cainiao.nbpickup.*identitycode*`）
     * 曾经也走这里，2026-09-26 真机实证是死路：预热连 `_m_h5_tk` 都不下发，直接回
     * `FAIL_SYS_SESSION_EXPIRED` —— 而同一时刻同一份 cookie 拉轨迹成功，说明不是登录态问题，
     * 是那条接口只在 APP 通道可用。身份码现在改为请菜鸟用它自己的会话取
     * （`CainiaoIdentityBridge`），别再往这里接。
     *
     * token 缓存按 **host** 共用是对的：`_m_h5_tk` 是按「appKey + 域名」下发的，与具体接口无关；
     * 换个接口就重新预热等于把请求数翻倍，而请求数正是风控的触发条件。反过来说，**换 host
     * 必须能换出一份新的**（见 [cachedTokens]），所以 host 是参数而不是常量。
     *
     * @param host 接口所在域名（含 `/h5/` 后缀）。默认是轨迹所在的 `acs.m.taobao.com`；
     *   订单列表在另一个域名，见 `TaobaoOrderApi`。
     * @return 响应体；cookie 缺失、预热失败、或网络异常时返回 null（原因写进 [lastError]）。
     *   风控命中时会顺手进指数退避（[markRiskBlocked]），调用方不必自己判。
     */
    fun callH5(
        api: String,
        version: String,
        data: String,
        cookie: String?,
        host: String = HOST,
    ): String? {
        if (cookie.isNullOrBlank()) {
            // 用户没在菜鸟里登录过淘宝系账号 —— 这不是错误，只是这条路走不通。
            XposedBridge.logAlways("cainiao h5: 没有可用 cookie，跳过 $api")
            return fail("没有可用的淘宝登录态（请在菜鸟里登录后重试）")
        }

        val base = "$host$api/$version/"
        // token 的三级来源：**cookie 里现成的**（菜鸟 WebView 跑过 H5 页面时写进 Cookies 库的，
        // 随 cookie 一起同步过来）→ 内存缓存（上次预热拿的）→ 预热。每次请求都预热等于
        // 请求数翻倍（预热那发也会被风控计数），能省则省。
        // cookie 里拿的不进缓存：它会随菜鸟的使用自己更新，缓存反而可能钉死一份过期的。
        // 缓存与预热都按 **host** 归口（理由见 [cachedTokens]）。
        //
        // ⚠️ cookie 里那份 `_m_h5_tk` **只在默认 host 上直接用**：它是菜鸟 WebView 写给
        // 「它自己最近访问过的域」的，而同步过来的 cookie 串不带 host_key，我们分不出它属于谁。
        // 默认 host（轨迹）已经真机验证过它可用；换 host 时宁可多预热一次，也不拿一份
        // 不知道属于谁的 token 去撞 —— 撞上的表现是 TOKEN_EMPTY，与「压根没 token」同形。
        val token = (if (host == HOST) tokenFromCookie(cookie) else null)
            ?: cachedTokens[host]
            ?: warmUp(base, cookie)?.also { cachedTokens[host] = it }
            ?: run {
                XposedBridge.logAlways("cainiao h5: 预热没拿到 token，跳过 $api")
                return fail(lastError ?: "预热没拿到 token（网络不通或被风控拦下）")
            }

        val timestamp = System.currentTimeMillis().toString()
        val sign = MtopSign.wapSign(MtopSign.tokenOf(token.raw), timestamp, APP_KEY, data)

        val url = base + "?jsv=2.3.18&appKey=" + APP_KEY + "&t=" + timestamp + "&sign=" + sign +
            "&type=originaljson&data=" + URLEncoder.encode(data, "UTF-8")

        val body = get(url, cookieWithTokens(cookie, token)).first
        // 风控检查放在这里而不是「解析失败之后」：预热通过了但正式请求被拦同样存在
        // （token 拿到了、请求节奏太密），而这两个接口的失败长相不同 ——
        // 让退避只覆盖一半链路等于没覆盖。
        if (isRiskResponse(body)) {
            markRiskBlocked()
            XposedBridge.logAlways("cainiao h5: 请求被风控，指数退避（档位=$backoffLevel）")
            lastError = "被淘宝风控拦下（按请求频率保护），稍后再试"
        }
        return body
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
    private fun warmUp(base: String, cookie: String): Token? {
        val (body, setCookies) = get("${base}?appKey=$APP_KEY", cookie)

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
