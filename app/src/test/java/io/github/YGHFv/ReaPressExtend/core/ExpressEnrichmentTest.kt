package io.github.YGHFv.ReaPressExtend.core

import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主富化链路的纯逻辑测试。
 *
 * 覆盖三层：快递公司名识别（[Courier.fromCompanyName]）、定位到哪条记录
 * （[ExpressEnrichmentMatcher]）、以及合进记录时「什么能覆盖什么不能」
 * （[ExpressRecord.mergeEnrichment]）。这三处都是纯函数，不依赖 Android 运行时。
 */
class CourierCompanyNameTest {

    @Test
    fun `认得出菜鸟返回的带后缀公司名`() {
        assertEquals(Courier.ZHONGTONG, Courier.fromCompanyName("中通快递"))
        assertEquals(Courier.YUANTONG, Courier.fromCompanyName("圆通速递"))
        assertEquals(Courier.SHUNFENG, Courier.fromCompanyName("顺丰速运"))
        assertEquals(Courier.YUNDA, Courier.fromCompanyName("韵达速递"))
        assertEquals(Courier.JITU, Courier.fromCompanyName("极兔速递"))
    }

    @Test
    fun `邮政的各种写法都落到 EMS`() {
        // 菜鸟的 tpName 对同一家公司有多个写法，只认一个会漏掉一批包裹
        assertEquals(Courier.EMS, Courier.fromCompanyName("邮政快递包裹"))
        assertEquals(Courier.EMS, Courier.fromCompanyName("中国邮政"))
        assertEquals(Courier.EMS, Courier.fromCompanyName("EMS"))
    }

    @Test
    fun `认不出时返回 UNKNOWN 而不是猜`() {
        // 「菜鸟速递」的品牌词不含任何一家快递公司的名字，猜成分拣中心或某家快递都是错的
        assertEquals(Courier.UNKNOWN, Courier.fromCompanyName("菜鸟速递"))
        assertEquals(Courier.UNKNOWN, Courier.fromCompanyName("某某物流"))
        assertEquals(Courier.UNKNOWN, Courier.fromCompanyName(null))
        assertEquals(Courier.UNKNOWN, Courier.fromCompanyName("  "))
    }

    @Test
    fun `界面简称不能用前两个字截出来`() {
        // EMS 的 displayName 是「中国邮政」，`take(2)` 会得到「中国」—— 那不是快递公司名。
        // 简称是逐条写死的，这条用例就是把「不许截字符串」钉住。
        assertEquals("邮政", Courier.EMS.shortName)
        assertEquals("中通", Courier.ZHONGTONG.shortName)
        assertEquals("顺丰", Courier.SHUNFENG.shortName)
        assertEquals("极兔", Courier.JITU.shortName)
        // UNKNOWN 的简称就是标题里那个「快递」，调用方不用另判
        assertEquals("快递", Courier.UNKNOWN.shortName)
    }

    @Test
    fun `简称表同时就是识别关键字表`() {
        // byCompanyKeyword 是从 shortName 派生的，所以「显示成邮政」和「认得出邮政」
        // 必须永远同时成立。这条用例防的是「哪天有人只改了一边」。
        for (courier in Courier.entries) {
            if (courier == Courier.UNKNOWN) continue
            assertEquals(courier, Courier.fromCompanyName(courier.shortName))
        }
    }
}

class ExpressEnrichmentMatcherTest {

    private fun notification(
        tracking: String? = null,
        pickup: String? = null,
        station: String? = "菜鸟驿站(杭州文一西路店)",
        rawText: String = "您的包裹已到站",
        at: Long = 1000L,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = rawText,
        trackingNumber = tracking,
        pickupCode = pickup,
        station = station,
        status = ExpressStatus.READY_FOR_PICKUP,
        timestamp = at,
    )

    private fun enrichment(
        tracking: String? = "SF1234567890123",
        station: String? = "杭州文一西路店",
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "菜鸟富化",
        trackingNumber = tracking,
        station = station,
        origin = ExpressOrigin.ENRICHMENT,
    )

