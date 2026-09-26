package io.github.YGHFv.ReaPressExtend.core

import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationSpot
import io.github.YGHFv.ReaPressExtend.notification.SpotPickReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 身份码 + 驿站扩展规则的单测。
 *
 * 全是纯函数（`Code128` / `GeoDistance` / `CainiaoIdentity` 的时效判定 / `ExpressStationRules` /
 * `ExpressHomeGrouper` 的取码取址），所以能在 JVM 单测里跑 —— 本工程没有 Robolectric，
 * `android.jar` 是桩，碰 `Context` 会抛 "not mocked"。
 *
 * 为什么这些值得钉：
 * - **条码**：编错了界面看着完全正常，只是用户在自助机上扫不出来 —— 而那是最糟的失败形态
 *   （人站在机器前，后面还排着队）。
 * - **身份码时效**：判错方向的两种后果都不小 —— 当作过期会永远退到取件码（功能白做），
 *   当作没过期会让用户拿着作废的码站在柜台前。
 * - **最近驿站**：算错方向或漏了弧度换算，表现是「选了个几百公里外的驿站」，看着像数据问题。
 * - **默认取件码 / 精确地址的链式查找**：用户是「合并了之后在其中一行上填的」，
 *   查不到链尾就会表现为「填了但卡片没变」。
 */
class ExpressIdentityTest {

    // ------------------------------------------------------------ Code 128

    @Test
    fun `Code128 编一个字符的已知向量`() {
        // "A" → Start B(104) · 'A'=33 · checksum=(104+33*1)%103=34 · Stop(106)
        // 四段的模块宽度串起来就是整根条码；25 个模块（6+6+6+7）。
        val widths = Code128.encodeB("A")
        assertNotNull(widths)
        assertEquals(
            listOf(
                2, 1, 1, 2, 1, 4, // Start B
                1, 1, 1, 3, 2, 3, // 'A'
                1, 3, 1, 1, 2, 3, // checksum 34
                2, 3, 3, 1, 1, 1, 2, // Stop
            ),
            widths,
        )
    }

    @Test
    fun `Code128 校验和按位置加权`() {
        // "AB"：Start(104) + 33*1 + 34*2 = 205 → 205 % 103 = 102。
        // 位置从 1 开始这件事很容易写成 0 —— 那样条码长得一样，只是扫不出来。
        val widths = Code128.encodeB("AB")!!
        // 整根条码 = Start · 'A' · 'B' · 校验符 · Stop，数据符一律 6 个模块（Stop 是 7 个）。
        // 校验符是**第 4 段**：前面站了 Start、'A'、'B' 三段，所以从下标 3*6 = 18 起。
        // 这里曾经写成 12 —— 那是在断言 'B' 的图案（131123），看着也是 6 个数字，很像个"校验符"。
        assertEquals(listOf(4, 1, 1, 1, 3, 1), widths.subList(18, 24))
        // 顺带把整根钉住：只钉一段的话，"谁站在第 4 段"这件事本身就没被验证。
        assertEquals(
            listOf(
                2, 1, 1, 2, 1, 4, // Start B(104)
                1, 1, 1, 3, 2, 3, // 'A' = 33
                1, 3, 1, 1, 2, 3, // 'B' = 34
                4, 1, 1, 1, 3, 1, // 校验符 102
                2, 3, 3, 1, 1, 1, 2, // Stop
            ),
            widths,
        )
    }

    @Test
    fun `Code128 整张表都是十一模块且两两不同`() {
        // 规格给的两个不变量，正好能当成整张表的回归网：
        //   ① 每个符号的模块宽度相加恒为 11（只有 Stop 是 13）；
        //   ② 107 项图案两两不同。
        // 抄错一位数字 → ① 会当场炸；抄重复了一项 → ② 会当场炸。
        // 这类错在界面上完全看不出来（条码该黑的黑、该白的白），只会在自助机前扫不出来。
        // B 组能编 ASCII 32..126，所以把 95 个字符连起来编一遍，0..94 这 95 项就都被过了一遍。
        val text = (32..126).map { it.toChar() }.joinToString("")
        val widths = Code128.encodeB(text)!!
        // Start(11) + 95 个数据符(11) + 校验符(11) + Stop(13)。
        assertEquals(11 + 95 * 11 + 11 + 13, widths.sum())

        // 每个符号占 6 个模块，第 0 段是 Start，所以值 v 的图案落在下标 6*(v+1) 起。
        val patterns = (0..94).map { v -> widths.subList(6 * (v + 1), 6 * (v + 2)) }
        assertTrue("图案重复说明表里抄重了一项", patterns.toSet().size == patterns.size)
        // 抽三个点核对（分别来自表头、表中、表尾）：' '=0、'@'=32、'~'=94。
        assertEquals(listOf(2, 1, 2, 2, 2, 2), patterns[0])
        assertEquals(listOf(2, 3, 2, 1, 2, 1), patterns[32])
        assertEquals(listOf(1, 3, 1, 1, 4, 1), patterns[94])
    }

