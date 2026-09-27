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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 淘宝物流 SSR 页解析的单测。
 *
 * 样本按 Halo0sama/ExpressAssistant（MIT）`api/TbOrders.kt` 里的抠法构造
 * （`__ICE_SUSPENSE_LOADER__` 标记 + `result.data` 两个可能的外层键）——
 * 本仓还没有真机页面。哪天接口改版，表现会是「订单列表明明有订单，一个运单号都拿不到」，
 * 那时先看这里是不是红了。
 */
class SsrLogisticsTest {

    @Test
    fun `新版外层键 newLogistics 能拿到运单号`() {
        val html = page(fields("JT0000000000000", "极兔速递"), new = true)

        val parcel = SsrLogisticsParser.parse(html)

        assertEquals("JT0000000000000", parcel?.mailNo)
        assertEquals("极兔速递", parcel?.courierName)
    }

    @Test
    fun `旧版外层键 logisticsDetailH5 也要认`() {
        val html = page(fields("SF1234567890", "顺丰速运"), new = false)

        assertEquals("SF1234567890", SsrLogisticsParser.parse(html)?.mailNo)
    }

    @Test
    fun `运单号只在外层公司节点里时也能拿到`() {
        val onlyInCompany = JSONObject()
            .put("logisticCompany", JSONObject().put("name", "中通快递").put("mailNo", "ZT999"))

        val parcel = SsrLogisticsParser.parse(page(onlyInCompany))

        assertEquals("ZT999", parcel?.mailNo)
        assertEquals("中通快递", parcel?.courierName)
    }

    @Test
    fun `登录页没有那段标记_返回 null 而不是空数据`() {
        // cookie 失效时服务端回的是登录页 —— 这正是本方法最重要的一个用法：
        // 「拿不到」与「拿到了但不是我们想要的」必须分得开。
        assertNull(SsrLogisticsParser.parse("<html><body>请登录</body></html>"))
    }

    @Test
    fun `标记在但 JSON 坏了_也不抛`() {
        assertNull(SsrLogisticsParser.parse("<script>window['__ICE_SUSPENSE_LOADER__']['undefined'] = {oops"))
    }

    @Test
    fun `结构对但没有运单号_返回 null`() {
        val html = page(JSONObject().put("logisticCompany", JSONObject().put("name", "极兔速递")))
        assertNull(SsrLogisticsParser.parse(html))
    }

    @Test
    fun `后面的 HTML 尾巴不影响解析`() {
        // JSONTokener 只读一个完整值就停 —— 这条钉住它，别哪天换成正则去抠。
        val html = page(fields("JT0000000000000", "极兔速递")) + "\n</script></body></html>"
        assertEquals("JT0000000000000", SsrLogisticsParser.parse(html)?.mailNo)
    }

    private fun fields(mailNo: String, courier: String): JSONObject = JSONObject()
        .put("mailNo", mailNo)
        .put("logisticCompany", JSONObject().put("name", courier))

    /**
     * 拼一个含 ICE 首屏数据的页面。
     *
     * @param new 挂在哪个外层键下 —— `newLogistics`（新版）与 `logisticsDetailH5`（旧版）
     *   是两个真的会互相替代的键，所以**一次只能放一个**，否则「旧版也认」那条测试会假绿。
     */
    private fun page(fields: JSONObject, new: Boolean = true): String {
        val key = if (new) "newLogistics" else "logisticsDetailH5"
        val payload = JSONObject().put(
            "result",
            JSONObject().put(
                "data",
                JSONObject().put(key, JSONObject().put("fields", fields)),
            ),
        )
        return "<html><script>window['__ICE_SUSPENSE_LOADER__']['undefined'] = $payload</script></html>"
    }
}