    @Test
    fun `通知里的截断运单号能被全号认出来`() {
        // 通知写的是尾号，宿主给的是全号 —— 这是最主要的一种情形
        val records = listOf(notification(tracking = "4567890123"))
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, enrichment()))
    }

    @Test
    fun `运单号完全一致直接命中`() {
        val records = listOf(notification(tracking = "SF1234567890123"))
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, enrichment()))
    }

    @Test
    fun `原文提到运单号尾号即算证据`() {
        val records = listOf(
            notification(rawText = "您的包裹已到菜鸟驿站，运单号尾号0123，请及时取件"),
        )
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, enrichment()))
    }

    @Test
    fun `没有尾号字样时长尾号也算证据`() {
        // 菜鸟有些模板直接把尾号贴在单号后面，不带「尾号」二字；
        // 六位相同数字属于巧合的概率可以忽略，够格当证据
        val records = listOf(notification(rawText = "包裹 890123 已到站"))
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, enrichment()))
    }

    @Test
    fun `只有驿站名相同不足以确认`() {
        // 同一驿站常常同时有好几个包裹。按驿站名认包裹 = 有一半概率把运单号写到别人身上，
        // 用户照着去驿站报号会取错件 —— 所以这一条必须不命中
        val records = listOf(notification(rawText = "您的包裹已到站"))
        assertEquals(-1, ExpressEnrichmentMatcher.indexOfTarget(records, enrichment()))
    }

    @Test
    fun `驿站名相同再叠一个尾号证据才够`() {
        val records = listOf(
            notification(
                rawText = "您的包裹已到菜鸟驿站，运单号尾号0123",
                station = "菜鸟驿站(杭州文一西路店)",
            ),
        )
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, enrichment()))
    }

    @Test
    fun `取件码一致算强证据`() {
        // 富化侧目前读不出取件码，但接口一旦能给，这条规则就该立刻生效
        val records = listOf(notification(pickup = "8-2-3021"))
        val fromHost = enrichment(tracking = null).copy(pickupCode = "8-2-3021")
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, fromHost))
    }

    @Test
    fun `没有候选时返回 -1`() {
        assertEquals(-1, ExpressEnrichmentMatcher.indexOfTarget(emptyList(), enrichment()))
    }

    @Test
    fun `同分时取最新的那条`() {
        // 记录按时间倒序传入（load 的顺序），取件码相同说明是同一格，
        // 但包裹身份仍以最新那条为准 —— 用户在看的就是它
        val records = listOf(
            notification(pickup = "8-2-3021", at = 2000L),
            notification(pickup = "8-2-3021", at = 1000L),
        )
        val fromHost = enrichment(tracking = null).copy(pickupCode = "8-2-3021")
        assertEquals(0, ExpressEnrichmentMatcher.indexOfTarget(records, fromHost))
    }

    @Test
    fun `驿站名比较忽略品牌前缀括号与空白`() {
        assertEquals(
            ExpressEnrichmentMatcher.stationCore("杭州文一西路店"),
            ExpressEnrichmentMatcher.stationCore("菜鸟驿站(杭州文一西路店)"),
        )
        assertEquals(
            ExpressEnrichmentMatcher.stationCore("杭州文一西路店"),
            ExpressEnrichmentMatcher.stationCore("菜鸟驿站杭州文一西路店"),
        )
        assertNull(ExpressEnrichmentMatcher.stationCore("   "))
        assertNull(ExpressEnrichmentMatcher.stationCore(null))
    }
}

class ExpressRecordMergeEnrichmentTest {

    private fun notification(
        tracking: String? = null,
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
        station: String? = null,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "您的包裹已到站",
        trackingNumber = tracking,
        station = station,
        status = status,
        timestamp = 1000L,
    )

    private fun enrichment(
        tracking: String? = "SF1234567890123",
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
        station: String? = "杭州文一西路店",
        pickupCode: String? = "8-2-3021",
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "菜鸟富化",
        trackingNumber = tracking,
        courier = Courier.SHUNFENG,
        station = station,
        pickupCode = pickupCode,
        status = status,
        origin = ExpressOrigin.ENRICHMENT,
    )

    @Test
    fun `补上空字段`() {
        val merged = notification().mergeEnrichment(enrichment())

        assertEquals("SF1234567890123", merged.trackingNumber)
        assertEquals(Courier.SHUNFENG, merged.courier)
        assertEquals("8-2-3021", merged.pickupCode)
        assertEquals("杭州文一西路店", merged.station)
    }

    @Test
    fun `已有字段不被覆盖`() {
        val original = notification(station = "菜鸟驿站(用户看到的写法)").copy(
            courier = Courier.ZHONGTONG,
            pickupCode = "9-9-9999",
        )
        val merged = original.mergeEnrichment(enrichment())

        // 用户是照着通知去找件的，通知上写的东西不能被另一个来源改写
        assertEquals("菜鸟驿站(用户看到的写法)", merged.station)
        assertEquals(Courier.ZHONGTONG, merged.courier)
        assertEquals("9-9-9999", merged.pickupCode)
    }

    @Test
    fun `截断的运单号升级成全号`() {
        val merged = notification(tracking = "4567890123").mergeEnrichment(enrichment())
        assertEquals("SF1234567890123", merged.trackingNumber)
    }