    @Test
    fun `Code128 拒绝编不了的输入`() {
        assertNull("空串没有意义", Code128.encodeB(""))
        // 'é' 是 U+00E9，超出 B 组（32..126）。**返回 null 而不是硬编** ——
        // 编出来也是一根扫不出来的条码，界面宁可只显示数字。
        assertNull(Code128.encodeB("café"))
    }

    @Test
    fun `Code128 输出永远是条空交替`() {
        val widths = Code128.encodeB("1-5-8644")!!
        // 从「条」开始、条空交替：长度是奇数，且每一项都大于 0。
        assertEquals(1, widths.size % 2)
        assertTrue(widths.all { it > 0 })
    }

    // ------------------------------------------------------------ 距离

    @Test
    fun `GeoDistance 同一点距离为零`() {
        assertEquals(0.0, GeoDistance.meters(31.2, 121.4, 31.2, 121.4), 0.001)
    }

    @Test
    fun `GeoDistance 赤道上差一个经度约一百一十一公里`() {
        // 这条专门钉「有没有把度转成弧度」：漏了那一步会得到一个大几十倍的值，
        // 而它在真机上只表现为「最近驿站选错了」。
        val meters = GeoDistance.meters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, meters, 500.0)
    }

    @Test
    fun `nearest 挑最近的并跳过没有坐标的候选`() {
        val items = listOf("远" to (31.30 to 121.50), "近" to (31.201 to 121.401), "没坐标" to null)
        val nearest = GeoDistance.nearest(31.2, 121.4, items) { it.second }
        assertEquals("近", nearest?.first)
    }

    @Test
    fun `nearest 全都没坐标时返回 null 而不是瞎猜`() {
        val items = listOf("A" to null, "B" to null)
        assertNull(GeoDistance.nearest(31.2, 121.4, items) { it.second })
    }

    // ------------------------------------------------------------ 身份码结果

    // 身份码本身改由**菜鸟用自己的会话**取（模块侧的解析路径已经删掉了，见 CainiaoIdentity
    // 的类注释），所以这里钉的是「拿到一份码之后怎么判断它还能不能用」与「失败原因怎么归类」。

    @Test
    fun `拿不到有效期的码一律当作可用`() {
        // 宿主没给 expireTime 时，一个「有码」总比一个「显示了但不敢用」有用 ——
        // 而把它当成过期会让每一次都退到取件码，等于这条链路白做。
        val identity = CainiaoIdentity(code = "123456")
        assertTrue(identity.isValidAt(1_800_000_000_000L))
    }

    @Test
    fun `过了有效期的码不再当作可用`() {
        val identity = CainiaoIdentity(code = "123456", expireAt = 1_800_000_000_000L)
        assertFalse(identity.isValidAt(1_800_000_000_001L))
        // 边界取「到期那一刻算过期」：服务端给的是秒级时间戳，多留一秒就可能扫不出来。
        assertFalse(identity.isValidAt(1_800_000_000_000L))
        assertTrue(identity.isValidAt(1_799_999_999_999L))
    }

    @Test
    fun `风控要能被识别出来`() {
        // 「等一会儿」和「去打开一次菜鸟」是两个完全不同的动作，所以这条判断必须在数据层
        // 定死，而不是让界面各自 contains 一遍字符串（那样迟早会漏一处）。
        assertTrue(CainiaoIdentityResult.HostFailed("FAIL_SYS_USER_VALIDATE::哎哟喂,被挤爆啦").riskBlocked)
        assertTrue(CainiaoIdentityResult.HostFailed("RGV587_ERROR::SM").riskBlocked)
        assertFalse(CainiaoIdentityResult.HostFailed("菜鸟没能给出身份码").riskBlocked)
    }

    // ------------------------------------------------------------ 驿站四张表

    @Test
    fun `缺省码和精确地址跟着改名链找`() {
        // 用户是**在管理页那一行**上填的，而那一行可能对应链上的另一个键 ——
        // 只查一跳会漏掉一半件，表现为「设了默认码但卡片没变」。
        val rules = ExpressStationRules(
            renames = mapOf("阜阳颍滨花园店" to "颖滨23号楼109颖滨花园菜拼多多驿站"),
            pickupCodes = mapOf("颖滨23号楼109颖滨花园菜拼多多驿站" to "1-5-8644"),
            addresses = mapOf("颖滨23号楼109颖滨花园菜拼多多驿站" to "5号楼2单元"),
        )

        assertEquals("1-5-8644", rules.pickupCodeFor("阜阳颍滨花园店"))
        assertEquals("1-5-8644", rules.pickupCodeFor("颖滨23号楼109颖滨花园菜拼多多驿站"))
        assertEquals("5号楼2单元", rules.addressFor("阜阳颍滨花园店"))
        assertNull(rules.pickupCodeFor("别的驿站"))
    }

    @Test
    fun `own key 优先于链上的值`() {
        val rules = ExpressStationRules(
            renames = mapOf("A店" to "B店"),
            pickupCodes = mapOf("A店" to "自己", "B店" to "目标"),
        )
        assertEquals("自己", rules.pickupCodeFor("A店"))
        assertEquals("目标", rules.pickupCodeFor("B店"))
    }

    @Test
    fun `hasRule 四张表任一有值都算`() {
        assertTrue(ExpressStationRules(pickupCodes = mapOf("A店" to "1")).hasRule("A店"))
        assertTrue(ExpressStationRules(addresses = mapOf("A店" to "5号楼")).hasRule("A店"))
        assertTrue(ExpressStationRules(renames = mapOf("A店" to "家门口")).hasRule("A店"))
        // 只选了身份码来源、别的都没动，同样算「已自定义」—— 否则用户会以为自己的选择没保存。
        assertTrue(
            ExpressStationRules(identitySources = mapOf("A店" to IdentitySource.PDD)).hasRule("A店"),
        )
        assertFalse(ExpressStationRules.EMPTY.hasRule("A店"))
        assertTrue(ExpressStationRules.EMPTY.isEmpty)
        assertFalse(ExpressStationRules(pickupCodes = mapOf("A店" to "1")).isEmpty)
        assertFalse(
            ExpressStationRules(identitySources = mapOf("A店" to IdentitySource.PDD)).isEmpty,
        )
    }

    // ------------------------------------------------------------ 身份码来源

    @Test
    fun `身份码来源解析枚举名，不认识的一律当没设过`() {
        assertEquals(IdentitySource.CAINIAO, IdentitySource.parse("CAINIAO"))
        // 大小写不敏感：值将来可能由别的地方写进来，不该因为大小写丢规则。
        assertEquals(IdentitySource.PDD, IdentitySource.parse("pdd"))
        // ⚠️ 认不出来**必须**返回 null 而不是 CAINIAO：猜错平台会让用户拿着
        // 那家机器不认的码站在柜台前，而返回 null 只是回到默认行为。
        assertNull(IdentitySource.parse("MEITUAN"))
        assertNull(IdentitySource.parse(null))
        assertNull(IdentitySource.parse("   "))
    }

    @Test
    fun `身份码来源也走改名链`() {
        // 与默认码同一条规矩：用户是在**管理页那一行**上选的，而那一行可能挂在链上别的键。
        val rules = ExpressStationRules(
            renames = mapOf("阜阳颍滨花园店" to "颖滨23号楼109颖滨花园菜拼多多驿站"),
            identitySources = mapOf("颖滨23号楼109颖滨花园菜拼多多驿站" to IdentitySource.PDD),
        )
        assertEquals(IdentitySource.PDD, rules.identitySourceFor("阜阳颍滨花园店"))
        assertEquals(
            IdentitySource.PDD,
            rules.identitySourceFor("颖滨23号楼109颖滨花园菜拼多多驿站"),
        )
        assertNull(rules.identitySourceFor("别的驿站"))
    }

    @Test
    fun `目前只有菜鸟能真取到码`() {
        // 弹窗靠它决定「发请求」还是「如实说明暂未支持」。拼多多将来接上了这条会红 ——
        // 那时改它，也就顺便被迫确认一遍「拼多多的码到底从哪来」。
        assertTrue(IdentitySource.CAINIAO.supported)
        assertFalse(IdentitySource.PDD.supported)
    }

    // ------------------------------------------------------------ 界面取码取址

    private fun record(
        pickup: String? = null,
        station: String? = null,
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
        tracking: String? = null,
        at: Long = 0L,
        lat: Double? = null,
        lng: Double? = null,
        address: String? = null,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "raw",
        trackingNumber = tracking,
        pickupCode = pickup,
        station = station,
        status = status,
        timestamp = at,
        stationLat = lat,
        stationLng = lng,
        stationAddress = address,
    )

    @Test
    fun `记录自己的取件码优先于驿站默认码`() {
        val rules = ExpressStationRules(pickupCodes = mapOf("阳光花园店" to "默认码"))

        assertEquals("1-1-1111", ExpressHomeGrouper.pickupCodeOf(record(pickup = "1-1-1111", station = "阳光花园店"), rules))
        assertEquals("默认码", ExpressHomeGrouper.pickupCodeOf(record(station = "阳光花园店"), rules))
        // 认不出驿站时没有默认码可用 —— 不能退化成「随便给一个」。
        assertNull(ExpressHomeGrouper.pickupCodeOf(record(), rules))
    }

    @Test
    fun `宿主地址优先于用户填的精确地址`() {
        val rules = ExpressStationRules(addresses = mapOf("阳光花园店" to "5号楼2单元"))
        assertEquals(
            "宿主给的地址",
            ExpressHomeGrouper.stationAddressOf(
                record(station = "阳光花园店", address = "宿主给的地址"),
                rules,
            ),
        )
        assertEquals(
            "5号楼2单元",
            ExpressHomeGrouper.stationAddressOf(record(station = "阳光花园店"), rules),
        )
    }

    @Test
    fun `spots 聚合坐标与取件码并按最近来件排序`() {
        val records = listOf(
            record(pickup = "1-1", station = "A站", tracking = "SF1", at = 200L, lat = 31.2, lng = 121.4),
            // A 站的坐标只在下发过的那一条上给（宿主常常只给一部分行）。
            record(station = "A站", tracking = "SF2", at = 100L),
            record(pickup = "2-2", station = "B站", tracking = "SF3", at = 300L),
        )
        val spots = ExpressHomeGrouper.spots(records, ExpressStationRules.EMPTY)

        // 最近来件的是 B 站（300），排前面。
        assertEquals(listOf("B站", "A站"), spots.map { it.displayName })
        val a = spots.first { it.displayName == "A站" }
        assertEquals("1-1", a.pickupCode)
        // A 站两条记录都是待取件（工厂的默认状态），所以件数是 2 而不是 1。
        assertEquals(2, a.readyCount)
        // 坐标只有一部分行有 —— 整组里有一条给了就算这一站有位置。
        assertEquals(31.2, requireNotNull(a.position).lat, 0.0001)
        assertNull(spots.first { it.displayName == "B站" }.position)
    }

    @Test
    fun `spots 把宿主放大 1e5 的坐标归一化`() {
        // 真机原值（合肥南湖春城）：3177340 / 11726441。
        val records = listOf(
            record(station = "A站", tracking = "SF1", at = 100L, lat = 3177340.0, lng = 11726441.0),
        )
        val a = ExpressHomeGrouper.spots(records, ExpressStationRules.EMPTY).single()
        val point = requireNotNull(a.position)

        assertEquals(31.7734, point.lat, 0.000001)
        assertEquals(117.26441, point.lng, 0.000001)
    }

    @Test
    fun `spots 的取件码优先取待取件那一件`() {
        // 同一站里躺着一条已签收的历史件和一条待取件：念出来的必须是后者。
        val records = listOf(
            record(pickup = "旧码", station = "A站", tracking = "SF1", status = ExpressStatus.SIGNED, at = 300L),
            record(pickup = "新码", station = "A站", tracking = "SF2", at = 200L),
        )
        val a = ExpressHomeGrouper.spots(records, ExpressStationRules.EMPTY).single()

        assertEquals("新码", a.pickupCode)
        assertEquals(1, a.readyCount)
    }

    @Test
    fun `spots 的待取件件数不算已签收与已手动取件的`() {
        val records = listOf(
            record(station = "A站", tracking = "SF1", at = 100L),
            record(station = "A站", tracking = "SF2", status = ExpressStatus.SIGNED, at = 100L),
            record(station = "A站", tracking = "SF3", status = ExpressStatus.IN_TRANSIT, at = 100L),
        )
        val a = ExpressHomeGrouper.spots(records, ExpressStationRules.EMPTY).single()

        assertEquals(1, a.readyCount)
    }

    // ------------------------------------------------------------ 取件点挑选

    @Test
    fun `pickSpot 人在驿站附近时按距离`() {
        val near = spot("近站", code = "1-1", lat = 31.7800, lng = 117.2600, ready = 1)
        val far = spot("远站", code = "2-2", lat = 32.9400, lng = 115.8100, ready = 10)

        // 站在「近站」门口：哪怕远站件多得多，也该给近站的码。
        val picked = ExpressHomeGrouper.pickSpot(
            spots = listOf(far, near),
            position = GeoPoint(31.7790, 117.2610),
        )

        assertEquals("近站", picked?.spot?.displayName)
        assertEquals(SpotPickReason.NEARBY, picked?.reason)
    }

    @Test
    fun `pickSpot 够不着任何驿站时按待取件件数`() {
        val few = spot("少站", code = "1-1", lat = 31.7800, lng = 117.2600, ready = 1)
        val many = spot("多站", code = "2-2", lat = 32.9400, lng = 115.8100, ready = 10)

        // 离家 200 公里：两个站都不算「附近」，这时距离只是「谁更远」，
        // 真正相关的是「哪站有我的件要取」。
        val picked = ExpressHomeGrouper.pickSpot(
            spots = listOf(few, many),
            position = GeoPoint(39.9000, 116.4000),
        )

        assertEquals("多站", picked?.spot?.displayName)
        assertEquals(SpotPickReason.READY_COUNT, picked?.reason)
    }

    @Test
    fun `pickSpot 没有定位时按待取件件数而不是靠有坐标的那个`() {
        // 这是真机实测踩到的形状：手里 10 件待取的站没坐标，只有 1 件且 12 天前到站的站有坐标。
        // 老实现把「没坐标」排除掉，于是每次都给那个只有 1 件的站。
        val noGeoCrowded = spot("有件的站", code = "1-1", lat = null, lng = null, ready = 10)
        val geoTiny = spot("有坐标的站", code = "2-2", lat = 31.7734, lng = 117.2644, ready = 1)

        val picked = ExpressHomeGrouper.pickSpot(
            spots = listOf(geoTiny, noGeoCrowded),
            position = null,
        )

        assertEquals("有件的站", picked?.spot?.displayName)
        assertEquals(SpotPickReason.READY_COUNT, picked?.reason)
    }

    @Test
    fun `pickSpot 优先挑得出一串码的站`() {
        val codeless = spot("没码的站", code = null, lat = 31.7700, lng = 117.2600, ready = 3)
        val withCode = spot("有码的站", code = "1-1", lat = 32.9400, lng = 115.8100, ready = 1)

        // 没码的站就算更近也不用：这一屏要回答的是「念什么」。
        val picked = ExpressHomeGrouper.pickSpot(
            spots = listOf(codeless, withCode),
            position = GeoPoint(31.7700, 117.2601),
        )

        assertEquals("有码的站", picked?.spot?.displayName)
    }

    @Test
    fun `pickSpot 空列表与单点`() {
        assertNull(ExpressHomeGrouper.pickSpot(emptyList(), GeoPoint(31.0, 121.0)))
        val only = spot("唯一的站", code = "1-1", lat = null, lng = null, ready = 0)
        assertEquals("唯一的站", ExpressHomeGrouper.pickSpot(listOf(only), null)?.spot?.displayName)
    }

    /** 造一个取件点。坐标传 null 表示宿主没给这一站发坐标。 */
    private fun spot(
        name: String,
        code: String?,
        lat: Double?,
        lng: Double?,
        ready: Int,
    ) = ExpressStationSpot(
        displayName = name,
        position = if (lat != null && lng != null) GeoPoint(lat, lng) else null,
        pickupCode = code,
        readyCount = ready,
    )

    @Test
    fun `spots 带上该站选的身份码来源`() {
        val rules = ExpressStationRules(identitySources = mapOf("B站" to IdentitySource.PDD))
        val records = listOf(
            record(station = "A站", tracking = "SF1", at = 100L),
            record(station = "B站", tracking = "SF2", at = 200L),
        )
        val spots = ExpressHomeGrouper.spots(records, rules)

        assertEquals(IdentitySource.PDD, spots.first { it.displayName == "B站" }.identitySource)
        // 没选过就是 null，**不是**写死成菜鸟 —— 默认由调用方决定。存进点位的话，
        // 将来「默认改成别的平台」就改不动已经算出来的这些了。
        assertNull(spots.first { it.displayName == "A站" }.identitySource)
    }

    @Test
    fun `spots 不收没有地名的记录`() {
        // 弹窗要回答「去哪取」，一个说不出名字的点位帮不上忙。
        val records = listOf(record(pickup = "1-1", tracking = "SF1", at = 100L))
        assertTrue(ExpressHomeGrouper.spots(records, ExpressStationRules.EMPTY).isEmpty())
    }
}
