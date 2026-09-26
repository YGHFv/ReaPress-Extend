package io.github.YGHFv.ReaPressExtend.notification

import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 焦点通知参数的单测。
 *
 * 这里钉的**不是**「系统一定会怎么渲染」（那要真机看），而是我们这侧可控的三件事：
 * 协议版本原样回填、文案与通知正文同源、以及**没有依据的字段一个都不写**。
 */
class MiuiFocusPayloadTest {

    private fun record(
        courier: Courier = Courier.SHUNFENG,
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
        pickup: String? = "8-2-3021",
        station: String? = "文一西路店",
        tracking: String? = "SF1234567890123",
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "原始文案",
        trackingNumber = tracking,
        courier = courier,
        pickupCode = pickup,
        station = station,
        status = status,
        timestamp = 1000L,
    )

    private fun paramOf(json: String) = JSONObject(json).getJSONObject("param_v2")

    @Test
    fun `协议版本原样回填`() {
        // 系统按这个数选模板；写死成 1 会让高版本系统按错的模板解析
        assertEquals(3, paramOf(MiuiFocusPayload.build(record(), 3)).getInt("protocol"))
        assertEquals(1, paramOf(MiuiFocusPayload.build(record(), 1)).getInt("protocol"))
    }

    @Test
    fun `标题与副文案和通知正文同源`() {
        val param = paramOf(MiuiFocusPayload.build(record(), 1))
        val base = param.getJSONObject("baseInfo")

        assertEquals("顺丰 · 待取件", base.getString("title"))
        assertEquals("取件码 8-2-3021 · 文一西路店", base.getString("subContent"))
        // 状态栏（OS2 起）与息屏文案
        assertEquals("顺丰 · 待取件", param.getString("ticker"))
        assertEquals("取件码 8-2-3021 · 文一西路店", param.getString("aodTitle"))
    }

    @Test
    fun `没有副文案时不写空串`() {
        // 空串与「没有这个字段」在系统那边不是一回事 —— 宁可整条不写
        val param = paramOf(
            MiuiFocusPayload.build(record(pickup = null, station = null, tracking = null), 1),
        )
        assertFalse(param.getJSONObject("baseInfo").has("subContent"))
        assertFalse(param.has("aodTitle"))
    }

    @Test
    fun `无权限时退化为普通通知`() {
        // filterWhenNoPermission=false：权限被关掉时通知正常显示、不被过滤。
        // 模块的通知替代的是被吞掉的原通知，丢一条就是全没了
        assertFalse(paramOf(MiuiFocusPayload.build(record(), 1)).getBoolean("filterWhenNoPermission"))
    }

    @Test
    fun `不含没有依据的模板字段`() {
        // baseInfo.type（模板类型）的取值含义只在未公开的《模板库》里，
        // 猜错可能把快递渲染成计时器模样的卡片
        val param = paramOf(MiuiFocusPayload.build(record(), 1))
        assertFalse(param.getJSONObject("baseInfo").has("type"))
        // 岛参数同理：本机（HyperOS 1.0 / 协议 1）不支持岛，写了也无从验证
        assertFalse(param.has("param_island"))
    }
}