    @Test
    fun `运单号互不包含时保留原值`() {
        // 两条来源指向不同单号时宁可不改：写错运单号会让用户取错件，
        // 而留着通知里那串至少是用户核对过的
        val merged = notification(tracking = "YT9999999999999").mergeEnrichment(enrichment())
        assertEquals("YT9999999999999", merged.trackingNumber)
    }

    @Test
    fun `状态只前进不后退`() {
        val ready = notification(status = ExpressStatus.READY_FOR_PICKUP)
        val stale = ready.mergeEnrichment(enrichment(status = ExpressStatus.IN_TRANSIT))
        assertEquals(ExpressStatus.READY_FOR_PICKUP, stale.status)

        val inTransit = notification(status = ExpressStatus.IN_TRANSIT)
        val advanced = inTransit.mergeEnrichment(enrichment(status = ExpressStatus.READY_FOR_PICKUP))
        assertEquals(ExpressStatus.READY_FOR_PICKUP, advanced.status)
    }

    @Test
    fun `没有任何新信息时返回原对象本身`() {
        // 详情页会被反复打开，无变化时调用方靠引用相等跳过落盘与日志
        val original = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "您的包裹已到站",
            trackingNumber = "SF1234567890123",
            courier = Courier.SHUNFENG,
            station = "杭州文一西路店",
            pickupCode = "8-2-3021",
            status = ExpressStatus.READY_FOR_PICKUP,
            timestamp = 1000L,
        )
        assertSame(original, original.mergeEnrichment(enrichment()))
    }
}

class ExpressRecordStoreEnrichTest {

    private fun notification(
        tracking: String? = null,
        pickup: String? = null,
        station: String? = "菜鸟驿站(杭州文一西路店)",
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
        rawText: String = "您的包裹已到站",
        at: Long = 1000L,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = rawText,
        trackingNumber = tracking,
        pickupCode = pickup,
        station = station,
        status = status,
        timestamp = at,
    )

    private fun enrichment(
        tracking: String? = "SF1234567890123",
        station: String? = "杭州文一西路店",
        pickupCode: String? = null,
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
        at: Long = 2000L,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "菜鸟富化",
        trackingNumber = tracking,
        station = station,
        pickupCode = pickupCode,
        status = status,
        origin = ExpressOrigin.ENRICHMENT,
        timestamp = at,
    )

    @Test
    fun `配不上已有记录时新建一条`() {
        // 通知被用户划掉了、权限没给、或者拦的那一刻模块没跑 —— 这些情况下宿主里明明躺着
        // 这件包裹，模块不该一无所知。这是本次放开的那个口子。
        val current = listOf(notification(tracking = "YT9999999999999"))
        val result = ExpressRecordStore.applyEnrichment(current, enrichment())!!

        assertEquals(2, result.records.size)
        assertEquals(ExpressRecordStore.NO_MATCH, result.matchedIndex)
        assertEquals(ExpressOrigin.ENRICHMENT, result.records.last().origin)
    }

    @Test
    fun `一条记录都没有时同样新建`() {
        // 模块刚装上、历史是空的，但用户已经打开过菜鸟详情页
        val result = ExpressRecordStore.applyEnrichment(emptyList(), enrichment())!!

        assertEquals(1, result.records.size)
        assertEquals(ExpressRecordStore.NO_MATCH, result.matchedIndex)
    }

    @Test
    fun `配得上时合并而不是新建`() {
        val current = listOf(notification(tracking = "567890123", pickup = "8-2-3021"))
        val result = ExpressRecordStore.applyEnrichment(current, enrichment())!!

        assertEquals(1, result.records.size)
        assertEquals(0, result.matchedIndex)
        val merged = result.records.single()
        assertEquals("SF1234567890123", merged.trackingNumber) // 尾号升级成全号
        assertEquals("8-2-3021", merged.pickupCode) // 通知独有，必须留住
        assertEquals("菜鸟驿站(杭州文一西路店)", merged.station) // 通知里的写法优先，不被改写
    }

    @Test
    fun `配上但没有新信息时不改动存储`() {
        // 列表会被反复刷新，这条路径必须静默 —— 否则日志和落盘都被无意义的重复淹掉
        val already = notification(tracking = "SF1234567890123").copy(
            station = "杭州文一西路店",
        )
        assertNull(ExpressRecordStore.applyEnrichment(listOf(already), enrichment()))
    }

    @Test
    fun `没有强标识的记录没有身份`() {
        // 判据在 ExpressRecord.hasIdentity，存储层（upsert / enrich / load）拿它当准入条件：
        // 连运单号和取件码都没有的推送（真机 2026-09-26 的「📦 揽件通知」）记进来只会变成
        // 首页上一块「快递包裹 / 未知」的空壳卡片 —— 它去重靠原文、富化永远配不上。
        assertFalse(notification(tracking = null, pickup = null).hasIdentity)
        assertTrue(notification(tracking = null, pickup = "8-2-3021").hasIdentity)
        assertTrue(notification(tracking = "SF1234567890123", pickup = null).hasIdentity)
        // 富化侧同一条规矩：宿主行只要了站点、没带单号和取件码的，同样进不了存储
        assertFalse(enrichment(tracking = null, pickupCode = null).hasIdentity)
    }

