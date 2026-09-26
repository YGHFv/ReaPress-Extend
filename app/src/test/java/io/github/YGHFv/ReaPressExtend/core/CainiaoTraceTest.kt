package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二条数据源（淘宝 `queryalltrace` 全轨迹通道）的单测。
 *
 * 样本照 2026-09-26 真机实测的响应改写（极兔 JT3178691239988，15 条轨迹）——
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
        val raw = "【阜阳颍泉双河社区网点】的兔兔快递员：董力（13951517595）正在为您派件" +
            "（有事先呼我，勿找平台，少一次投诉，多一份感恩！），投诉电话（0558-5011429/13515572187）。" +
            "【952300为极兔快递员外呼专属号码，请放心接听】"
        val cleaned = ExpressTraceText.clean(raw)

        // 真动态（哪个网点、谁在派件、电话）必须留着
        assertTrue(cleaned.contains("阜阳颍泉双河社区网点"))
        assertTrue(cleaned.contains("董力"))
        assertTrue(cleaned.contains("13951517595"))
        // 广告段整段消失
        assertFalse(cleaned.contains("勿找平台"))
        assertFalse(cleaned.contains("少一次投诉"))
        assertFalse(cleaned.contains("请放心接听"))
    }

    @Test
    fun `同样是方括号_网点名不能被误删`() {
        // 【上海嘉定曹安路网点】长得和广告段一模一样，靠「不含特征词」这条兜住
        val raw = "快件已到达【上海嘉定曹安路网点】，正在分拣"
        assertEquals(raw, ExpressTraceText.clean(raw))
    }

    @Test
    fun `整条都是广告时清洗成空串`() {
        assertEquals("", ExpressTraceText.clean("（物流问题无需找商家，请联系956025）"))
    }

    @Test
    fun `括号不配对时整条原样保留`() {
        // 半个括号多半意味着模板和我们预期的不一样，此时不动比猜着删安全
        val raw = "快件已到达【上海嘉定曹安路网点"
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
            ExpressTracePoint("2026-09-26 13:19:31", "快件已到达【颍东集散点】"),
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
            "e2009accb92774855df71be085be21f8",
            MtopSign.tokenOf("e2009accb92774855df71be085be21f8_1790410278831"),
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
            "411223f93f6c5ce02e915792f1938989",
            MtopSign.wapSign(
                token = "e2009accb92774855df71be085be21f8",
                timestamp = "1790410278831",
                appKey = MtopSign.TAOBAO_H5_APP_KEY,
                data = """{"mailNo":"JT3178691239988"}""",
            ),
        )
    }

    // ------------------------------------------------------------ 响应解析

    @Test
    fun `字段路径照接口原样`() {
        val info = CainiaoTraceParser.parse(body(lastStatus = "待取件", address = STATION_ADDRESS))!!

        assertEquals("JT3178691239988", info.trackingNumber)
        assertEquals("极兔速递", info.courierName)
        assertEquals("HTKY", info.courierCode)
        assertEquals("952300", info.courierPhone)
        assertEquals("云南一级白糖砂糖", info.goodsName)
        assertEquals("https://img.alicdn.com/x.jpg", info.goodsImage)
        // 全轨迹两条都留下来了
        assertEquals(2, info.points.size)
        assertEquals("快件已揽收", info.points[0].text)
        // 解析出来的轨迹已经洗过：广告那段括号没了，驿站名和状态留着
        assertEquals("快件已存放至【颖滨花园菜鸟驿站】", info.points[1].text)
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
        val withAddress = record(address = "颖滨23号楼109")
        val another = record(address = "别的地方", image = "https://img.alicdn.com/y.jpg")

        val merged = withAddress.mergeEnrichment(another)
        assertEquals("颖滨23号楼109", merged.stationAddress)
        assertEquals("https://img.alicdn.com/y.jpg", merged.goodsImage)

        // 反向：本记录空着时才吸收
        assertEquals("别的地方", record().mergeEnrichment(another).stationAddress)
    }

    // ------------------------------------------------------------ 样本

    private fun record(
        trace: List<ExpressTracePoint> = emptyList(),
        address: String? = null,
        image: String? = null,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "您的包裹已到达",
        trackingNumber = "JT3178691239988",
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
          "mailNo":"JT3178691239988",
          "cp":{"tpName":"极兔速递","tpCode":"HTKY","tpContact":"952300"},
          "packageStatus":{"newStatusDesc":"待取件","status":"派送中"},
          "packageItems":[{"goodsName":"云南一级白糖砂糖","allPicUrl":"https://img.alicdn.com/x.jpg"}],
          "fullTraceDetail":[
            {"time":"2026-09-20 10:00:00","statusDesc":"快件已揽收","desc":"快件已揽收"},
            {"time":"2026-09-26 13:19:31","statusDesc":"$lastStatus",
             "desc":"快件已存放至【颖滨花园菜鸟驿站】（物流问题无需找商家，请联系956025）",
             "address":"$address"}
          ]
        }]}}
    """.trimIndent()

    private val STATION_ADDRESS = "颖滨23号楼109颖滨花园菜拼多多驿站"
}
