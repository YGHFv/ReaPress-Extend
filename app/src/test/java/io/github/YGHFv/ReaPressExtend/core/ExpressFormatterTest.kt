package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `待取件标题带快递公司简称`() {
        // 简称而不是 `displayName`：通知栏一行放不下多少字，省下的空间留给状态和取件码。
        val r = record(courier = Courier.SHUNFENG, status = ExpressStatus.READY_FOR_PICKUP)
        assertEquals("顺丰 · 待取件", ExpressFormatter.title(r))
    }

    @Test
    fun `未知公司标题退化为快递`() {
        val r = record(status = ExpressStatus.IN_TRANSIT)
        assertEquals("快递 · 运输中", ExpressFormatter.title(r))
    }

    @Test
    fun `未知状态时标题只有公司名`() {
        val r = record(courier = Courier.YUANTONG)
        assertEquals("圆通", ExpressFormatter.title(r))
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
        assertEquals("顺丰 · 已签收", ExpressFormatter.summaryTitle(records))
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
        assertTrue(body.contains("· 顺丰 · 待取件（1-1-111）"))
        assertTrue(body.contains("· 圆通 · 运输中"))
    }

    // ---- 运输中卡片的副行 ----

    @Test
    fun `只有运单号时不显示尾号`() {
        // 运单号尾号不是独立信息 —— 全号已经在标题里了，再补一句就是把同一串数字说两遍
        val r = record(courier = Courier.JITU, tracking = "JT3100000007996")
        assertNull(ExpressFormatter.detailLine(r))
    }

    @Test
    fun `手机尾号照常显示`() {
        val r = record(tracking = "JT3100000007996").copy(phoneTail = "9976")
        assertEquals("手机尾号9976", ExpressFormatter.detailLine(r))
    }

    @Test
    fun `手机尾号后面接运单动态`() {
        val r = record(tracking = "JT3100000007996")
            .copy(phoneTail = "9976", logisticsDetail = "已发往【上海转运中心】")
        assertEquals("手机尾号9976 · 已发往【上海转运中心】", ExpressFormatter.detailLine(r))
    }

    @Test
    fun `只有运单动态时也显示`() {
        val r = record(tracking = "JT3100000007996").copy(logisticsDetail = "已发往【上海转运中心】")
        assertEquals("已发往【上海转运中心】", ExpressFormatter.detailLine(r))
    }

    @Test
    fun `空白运单动态被忽略`() {
        val r = record(tracking = "SF1").copy(logisticsDetail = "   ")
        assertNull(ExpressFormatter.detailLine(r))
    }

    // ---- 状态文案（用户确认取件）----

    @Test
    fun `用户确认取件后状态文案是已取件`() {
        // 宿主还没推「已签收」时，界面上该显示用户做过的事，不是那个已经过期的「待取件」
        val r = record(status = ExpressStatus.READY_FOR_PICKUP).copy(pickedUpAt = 1L)
        assertEquals("已取件", ExpressFormatter.statusLabel(r))
    }

    @Test
    fun `宿主推来已签收后改用它自己的说法`() {
        val r = record(status = ExpressStatus.SIGNED).copy(pickedUpAt = 1L)
        assertEquals("已签收", ExpressFormatter.statusLabel(r))
    }

    @Test
    fun `没标记时就是宿主状态`() {
        val r = record(status = ExpressStatus.READY_FOR_PICKUP)
        assertEquals("待取件", ExpressFormatter.statusLabel(r))
    }
}