    @Test
    fun `富化反复到达不会重复建`() {
        // 第一次没有候选，新建；第二次应当认出刚建的那条并判定无变化
        val first = ExpressRecordStore.applyEnrichment(emptyList(), enrichment())!!
        assertNull(ExpressRecordStore.applyEnrichment(first.records, enrichment()))
    }

    @Test
    fun `通知尾号与富化全号在 upsert 时算同一件`() {
        // 富化先落库（全号），通知后到（只有尾号）。放宽判定要认出它们是同一件，
        // 否则首页上同一件包裹会变成两张卡片
        val late = notification(tracking = "7890123", pickup = "8-2-3021", at = 3000L)
        val result = ExpressRecordStore.applyUpsert(listOf(enrichment()), late)!!

        assertEquals(1, result.size)
        val merged = result.single()
        assertEquals("SF1234567890123", merged.trackingNumber) // 合并后留全号，不是刚来的尾号
        assertEquals("8-2-3021", merged.pickupCode)
        assertEquals("您的包裹已到站", merged.rawText) // 通知原文比合成文案更贴近用户看到的
    }

    @Test
    fun `只有四位尾号不算同一件`() {
        // 四位数字在任意两个运单号之间都可能撞上，不能当身份证据 —— 否则会把不相关的
        // 两个包裹合并成一条，用户念着 A 的取件码去找 B 的单号
        val result = ExpressRecordStore.applyUpsert(
            listOf(enrichment()),
            notification(tracking = "0123"),
        )!!
        assertEquals(2, result.size)
    }

    @Test
    fun `单号互不相关的两条不会合并`() {
        val current = listOf(notification(tracking = "SF1234567890123"))
        val result = ExpressRecordStore.applyUpsert(
            current,
            notification(tracking = "YT9999999999999"),
        )!!
        assertEquals(2, result.size)
    }

    @Test
    fun `状态倒退时不改动存储`() {
        // 乱序推送（先收到「待取件」再收到「运输中」）不该把状态退回去
        val current = listOf(
            notification(tracking = "SF1234567890123", status = ExpressStatus.READY_FOR_PICKUP),
        )
        assertNull(
            ExpressRecordStore.applyUpsert(
                current,
                notification(
                    tracking = "SF1234567890123",
                    status = ExpressStatus.IN_TRANSIT,
                    at = 3000L,
                ),
            ),
        )
    }
}

/**
 * 「同一个包裹被拆成两条」的回归测试。
 *
 * 现场是 2026-09-26 用户报的：首页「代收点 5 件」和「颍滨花园驿站 6 件」加起来 11 件，
 * 而实际只有 6 件包裹 —— 每条通知记录都多了一份。
 *
 * 根因不是「取件码比不出来」，而是**两边各缺一个强标识、且缺的正好是对方有的那个**：
 * 富化第一次到达时宿主没下发 `authCode`（真机 04:27 那批 pickup/station 全是 null），
 * 库里那条只有运单号；通知 13:20 到达，只有取件码 —— 没有任何可比的东西，只能各自成条。
 * 13:21 富化补齐取件码时，匹配到的是**自己那条**（运单号精确 100 分 > 取件码 50 分），
 * 通知那条就成了孤儿。
 *
 * 修法见 [ExpressRecordStore.applyEnrichment]：合并让代表记录把两边的强标识都拿到之后，
 * 再回头收编那些原本够不着的记录。
 */
class ExpressRecordStoreAbsorbTest {

    private fun notification(
        tracking: String? = null,
        pickup: String? = null,
        station: String? = null,
        rawText: String = "您的包裹已到站，取件码 $pickup",
        at: Long = 3000L,
    ) = ExpressRecord(
        sourcePackage = "com.taobao.taobao",
        rawText = rawText,
        trackingNumber = tracking,
        pickupCode = pickup,
        station = station,
        status = ExpressStatus.READY_FOR_PICKUP,
        timestamp = at,
    )

    private fun fromHost(
        tracking: String? = "JT3178691239988",
        pickup: String? = null,
        station: String? = "颍滨花园驿站",
        at: Long = 2000L,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "运单号 $tracking",
        trackingNumber = tracking,
        courier = Courier.JITU,
        pickupCode = pickup,
        station = station,
        status = ExpressStatus.READY_FOR_PICKUP,
        origin = ExpressOrigin.ENRICHMENT,
        timestamp = at,
    )

