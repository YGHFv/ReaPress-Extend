package io.github.YGHFv.ReaPressExtend.core

import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页分组与记录存储的单测。
 *
 * 两者都是纯函数（分组只吃 List，存储序列化只吃 String），所以能在 JVM 单测里跑 ——
 * 本工程没有 Robolectric，`android.jar` 是桩，碰 `Context` 会抛 "not mocked"。
 */
class ExpressHomeGroupTest {

    private fun record(
        pickup: String? = null,
        station: String? = null,
        status: ExpressStatus = ExpressStatus.UNKNOWN,
        tracking: String? = null,
        at: Long = 0L,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "raw",
        trackingNumber = tracking,
        pickupCode = pickup,
        station = station,
        status = status,
        timestamp = at,
    )

    @Test
    fun `到站包裹按驿站聚合`() {
        val records = listOf(
            record(pickup = "3-2-4008", station = "菜鸟驿站(临河阳光花园店)", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "17-5-8644", station = "菜鸟驿站(合肥南湖新城店)", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "1-6-4011", station = "菜鸟驿站(临河阳光花园店)", status = ExpressStatus.ARRIVED_STATION),
        )
        val sections = ExpressHomeGrouper.group(records)

        val pickupSection = sections.first { it.title == "到站包裹" }
        assertEquals(3, pickupSection.count)
        assertEquals(2, pickupSection.stationGroups.size)
        // 同一个驿站的兩件必须聚在一组
        val fuyang = pickupSection.stationGroups.first { it.station.contains("临河") }
        assertEquals(2, fuyang.records.size)
    }

    @Test
    fun `运输中平铺不按驿站聚合`() {
        val records = listOf(
            record(tracking = "SF001", station = "某驿站", status = ExpressStatus.IN_TRANSIT),
            record(tracking = "SF002", station = "另一驿站", status = ExpressStatus.DELIVERING),
        )
        val sections = ExpressHomeGrouper.group(records)

        val transit = sections.first { it.title == "运输中" }
        assertEquals(2, transit.count)
        assertTrue("运输中不该按驿站分组", transit.stationGroups.isEmpty())
        assertEquals(2, transit.records.size)
    }

    @Test
    fun `无驿站的待取件归入未知地点而不是丢弃`() {
        val records = listOf(
            record(pickup = "8-2-3021", station = null, status = ExpressStatus.READY_FOR_PICKUP),
        )
        val sections = ExpressHomeGrouper.group(records)
        val pickup = sections.first { it.title == "到站包裹" }

        assertEquals(1, pickup.count)
        assertEquals(ExpressHomeGrouper.UNKNOWN_STATION, pickup.stationGroups.first().station)
    }

