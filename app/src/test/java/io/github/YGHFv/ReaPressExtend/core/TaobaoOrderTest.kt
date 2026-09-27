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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订单列表（`mtop.taobao.order.queryboughtlistv2`）解析的单测。
 *
 * 样本按 Halo0sama/ExpressAssistant（MIT）`api/TbOrders.kt` 里解析的字段路径构造 ——
 * 那是我们**唯一**的格式依据（本仓还没有真机样本）。所以这一批断言钉的是「对照项目读过的
 * 那几条路径，我们也能读到」；哪天有真机数据，应该先拿它换掉这里的构造样本。
 *
 * 另有一批钉 [TaobaoOrder.isInTransit] —— 它不是解析问题，是**要不要多发一次请求**的判据，
 * 而请求数直接对应风控风险（2026-09-26 实测）。这条判据写松了，代价是账号被处罚。
 */
class TaobaoOrderTest {

    // ------------------------------------------------------------ 新格式（mainOrders）

    @Test
    fun `新格式_data result 是字符串_要再解一层`() {
        val body = bodyWithResult(
            JSONObject()
                .put("id", "1234567890")
                .put("extra", JSONObject().put("tradeStatus", "WAIT_BUYER_CONFIRM_GOODS"))
                .put("statusInfo", JSONObject().put("text", "卖家已发货"))
                .put(
                    "subOrders",
                    JSONArray().put(
                        JSONObject().put(
                            "itemInfo",
                            JSONObject().put("title", "测试商品一号").put("pic", "https://img/a.jpg"),
                        ),
                    ),
                ),
        )

        val orders = TaobaoOrderParser.parse(body)

        assertEquals(1, orders.size)
        assertEquals("1234567890", orders[0].orderId)
        assertEquals("WAIT_BUYER_CONFIRM_GOODS", orders[0].tradeStatus)
        assertEquals("卖家已发货", orders[0].statusText)
        assertEquals("测试商品一号", orders[0].goodsName)
        assertEquals("https://img/a.jpg", orders[0].goodsPic)
    }

    @Test
    fun `数据缺了哪一层都不抛_只返回空表`() {
        // 这几种在真机上都会遇到：cookie 失效回的是错误体、接口改版会换掉外层键。
        // 它们必须都收敛成「空表」——抛异常会让整条兜底链路断在半路。
        assertEquals(emptyList<TaobaoOrder>(), TaobaoOrderParser.parse(""))
        assertEquals(emptyList<TaobaoOrder>(), TaobaoOrderParser.parse("not json at all"))
        assertEquals(emptyList<TaobaoOrder>(), TaobaoOrderParser.parse("""{"data":{}}"""))
        assertEquals(emptyList<TaobaoOrder>(), TaobaoOrderParser.parse("""{"ret":["FAIL_SYS_TOKEN_EMPTY"]}"""))
    }

    @Test
    fun `没有订单号的条目被跳过_不留空壳`() {
        val body = bodyWithResult(
            JSONObject().put("extra", JSONObject().put("tradeStatus", "WAIT_BUYER_CONFIRM_GOODS")),
            JSONObject().put("id", "99").put("statusInfo", JSONObject().put("text", "运输中")),
        )

        val orders = TaobaoOrderParser.parse(body)

        assertEquals(listOf("99"), orders.map { it.orderId })
    }

    // ------------------------------------------------------------ 旧格式（Main_*）

    @Test
    fun `旧格式走 data data 下的 Main_ 键`() {
        val body = JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "data",
                    JSONObject().put(
                        "Main_555",
                        JSONObject().put(
                            "fields",
                            JSONObject().put("orderId", "555").put("orderStatus", "等待收货"),
                        ),
                    ),
                ),
            )
            .toString()

        val orders = TaobaoOrderParser.parse(body)

        assertEquals(1, orders.size)
        assertEquals("555", orders[0].orderId)
        assertEquals("等待收货", orders[0].statusText)
    }

    @Test
    fun `旧格式缺 orderId 时从键名里剥`() {
        val body = JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "data",
                    JSONObject().put("Main_777", JSONObject().put("fields", JSONObject())),
                ),
            )
            .toString()

        assertEquals(listOf("777"), TaobaoOrderParser.parse(body).map { it.orderId })
    }

    // ------------------------------------------------------------ jsonp 外壳

    @Test
    fun `jsonp 外壳能剥掉`() {
        val payload = """{"data":{"result":"{}"}}"""
        assertEquals(payload, TaobaoOrderParser.unwrap("mtopjsonp3($payload)"))
    }

    @Test
    fun `本来就是 JSON 时不碰它`() {
        val body = """{"data":{"result":"{}"}}"""
        assertEquals(body, TaobaoOrderParser.unwrap(body))
    }

    // ------------------------------------------------------------ 值不值得去查物流

    @Test
    fun `已发货的订单要去查`() {
        assertTrue(TaobaoOrder(orderId = "1", tradeStatus = "WAIT_BUYER_CONFIRM_GOODS").isInTransit)
        assertTrue(TaobaoOrder(orderId = "2", statusText = "卖家已发货").isInTransit)
        assertTrue(TaobaoOrder(orderId = "3", statusText = "运输中").isInTransit)
        assertTrue(TaobaoOrder(orderId = "4", statusText = "派送中").isInTransit)
    }

    @Test
    fun `结束与未发货的订单不去查`() {
        // 这几类占了订单列表的大多数 —— 把它们挡在外面，才是「不白发请求」的主要来源。
        assertFalse(TaobaoOrder(orderId = "1", statusText = "交易成功").isInTransit)
        assertFalse(TaobaoOrder(orderId = "2", statusText = "交易关闭").isInTransit)
        assertFalse(TaobaoOrder(orderId = "3", statusText = "等待付款").isInTransit)
        assertFalse(TaobaoOrder(orderId = "4", statusText = "卖家正在备货，等待发货").isInTransit)
        assertFalse(TaobaoOrder(orderId = "5").isInTransit)
    }

    @Test
    fun `结束词压过在途词`() {
        // 「退款」与「运输中」同时出现的文案真机上见过（退款中的件仍在路上）——
        // 那时**不问**：退款件的物流对用户没有价值，而每一次请求都要冒风控的账。
        assertFalse(TaobaoOrder(orderId = "1", statusText = "退款成功，运输中").isInTransit)
    }

    /** 构造新格式响应：`data.result` 是一段**字符串**（内层 JSON 的文本）。 */
    private fun bodyWithResult(vararg orders: JSONObject): String = JSONObject()
        .put("api", "mtop.taobao.order.queryboughtlistv2")
        .put(
            "data",
            JSONObject().put("result", JSONObject().put("mainOrders", JSONArray(orders.toList())).toString()),
        )
        .toString()
}