    @Test
    fun `真机现场：富化补齐取件码后把孤儿通知记录收编回来`() {
        // ① 富化先到，宿主当时没给取件码 → 只有运单号
        // ② 通知后到，只有取件码 → 两边没有共同强标识，只能新建（这一步的行为不变）
        val late = notification(pickup = "1-1-6004", station = "代收点")
        val seeded = ExpressRecordStore.applyUpsert(listOf(fromHost()), late)!!
        assertEquals(2, seeded.size)

        // ③ 富化补齐取件码 → 收编，首页从两张卡变回一张
        val result = ExpressRecordStore.applyEnrichment(
            seeded,
            fromHost(pickup = "1-1-6004"),
        )!!

        assertEquals(1, result.records.size)
        assertEquals(1, result.absorbedCount)
        val merged = result.records.single()
        assertEquals("JT3178691239988", merged.trackingNumber)
        assertEquals("1-1-6004", merged.pickupCode) // 通知独有的字段留住了
        // 通知里的「代收点」是个占位词，没有地点信息 —— 必须让位给宿主给的真名
        assertEquals("颍滨花园驿站", merged.station)
    }

    @Test
    fun `富化内容没变也照样收编已存在的重复`() {
        // 用户设备上先于本修复产生的重复，不该要求「再来一条新数据」才合 ——
        // 富化反复送达时内容完全一样，但收编判定必须照跑，否则两张卡片永远消不掉。
        // 这也是升级后不用清数据、打开一次菜鸟首页就自愈的原因。
        val host = fromHost(pickup = "1-1-6004")
        val current = listOf(notification(pickup = "1-1-6004", station = "代收点"), host)

        val result = ExpressRecordStore.applyEnrichment(current, host)!!
        assertEquals(1, result.records.size)
        assertEquals(1, result.absorbedCount)
    }

    @Test
    fun `收编不会把运单号冲突的记录并进来`() {
        // 同一货架格先后放过两件是真实存在的：取件码相同，但运单号不同就是两件。
        // 收编用的是 isSamePackageAs 的严格语义，运单号冲突一律不合 —— 合错了用户会取错件。
        val host = fromHost(pickup = "1-1-6004")
        val other = notification(tracking = "YT9999999999999", pickup = "1-1-6004")

        // 富化这条带一点新信息，否则「无变化」会走静默返回，测不到收编那一支
        val result = ExpressRecordStore.applyEnrichment(
            listOf(other, host),
            host.copy(goodsName = "海天上等蚝油"),
        )!!
        assertEquals(2, result.records.size)
        assertEquals(0, result.absorbedCount)
    }

    @Test
    fun `收编后仍能正常合并后续的富化`() {
        // 收编会重建列表（代表记录换到新的下标），后续富化必须还能找到它，
        // 否则一次收编之后这段数据就成了没人认领的孤儿
        val late = notification(pickup = "1-1-6004", station = "代收点")
        val seeded = ExpressRecordStore.applyUpsert(listOf(fromHost()), late)!!
        val absorbed = ExpressRecordStore.applyEnrichment(seeded, fromHost(pickup = "1-1-6004"))!!

        val next = ExpressRecordStore.applyEnrichment(
            absorbed.records,
            fromHost(pickup = "1-1-6004").copy(goodsName = "海天上等蚝油"),
        )!!
        assertEquals(1, next.records.size)
        assertEquals("海天上等蚝油", next.records.single().goodsName)
    }
}

class ExpressRecordEnrichmentSerializationTest {

    @Test
    fun `富化字段能往返`() {
        val original = ExpressRecord(
            sourcePackage = "com.cainiao.wireless",
            rawText = "菜鸟富化",
            trackingNumber = "SF1234567890123",
            courier = Courier.SHUNFENG,
            station = "杭州文一西路店",
            pickupCode = "8-2-3021",
            title = "菜鸟",
            origin = ExpressOrigin.ENRICHMENT,
            timestamp = 2000L,
        )
        val restored = ExpressRecordStore.parse(ExpressRecordStore.serialize(listOf(original))).single()

        assertEquals("8-2-3021", restored.pickupCode)
        assertEquals(ExpressOrigin.ENRICHMENT, restored.origin)
    }

    @Test
    fun `旧版本记录读出来是通知来源`() {
        // 升级前存下来的 JSON 里没有 origin 这个键（更早的版本还存过 man / manPhone，
        // 那两个键现在直接忽略）。读旧数据不能让整个列表挂掉，也不能凭空造出富化字段。
        val legacy = """[{"pkg":"com.cainiao.wireless","raw":"旧记录","tn":"SF1234567890123",
            "courier":"SHUNFENG","pickup":"8-2-3021","station":"杭州文一西路店",
            "status":"READY_FOR_PICKUP","title":"菜鸟","kw":[],"conf":80,"at":1000}]"""
            .replace("\n", "")

        val restored = ExpressRecordStore.parse(legacy).single()
        assertEquals(ExpressOrigin.NOTIFICATION, restored.origin)
        assertEquals("SF1234567890123", restored.trackingNumber)
        assertEquals("8-2-3021", restored.pickupCode)
        assertTrue(restored.matchedKeywords.isEmpty())
    }
}