    @Test
    fun `空白驿站名同样归入未知地点`() {
        val records = listOf(
            record(pickup = "1234", station = "   ", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val sections = ExpressHomeGrouper.group(records)
        assertEquals(
            ExpressHomeGrouper.UNKNOWN_STATION,
            sections.first().stationGroups.first().station,
        )
    }

    @Test
    fun `未知状态单独成组不丢失`() {
        // 解析没抽到状态词，但确实是快递通知 —— 不能让它凭空消失
        val records = listOf(record(tracking = "SF001", status = ExpressStatus.UNKNOWN))
        val sections = ExpressHomeGrouper.group(records)
        assertTrue(sections.any { it.title == "其他" })
    }

    @Test
    fun `已签收与异常归入同一组`() {
        val records = listOf(
            record(tracking = "SF001", status = ExpressStatus.SIGNED),
            record(tracking = "SF002", status = ExpressStatus.FAILED),
        )
        val sections = ExpressHomeGrouper.group(records)
        val done = sections.first { it.title == "已签收 / 异常" }
        assertEquals(2, done.count)
    }

    @Test
    fun `分组顺序固定为 到站 运输中 其他 已签收`() {
        val records = listOf(
            record(tracking = "A", status = ExpressStatus.SIGNED),
            record(tracking = "B", status = ExpressStatus.IN_TRANSIT),
            record(pickup = "1", station = "站", status = ExpressStatus.READY_FOR_PICKUP),
            record(tracking = "C", status = ExpressStatus.UNKNOWN),
        )
        val titles = ExpressHomeGrouper.group(records).map { it.title }
        assertEquals(listOf("到站包裹", "运输中", "其他", "已签收 / 异常"), titles)
    }

    @Test
    fun `空输入产出空分组`() {
        assertTrue(ExpressHomeGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `有名字的驿站排在未知地点之前`() {
        val records = listOf(
            record(pickup = "1", station = null, status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "2", station = "菜鸟驿站(A店)", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val groups = ExpressHomeGrouper.group(records).first().stationGroups
        // 显示的是归一化后的名字：品牌前缀和外层括号在身份判定那一步就剥掉了
        assertEquals("A店", groups.first().station)
        assertEquals(ExpressHomeGrouper.UNKNOWN_STATION, groups.last().station)
    }

    @Test
    fun `组内按取件码排序`() {
        // 同一货架的包裹排在一起，找件时更顺
        val records = listOf(
            record(pickup = "9-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "1-1-2222", station = "站", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "5-1-3333", station = "站", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val group = ExpressHomeGrouper.group(records).first().stationGroups.first()
        assertEquals(listOf("1-1-2222", "5-1-3333", "9-1-1111"), group.records.map { it.pickupCode })
    }

    // ---- 确认取件（整站取完才移出待取件）----

    @Test
    fun `部分确认取件时仍留在到站包裹且已取的沉到组内末尾`() {
        val records = listOf(
            record(pickup = "1-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "2-2-2222", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
                .copy(pickedUpAt = 100L),
            record(pickup = "3-3-3333", station = "站", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val pickup = ExpressHomeGrouper.group(records).first { it.title == "到站包裹" }

        // 卡片不出这一档：用户还得知道「这站还剩几件没拿」
        assertEquals(3, pickup.count)
        val group = pickup.stationGroups.single()
        // 没取的在前（同档内仍按取件码排），已取的沉到最后
        assertEquals(listOf("1-1-1111", "3-3-3333", "2-2-2222"), group.records.map { it.pickupCode })
    }

    @Test
    fun `整站全部确认后才一起移出到站包裹`() {
        val records = listOf(
            record(pickup = "1-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
                .copy(pickedUpAt = 1L),
            record(pickup = "2-2-2222", station = "站", status = ExpressStatus.ARRIVED_STATION)
                .copy(pickedUpAt = 2L),
        )
        val sections = ExpressHomeGrouper.group(records)

        assertTrue("全站取完就不该再待在待取件里", sections.none { it.title == "到站包裹" })
        val done = sections.first { it.title == "已签收 / 异常" }
        assertEquals(2, done.count)
        assertTrue("记录本身不能丢，换一档继续留着", done.records.all { it.isPickedUp })
    }

    @Test
    fun `单件驿站标记一次就直接移出待取件`() {
        // 只有一件的时候「拿完这件」就等于「这站取完了」
        val records = listOf(
            record(pickup = "1-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
                .copy(pickedUpAt = 1L),
        )
        val sections = ExpressHomeGrouper.group(records)
        assertTrue(sections.none { it.title == "到站包裹" })
        assertEquals(1, sections.first { it.title == "已签收 / 异常" }.count)
    }

    @Test
    fun `整站确认只看同一个取件地点`() {
        val records = listOf(
            record(pickup = "1-1-1111", station = "A站", status = ExpressStatus.READY_FOR_PICKUP)
                .copy(pickedUpAt = 1L),
            record(pickup = "2-2-2222", station = "B站", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val pickup = ExpressHomeGrouper.group(records).first { it.title == "到站包裹" }

        assertEquals("A 站取完不关 B 站的事", 1, pickup.count)
        assertEquals("B站", pickup.stationGroups.single().station)
    }

    @Test
    fun `已签收的旧记录不参与整站确认`() {
        // 若把签收记录也算进「全站」，这个地点就永远凑不满，用户再也移不掉它
        val records = listOf(
            record(tracking = "SF001", station = "站", status = ExpressStatus.SIGNED),
            record(pickup = "1-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
                .copy(pickedUpAt = 1L),
        )
        val sections = ExpressHomeGrouper.group(records)

        assertTrue(sections.none { it.title == "到站包裹" })
        assertEquals(2, sections.first { it.title == "已签收 / 异常" }.count)
    }

    @Test
    fun `撤销标记后整站重新回到到站包裹`() {
        // 这是「双击已取件可以还原」的分组侧证据：撤销必须让整组回到待取件，
        // 而不是留在「已签收 / 异常」里 —— 那是误触后唯一的退路。
        val single = record(pickup = "1-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
            .copy(pickedUpAt = 1L)
        assertTrue(ExpressHomeGrouper.group(listOf(single)).none { it.title == "到站包裹" })

        val restored = single.copy(pickedUpAt = null)
        val pickup = ExpressHomeGrouper.group(listOf(restored)).first { it.title == "到站包裹" }
        assertEquals(1, pickup.count)
        assertEquals("站", pickup.stationGroups.single().station)
    }

    @Test
    fun `多件里撤销一件后整组回到到站包裹`() {
        val a = record(pickup = "1-1-1111", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
            .copy(pickedUpAt = 1L)
        val b = record(pickup = "2-2-2222", station = "站", status = ExpressStatus.READY_FOR_PICKUP)
            .copy(pickedUpAt = 2L)

        // 撤销 a：b 仍标着，但「整站全部确认」不再成立 → 两件都回到到站包裹
        val pickup = ExpressHomeGrouper.group(listOf(a.copy(pickedUpAt = null), b))
            .first { it.title == "到站包裹" }
        assertEquals(2, pickup.count)
        // 还标着的那件沉在后面，撤销过的那件回到前面（未取的算「还得拿」）
        assertEquals(listOf("1-1-1111", "2-2-2222"), pickup.stationGroups.single().records.map { it.pickupCode })
    }

    // ---- 驿站身份：同一个取件地点的不同写法 ----

    @Test
    fun `同一驿站的两种写法合成一张卡`() {
        // 真机样本（2026-09-26）：通知里是简称，宿主 packageStation.name 带楼栋号
        val records = listOf(
            record(
                pickup = "1-1-1111",
                station = "阳光花园菜拼多多驿站",
                status = ExpressStatus.READY_FOR_PICKUP,
            ),
            record(
                pickup = "2-2-2222",
                station = "阳光23号楼109阳光花园菜拼多多驿站",
                status = ExpressStatus.READY_FOR_PICKUP,
            ),
        )
        val pickup = ExpressHomeGrouper.group(records).first { it.title == "到站包裹" }

        assertEquals("同一个地点只该有一张卡", 1, pickup.stationGroups.size)
        assertEquals(2, pickup.stationGroups.single().records.size)
        // 显示用信息最全的那个写法（带楼栋号，找起来少问一次人）
        assertEquals("阳光23号楼109阳光花园菜拼多多驿站", pickup.stationGroups.single().station)
    }

    @Test
    fun `品牌前缀与括号不同的写法合成一张卡`() {
        val records = listOf(
            record(
                pickup = "1-1-1111",
                station = "菜鸟驿站(临河阳光花园店)",
                status = ExpressStatus.READY_FOR_PICKUP,
            ),
            record(pickup = "2-2-2222", station = "临河阳光花园店", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val pickup = ExpressHomeGrouper.group(records).first { it.title == "到站包裹" }

        assertEquals(1, pickup.stationGroups.size)
        assertEquals("临河阳光花园店", pickup.stationGroups.single().station)
    }

    @Test
    fun `整站确认跨写法也生效`() {
        // 两种写法其实是同一站 → 都标记后整批移出，而不是各自卡在待取件里凑不满
        val records = listOf(
            record(
                pickup = "1-1-1111",
                station = "阳光花园菜拼多多驿站",
                status = ExpressStatus.READY_FOR_PICKUP,
            ).copy(pickedUpAt = 1L),
            record(
                pickup = "2-2-2222",
                station = "阳光23号楼109阳光花园菜拼多多驿站",
                status = ExpressStatus.READY_FOR_PICKUP,
            ).copy(pickedUpAt = 2L),
        )
        val sections = ExpressHomeGrouper.group(records)

        assertTrue(sections.none { it.title == "到站包裹" })
        assertEquals(2, sections.first { it.title == "已签收 / 异常" }.count)
    }

    @Test
    fun `用户规则能把两个名字毫不相干的驿站并成一张卡`() {
        // 机器认不出来（这两个名字没有任何字符串关系），只有住那儿的人知道是同一处
        val records = listOf(
            record(pickup = "1-1-1111", station = "南门驿站", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "2-2-2222", station = "南门小区代收点", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val rules = ExpressStationRules(mapOf("南门驿站" to "南门小区代收点"))
        val pickup = ExpressHomeGrouper.group(records, rules).first { it.title == "到站包裹" }

        assertEquals(1, pickup.stationGroups.size)
        assertEquals("南门小区代收点", pickup.stationGroups.single().station)
    }

    @Test
    fun `用户规则只换显示名，不动记录里的原始写法`() {
        val records = listOf(
            record(
                pickup = "1-1-1111",
                station = "菜鸟驿站(临河阳光花园店)",
                status = ExpressStatus.READY_FOR_PICKUP,
            ),
        )
        val rules = ExpressStationRules(mapOf("临河阳光花园店" to "家门口"))
        val group = ExpressHomeGrouper.group(records, rules).first().stationGroups.single()

        assertEquals("家门口", group.station)
        // 原始串留在记录里 —— 这正是「恢复默认」不需要备份任何东西的原因
        assertEquals("菜鸟驿站(临河阳光花园店)", group.records.single().station)
    }

    @Test
    fun `撤销规则后回到原始名字`() {
        val records = listOf(
            record(pickup = "1-1-1111", station = "临河阳光花园店", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val renamed = ExpressHomeGrouper.group(
            records,
            ExpressStationRules(mapOf("临河阳光花园店" to "家门口")),
        ).first().stationGroups.single().station

        val restored = ExpressHomeGrouper.group(records, ExpressStationRules.EMPTY)
            .first().stationGroups.single().station

        assertEquals("家门口", renamed)
        assertEquals("临河阳光花园店", restored)
    }

    // ---- 驿站管理页的数据源 ----

    @Test
    fun `stations 列出所有驿站包括已签收的`() {
        // 管理页要能管到所有驿站：只有已签收包裹的驿站也得在列表里，
        // 否则用户想合并两个名字时可能找不到其中一方
        val records = listOf(
            record(pickup = "1-1-1111", station = "A站", status = ExpressStatus.READY_FOR_PICKUP),
            record(tracking = "SF1", station = "B站", status = ExpressStatus.SIGNED),
            record(tracking = "SF2", station = null, status = ExpressStatus.IN_TRANSIT),
        )
        val stations = ExpressHomeGrouper.stations(records)

        assertEquals(3, stations.size)
        // 未知地点垫底，和首页卡片同一个顺序
        assertEquals(ExpressHomeGrouper.UNKNOWN_STATION, stations.last().key)
        assertEquals(listOf("A站", "B站"), stations.dropLast(1).map { it.displayName })
    }

    @Test
    fun `stations 列出同一驿站的原始写法`() {
        val records = listOf(
            record(
                pickup = "1",
                station = "菜鸟驿站(临河阳光花园店)",
                status = ExpressStatus.READY_FOR_PICKUP,
            ),
            record(pickup = "2", station = "临河阳光花园店", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val station = ExpressHomeGrouper.stations(records).single()

        // key 是规则表的键（归一化后的名字），rawNames 是用户要认出来的那几串原始写法
        assertEquals("临河阳光花园店", station.key)
        assertEquals(2, station.count)
        assertEquals(
            listOf("菜鸟驿站(临河阳光花园店)", "临河阳光花园店"),
            station.rawNames,
        )
        assertFalse(station.renamed)
    }

    @Test
    fun `stations 标出被手工改过的驿站`() {
        val records = listOf(
            record(pickup = "1", station = "临河阳光花园店", status = ExpressStatus.READY_FOR_PICKUP),
        )
        val station = ExpressHomeGrouper.stations(
            records,
            ExpressStationRules(mapOf("临河阳光花园店" to "家门口")),
        ).single()

        assertEquals("家门口", station.displayName)
        assertTrue(station.renamed)
    }
}

class ExpressRecordStoreTest {

    private fun record(
        tracking: String? = "SF123",
        status: ExpressStatus = ExpressStatus.IN_TRANSIT,
        at: Long = 1000L,
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "raw-$tracking-$status",
        trackingNumber = tracking,
        courier = Courier.SHUNFENG,
        pickupCode = "8-2-3021",
        station = "菜鸟驿站(测试店)",
        status = status,
        title = "菜鸟",
        matchedKeywords = listOf("快递", "取件码"),
        confidence = 95,
        timestamp = at,
    )

    @Test
    fun `序列化与反序列化往返一致`() {
        val original = listOf(record(), record(tracking = "YT999"))
        val restored = ExpressRecordStore.parse(ExpressRecordStore.serialize(original))

        assertEquals(2, restored.size)
        val first = restored.first { it.trackingNumber == "SF123" }
        assertEquals(Courier.SHUNFENG, first.courier)
        assertEquals(ExpressStatus.IN_TRANSIT, first.status)
        assertEquals("8-2-3021", first.pickupCode)
        assertEquals("菜鸟驿站(测试店)", first.station)
        assertEquals(listOf("快递", "取件码"), first.matchedKeywords)
        assertEquals(95, first.confidence)
        assertEquals(1000L, first.timestamp)
    }

    @Test
    fun `null 字段往返后仍是 null`() {
        val original = listOf(record().copy(trackingNumber = null, pickupCode = null, station = null, title = null))
        val restored = ExpressRecordStore.parse(ExpressRecordStore.serialize(original)).single()

        assertNull(restored.trackingNumber)
        assertNull(restored.pickupCode)
        assertNull(restored.station)
        assertNull(restored.title)
    }

    @Test
    fun `空串与损坏输入返回空列表而不是崩`() {
        assertTrue(ExpressRecordStore.parse(null).isEmpty())
        assertTrue(ExpressRecordStore.parse("").isEmpty())
        assertTrue(ExpressRecordStore.parse("not json").isEmpty())
        assertTrue(ExpressRecordStore.parse("{}").isEmpty())
    }

    @Test
    fun `未知枚举值退回默认而不是抛异常`() {
        // App 升级后旧记录里可能有已删除的状态名，读的时候不能让整个列表挂掉
        val json = JSONArray().put(
            org.json.JSONObject().apply {
                put("pkg", "com.cainiao.wireless")
                put("raw", "x")
                put("status", "SOME_REMOVED_STATUS")
                put("courier", "REMOVED_COURIER")
            },
        ).toString()
        val restored = ExpressRecordStore.parse(json).single()
        assertEquals(ExpressStatus.UNKNOWN, restored.status)
        assertEquals(Courier.UNKNOWN, restored.courier)
    }

    @Test
    fun `序列化产出合法 JSON 数组`() {
        val json = ExpressRecordStore.serialize(listOf(record()))
        assertNotNull(JSONArray(json))
        assertEquals(1, JSONArray(json).length())
    }

    // ---- 用户确认取件（pickedUpAt）----

    @Test
    fun `已取件标记能往返序列化`() {
        val original = listOf(record().copy(pickedUpAt = 1_790_000_000_000L))
        val restored = ExpressRecordStore.parse(ExpressRecordStore.serialize(original)).single()
        assertEquals(1_790_000_000_000L, restored.pickedUpAt)
        assertTrue(restored.isPickedUp)
    }

    @Test
    fun `旧 JSON 没有已取件键时读成未取件`() {
        // 升级不丢历史记录：缺键一律退回 null，而不是某个「看起来像时间」的值
        val json = JSONArray().put(
            org.json.JSONObject().apply {
                put("pkg", "com.cainiao.wireless")
                put("raw", "x")
                put("status", "READY_FOR_PICKUP")
                put("pickup", "1-6-4011")
            },
        ).toString()
        assertNull(ExpressRecordStore.parse(json).single().pickedUpAt)
    }

    @Test
    fun `applyPickedUp 只动目标那一条`() {
        val a = record(tracking = "SF111")
        val b = record(tracking = "SF222")
        val result = ExpressRecordStore.applyPickedUp(listOf(a, b), b.dedupeKey, 123L)

        assertNotNull(result)
        assertNull("别的记录不能被连坐", result!![0].pickedUpAt)
        assertEquals(123L, result[1].pickedUpAt)
    }

    @Test
    fun `applyPickedUp 撤销标记写回 null`() {
        val marked = record().copy(pickedUpAt = 999L)
        val result = ExpressRecordStore.applyPickedUp(listOf(marked), marked.dedupeKey, null)
        assertNull(result!!.single().pickedUpAt)
    }

    @Test
    fun `applyPickedUp 目标不存在或状态没变时返回 null`() {
        val plain = record()
        assertNull(
            "定位不到目标（比如刚好被上限裁掉）不该报错，界面刷新一次就自愈",
            ExpressRecordStore.applyPickedUp(listOf(plain), "tn:NOT_EXIST", 1L),
        )
        val marked = plain.copy(pickedUpAt = 5L)
        assertNull(
            "重复标记同一个状态不产生变化，不该白写一次存储",
            ExpressRecordStore.applyPickedUp(listOf(marked), marked.dedupeKey, 5L),
        )
    }

    @Test
    fun `通知再来一条不会冲掉已取件标记`() {
        // 用户取走后宿主还会推「已签收」之类的更新，合并规则必须把标记带着走
        val picked = record(status = ExpressStatus.READY_FOR_PICKUP, at = 1000L)
            .copy(pickedUpAt = 999L)
        val incoming = record(status = ExpressStatus.READY_FOR_PICKUP, at = 2000L)

        val merged = ExpressRecordStore.applyUpsert(listOf(picked), incoming)!!.single()
        assertEquals(999L, merged.pickedUpAt)

        // 富化那条路（mergeEnrichment 双向合并）同样不能丢
        assertEquals(999L, picked.mergeEnrichment(incoming).pickedUpAt)
        assertEquals(999L, incoming.mergeEnrichment(picked).pickedUpAt)
    }

    // ---- 同一包裹的识别（合并规则）----

    @Test
    fun `取件码相同驿站名详略不同视为同一包裹`() {
        val a = record().copy(
            trackingNumber = null,
            pickupCode = "17-5-8644",
            station = "菜鸟驿站(合肥南湖新城华韵古筝店)",
        )
        val b = record().copy(
            trackingNumber = null,
            pickupCode = "17-5-8644",
            station = "菜鸟驿站(合肥南湖新城店)",
        )
        assertTrue("驿站名不参与身份判定", a.isSamePackageAs(b))
    }

    @Test
    fun `运单号不同视为不同包裹即使取件码相同`() {
        val a = record().copy(trackingNumber = "SF111", pickupCode = "8-2-3021")
        val b = record().copy(trackingNumber = "SF222", pickupCode = "8-2-3021")
        assertFalse("运单号全局唯一，不同就是两件", a.isSamePackageAs(b))
    }

    @Test
    fun `一边有运单号一边没有时按取件码匹配`() {
        val a = record().copy(trackingNumber = null, pickupCode = "8-2-3021")
        val b = record().copy(trackingNumber = "SF111", pickupCode = "8-2-3021")
        assertTrue("菜鸟的待取件通知常不带运单号，不能因此拆成两件", a.isSamePackageAs(b))
    }

    @Test
    fun `都没有运单号和取件码时退回原文比对`() {
        val a = record().copy(trackingNumber = null, pickupCode = null, rawText = "同一个原文")
        val b = record().copy(trackingNumber = null, pickupCode = null, rawText = "另一个原文")
        assertFalse(a.isSamePackageAs(b))
        assertTrue(a.isSamePackageAs(a.copy()))
    }

    @Test
    fun `不同取件码且都无运单号视为不同包裹`() {
        val a = record().copy(trackingNumber = null, pickupCode = "1-1-1111")
        val b = record().copy(trackingNumber = null, pickupCode = "2-2-2222")
        assertFalse(a.isSamePackageAs(b))
    }
}
