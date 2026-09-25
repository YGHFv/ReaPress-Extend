package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器单测。
 *
 * 语料全部是**真实形态**的快递通知文案 —— 菜鸟/短信/拼多多三家各取几条典型句式。
 * 纯函数、无 Android 依赖，所以能直接跑 JVM 单测（本工程没有 Robolectric，
 * `android.jar` 是桩，碰 Notification 会抛 "not mocked"）。
 */
class ExpressParserTest {

    // ---- 运单号 ----

    @Test
    fun `顺丰运单号带 SF 前缀`() {
        val text = "您的顺丰快递已发出，运单号 SF1234567890123，请留意查收"
        assertEquals("SF1234567890123", ExpressParser.parseTrackingNumber(text))
        assertEquals(Courier.SHUNFENG, Courier.fromTrackingNumber("SF1234567890123"))
    }

    @Test
    fun `圆通运单号带 YT 前缀`() {
        assertEquals("YT1234567890123", ExpressParser.parseTrackingNumber("圆通速递 YT1234567890123 已揽收"))
    }

    @Test
    fun `纯数字 12-15 位认作运单号`() {
        assertEquals("123456789012", ExpressParser.parseTrackingNumber("您的包裹 123456789012 已到站"))
    }

    @Test
    fun `手机号不会被误认成运单号`() {
        // 快递短信里手机号几乎必然出现，11 位纯数字必须排除
        val text = "您的包裹已到菜鸟驿站，联系电话 13812345678，取件码 8-2-3021"
        assertNull(ExpressParser.parseTrackingNumber(text))
    }

    @Test
    fun `以年份开头的 12 位数字不当运单号`() {
        assertNull(ExpressParser.parseTrackingNumber("2024-01-15 您的包裹已发出 202401151234"))
    }

    @Test
    fun `无运单号时返回 null`() {
        assertNull(ExpressParser.parseTrackingNumber("您的包裹正在派送中"))
    }

    // ---- 取件码 ----

    @Test
    fun `取件码带前缀`() {
        assertEquals("8-2-3021", ExpressParser.parsePickupCode("您的包裹已到菜鸟驿站，取件码 8-2-3021，请及时取件"))
    }

    @Test
    fun `取件码带中文冒号`() {
        assertEquals("A1234", ExpressParser.parsePickupCode("丰巢快递柜取件码：A1234"))
    }

    @Test
    fun `菜鸟驿站货架格式无前缀也认得出`() {
        assertEquals("8-2-3021", ExpressParser.parsePickupCode("包裹已入站 8-2-3021 请取件"))
    }

    @Test
    fun `无取件码时返回 null`() {
        assertNull(ExpressParser.parsePickupCode("您的包裹正在运输中"))
    }

    // ---- 驿站 ----

    @Test
    fun `驿站名带括号门店`() {
        assertEquals(
            "菜鸟驿站(杭州文一西路店)",
            ExpressParser.parseStation("您的包裹已到菜鸟驿站(杭州文一西路店)，请及时取件"),
        )
    }

    @Test
    fun `丰巢柜名`() {
        assertEquals("丰巢", ExpressParser.parseStation("包裹已放入丰巢，请凭取件码取件"))
    }

    // ---- 手机尾号 ----

    @Test
    fun `手机尾号在手机二字之后`() {
        assertEquals(
            "1234",
            ExpressParser.parsePhoneTail("您的包裹已放入快递柜，手机尾号1234，取件码 8-2-3021"),
        )
    }

    @Test
    fun `手机号后四位带冒号`() {
        assertEquals("5678", ExpressParser.parsePhoneTail("凭手机号后四位：5678 取件"))
    }

    @Test
    fun `尾号在手机之前的语序也认`() {
        assertEquals("1234", ExpressParser.parsePhoneTail("尾号1234的手机请到前台取件"))
    }

    @Test
    fun `单独的尾号不认成手机尾号`() {
        // 这是运单号尾号的常见写法 —— 认了就会让用户去跟店员报一个错的号
        assertNull(ExpressParser.parsePhoneTail("您的包裹运单号尾号0123，请及时取件"))
    }

    @Test
    fun `数字串更长时不当成手机尾号`() {
        // 「手机尾号12345」里的数字串比 4 位长，说不清到底哪四位是尾号 —— 宁可不要
        assertNull(ExpressParser.parsePhoneTail("手机尾号12345"))
    }

    // ---- 状态 ----

    @Test
    fun `已签收优先于签收`() {
        assertEquals(ExpressStatus.SIGNED, ExpressParser.parseStatus("您的包裹已签收，感谢使用"))
    }

    @Test
    fun `投递失败优先于派送`() {
        // 失败通知里常常也写着「派送」，顺序错会把失败判成派送中
        assertEquals(ExpressStatus.FAILED, ExpressParser.parseStatus("派送失败，快递员将再次派送"))
    }

    @Test
    fun `取件码即待取件`() {
        assertEquals(ExpressStatus.READY_FOR_PICKUP, ExpressParser.parseStatus("取件码 8-2-3021"))
    }

    @Test
    fun `运输中`() {
        assertEquals(ExpressStatus.IN_TRANSIT, ExpressParser.parseStatus("您的包裹已发出，运输中"))
    }

    @Test
    fun `无状态词返回 UNKNOWN`() {
        assertEquals(ExpressStatus.UNKNOWN, ExpressParser.parseStatus("这是一条普通消息"))
    }

    // ---- 一站式解析 ----

    @Test
    fun `菜鸟到站通知完整解析`() {
        val text = "您的包裹已到菜鸟驿站(杭州文一西路店)，取件码 8-2-3021，请及时取件"
        val verdict = ExpressClassifier.classify("com.cainiao.wireless", text, ExpressRule())
        val record = ExpressParser.parse("com.cainiao.wireless", "菜鸟", text, verdict, timestamp = 1000L)

        assertEquals(ExpressStatus.READY_FOR_PICKUP, record.status)
        assertEquals("8-2-3021", record.pickupCode)
        assertEquals("菜鸟驿站(杭州文一西路店)", record.station)
        assertNull(record.trackingNumber)
        assertTrue(record.matchedKeywords.isNotEmpty())
        assertEquals(1000L, record.timestamp)
    }

    @Test
    fun `顺丰通知完整解析`() {
        val text = "您的顺丰快递 SF1234567890123 正在派送中，请保持电话畅通"
        val verdict = ExpressClassifier.classify("com.xunmeng.pinduoduo", text, ExpressRule())
        val record = ExpressParser.parse("com.xunmeng.pinduoduo", "拼多多", text, verdict)

        assertEquals("SF1234567890123", record.trackingNumber)
        assertEquals(Courier.SHUNFENG, record.courier)
        assertEquals(ExpressStatus.DELIVERING, record.status)
    }
}
