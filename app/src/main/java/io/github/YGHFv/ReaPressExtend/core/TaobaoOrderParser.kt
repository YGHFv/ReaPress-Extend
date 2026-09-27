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

package io.github.YGHFv.ReaPressExtend.core

import org.json.JSONArray
import org.json.JSONObject

/** 一条淘宝 / 天猫订单（`mtop.taobao.order.queryboughtlistv2` 的条目）；不是包裹、没有运单号，要再打该订单的物流 SSR 页才拿得到。 */
data class TaobaoOrder(
    val orderId: String,
    val tradeStatus: String = "",
    val statusText: String = "",
    val goodsName: String = "",
    val goodsPic: String = "",
) {

    /** 风控按请求频率收网（实测连拉 5 单后第 6 单的预热即被拦），判据双向：先看结束词（命中即否）再看在途词，只写一面都会错。 */
    val isInTransit: Boolean
        get() {
            if (SETTLED_MARKERS.any { statusText.contains(it) }) return false
            if (tradeStatus in TRANSIT_TRADE_STATUSES) return true
            return TRANSIT_MARKERS.any { statusText.contains(it) }
        }

    private companion object {
        /** 「等待发货」也算已结束（那时还没有运单号）；发货后订单会自己变成「已发货」，别去掉它。 */
        val SETTLED_MARKERS = listOf(
            "交易成功", "已完成", "交易关闭", "已关闭", "已取消", "退款", "等待付款", "等待发货",
        )

        val TRANSIT_TRADE_STATUSES = setOf("WAIT_BUYER_CONFIRM_GOODS")

        val TRANSIT_MARKERS = listOf(
            "运输中", "派送", "派件", "待收货", "已发货", "已揽收", "揽收", "待取件", "到达", "配送",
        )
    }
}

/** `mtop.taobao.order.queryboughtlistv2` 的响应解析；纯函数。`data.result` 是字符串（内层 JSON 文本），旧格式在 `data.data` 下一堆 `Main_*` 键，两种都要剥 —— 剥错一层只会得到静默空列表。 */
object TaobaoOrderParser {

    fun parse(body: String): List<TaobaoOrder> = try {
        JSONObject(unwrap(body)).optJSONObject("data")?.let { readOrders(it) } ?: emptyList()
    } catch (e: Throwable) {
        emptyList()
    }

    /** 去掉 jsonp 外壳；`callH5` 几个接口共用，JSON 直接返回。 */
    internal fun unwrap(body: String): String {
        val text = body.trim()
        if (text.startsWith("{")) return text
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        return if (open in 0 until close) text.substring(open + 1, close) else text
    }

    private fun readOrders(data: JSONObject): List<TaobaoOrder> {
        val inner = runCatching { JSONObject(data.optString("result")) }.getOrNull()
            ?: data.optJSONObject("data")
            ?: return emptyList()
        inner.optJSONArray("mainOrders")?.let { return readMainOrders(it) }
        return readLegacyOrders(inner)
    }

    private fun readMainOrders(array: JSONArray): List<TaobaoOrder> = buildList {
        for (index in 0 until array.length()) {
            val order = array.optJSONObject(index) ?: continue
            val id = order.optString("id")
            if (id.isBlank()) continue
            val item = order.optJSONArray("subOrders")
                ?.optJSONObject(0)?.optJSONObject("itemInfo")
            add(
                TaobaoOrder(
                    orderId = id,
                    tradeStatus = order.optJSONObject("extra")?.optString("tradeStatus").orEmpty(),
                    statusText = order.optJSONObject("statusInfo")?.optString("text").orEmpty(),
                    goodsName = item?.optString("title").orEmpty(),
                    goodsPic = item?.optString("pic").orEmpty(),
                ),
            )
        }
    }

    /** 旧格式：订单号优先取 `fields.orderId`，缺失才从键名里剥 —— 键名可能是子订单号，顺序不能反。 */
    private fun readLegacyOrders(inner: JSONObject): List<TaobaoOrder> = buildList {
        val keys = inner.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith("Main_")) continue
            val fields = inner.optJSONObject(key)?.optJSONObject("fields") ?: continue
            val id = fields.optString("orderId").ifBlank { key.removePrefix("Main_") }
            if (id.isBlank()) continue
            add(TaobaoOrder(orderId = id, statusText = fields.optString("orderStatus")))
        }
    }
}
