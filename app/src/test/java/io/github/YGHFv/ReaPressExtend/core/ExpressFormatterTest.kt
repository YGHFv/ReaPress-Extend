package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressFormatterTest {

    private fun record(
        courier: Courier = Courier.UNKNOWN,
        status: ExpressStatus = ExpressStatus.UNKNOWN,
        pickup: String? = null,
        station: String? = null,
        tracking: String? = null,
        raw: String = "原始文案",
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = raw,
        trackingNumber = tracking,
        courier = courier,
        pickupCode = pickup,
        station = station,
        status = status,
    )

    @Test
    fun `待取件标题带快递公司`() {
        val r = record(courier = Courier.SHUNFENG, status = ExpressStatus.READY_FOR_PICKUP)
        assertEquals("顺丰速运 · 待取件", ExpressFormatter.title(r))
    }

    @Test
    fun `未知公司标题退化为快递`() {
        val r = record(status = ExpressStatus.IN_TRANSIT)
        assertEquals("快递 · 运输中", ExpressFormatter.title(r))
    }

    @Test
    fun `未知状态时标题只有公司名`() {
        val r = record(courier = Courier.YUANTONG)
        assertEquals("圆通速递", ExpressFormatter.title(r))
    }

    @Test
    fun `正文字段按取件码 地点 运单号 状态排序`() {
        val r = record(
            courier = Courier.SHUNFENG,
            status = ExpressStatus.READY_FOR_PICKUP,
            pickup = "8-2-3021",
            station = "菜鸟驿站(文一西路店)",
            tracking = "SF1234567890123",
        )
        assertEquals(
            "取件码：8-2-3021\n地点：菜鸟驿站(文一西路店)\n运单号：SF1234567890123\n状态：待取件",
            ExpressFormatter.body(r),
        )
    }

    @Test
    fun `缺失字段整行省略`() {
        val r = record(status = ExpressStatus.DELIVERING)
        assertEquals("状态：派送中", ExpressFormatter.body(r))
    }

    @Test
    fun `一个字段都没有时退回原文首行`() {
        val r = record(raw = "您的包裹已发出\n第二行不该出现")
        assertEquals("您的包裹已发出", ExpressFormatter.body(r))
    }

    @Test
    fun `空白字段被忽略`() {
        val r = record(status = ExpressStatus.SIGNED, pickup = "   ", station = "")
        assertEquals("状态：已签收", ExpressFormatter.body(r))
    }

    @Test
    fun `多条合并标题优先报告待取件数`() {
        val records = listOf(
            record(status = ExpressStatus.READY_FOR_PICKUP),
            record(status = ExpressStatus.READY_FOR_PICKUP),
            record(status = ExpressStatus.IN_TRANSIT),
        )
        assertEquals("有 2 个包裹待取件", ExpressFormatter.summaryTitle(records))
    }

    @Test
    fun `单条合并标题走单条逻辑`() {
        val records = listOf(record(courier = Courier.SHUNFENG, status = ExpressStatus.SIGNED))
        assertEquals("顺丰速运 · 已签收", ExpressFormatter.summaryTitle(records))
    }

    @Test
    fun `空列表标题兜底`() {
        assertEquals("快递", ExpressFormatter.summaryTitle(emptyList()))
    }

    @Test
    fun `多条合并正文每行一条摘要`() {
        val records = listOf(
            record(courier = Courier.SHUNFENG, status = ExpressStatus.READY_FOR_PICKUP, pickup = "1-1-111"),
            record(courier = Courier.YUANTONG, status = ExpressStatus.IN_TRANSIT),
        )
        val body = ExpressFormatter.summaryBody(records)
        assertTrue(body.contains("· 顺丰速运 · 待取件（1-1-111）"))
        assertTrue(body.contains("· 圆通速递 · 运输中"))
    }
}
