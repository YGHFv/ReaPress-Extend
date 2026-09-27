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

import io.github.YGHFv.ReaPressExtend.core.SsrLogisticsParser
import io.github.YGHFv.ReaPressExtend.core.SsrParcel
import io.github.YGHFv.ReaPressExtend.core.TaobaoOrder
import io.github.YGHFv.ReaPressExtend.core.TaobaoOrderParser
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 淘宝订单 → 运单号，不经过菜鸟进程的兜底通路（覆盖面只有淘宝 / 天猫订单，拼多多等仍只有菜鸟本地表有）。
 * 两跳：[listOrders] 拉订单列表（里面没有运单号），[ssrParcel] 对在途订单打物流 SSR 页从 HTML 首屏
 * 抠运单号，之后交给 `CainiaoTraceFetcher` 的轨迹链路。两跳都算请求数，风控按请求频率收网（实测）：
 * 第一跳走 [CainiaoTraceApi.callH5] 共用退避，第二跳由调用方拉开间隔、限制件数。
 */
internal object TaobaoOrderApi {

    /** 订单接口域名与轨迹接口不是同一个，token 也分开缓存（见 [CainiaoTraceApi.callH5]）。 */
    private const val ORDER_HOST = "https://h5api.m.taobao.com/h5/"

    private const val ORDER_API = "mtop.taobao.order.queryboughtlistv2"
    private const val ORDER_VERSION = "1.0"

    private const val SSR_BASE =
        "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail"

    private const val CONNECT_TIMEOUT_MS = 8_000

    private const val READ_TIMEOUT_MS = 12_000

    /** 空列表 = 没登录态 / 接口变了 / 被风控，三种情况处理一样（本次直连到此为止），具体看 [CainiaoTraceApi] 的日志。 */
    fun listOrders(cookie: String?, page: Int = 1): List<TaobaoOrder> {
        val body = CainiaoTraceApi.callH5(
            api = ORDER_API,
            version = ORDER_VERSION,
            data = orderBody(page),
            cookie = cookie,
            host = ORDER_HOST,
        ) ?: return emptyList()
        return TaobaoOrderParser.parse(body)
    }

    /** 某个订单的物流页 → 运单号；解析失败（登录页 / 结构变了）返回 null。 */
    fun ssrParcel(cookie: String?, orderId: String): SsrParcel? {
        if (cookie.isNullOrBlank() || orderId.isBlank()) return null
        val url = "$SSR_BASE?x-ssr=true&bizOrderId=" + URLEncoder.encode(orderId, "UTF-8")
        val html = runCatching { get(url, cookie) }.getOrElse { error ->
            XposedBridge.logError("taobao ssr 请求失败 order=$orderId", error)
            return null
        }
        return SsrLogisticsParser.parse(html)
    }

    /** 请求体字段与取值照抄 Halo0sama/ExpressAssistant（MIT）—— 它们不可推导（ttid 等是页面埋点标识），少一个就可能被服务端当成非正常客户端。 */
    private fun orderBody(page: Int): String = JSONObject()
        .put("tabCode", "all")
        .put("page", page)
        .put("OrderType", "OrderList")
        .put("templateConfigVersion", "0")
        .put("appName", "tborder")
        .put("appVersion", "3.0")
        .put("condition", "{\"version\":\"1.0.0\",\"appChannel\":\"\"}")
        .put("ttid", "201200@taobao_h5_9.18.0")
        .put("requestIdentity", "#t#ip#h5")
        .toString()

    /** SSR 页 GET：要跟随重定向（普通网页，被风控时没有 ret 响应体）；只要 cookie 没有签名。用 HttpURLConnection，模块依赖不会进宿主进程。 */
    private fun get(url: String, cookie: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", CainiaoTraceApi.preferredUa ?: CainiaoTraceApi.UA_DEFAULT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Cookie", cookie)
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