/**
 * 宿主富化那批「通知文案里没有」的字段：电商平台、商品名、到站时间、运单动态、驿站营业时间。
 *
 * 字段名与取值都来自真机 dump（`log/run5` 的 `package_list_v4_package_info`）：
 * `pkgSourceDesc` / `packageItem[0].itemTitle` / `logisticsGmtModified` /
 * `lastLogisticDetail` / `packageStation.officeTime`。
 */
class ExpressHostFieldsTest {

    private val day = 24L * 60 * 60 * 1000

    /**
     * 时区显式钉死。
     *
     * `inStationLabel` 按自然日差算，所以结果依赖时区 —— 拿 `systemDefault()` 测，
     * CI 机器和本机时区不同就会红。用东八区是因为真机样本都是在国内采的。
     */
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun host(
        platform: String? = null,
        goodsName: String? = null,
        arrivalAt: Long? = null,
        logisticsDetail: String? = null,
        stationHours: String? = null,
        status: ExpressStatus = ExpressStatus.READY_FOR_PICKUP,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "菜鸟富化",
        trackingNumber = "79030000000009",
        pickupCode = "1-6-4011",
        station = "阳光花园菜拼多多驿站",
        status = status,
        platform = platform,
        goodsName = goodsName,
        arrivalAt = arrivalAt,
        logisticsDetail = logisticsDetail,
        stationHours = stationHours,
        origin = ExpressOrigin.ENRICHMENT,
        timestamp = 2000L,
    )

    @Test
    fun `宿主字段能往返序列化`() {
        val original = host(
            platform = "淘宝",
            goodsName = "云南一级白糖砂糖食用纯甘蔗糖",
            arrivalAt = 1_790_000_000_000L,
        )
        val restored = ExpressRecordStore.parse(ExpressRecordStore.serialize(listOf(original))).single()

        assertEquals("淘宝", restored.platform)
        assertEquals("云南一级白糖砂糖食用纯甘蔗糖", restored.goodsName)
        assertEquals(1_790_000_000_000L, restored.arrivalAt)
    }

    @Test
    fun `运单动态与营业时间能往返序列化`() {
        val original = host(
            logisticsDetail = "已发往【上海转运中心】",
            stationHours = "08:00-21:00",
        )
        val restored = ExpressRecordStore.parse(ExpressRecordStore.serialize(listOf(original))).single()

        assertEquals("已发往【上海转运中心】", restored.logisticsDetail)
        assertEquals("08:00-21:00", restored.stationHours)
    }

    @Test
    fun `旧 JSON 里没有 ptail dyn hours 三个键`() {
        // 这三个键是后加的。旧数据读出来必须是 null 而不是空串或 "null" ——
        // 「没有营业时间」和「营业时间是空串」在界面上都要整段省略，但模型里得干净。
        val legacy = """[{"pkg":"com.cainiao.wireless","raw":"旧记录","tn":"79030000000009",
            "courier":"ZHONGTONG","pickup":"1-6-4011","station":"阳光花园菜拼多多驿站",
            "status":"READY_FOR_PICKUP","title":"菜鸟","origin":"ENRICHMENT","kw":[],
            "conf":100,"at":1000,"platform":"淘宝","goods":"云南白糖","arrival":1790000000000}]"""
            .replace("\n", "")

        val restored = ExpressRecordStore.parse(legacy).single()
        assertNull(restored.phoneTail)
        assertNull(restored.logisticsDetail)
        assertNull(restored.stationHours)
        // 同一批老键照旧读得出来 —— 兼容性出问题不能只影响新字段
        assertEquals("淘宝", restored.platform)
        assertEquals(1_790_000_000_000L, restored.arrivalAt)
    }

    @Test
    fun `旧版本 JSON 里没有这三个键`() {
        // 升级前存下来的 JSON 没有 platform / goods / arrival。读旧数据不能让整个列表挂掉，
        // 也不能凭空造出富化字段 —— 少三个字段是正常的，多出假数据才是事故。
        val legacy = """[{"pkg":"com.cainiao.wireless","raw":"旧记录","tn":"79030000000009",
            "courier":"ZHONGTONG","pickup":"1-6-4011","station":"阳光花园菜拼多多驿站",
            "status":"READY_FOR_PICKUP","title":"菜鸟","origin":"ENRICHMENT","kw":[],
            "conf":100,"at":1000}]"""
            .replace("\n", "")

        val restored = ExpressRecordStore.parse(legacy).single()
        assertNull(restored.platform)
        assertNull(restored.goodsName)
        assertNull(restored.arrivalAt)
        // 旧字段照旧读得出来 —— 兼容性出问题不能只影响新字段
        assertEquals("1-6-4011", restored.pickupCode)
        assertEquals("阳光花园菜拼多多驿站", restored.station)
    }

