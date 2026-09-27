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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PddCacheDiscovery] 的单测。
 *
 * ⚠️ 全部用虚构数据（隐私红线）：运单号 `770012340000005`、手机号 `188****0000`
 * 都是化名。真机原文只进 `log/`。
 *
 * ⚠️ 按项目那条老规矩：**涉及正则字符语义的改动，JVM 单测通过不算证据** ——
 * JVM 的 `\b` 与 Android（ICU）不同。本解析器刻意只用显式 ASCII 断言
 * （`(?<![0-9A-Za-z])`），所以 JVM 与 ART 行为一致；但这仍要 ART 自检兜一遍
 * （往模块塞临时自检、结果落 `files/module-log.txt`），再上真机。
 */
class PddCacheDiscoveryTest {

    /** 一个典型的驿站单缓存片段（形状按真机窗口核实，内容化名）。 */
    private fun cacheFile(
        pickupText: String,
        tn: String = "770012340000005",
        orderSn: String = "260922-999000000000001",
    ): String {
        val pickup = if (pickupText.isEmpty()) "" else """"pick_up_desc":[{"text":"$pickupText","type":1}],"""
        return """
            {"some_field":1,$pickup"tracking_num":"运单号: $tn",
             "order_sn":"$orderSn",
             "express_link_url":"goods_express.html?tracking_number\u003d$tn&order_sn\u003d$orderSn"}
        """.trimIndent()
    }

