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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

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
    fun `摘要行是取件码加驿站`() {
        // 只有「取件码」保留字段名 —— 光一串 `8-2-3021` 用户不知道那是什么；
        // 「地点 / 运单号 / 状态」的标签全砍掉（驿站名、运单号都自带辨识度，状态在标题里）
        val r = record(
            courier = Courier.SHUNFENG,
            status = ExpressStatus.READY_FOR_PICKUP,
            pickup = "8-2-3021",
            station = "菜鸟驿站(文一西路店)",
        )
        assertEquals("取件码 8-2-3021 · 菜鸟驿站(文一西路店)", ExpressFormatter.summaryLine(r))
    }

    @Test
    fun `正文是摘要加运单号且不写状态`() {
        val r = record(
            courier = Courier.SHUNFENG,
            status = ExpressStatus.READY_FOR_PICKUP,
            pickup = "8-2-3021",
            station = "菜鸟驿站(文一西路店)",
            tracking = "SF1234567890123",
        )
        assertEquals(
            "取件码 8-2-3021 · 菜鸟驿站(文一西路店)\n顺丰 SF1234567890123",
            ExpressFormatter.body(r),
        )
    }

    @Test
    fun `有取件码时运单号与动态另起两行`() {
        // 到站件的摘要只说「去哪取」，运单号和动态是另外两件事，各占一行
        val r = record(
            courier = Courier.SHUNFENG,
            status = ExpressStatus.READY_FOR_PICKUP,
            pickup = "8-2-3021",
            station = "文一西路店",
            tracking = "SF1234567890123",
        ).copy(logisticsDetail = "包裹已到站")
        assertEquals(
            "取件码 8-2-3021 · 文一西路店\n顺丰 SF1234567890123\n包裹已到站",
            ExpressFormatter.body(r),
        )
    }

    @Test
    fun `路上那些件的摘要退到运单动态加运单号`() {
        val r = record(courier = Courier.JITU, status = ExpressStatus.IN_TRANSIT, tracking = "JT123")
            .copy(logisticsDetail = "已发往【上海转运中心】")
        assertEquals("已发往【上海转运中心】 · 极兔 JT123", ExpressFormatter.summaryLine(r))
        // 摘要行已经吃下动态，正文不该再重复一遍
        assertEquals("已发往【上海转运中心】 · 极兔 JT123", ExpressFormatter.body(r))
    }

    @Test
    fun `只有运单号时摘要不给公司名加戏`() {
        val r = record(status = ExpressStatus.IN_TRANSIT, tracking = "SF1234567890123")
        assertEquals("SF1234567890123", ExpressFormatter.summaryLine(r))
    }

    @Test
    fun `一个字段都没有时退回原文首行`() {
        val r = record(raw = "您的包裹已发出\n第二行不该出现")
        assertEquals("您的包裹已发出", ExpressFormatter.body(r))
    }

    @Test
    fun `空白字段被忽略`() {
        val r = record(
            courier = Courier.SHUNFENG,
            status = ExpressStatus.SIGNED,
            pickup = "   ",
            station = "",
            tracking = "SF123",
        )
        assertEquals("顺丰 SF123", ExpressFormatter.body(r))
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

    @Test
    fun `relativeAge 分段口径`() {
        val now = 1_800_000_000_000L
        // 30 秒内不显示（「0分钟前」是错误观感）
        assertNull(ExpressFormatter.relativeAge(now - 30_000L, now))
        // 一小时内说分钟
        assertEquals("5分钟前", ExpressFormatter.relativeAge(now - 5 * 60_000L, now))
        assertEquals("59分钟前", ExpressFormatter.relativeAge(now - 59 * 60_000L, now))
        // 一天内说小时
        assertEquals("1小时前", ExpressFormatter.relativeAge(now - 60 * 60_000L, now))
        assertEquals("23小时前", ExpressFormatter.relativeAge(now - 23 * 3_600_000L, now))
        // 再往上说天
        assertEquals("2天前", ExpressFormatter.relativeAge(now - 2 * 86_400_000L, now))
        // 时钟回拨 / 宿主给错时间：null，整段不显示
        assertNull(ExpressFormatter.relativeAge(now + 1, now))
    }

    @Test
    fun `statusSince 取轨迹与宿主时间里较新的`() {
        // 真机 2026-09-26：13:54 已派送的件显示「11小时前」—— 宿主 gmt_modified 停在凌晨，
        // 而真正的派送时刻轨迹里写着。起算点必须两个来源取较新者。
        val zone = ZoneId.of("Asia/Shanghai")
        val dawn = LocalDateTime.parse("2026-09-26T04:47:00").atZone(zone).toInstant().toEpochMilli()
        val dispatch = LocalDateTime.parse("2026-09-26T13:54:00").atZone(zone).toInstant().toEpochMilli()

        // 轨迹更新 → 用轨迹的（13:54 派送 vs 凌晨 gmt_modified）
        val freshTrace = record().copy(timestamp = dawn).copy(
            trace = listOf(
                ExpressTracePoint("2026-09-25 09:00:00", "快件已到达【转运中心】"),
                ExpressTracePoint("2026-09-26 13:54:00", "快递员正在派件"),
            ),
        )
        assertEquals(dispatch, ExpressFormatter.statusSince(freshTrace, zone))

        // 轨迹是旧的（两天前拉的）→ 宿主时间兜底
        val staleTrace = record().copy(timestamp = dispatch).copy(
            trace = listOf(ExpressTracePoint("2026-09-24 09:00:00", "快件已发出")),
        )
        assertEquals(dispatch, ExpressFormatter.statusSince(staleTrace, zone))

        // 没轨迹没时间 → null（整段不显示，不编造）
        assertNull(ExpressFormatter.statusSince(record().copy(timestamp = 0L), zone))
        // 时间格式不对（解析不出）→ 当它不存在
        val badTime = record().copy(timestamp = dawn).copy(
            trace = listOf(ExpressTracePoint("9月26日 下午", "快递员正在派件")),
        )
        assertEquals(dawn, ExpressFormatter.statusSince(badTime, zone))
    }
}
