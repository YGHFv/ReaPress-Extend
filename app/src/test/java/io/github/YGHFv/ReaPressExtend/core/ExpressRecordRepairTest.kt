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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读路径自愈的单测 —— 输入一律是**真机上已经落库的那条记录**（2026-09-27 `reapress_records.xml`
 * 里 `origin=NOTIFICATION` 的唯一一条），字段值照抄，只有需要断言的数字保留原样。
 *
 * 为什么这批用例重要：这条记录是「旧解析器写坏 + 解析修好也救不回来」的活样本。
 * 用户看到的现象是首页多出一个叫 `驿站取尾号1234包裹` 的分组、短信里的取件码一个都没有。
 */
class ExpressRecordRepairTest {

    /** 真机那条短信记录，原文与落库值照抄。 */
    private fun staleSmsRecord() = ExpressRecord(
        sourcePackage = "com.android.mms",
        rawText = "+8613800138000\n【中通快递】凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹",
        trackingNumber = "8613800138000",
        courier = Courier.UNKNOWN,
        pickupCode = null,
        station = "驿站取尾号1234包裹",
        status = ExpressStatus.UNKNOWN,
        title = "+8613800138000",
        origin = ExpressOrigin.NOTIFICATION,
    )

    @Test
    fun `手机号冒充的运单号被清掉`() {
        val fixed = ExpressRecordRepair.repair(staleSmsRecord())
        // `+8613800138000` 是短信的**发件号码**（通知标题），旧解析器靠「12-15 位纯数字」
        // 这条规则把它收成了运单号 —— 于是这条记录在首页是一串谁也不认识的数字，
        // 而真正的包裹因为「运单号不相等」永远配不上它。
        assertNull(fixed.trackingNumber)
    }

    @Test
    fun `取件码被补回来`() {
        // 这是「解析层修好也白搭」的正面例子：旧值 null，而 null 永远等不到别人来填
        val fixed = ExpressRecordRepair.repair(staleSmsRecord())
        assertEquals("1-1-2001", fixed.pickupCode)
    }

    @Test
    fun `驿站名按当前解析逻辑重扫`() {
        val fixed = ExpressRecordRepair.repair(staleSmsRecord())
        assertEquals("阳光花园菜鸟驿站", fixed.station)
    }

    @Test
    fun `包裹尾号被补回来`() {
        val fixed = ExpressRecordRepair.repair(staleSmsRecord())
        assertEquals("1234", fixed.parcelTail)
        // 手机尾号是另一回事，这句里没写，不许被 `取尾号1234` 顶上来
        assertNull(fixed.phoneTail)
    }

    @Test
    fun `公司名从通知标题里认出来`() {
        val fixed = ExpressRecordRepair.repair(staleSmsRecord())
        assertEquals(Courier.ZHONGTONG, fixed.courier)
    }

    @Test
    fun `到站的话术推进成待取件`() {
        // 「取尾号1234包裹」整句里没有「取件码」三个字，不收它这条件就只能待在首页「其他」档，
        // 而它明明已经躺在驿站里等着取了。
        val fixed = ExpressRecordRepair.repair(staleSmsRecord())
        assertEquals(ExpressStatus.READY_FOR_PICKUP, fixed.status)
    }

    @Test
    fun `修完的记录仍然有身份`() {
        // 运单号被清掉之后，强标识就只剩取件码了 —— 补不回来的话整条记录会被
        // `load` 的 `filter { hasIdentity }` 丢掉（等于用户白白少一个包裹）。
        assertTrue(ExpressRecordRepair.repair(staleSmsRecord()).hasIdentity)
    }

    // ------------------------------------------------------------ 不许乱动

