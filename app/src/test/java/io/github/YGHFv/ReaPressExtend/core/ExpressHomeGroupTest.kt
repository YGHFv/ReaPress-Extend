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
            record(pickup = "3-2-4008", station = "菜鸟驿站(阜阳颍滨花园店)", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "17-5-8644", station = "菜鸟驿站(合肥南湖春城店)", status = ExpressStatus.READY_FOR_PICKUP),
            record(pickup = "1-6-4011", station = "菜鸟驿站(阜阳颍滨花园店)", status = ExpressStatus.ARRIVED_STATION),
        )
        val sections = ExpressHomeGrouper.group(records)

        val pickupSection = sections.first { it.title == "到站包裹" }
        assertEquals(3, pickupSection.count)
        assertEquals(2, pickupSection.stationGroups.size)
        // 同一个驿站的兩件必须聚在一组
        val fuyang = pickupSection.stationGroups.first { it.station.contains("阜阳") }
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
        assertEquals("菜鸟驿站(A店)", groups.first().station)
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

    // ---- 同一包裹的识别（合并规则）----

    @Test
    fun `取件码相同驿站名详略不同视为同一包裹`() {
        val a = record().copy(
            trackingNumber = null,
            pickupCode = "17-5-8644",
            station = "菜鸟驿站(合肥南湖春城华韵古筝店)",
        )
        val b = record().copy(
            trackingNumber = null,
            pickupCode = "17-5-8644",
            station = "菜鸟驿站(合肥南湖春城店)",
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
