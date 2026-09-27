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
 * 淘宝订单 → 运单号 —— **不经过菜鸟进程**的那条数据通路。
 *
 * ## 它补的是哪一格
 *
 * 模块的包裹数据一直有个前提：菜鸟得活着（hook 才有机会读它本地那张包裹表，
 * 见 `CainiaoPackageHook` 的自查）。菜鸟被系统回收后就只剩「等它下次自己起来」。
 * 这个文件是第三条路：**用菜鸟 WebView 里那份淘宝登录态，直接问淘宝要订单**，
 * 拿到运单号之后交给现有的轨迹链路 —— 菜鸟死着也能更新。
 *
 * ⚠️ **覆盖面只有淘宝 / 天猫的订单**：拼多多、京东、别人寄来的件不在这里，
 * 那些仍只有菜鸟本地表有。所以它是**兜底**，不是替代。
 *
 * ## 两跳（缺一不可）
 *
 * 1. [listOrders]：`mtop.taobao.order.queryboughtlistv2` 拿订单列表 ——
 *    **这里面没有运单号**，只有订单号和状态；
 * 2. [ssrParcel]：对每个在途订单请求物流 SSR 页，从 HTML 的首屏数据里抠出运单号。
 *
 * 拿到运单号之后的活不在这里 —— 交给 `CainiaoTraceFetcher`（它带闸门、串行、风控退避，
 * 而且拿的是完整轨迹 + 商品图 + 驿站地址，比 SSR 页那份缩写版好得多）。
 *
 * ## 风控
 *
 * 两跳都算请求数，而风控正是按**请求频率**收网的（2026-09-26 实测）。所以：
 * 第一跳走 [CainiaoTraceApi.callH5]（共用风控退避与 token 缓存），第二跳由调用方
 * （`CainiaoDirectFetcher`）拉开间隔、限制件数。
 */
internal object TaobaoOrderApi {

    /**
     * 订单接口的域名。**与轨迹不是同一个** —— 见 [CainiaoTraceApi.callH5] 的 host 参数，
     * 两边的 token 也是分开缓存的。
     */
    private const val ORDER_HOST = "https://h5api.m.taobao.com/h5/"

    private const val ORDER_API = "mtop.taobao.order.queryboughtlistv2"
    private const val ORDER_VERSION = "1.0"

    /** 物流 SSR 页（整页 HTML，不是 mtop 接口）。 */
    private const val SSR_BASE =
        "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail"

    private const val CONNECT_TIMEOUT_MS = 8_000

    /** SSR 页是整页 HTML，比接口响应大得多，读超时给宽一点。 */
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * 订单列表。
     *
     * @return 空列表表示「没登录态 / 接口变了 / 被风控」——三种情况调用方的处理一样
     *   （本次直连到此为止），具体是哪一种看模块日志里 [CainiaoTraceApi] 留下的那句。
     */
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

    /** 某个订单的物流页 → 运单号。解析失败（登录页 / 结构变了）返回 null。 */
    fun ssrParcel(cookie: String?, orderId: String): SsrParcel? {
        if (cookie.isNullOrBlank() || orderId.isBlank()) return null
        val url = "$SSR_BASE?x-ssr=true&bizOrderId=" + URLEncoder.encode(orderId, "UTF-8")
        val html = runCatching { get(url, cookie) }.getOrElse { error ->
            XposedBridge.logError("taobao ssr 请求失败 order=$orderId", error)
            return null
        }
        return SsrLogisticsParser.parse(html)
    }

    /**
     * 订单列表的请求体。字段名与取值**照抄** Halo0sama/ExpressAssistant（MIT）那份实现 ——
     * 它们不是可推导出来的（`ttid` / `requestIdentity` 这类是页面自己的埋点标识），
     * 少一个就可能被服务端当成非正常客户端。改动前先想想值不值得。
     */
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

    /**
     * SSR 页的 GET。
     *
     * 与 [CainiaoTraceApi] 的两条差异：
     * - **跟随重定向**：这条不是 mtop 接口，被风控时不会有那套 `ret` 响应体；
     *   正常也会经过跳转，不跟随就什么都拿不到。
     * - 只要 cookie，**没有签名**：SSR 页是普通网页请求。
     *
     * 用 `HttpURLConnection` 而不是第三方库：这段代码会被注入宿主进程，模块的依赖不会一起进去
     * （理由同 [CainiaoTraceApi]）。
     */
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
