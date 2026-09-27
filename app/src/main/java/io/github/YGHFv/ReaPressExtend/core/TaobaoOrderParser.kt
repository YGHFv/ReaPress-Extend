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

/**
 * 一条淘宝 / 天猫订单 —— `mtop.taobao.order.queryboughtlistv2` 的条目。
 *
 * ⚠️ **它不是包裹**：这里面没有运单号。运单号要再打一次该订单的物流 SSR 页才拿得到
 * （见 [SsrLogisticsParser]）。所以这个类的全部用途是「从订单里挑出值得去问一次运单号的那些」。
 *
 * 字段路径照 Halo0sama/ExpressAssistant（MIT）的 `api/TbOrders.kt` 核对过，解析器只读它读过的
 * 路径 —— 没有真机样本之前不猜别的字段。
 */
data class TaobaoOrder(
    /** 淘宝订单号（`mainOrders[].id`）—— 物流 SSR 页的 `bizOrderId` 就是它。 */
    val orderId: String,
    /** 交易状态枚举（`extra.tradeStatus`），如 `WAIT_BUYER_CONFIRM_GOODS`。 */
    val tradeStatus: String = "",
    /** 给用户看的状态文案（`statusInfo.text`），如「卖家已发货」。 */
    val statusText: String = "",
    /** 商品名（`subOrders[0].itemInfo.title`）。 */
    val goodsName: String = "",
    /** 商品图（`subOrders[0].itemInfo.pic`）。 */
    val goodsPic: String = "",
) {

    /**
     * 值不值得为它去查一次物流（= 值不值得多发一次请求）。
     *
     * 风控按**请求频率**收网（2026-09-26 实测：连拉 5 单成功后第 6 单的**预热**就被拦），
     * 所以「多问一个订单」是有真实代价的 —— 已完成 / 关闭 / 还没发货的订单问了也是白问。
     *
     * 判据是**双向**的：先看结束词（命中即否），再看在途词。只写一面都会错 ——
     * 只写在途词会让没见过的写法全被挡掉（漏掉真在途的件），只写结束词则会把「等待付款」
     * 当成在途（白发请求）。名字里的「在途」是给读代码的人看的，不是给用户看的。
     */
    val isInTransit: Boolean
        get() {
            if (SETTLED_MARKERS.any { statusText.contains(it) }) return false
            if (tradeStatus in TRANSIT_TRADE_STATUSES) return true
            return TRANSIT_MARKERS.any { statusText.contains(it) }
        }

    private companion object {
        /**
         * 交易已经结束 —— 不会再有新的物流动态。
         *
         * ⚠️ 「等待发货」也算结束：那时还没有运单号，问了必然白问。
         * 但它**必须同时出现在这里和「不在途」的结果里**，别为了「以后万一发货了」把它去掉 ——
         * 发货之后订单会自己变成「已发货」，那时再问不迟。
         */
        val SETTLED_MARKERS = listOf(
            "交易成功", "已完成", "交易关闭", "已关闭", "已取消", "退款", "等待付款", "等待发货",
        )

        /**
         * 交易状态里明确表示「已发货、等收货」。
         *
         * 只收一个值：拿不准的枚举宁可不认（漏一次更新的代价 < 多发一次请求的代价）。枚举变了
         * 还有下面的文案兜底，两条都失效时表现是「直连什么也没拉到」，届时看日志即可定位。
         */
        val TRANSIT_TRADE_STATUSES = setOf("WAIT_BUYER_CONFIRM_GOODS")

        /** 状态文案里的在途词（兜底：枚举可能变，人话文案更稳）。 */
        val TRANSIT_MARKERS = listOf(
            "运输中", "派送", "派件", "待收货", "已发货", "已揽收", "揽收", "待取件", "到达", "配送",
        )
    }
}

/**
 * `mtop.taobao.order.queryboughtlistv2` 的响应解析。
 *
 * 纯函数、不碰网络也不碰时钟 —— 网络在 `hook/TaobaoOrderApi`，拆开是为了能在 JVM 单测里
 * 钉住字段路径（接口改字段名时这里应该先红）。
 *
 * ## 为什么要剥两层
 *
 * 响应的 `data.result` 是一个**字符串**（内层 JSON 的文本），不是对象；旧格式则直接是
 * `data.data` 下的一堆 `Main_*` 键。两种写法对照项目里都处理了，这里都留 ——
 * 剥错一层的表现是「明明有订单却拿到空列表」，而且**不报错**，最难查。
 */
object TaobaoOrderParser {

    /** @return 空列表 = 「没有订单」或「结构不认识」。两种情况调用方的处理一样（本次直连到此为止）。 */
    fun parse(body: String): List<TaobaoOrder> = try {
        JSONObject(unwrap(body)).optJSONObject("data")?.let { readOrders(it) } ?: emptyList()
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * 去掉 jsonp 外壳。
     *
     * 当前请求用 `type=originaljson`，响应本身就是 JSON；但 `callH5` 是几个接口共用的，
     * 万一哪天换成 jsonp（对照项目就是），这里能直接接住 —— 而反过来的失误（把 jsonp 当 JSON 解）
     * 只会得到一个静默的空列表。
     */
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
            // 没有订单号就没法问物流 —— 跳过而不是留一条只有商品名的空壳。
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

    /**
     * 旧格式：`data.data` 下一堆 `Main_<订单号>`，每个里面有 `fields`。
     *
     * 订单号优先取 `fields.orderId`，缺失才从键名里剥 —— 键名剥出来的可能是**子订单号**
     * （电商的父/子单结构），能当 `bizOrderId` 用但未必与 `fields` 一致，所以顺序不能反。
     */
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