    @Test
    fun `主路 - 出示手机号的驿站单 - 抽出手机尾号而不误收取件码`() {
        val text = cacheFile("取件手机号 188****0000")
        val result = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text))
        assertEquals(1, result.size)
        val pkg = result.first()
        assertEquals("770012340000005", pkg.trackingNumber)
        assertEquals("260922-999000000000001", pkg.orderSn)
        // ⚠️ 末四位前面贴着星号 —— 不许被当成取件码（真机数据形状）。
        assertNull(pkg.pickupCode)
        assertEquals("0000", pkg.phoneTail)
    }

    @Test
    fun `主路 - 连字符取件码 - 收进 pickupCode`() {
        val text = cacheFile("取件码 1-1-2001")
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertEquals("1-1-2001", pkg.pickupCode)
        assertNull(pkg.phoneTail)
    }

    @Test
    fun `主路 - 纯数字取件码 - 收进 pickupCode`() {
        val text = cacheFile("凭取件码 123456 取件")
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertEquals("123456", pkg.pickupCode)
    }

    @Test
    fun `含手机或尾号字样的提示 - 纯数字不收进 pickupCode`() {
        // 「手机尾号0000」里的数字是尾号不是码 —— 收错比缺更糟。
        // （这条提示没带掩码手机号，phoneTail 也抽不出 —— 那要等带 188****0000 形状的来源。）
        val text = cacheFile("手机尾号0000")
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertNull(pkg.pickupCode)
        assertNull(pkg.phoneTail)
    }

    @Test
    fun `补路 - 没有取件提示的单 - 从链接里取运单号`() {
        val text = cacheFile(pickupText = "", tn = "990000000000009")
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertEquals("990000000000009", pkg.trackingNumber)
        assertNull(pkg.pickupCode)
        assertNull(pkg.phoneTail)
    }

    @Test
    fun `同单出现在多个文件 - 字段按只填空合并`() {
        val files = mapOf(
            "cache/pdd_cache/old.0" to cacheFile("取件手机号 188****0000"),
            "cache/pdd_cache/new.0" to cacheFile("取件码 1-1-2001"),
        )
        val result = PddCacheDiscovery.parse(files)
        assertEquals(1, result.size)
        val pkg = result.single()
        // 取件码出现了，就是它优先；手机尾号与订单号都不被冲掉。
        assertEquals("1-1-2001", pkg.pickupCode)
        assertEquals("0000", pkg.phoneTail)
        assertEquals("260922-999000000000001", pkg.orderSn)
    }

    @Test
    fun `非 pdd_cache 路径 - 忽略`() {
        val text = cacheFile("取件码 1-1-2001")
        val result = PddCacheDiscovery.parse(mapOf("code_cache/junk.0" to text))
        assertEquals(0, result.size)
    }

    @Test
    fun `全零运单号 - 滤掉测试数据`() {
        val text = cacheFile("取件码 1-1-2001", tn = "000000000000000")
        val result = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text))
        assertEquals(0, result.size)
    }

    @Test
    fun `订单号段不被误认成取件码`() {
        // order_sn 的两段数字贴着连字符 —— 连字符码正则的前后断言必须把它们挡在外面。
        val text = cacheFile("请到服务台领取")
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertNull(pkg.pickupCode)
    }

    @Test
    fun `mojibake - ISO_8859_1 读入的中文提示能转回`() {
        val chinese = "取件手机号 188****0000"
        // 模拟「按 ISO_8859_1 读入 UTF-8 字节」：先造出 mojibake 串。
        val mojibake = String(chinese.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val text = cacheFile(mojibake)
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        // 转回后才能认出「手机号」字样，取件码不被误收。
        assertNull(pkg.pickupCode)
        assertEquals("0000", pkg.phoneTail)
    }

    // --------------------------------------------------- 分栏 / 驿站 / 状态（v2）

    /** 待取件分栏片段（形状按真机样本，内容化名）。 */
    private fun gotTabFile(): String {
        val mojibake = { s: String -> String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1) }
        return """
            {"express_tab":{"tab_list":[
              {"tab_id":"sign","tab_name":"${mojibake("已签收")}","tab_num":0,"orders":[]},
              {"tab_id":"got","tab_name":"${mojibake("待取件")}","tab_num":1,
               "items":[{"pick_up_info":{"company_name":"${mojibake("某花园驿站")}","address":"${mojibake("某楼23号楼109")}","contact_phone":"18000000000"},
                "orders":[{"pick_up_desc":[{"type":1,"text":"${mojibake("取件码 1-3-4024")}"}],
                  "tracking_num":"${mojibake("中通快递")}: 770012340000005",
                  "order_sn":"260921-999000000000002",
                  "express_link_url":"goods_express.html?tracking_number\u003d770012340000005",
                  "additional_desc":[{"type":1,"text":"${mojibake("9月24日18:22送达，已超3天未取")}"}]}]}]}]}}
        """.trimIndent()
    }

    @Test
    fun `分栏 - 待取件栏的单 - 状态驿站公司提示语齐活`() {
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to gotTabFile())).single()
        assertEquals("770012340000005", pkg.trackingNumber)
        assertEquals(ExpressStatus.READY_FOR_PICKUP, pkg.status)
        assertEquals("某花园驿站", pkg.station)
        assertEquals("某楼23号楼109", pkg.stationAddress)
        assertEquals(Courier.ZHONGTONG, pkg.courier)
        assertEquals("1-3-4024", pkg.pickupCode)
        assertEquals("9月24日18:22送达，已超3天未取", pkg.logisticsDetail)
    }

    @Test
    fun `分栏 - onroad 运输中 - 刻意不设状态`() {
        // IN_TRANSIT 会把富化合并里「CREATED 纠正 IN_TRANSIT」的修正盖回去（项目老坑）。
        val text = """
            {"tab_id":"onroad","orders":[{"tracking_num":"${String("运单号".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)}: 880000000000001"}]}
        """.trimIndent()
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertEquals(ExpressStatus.UNKNOWN, pkg.status)
    }

    @Test
    fun `订单列表 - 交易成功提示词 - 历史件标已签收`() {
        // orderList 缓存形状：chat_status_prompt 贴在 express URL 前 ~100-400 字符。
        val prompt = String("交易成功".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val text = """{"param":"{\"chat_status_prompt\":\"$prompt\"}","bt":{"url":""" +
            """"goods_express.html?tracking_number\u003dJT00000000000001\u0026order_sn\u003d260811-999000000000003"}"""
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertEquals(ExpressStatus.SIGNED, pkg.status)
        assertEquals("260811-999000000000003", pkg.orderSn)
    }

    @Test
    fun `提示词离锚点太远 - 不挂`() {
        val prompt = String("交易成功".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        // 3KB 的无关填充把提示词推到窗口外。
        val filler = "x".repeat(3000)
        val text = """{"param":"$prompt$filler","bt":{"url":""" +
            """"goods_express.html?tracking_number\u003d880000000000002"}"""
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertEquals(ExpressStatus.UNKNOWN, pkg.status)
    }

    @Test
    fun `取件提示过期 - 不挂给下一个单`() {
        // pick_up_desc 与运单锚点之间隔着 2KB —— 提示是上一单的尾巴，宁可丢也不张冠李戴。
        val filler = "y".repeat(2000)
        val text = """"pick_up_desc":[{"text":"${String("取件码 1-1-2001".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)}"}],$filler""" +
            """"tracking_num":"${String("运单号".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)}: 880000000000003""" + "\""
        val pkg = PddCacheDiscovery.parse(mapOf("cache/pdd_cache/a.0" to text)).single()
        assertNull(pkg.pickupCode)
    }
}