    @Test
    fun `宿主富化来的字段一个字都不改`() {
        // 富化记录的 rawText 是模块自己拼的 `运单号 X 站点 Y`，重扫它没有任何意义；
        // 更要紧的是宿主给的驿站名常常比通知里全（带楼栋号），不能被通知侧的写法顶掉。
        val enriched = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "运单号 JT2199803120840 站点 幸福小区54栋104店",
            trackingNumber = "JT2199803120840",
            courier = Courier.JITU,
            station = "幸福小区54栋104店",
            status = ExpressStatus.ARRIVED_STATION,
            origin = ExpressOrigin.ENRICHMENT,
        )
        assertEquals(enriched, ExpressRecordRepair.repair(enriched))
    }

    @Test
    fun `写得正常的驿站名不因为归一化会变形就重扫`() {
        // `normalize` 会剥掉品牌前缀和括号，那是**正常包装**、不是抓错。判据必须只看
        // 「句子的尾巴有没有被切下来」，否则每读一次都要重扫一遍、还会把宿主给的写法覆盖掉。
        val normal = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "您的包裹已到菜鸟驿站(杭州文一西路店)，取件码 8-2-3021，请及时取件",
            trackingNumber = null,
            pickupCode = "8-2-3021",
            station = "菜鸟驿站(杭州文一西路店)",
            status = ExpressStatus.READY_FOR_PICKUP,
            origin = ExpressOrigin.NOTIFICATION,
        )
        assertEquals("菜鸟驿站(杭州文一西路店)", ExpressRecordRepair.repair(normal).station)
    }

    @Test
    fun `认得出公司时不按原文再猜一遍`() {
        // 运单号前缀那条路更硬（`SF` 只可能是顺丰），不能让正文里顺带提到的品牌名把它改掉
        val byPrefix = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "您的顺丰快递 SF1234567890123 正在派送中，中通那边不负责这单",
            trackingNumber = "SF1234567890123",
            courier = Courier.SHUNFENG,
            status = ExpressStatus.DELIVERING,
            origin = ExpressOrigin.NOTIFICATION,
        )
        assertEquals(Courier.SHUNFENG, ExpressRecordRepair.repair(byPrefix).courier)
    }

    @Test
    fun `已经取件码的不被重解析结果覆盖`() {
        val kept = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "取件码 8-2-3021，另有取件码 9-9-9999",
            pickupCode = "8-2-3021",
            origin = ExpressOrigin.NOTIFICATION,
        )
        assertEquals("8-2-3021", ExpressRecordRepair.repair(kept).pickupCode)
    }

    @Test
    fun `状态只推进不倒退`() {
        // 富化把状态推到「已签收」之后，原文里还留着「取件码」两个字 ——
        // 读一次就把包裹打回「待取件」的话，首页上签收件会集体跳回来。
        val signed = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "单号 79035000001234 取件码 1-1-2001",
            trackingNumber = "79035000001234",
            pickupCode = "1-1-2001",
            status = ExpressStatus.SIGNED,
            origin = ExpressOrigin.ENRICHMENT,
        )
        assertEquals(ExpressStatus.SIGNED, ExpressRecordRepair.repair(signed).status)
    }

    @Test
    fun `说不出来在哪的驿站名被清空`() {
        // 关键词前后都啃不出地名（`…已到驿站 取件码…`）时只剩个裸的 `驿站`。
        // 它和 `代收点` 同类 —— 不是地名，留着只会在首页多一个分组并挡住富化的真名。
        val bare = ExpressRecord(
            sourcePackage = "com.android.shell",
            rawText = "【中通快递】单号7903000000000已到驿站 取件码 3-2-2008 请及时取件",
            trackingNumber = "7903000000000",
            pickupCode = "3-2-2008",
            station = "驿站 取件码 3-2-2008 请及时取件",
            origin = ExpressOrigin.NOTIFICATION,
        )
        val fixed = ExpressRecordRepair.repair(bare)
        assertFalse(ExpressStationName.hasLocation(fixed.station))
        // 有运单号、原文里也没有 `+` 前缀，运单号不许被动
        assertEquals("7903000000000", fixed.trackingNumber)
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `没有原文时原样返回`() {
        val blank = ExpressRecord(sourcePackage = "com.cainiao.wireless", rawText = "")
        assertSame(blank, ExpressRecordRepair.repair(blank))
    }

    @Test
    fun `没得修时返回原对象`() {
        // 调用方（[io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore.reconcile]）
        // 靠 `==` 判断「存储要不要重写」，同一个对象说明一个字节都不用动。
        val clean = ExpressRecord(
            sourcePackage = "com.android.mms",
            rawText = "【中通快递】单号 7903000000000 已到菜鸟驿站(杭州文一西路店)，取件码 8-2-3021",
            trackingNumber = "7903000000000",
            courier = Courier.ZHONGTONG,
            pickupCode = "8-2-3021",
            station = "菜鸟驿站(杭州文一西路店)",
            status = ExpressStatus.READY_FOR_PICKUP,
            origin = ExpressOrigin.NOTIFICATION,
        )
        assertSame(clean, ExpressRecordRepair.repair(clean))
    }

    @Test
    fun `重复修复结果不变`() {
        // 挂读路径的前提就是这个 —— `load` 每次读都会跑一遍
        val once = ExpressRecordRepair.repair(staleSmsRecord())
        assertEquals(once, ExpressRecordRepair.repair(once))
    }

    // ------------------------------------------------------------ PDD 诊断副标题（v23 扫描器的遗留）

    /** v23 PDD 扫描器给每条发现记录发的诊断串，库里真实形状照抄。 */
    private fun pddDiagnosticRecord(orderSn: String?) = ExpressRecord(
        sourcePackage = "com.xunmeng.pinduoduo",
        rawText = if (orderSn != null) "拼多多取快递缓存（订单 $orderSn）" else "拼多多取快递缓存",
        trackingNumber = "777398850599489",
        platform = "拼多多",
        status = ExpressStatus.UNKNOWN,
        origin = ExpressOrigin.ENRICHMENT,
    )

    @Test
    fun `PDD 旧诊断副标题带订单号的被清掉`() {
        val fixed = ExpressRecordRepair.repair(pddDiagnosticRecord("260410-367735652661006"))
        assertEquals("", fixed.rawText)
        // 清的只是显示层遗留，字段一个不动。
        assertEquals("777398850599489", fixed.trackingNumber)
        assertEquals(ExpressStatus.UNKNOWN, fixed.status)
    }

    @Test
    fun `PDD 旧诊断副标题无订单号的也被清掉`() {
        assertEquals("", ExpressRecordRepair.repair(pddDiagnosticRecord(null)).rawText)
    }

    @Test
    fun `PDD 诊断串形状不对的不清`() {
        // 整串精确匹配才清 —— 通知原文里万一真出现「拼多多」三个字，不能误伤。
        // （后半句是正经通知文案，走正常修复路径、可能推进状态，这里只验 raw 没被清。）
        val notOurs = ExpressRecord(
            sourcePackage = "com.android.mms",
            rawText = "拼多多取快递缓存（订单 260410-367735652661006）请凭取件码到驿站取件",
            trackingNumber = "777398850599489",
            origin = ExpressOrigin.NOTIFICATION,
        )
        assertEquals(notOurs.rawText, ExpressRecordRepair.repair(notOurs).rawText)
    }
}
