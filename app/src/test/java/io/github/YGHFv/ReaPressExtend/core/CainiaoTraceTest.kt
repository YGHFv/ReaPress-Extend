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
 * 第二条数据源（淘宝 `queryalltrace` 全轨迹通道）的单测。
 *
 * 样本照 2026-09-26 真机实测的响应改写（极兔 JT0000000000000，15 条轨迹）——
 * 结构、字段路径、广告话术都照原样，只有商品名换成了假名（仓库公开）。
 * 证据出处见 `express-source-research.md`。
 *
 * 这一批断言钉的是**契约**而不是实现：字段路径（`cp.tpName` 这种）、清洗的保守边界
 * （广告才删、网点名要留）、以及「运输中件的 address 不是取件地址」这条容易写错的规则。
 * 哪天接口改了字段名，这里应该先红。
 */
class CainiaoTraceTest {

    // ------------------------------------------------------------ 轨迹文案清洗

    @Test
    fun `广告话术被删掉_真动态留着`() {
        val raw = "【青山城南河东社区网点】的极兔快递员：张伟（13800138000）正在为您派件" +
            "（有事先呼我，勿找平台，少一次投诉，多一份感恩！），投诉电话（010-1234567/13800138001）。" +
            "【952300为极兔快递员外呼专属号码，请放心接听】"
        val cleaned = ExpressTraceText.clean(raw)

        // 真动态（哪个网点、谁在派件、电话）必须留着
        assertTrue(cleaned.contains("青山城南河东社区网点"))
        assertTrue(cleaned.contains("张伟"))
        assertTrue(cleaned.contains("13800138000"))
        // 广告段整段消失
        assertFalse(cleaned.contains("勿找平台"))
        assertFalse(cleaned.contains("少一次投诉"))
        assertFalse(cleaned.contains("请放心接听"))
    }

    @Test
    fun `同样是方括号_网点名不能被误删`() {
        // 【城市中心网点】长得和广告段一模一样，靠「不含特征词」这条兜住
        val raw = "快件已到达【城市中心网点】，正在分拣"
        assertEquals(raw, ExpressTraceText.clean(raw))
    }

    @Test
    fun `整条都是广告时清洗成空串`() {
        assertEquals("", ExpressTraceText.clean("（物流问题无需找商家，请联系956025）"))
    }

    @Test
    fun `括号不配对时整条原样保留`() {
        // 半个括号多半意味着模板和我们预期的不一样，此时不动比猜着删安全
        val raw = "快件已到达【城市中心网点"
        assertEquals(raw, ExpressTraceText.clean(raw))
    }

    @Test
    fun `连续空白被压成一个空格`() {
        assertEquals("快件 已到达 网点", ExpressTraceText.clean("快件   已到达\n\n网点"))
    }

    // ------------------------------------------------------------ 编解码

    @Test
    fun `编解码往返一致`() {
        val points = listOf(
            ExpressTracePoint("2026-09-20 10:00:00", "快件已揽收"),
            ExpressTracePoint("2026-09-26 13:19:31", "快件已到达【城东集散点】"),
        )
        assertEquals(points, ExpressTraceCodec.decode(ExpressTraceCodec.encode(points)))
    }

    @Test
    fun `没有轨迹只有一种表示`() {
        val empty = emptyList<ExpressTracePoint>()
        assertEquals(empty, ExpressTraceCodec.decode(ExpressTraceCodec.encode(empty)))
        assertEquals(empty, ExpressTraceCodec.decode(null))
        assertEquals(empty, ExpressTraceCodec.decode(""))
        assertEquals(empty, ExpressTraceCodec.decode("[]"))
    }

    @Test
    fun `坏数据解码成空表而不是抛异常`() {
        assertEquals(emptyList<ExpressTracePoint>(), ExpressTraceCodec.decode("{不是数组"))
    }

    @Test
    fun `缺正文的条目被丢掉`() {
        // 第二条正文是空串、第三条根本没有第二项 —— 留着会让详情页出现空白行
        val raw = """[["2026-09-26 13:19:31","快件已到达"],["2026-09-26 14:00:00",""],["2026-09-26 15:00:00"]]"""
        val decoded = ExpressTraceCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("快件已到达", decoded[0].text)
    }

