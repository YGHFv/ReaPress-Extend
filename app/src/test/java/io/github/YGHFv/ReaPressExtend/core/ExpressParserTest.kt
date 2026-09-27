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

    @Test
    fun `紧贴中文的运单号也要抽得到`() {
        // 2026-09-27 真机：`\b` 在 Android 上是 Unicode 词边界，`单号` 与数字之间不算边界，
        // 于是这条通知的运单号一条都没抽到（详情见 ExpressParser 文件头那张实测表）。
        assertEquals(
            "7903000000000",
            ExpressParser.parseTrackingNumber("单号7903000000000已到驿站 取件码 3-2-2008 请及时取件"),
        )
        assertEquals(
            "SF1234567890123",
            ExpressParser.parseTrackingNumber("您的快递已发出运单号SF1234567890123请留意查收"),
        )
    }

    @Test
    fun `带国家码的发件号码不当运单号`() {
        // 短信通知的标题就是发送号码。`+8613800138000` 剥掉 `+` 之后正好落进「12-15 位纯数字」
        // 这条规则里，真机上被记成了一单「运单号 8613800138000」的包裹。
        assertNull(ExpressParser.parseTrackingNumber("+8613800138000"))
        assertNull(ExpressParser.parseTrackingNumber("008613800138000 您的快递到了"))
        // 真正以 86 开头、但不符合手机号形状的长号仍然照收
        assertEquals("8612345678901", ExpressParser.parseTrackingNumber("运单号 8612345678901 已发出"))
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
    fun `紧贴中文的取件码也要抽得到`() {
        // 同 `紧贴中文的运单号也要抽得到`：真机原文里取件码两侧都是汉字，
        // `\b` 配不上，记录连强标识都没有（`upsert dropped`）。
        assertEquals("1-1-2001", ExpressParser.parsePickupCode("凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹"))
        assertEquals("17-5-2644", ExpressParser.parsePickupCode("凭17-5-2644到店取件"))
    }

    @Test
    fun `取件码紧跟在词后面带短横`() {
        assertEquals("6-2-2003", ExpressParser.parsePickupCode("存放已超过24小时【取件码-6-2-2003】，别忘了哦"))
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

    @Test
    fun `关键词后面跟着句子时只取到关键词`() {
        // 2026-09-27 真机原文（淘宝，超时未取提醒；取件码数字已替换）。原来的
        // `[^，。！\n]{0,20}` 会一路啃到取件码中间 —— 抽出来的是
        // `代收点存放已超过24小时 【取件码-8-2-3`（正好撞上 20 字上限），
        // 首页于是凭空多出一个叫这串字的驿站分组，用户报的就是这个。
        val text = "您还有包裹等待取件\n您购买的商品在代收点存放已超过24小时\u00a0【取件码-8-2-3021】，别忘了哦>>"
        assertEquals("代收点", ExpressParser.parseStation(text))
        // 取件码本身不受影响，照旧要抽得出来
        assertEquals("8-2-3021", ExpressParser.parsePickupCode(text))
        // 剩下的 `代收点` 是类型词不是地名 → 归一化后为空 → 归到「未知取件地点」，
        // 而不是自成一组，也不会挡住宿主富化的真名（见 ExpressStationNameTest）。
        assertEquals("", ExpressStationName.normalize(ExpressParser.parseStation(text)))
    }

    @Test
    fun `括号门店名不被后面的句子顶掉`() {
        // 括号门店后面直接接句子时，门店名必须完整留在括号里，不能被那半句话顶掉。
        assertEquals(
            "菜鸟驿站(杭州文一西路店)",
            ExpressParser.parseStation("您的包裹已到菜鸟驿站(杭州文一西路店)存放已超过24小时，请尽快取件"),
        )
    }

    @Test
    fun `关键词前面的地名要一起收下`() {
        // 2026-09-27 真机短信原文（尾号/取件码数字已替换）。旧实现只从关键词往后啃，
        // 抽出的是 `驿站取尾号1234包裹` —— 首页凭空多出一个叫这串字的驿站分组，
        // 而真正的店名 `阳光花园菜鸟驿站` 一个字都没留下。用户报的「快递站点识别不了」。
        val text = "凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹"
        assertEquals("阳光花园菜鸟驿站", ExpressParser.parseStation(text))
        // 上一句里 `到` 是停止词，所以 `凭1-1-2001到` 不会被吃进来
        assertEquals("阳光花园驿站", ExpressParser.parseStation("您的快递在阳光花园驿站存放已超过24小时"))
    }

    @Test
    fun `关键词前是叙述词时不多吃`() {
        // `已到` / `放入` 这类动词短语把「向前啃」挡在店名之外 —— 这是向前啃唯一的风险点，
        // 逐条钉住。注意 `菜鸟驿站` 前是 `到`，`丰巢` 前是 `放入`。
        assertEquals("菜鸟驿站", ExpressParser.parseStation("您的包裹已到菜鸟驿站，请及时取件"))
        assertEquals("丰巢", ExpressParser.parseStation("包裹已放入丰巢，请凭取件码取件"))
        assertEquals("代收点", ExpressParser.parseStation("您购买的商品在代收点存放已超过24小时"))
        // `菜鸟驿站` 自己就带着品牌前缀，不能再往前吃
        assertEquals("菜鸟驿站(杭州文一西路店)", ExpressParser.parseStation("菜鸟驿站(杭州文一西路店)提醒您取件"))
    }

    @Test
    fun `向前啃不会吃掉楼栋号`() {
        // 数字与 `号楼` 都是名字的一部分（`小区3号楼驿站`），不能因为「是数字」就停。
        assertEquals("小区3号楼菜鸟驿站", ExpressParser.parseStation("您的包裹已放入小区3号楼菜鸟驿站"))
    }

    // ---- 包裹尾号 ----

    @Test
    fun `取尾号里的数字是包裹尾号`() {
        assertEquals("1234", ExpressParser.parseParcelTail("凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹"))
        assertEquals("0123", ExpressParser.parseParcelTail("请取尾号：0123 的包裹"))
    }

    @Test
    fun `不带取字的尾号不认成包裹尾号`() {
        // 「运单号尾号0123」「手机尾号0123」都长这样，认了会去匹配一件无关的包裹
        // —— 手机尾号那条尤其糟：它根本不是包裹的标识。
        assertNull(ExpressParser.parseParcelTail("您的包裹运单号尾号0123，请及时取件"))
        assertNull(ExpressParser.parseParcelTail("您的手机尾号0123的包裹到了"))
    }

    @Test
    fun `包裹尾号与手机尾号互不抢`() {
        val text = "您的包裹已放入快递柜，手机尾号1234，取尾号5678包裹"
        assertEquals("1234", ExpressParser.parsePhoneTail(text))
        assertEquals("5678", ExpressParser.parseParcelTail(text))
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
    fun `取尾号也是待取件`() {
        // 驿站/快递柜最常见的到站话术，整句里一个「取件码」都没有。不收它这条短信
        // 只能判成 UNKNOWN —— 真机上它于是掉进首页「其他」档，而它明明已经躺在驿站里了。
        assertEquals(
            ExpressStatus.READY_FOR_PICKUP,
            ExpressParser.parseStatus("凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹"),
        )
    }

    @Test
    fun `运输中`() {
        assertEquals(ExpressStatus.IN_TRANSIT, ExpressParser.parseStatus("您的包裹已发出，运输中"))
    }

    @Test
    fun `菜鸟宿主的待发货与已揽件认得出`() {
        // 这两个是宿主 `logisticsStatusDesc` 的原话（不是通知文案），以前不在状态表里，
        // 于是这类件在首页掉进「其他」—— 表现就是「状态显示不出来」
        assertEquals(ExpressStatus.CREATED, ExpressParser.parseStatus("待发货"))
        assertEquals(ExpressStatus.PICKED_UP, ExpressParser.parseStatus("已揽件"))
        assertEquals(ExpressStatus.IN_TRANSIT, ExpressParser.parseStatus("已发货"))
    }

    @Test
    fun `已揽件与派件中不互相干扰`() {
        // 只差一个字，顺序或子串判错就会串档
        assertEquals(ExpressStatus.PICKED_UP, ExpressParser.parseStatus("您的包裹已揽件"))
        assertEquals(ExpressStatus.DELIVERING, ExpressParser.parseStatus("您的包裹派件中"))
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

    @Test
    fun `包裹正在等待揽收判为CREATED`() {
        // 宿主 lastLogisticDetail 的原话。不收它的话这类件会被 statusDesc 的
        // 笼统「运输中」抬进错误的档位。
        assertEquals(ExpressStatus.CREATED, ExpressParser.parseStatus("包裹正在等待揽收"))
        assertEquals(ExpressStatus.CREATED, ExpressParser.parseStatus("您的快递待揽收"))
        // 「已揽收」仍是 PICKED_UP，不能被新词截胡
        assertEquals(ExpressStatus.PICKED_UP, ExpressParser.parseStatus("快件已揽收"))
    }
}