    @Test
    fun `宿主字段同样只填空不覆盖`() {
        val fromNotification = host(
            platform = "淘宝",
            goodsName = "通知里的写法",
            logisticsDetail = "通知里的动态",
            stationHours = "09:00-20:00",
        )
        val merged = fromNotification.mergeEnrichment(
            host(
                platform = "天猫",
                goodsName = "宿主里的写法",
                arrivalAt = 123L,
                logisticsDetail = "宿主里的动态",
                stationHours = "08:00-21:00",
            ),
        )

        // 已有值不动，缺的那个才吸收 —— 和 station / pickupCode 同一套规则。
        // logisticsDetail 是例外：它是时序字段，跟随较新的一方（见下一条测试），
        // 两边 timestamp 相同时取 other，所以这里是「宿主里的动态」。
        assertEquals("淘宝", merged.platform)
        assertEquals("通知里的写法", merged.goodsName)
        assertEquals(123L, merged.arrivalAt)
        assertEquals("宿主里的动态", merged.logisticsDetail)
        assertEquals("09:00-20:00", merged.stationHours)
    }

    @Test
    fun `物流动态是时序字段_跟随较新的一方`() {
        // 2026-09-26 真机实证：状态都「派送中」了，正文还停在第一次落下的转运中心文案。
        // 旧的「只填空」规则会让过时的动态永久盖住宿主刷新出的新值。
        val stale = host(logisticsDetail = "快件离开【南宁转运中心】")
        val fresh = host(logisticsDetail = "快件已到达【蚌埠转运中心】").copy(timestamp = 9000L)
        assertEquals("快件已到达【蚌埠转运中心】", stale.mergeEnrichment(fresh).logisticsDetail)
        // 反方向（self 更新）不回退
        assertEquals("快件已到达【蚌埠转运中心】", fresh.mergeEnrichment(stale).logisticsDetail)
        // 较新的一方没给 detail 就保留旧值
        val bare = host().copy(timestamp = 9000L)
        assertEquals("快件离开【南宁转运中心】", stale.mergeEnrichment(bare).logisticsDetail)
    }

    @Test
    fun `本条没有动态时无条件收下_不看时间先后`() {
        // 2026-09-26 真机：13:54 已派送的件，卡片状态下面永远是空白 —— 就是这条路丢的。
        // 通知记录天生没有 logisticsDetail（通知文案里没这句）；宿主 gmt_modified（富化
        // timestamp）常常比通知到达时刻旧（宿主自己几小时不刷新）。时序规则若在这里也生效，
        // 富化带来的动态会被整条丢掉 —— 「时序取舍」的前提是两边都有值可比。
        val fromNotification = host().copy(timestamp = 9000L)
        val merged = fromNotification.mergeEnrichment(host(logisticsDetail = "快递员正在派件"))
        assertEquals("快递员正在派件", merged.logisticsDetail)
        // 反方向同理：富化记录自己没动态、通知侧也没有，合并结果仍是 null（不编造）。
        assertNull(host().mergeEnrichment(host()).logisticsDetail)
    }

    @Test
    fun `手机尾号只来自通知也能被「只填空」保住`() {
        // 宿主给不出手机尾号（encryReceiverTel 是加密串），富化一侧恒为 null。
        // 这条钉住的是「富化不能把通知里的手机尾号抹掉」。
        val fromNotification = host().copy(phoneTail = "1234")
        val merged = fromNotification.mergeEnrichment(host(platform = "淘宝"))

        assertEquals("1234", merged.phoneTail)
        assertEquals("淘宝", merged.platform)
    }

    @Test
    fun `完全没有商品信息时商品行返回 null`() {
        // 调用方整行省略，不能显示「商品：null」
        assertNull(ExpressFormatter.goodsSummary(host()))
        assertNull(ExpressFormatter.goodsLine(host()))
    }

    @Test
    fun `商品行只显示有的那部分`() {
        assertEquals("淘宝 · 云南白糖", ExpressFormatter.goodsSummary(host(platform = "淘宝", goodsName = "云南白糖")))
        assertEquals("云南白糖", ExpressFormatter.goodsSummary(host(goodsName = "云南白糖")))
        assertEquals("淘宝", ExpressFormatter.goodsSummary(host(platform = "淘宝")))
        // 正文里带字段名前缀，卡片上不带 —— 同一份拼装规则，两种呈现
        assertEquals("商品：淘宝 · 云南白糖", ExpressFormatter.goodsLine(host(platform = "淘宝", goodsName = "云南白糖")))
    }