    // ------------------------------------------------------------ MTOP H5 签名

    @Test
    fun `appKey 是淘宝 H5 的 12574478`() {
        assertEquals("12574478", MtopSign.TAOBAO_H5_APP_KEY)
    }

    @Test
    fun `从 _m_h5_tk 完整值里取签名用的 token`() {
        assertEquals(
            "00000000000000000000000000000000",
            MtopSign.tokenOf("00000000000000000000000000000000_1790410278831"),
        )
        // 没有下划线时原样返回
        assertEquals("abc", MtopSign.tokenOf("abc"))
    }

    @Test
    fun `md5 定长 32 位小写十六进制`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", MtopSign.md5("abc"))
        // 高位字节必须按 8 位补 0 而不是被符号扩展成 8 个 f
        assertEquals(32, MtopSign.md5("abc").length)
    }

    @Test
    fun `wapSign 按 token&t&appKey&data 拼接`() {
        assertEquals(
            "922e50f8507876a9254b16c53e810e2c",
            MtopSign.wapSign(
                token = "00000000000000000000000000000000",
                timestamp = "1790410278831",
                appKey = MtopSign.TAOBAO_H5_APP_KEY,
                data = """{"mailNo":"JT0000000000000"}""",
            ),
        )
    }

    // ------------------------------------------------------------ 响应解析

    @Test
    fun `字段路径照接口原样`() {
        val info = CainiaoTraceParser.parse(body(lastStatus = "待取件", address = STATION_ADDRESS))!!

        assertEquals("JT0000000000000", info.trackingNumber)
        assertEquals("极兔速递", info.courierName)
        assertEquals("HTKY", info.courierCode)
        assertEquals("952300", info.courierPhone)
        assertEquals("云南一级白糖砂糖", info.goodsName)
        assertEquals("https://img.alicdn.com/x.jpg", info.goodsImage)
        // 全轨迹两条都留下来了
        assertEquals(2, info.points.size)
        assertEquals("快件已揽收", info.points[0].text)
        // 解析出来的轨迹已经洗过：广告那段括号没了，驿站名和状态留着
        assertEquals("快件已存放至【阳光花园菜鸟驿站】", info.points[1].text)
    }

    @Test
    fun `newStatusDesc 优先于旧的 status`() {
        // 真机上出现过 status=派送中 / newStatusDesc=待取件：包裹已经卸到代收点，
        // 只是主状态还没翻。解析取新的那个。
        val info = CainiaoTraceParser.parse(body(lastStatus = "待取件", address = STATION_ADDRESS))
        assertEquals("待取件", info?.statusDesc)
    }

    @Test
    fun `没有 newStatusDesc 时退回 status`() {
        val raw = """{"data":{"result":[{"mailNo":"JT1","packageStatus":{"status":"运输中"}}]}}"""
        assertEquals("运输中", CainiaoTraceParser.parse(raw)?.statusDesc)
    }

    @Test
    fun `结论型状态可以直接拿来推进记录`() {
        // 卡片右上角那句「派送中 / 待取件」+ 分档（运输中 → 到站包裹）读的都是 record.status，
        // 而它以前只有宿主富化会给 —— 宿主不刷新，用户就看着旧状态（2026-09-27 用户报的）。
        // 这个样本改的是 packageStatus（`body()` 里那个字段是写死的「待取件」）。
        assertEquals(
            ExpressStatus.READY_FOR_PICKUP,
            CainiaoTraceParser.parse(statusBody("待取件"))?.status,
        )
        assertEquals(ExpressStatus.DELIVERING, CainiaoTraceParser.parse(statusBody("派送中"))?.status)
        assertEquals(ExpressStatus.ARRIVED_STATION, CainiaoTraceParser.parse(statusBody("已到站"))?.status)
        assertEquals(ExpressStatus.SIGNED, CainiaoTraceParser.parse(statusBody("已签收"))?.status)
    }

    @Test
    fun `运输中不收_免得把已下单抬回运输中`() {
        // 轨迹的 newStatusDesc 与宿主描述同源。收下「运输中」就等于把 mergeEnrichment 里
        // 那条「CREATED 纠正 IN_TRANSIT」原样盖回去（界面从「已下单」跳回「运输中」）。
        // 原话仍然照收（statusDesc），只是不当判据用。
        assertNull(CainiaoTraceParser.parse(statusBody("运输中"))?.status)
        assertEquals("运输中", CainiaoTraceParser.parse(statusBody("运输中"))?.statusDesc)
    }

    @Test
    fun `认不出的状态描述不给状态`() {
        assertNull(CainiaoTraceParser.parse(statusBody("莫名其妙的一句"))?.status)
    }

    /** 只带 `packageStatus`，用来单独钉「状态怎么取」这件事（`body()` 里那个字段是写死的）。 */
    private fun statusBody(desc: String): String =
        """{"data":{"result":[{"mailNo":"JT1","packageStatus":{"newStatusDesc":"$desc"}}]}}"""

    @Test
    fun `轨迹状态经合并只推进不回退`() {
        val arrived = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "x",
            trackingNumber = "JT1",
            status = ExpressStatus.ARRIVED_STATION,
        )
        // 轨迹这一趟给的是「运输中」也不该把到站件退回去（isAdvanceFrom 拦下）。
        val stale = arrived.copy(status = ExpressStatus.IN_TRANSIT)
        assertEquals(ExpressStatus.ARRIVED_STATION, arrived.mergeEnrichment(stale).status)
        // 正向：运输中的件被轨迹推到待取件，收下。
        val transit = arrived.copy(status = ExpressStatus.IN_TRANSIT)
        assertEquals(
            ExpressStatus.READY_FOR_PICKUP,
            transit.mergeEnrichment(arrived.copy(status = ExpressStatus.READY_FOR_PICKUP)).status,
        )
    }

    @Test
    fun `末条是到站类状态时才有取件地址`() {
        val arrived = CainiaoTraceParser.parse(body(lastStatus = "待取件", address = STATION_ADDRESS))
        assertEquals(STATION_ADDRESS, arrived?.stationAddress)
    }

    @Test
    fun `运输中件的 address 是转运中心_不能当取件地址`() {
        // 实测：末条 statusDesc=派送中 时 address 给的是派件网点（`泰瑞建材城S11栋113-115`）。
        // 当取件地址写进记录，用户会照着找错地方。
        val transit = CainiaoTraceParser.parse(body(lastStatus = "派送中", address = "泰瑞建材城S11栋113-115"))
        assertNull(transit?.stationAddress)
    }

    @Test
    fun `结构不符时返回 null 而不是空对象`() {
        assertNull(CainiaoTraceParser.parse("{}"))
        assertNull(CainiaoTraceParser.parse("不是 JSON"))
        assertNull(CainiaoTraceParser.parse("""{"data":{"result":[]}}"""))
    }

    // ------------------------------------------------------------ 合并规则

    @Test
    fun `轨迹只增不减`() {
        val old = record(trace = listOf(ExpressTracePoint("t1", "已揽收")))
        val fuller = record(
            trace = listOf(
                ExpressTracePoint("t1", "已揽收"),
                ExpressTracePoint("t2", "已到达"),
            ),
        )
        assertEquals(2, old.mergeEnrichment(fuller).trace.size)

        // 接口偶发返回残缺列表时不能把已经攒下的轨迹冲掉：
        // fuller(2 条) 收到 old(1 条) 的富化，仍保留自己的 2 条
        assertEquals(2, fuller.mergeEnrichment(old).trace.size)
    }

    @Test
    fun `驿站地址和商品图只填空不覆盖`() {
        val withAddress = record(address = "阳光23号楼109")
        val another = record(address = "别的地方", image = "https://img.alicdn.com/y.jpg")

        val merged = withAddress.mergeEnrichment(another)
        assertEquals("阳光23号楼109", merged.stationAddress)
        assertEquals("https://img.alicdn.com/y.jpg", merged.goodsImage)

        // 反向：本记录空着时才吸收
        assertEquals("别的地方", record().mergeEnrichment(another).stationAddress)
    }

    // ------------------------------------------------------------ 运单动态（末条轨迹）

    @Test
    fun `末条轨迹就是卡片上那句运单动态`() {
        val points = listOf(
            ExpressTracePoint("2026-09-20 10:00:00", "快件已揽收"),
            ExpressTracePoint("2026-09-26 13:19:31", "快件已到达【城东集散点】"),
        )
        // 「最早 → 最新」的末条，正是首页副行 / 通知正文上的那句运单动态
        assertEquals("快件已到达【城东集散点】", latestTraceDetail(points))
    }

    @Test
    fun `没有轨迹时不给动态`() {
        // 全是广告被清洗掉、或接口返回空表时都不能编一句出来 ——
        // 返回 null，合并时原样保留记录里已有的值
        assertNull(latestTraceDetail(emptyList()))
    }

    @Test
    fun `刚拉到的轨迹会顶掉过时的宿主动态`() {
        // 2026-09-27 用户报的现场：首页那句动态停在上次打开菜鸟时的样子，点进详情能拉到
        // 新轨迹，回到首页却不变。根因是轨迹拉取只写 trace，而卡片读的是 logisticsDetail。
        // 轨迹是自己刚拉的（timestamp 更新），走 mergeEnrichment 的时序规则顶掉旧的。
        val stored = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "您的包裹已到达",
            trackingNumber = "JT0000000000000",
            logisticsDetail = "快件离开【南宁转运中心】",
            status = ExpressStatus.IN_TRANSIT,
            timestamp = 1_000L,
        )
        val fetched = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "trace request",
            trackingNumber = "JT0000000000000",
            origin = ExpressOrigin.ENRICHMENT,
            timestamp = 9_000L,
            trace = listOf(
                ExpressTracePoint("2026-09-25 09:00:00", "快件离开【南宁转运中心】"),
                ExpressTracePoint("2026-09-26 13:19:31", "快件已到达【城东集散点】"),
            ),
            logisticsDetail = "快件已到达【城东集散点】",
        )

        val merged = stored.mergeEnrichment(fetched)
        assertEquals("快件已到达【城东集散点】", merged.logisticsDetail)
        // 首页卡片副行读的就是这个字段 —— 顺手把「拉回轨迹 → 卡片会变」这条链钉住
        assertEquals("快件已到达【城东集散点】", ExpressFormatter.detailLine(merged))
    }

    @Test
    fun `拉不到轨迹时不动已有的动态`() {
        // 末条被广告清洗掉时 latestTraceDetail 是 null，合并不能把已有的那句抹掉
        val stored = record().copy(logisticsDetail = "快递员正在派件")
        val bare = record().copy(timestamp = 9_000L, logisticsDetail = null)
        assertEquals("快递员正在派件", stored.mergeEnrichment(bare).logisticsDetail)
    }

    // ------------------------------------------------------------ 样本

    private fun record(
        trace: List<ExpressTracePoint> = emptyList(),
        address: String? = null,
        image: String? = null,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "您的包裹已到达",
        trackingNumber = "JT0000000000000",
        trace = trace,
        stationAddress = address,
        goodsImage = image,
    )

    /**
     * 一份照真实响应改写的 body。
     *
     * @param lastStatus 末条轨迹的状态描述 —— 用它来切「到站件」与「运输中件」两种情形
     * @param address 末条轨迹的 address
     */
    private fun body(lastStatus: String, address: String): String = """
        {"data":{"result":[{
          "mailNo":"JT0000000000000",
          "cp":{"tpName":"极兔速递","tpCode":"HTKY","tpContact":"952300"},
          "packageStatus":{"newStatusDesc":"待取件","status":"派送中"},
          "packageItems":[{"goodsName":"云南一级白糖砂糖","allPicUrl":"https://img.alicdn.com/x.jpg"}],
          "fullTraceDetail":[
            {"time":"2026-09-20 10:00:00","statusDesc":"快件已揽收","desc":"快件已揽收"},
            {"time":"2026-09-26 13:19:31","statusDesc":"$lastStatus",
             "desc":"快件已存放至【阳光花园菜鸟驿站】（物流问题无需找商家，请联系956025）",
             "address":"$address"}
          ]
        }]}}
    """.trimIndent()

    private val STATION_ADDRESS = "阳光23号楼109阳光花园菜鸟驿站"
}