    @Test
    fun `通知正文包含商品行`() {
        val body = ExpressFormatter.body(host(platform = "淘宝", goodsName = "云南白糖"))
        // 正文里不带「商品：」前缀（卡片上也没有）—— 读了就知道是什么，前缀只白占宽度
        assertTrue(body.contains("淘宝 · 云南白糖"))
        // 取件码要排在商品前面 —— 用户在驿站第一眼要看的是它
        assertTrue(body.indexOf("取件码").let { it >= 0 && it < body.indexOf("淘宝") })
    }

    @Test
    fun `入站时长只报天数不带到站前缀`() {
        // 前缀「已入站」在到站包裹分组里是废话（抬头已经说了），所以只留天数本身
        assertEquals("今天", ExpressFormatter.inStationLabel(0L, 3 * 3_600_000L, zone))
        assertEquals("1天", ExpressFormatter.inStationLabel(0L, day + 1L, zone))
        assertEquals("2天", ExpressFormatter.inStationLabel(0L, 2 * day + 5 * 60_000L, zone))
    }

    @Test
    fun `入站时长按自然日差算而不是24小时`() {
        // 真机样本（2026-09-26 落盘 + 用户确认「24号晚上到站」）：
        // 到站时刻 09-24 18:21:33，菜鸟首页显示「已入站2天」。
        // 按 24 小时整除只会得出 1 天（33 小时 ÷ 24），所以口径必须是「数日历格子」。
        val arrived = 1_790_245_293_000L // 2026-09-24 18:21:33 +08:00

        // 09-26 03:30 —— 用户看到「2天」的那一刻
        assertEquals("2天", ExpressFormatter.inStationLabel(arrived, 1_790_364_600_000L, zone))
        // 09-25 23:59 —— 差 29.6 小时，24 小时制也会说 1 天，这条是两边一致的基准
        assertEquals("1天", ExpressFormatter.inStationLabel(arrived, 1_790_351_940_000L, zone))
        // 09-25 00:10 —— 只过了 5.8 小时，24 小时制说「今天」，但跨过午夜就该算 1 天。
        // 这条是本测试的核心：它把「跨自然日即 1 天」这个口径钉死。
        assertEquals("1天", ExpressFormatter.inStationLabel(arrived, 1_790_266_200_000L, zone))
    }

    @Test
    fun `时间倒挂时不显示负数天数`() {
        // 时钟回拨或宿主给错时间都可能出现，宁可说「今天」也不能出现「-1天」
        assertEquals("今天", ExpressFormatter.inStationLabel(day * 5, 0L, zone))
    }

    @Test
    fun `营业时间压成钟点区间`() {
        // 真机实测格式（菜鸟 8.11.923 `packageStation.officeTime`）：中文长写法 →
        // 卡片上只要「几点到几点」。小时去前导零是用户指定口径。
        assertEquals("9:00-21:00", ExpressFormatter.stationHoursLabel("周一至周日09点00分到21点00分"))
        // 已经规范的写法走同一条规则，口径统一
        assertEquals("9:00-21:00", ExpressFormatter.stationHoursLabel("09:00-21:00"))
        // 「每天」和「周一至周日」等价，同样丢掉
        assertEquals("8:30-20:00", ExpressFormatter.stationHoursLabel("每天8:30-20:00"))
    }

    @Test
    fun `营业时间里非全周的星期前缀必须留着`() {
        // 丢掉「工作日」这半句会变成误导：用户周末跑一趟取不到
        assertEquals("周一至周五 · 9:00-18:00", ExpressFormatter.stationHoursLabel("周一至周五 09:00-18:00"))
    }

    @Test
    fun `营业时间有两段时不能截掉后半段`() {
        // 有午休的驿站会给两段，只取前两个钟点会显示成「9:00-12:00」——等于骗人
        assertEquals(
            "9:00-12:00，14:00-21:00",
            ExpressFormatter.stationHoursLabel("09点00分到12点00分，14点00分到21点00分"),
        )
    }

    @Test
    fun `营业时间认不出钟点时原样返回`() {
        // 宁可难看也不能编一个不存在的营业时间
        assertEquals("全天", ExpressFormatter.stationHoursLabel("全天"))
        assertEquals("09点00分", ExpressFormatter.stationHoursLabel("09点00分"))
        assertNull(ExpressFormatter.stationHoursLabel("   "))
        assertNull(ExpressFormatter.stationHoursLabel(null))
    }
}
