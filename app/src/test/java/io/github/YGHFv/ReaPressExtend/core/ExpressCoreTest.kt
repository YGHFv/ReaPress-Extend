package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressTextExtractorTest {

    @Test
    fun `bigText 优先于 text`() {
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_TITLE to "菜鸟",
            ExpressTextExtractor.EXTRA_TEXT to "短文本",
            ExpressTextExtractor.EXTRA_BIG_TEXT to "展开后的完整长文本",
        )
        assertEquals("展开后的完整长文本", ExpressTextExtractor.extractBody(extras))
    }

    @Test
    fun `textLines 数组拍平成多行`() {
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_TEXT_LINES to arrayOf("第一行", "第二行"),
        )
        assertEquals("第一行\n第二行", ExpressTextExtractor.extractBody(extras))
    }

    @Test
    fun `bigText 为空串时退到 text`() {
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_BIG_TEXT to "",
            ExpressTextExtractor.EXTRA_TEXT to "折叠态文本",
        )
        assertEquals("折叠态文本", ExpressTextExtractor.extractBody(extras))
    }

    @Test
    fun `全部缺失时返回空串`() {
        assertEquals("", ExpressTextExtractor.extractBody(emptyMap()))
    }

    @Test
    fun `title 与 body 拼成完整文本`() {
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_TITLE to "菜鸟",
            ExpressTextExtractor.EXTRA_TEXT to "您的包裹已到站",
        )
        assertEquals("菜鸟\n您的包裹已到站", ExpressTextExtractor.extractFullText(extras))
    }

    @Test
    fun `标题与正文重复时去重`() {
        // 很多 App 把标题原样塞进正文开头，不去重会让关键词重复命中、置信度虚高
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_TITLE to "菜鸟",
            ExpressTextExtractor.EXTRA_TEXT to "菜鸟",
        )
        assertEquals("菜鸟", ExpressTextExtractor.extractFullText(extras))
    }

    @Test
    fun `副标题参与拼接`() {
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_TITLE to "菜鸟",
            ExpressTextExtractor.EXTRA_SUB_TEXT to "待取件",
            ExpressTextExtractor.EXTRA_TEXT to "取件码 8-2-3021",
        )
        assertEquals("菜鸟\n待取件\n取件码 8-2-3021", ExpressTextExtractor.extractFullText(extras))
    }

    @Test
    fun `CharSequence 类型的值也能拍平`() {
        val extras = mapOf(ExpressTextExtractor.EXTRA_TEXT to StringBuilder("来自 StringBuilder"))
        assertEquals("来自 StringBuilder", ExpressTextExtractor.extractBody(extras))
    }

    @Test
    fun `extractTitle 去空白`() {
        val extras = mapOf(ExpressTextExtractor.EXTRA_TITLE to "  菜鸟  ")
        assertEquals("菜鸟", ExpressTextExtractor.extractTitle(extras))
    }

    @Test
    fun `非字符串类型不崩`() {
        // extras 里混进 Int 之类不该让抽取抛异常 —— 这是在 system_server 里跑的代码
        val extras = mapOf(ExpressTextExtractor.EXTRA_TEXT to 42)
        assertEquals("42", ExpressTextExtractor.extractBody(extras))
    }

    @Test
    fun `拼接结果能直接喂给解析器`() {
        val extras = mapOf(
            ExpressTextExtractor.EXTRA_TITLE to "菜鸟",
            ExpressTextExtractor.EXTRA_TEXT to "您的包裹已到菜鸟驿站，取件码 8-2-3021，请及时取件",
        )
        val text = ExpressTextExtractor.extractFullText(extras)
        val verdict = ExpressClassifier.classify("com.cainiao.wireless", text, ExpressRule())
        assertTrue(verdict.isExpress)
        assertEquals("8-2-3021", ExpressParser.parsePickupCode(text))
    }
}

class ExpressDedupeTest {

    private val now = 1_000_000L

    private fun record(
        tracking: String? = null,
        pickup: String? = null,
        station: String? = null,
        status: ExpressStatus = ExpressStatus.UNKNOWN,
        raw: String = "raw",
    ) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = raw,
        trackingNumber = tracking,
        pickupCode = pickup,
        station = station,
        status = status,
    )

    @Test
    fun `首次记录被接受`() {
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(tracking = "SF123"), now))
    }

    @Test
    fun `同运单号同状态重复被丢弃`() {
        val dedupe = ExpressDedupe()
        val r = record(tracking = "SF123", status = ExpressStatus.IN_TRANSIT)
        assertTrue(dedupe.shouldAccept(r, now))
        assertFalse(dedupe.shouldAccept(r, now + 1000))
    }

    @Test
    fun `状态推进时放行`() {
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(tracking = "SF123", status = ExpressStatus.IN_TRANSIT), now))
        assertTrue(
            dedupe.shouldAccept(
                record(tracking = "SF123", status = ExpressStatus.READY_FOR_PICKUP),
                now + 1000,
            ),
        )
    }

    @Test
    fun `状态倒退时丢弃`() {
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(tracking = "SF123", status = ExpressStatus.SIGNED), now))
        // 乱序推送：先收到「已签收」再收到「运输中」，不该把状态退回去
        assertFalse(
            dedupe.shouldAccept(
                record(tracking = "SF123", status = ExpressStatus.IN_TRANSIT),
                now + 1000,
            ),
        )
    }

    @Test
    fun `同状态但文案变了时放行`() {
        val dedupe = ExpressDedupe()
        assertTrue(
            dedupe.shouldAccept(
                record(tracking = "SF123", status = ExpressStatus.READY_FOR_PICKUP, raw = "取件码 1234"),
                now,
            ),
        )
        // 同状态、不同文案（比如追加了「即将退回」）应当让用户看到
        assertTrue(
            dedupe.shouldAccept(
                record(tracking = "SF123", status = ExpressStatus.READY_FOR_PICKUP, raw = "取件码 1234，即将退回"),
                now + 1000,
            ),
        )
    }

    @Test
    fun `不同运单号各自独立`() {
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(tracking = "SF111"), now))
        assertTrue(dedupe.shouldAccept(record(tracking = "SF222"), now))
        assertEquals(2, dedupe.size())
    }

    @Test
    fun `无运单号时退到取件码加驿站`() {
        val dedupe = ExpressDedupe()
        val r = record(
            pickup = "8-2-3021",
            station = "菜鸟驿站",
            status = ExpressStatus.READY_FOR_PICKUP,
            raw = "取件码 8-2-3021",
        )
        assertTrue(dedupe.shouldAccept(r, now))
        assertFalse(dedupe.shouldAccept(r, now + 1000))
    }

    @Test
    fun `取件码相同但驿站名详略不同视为同一包裹`() {
        // 真实场景：同一个包裹的两次推送里驿站名一个写全、一个写简
        // （「菜鸟驿站(合肥南湖春城华韵古筝店)」vs「菜鸟驿站(合肥南湖春城店)」）。
        // 驿站名不参与身份判定，否则首页上会出现两张卡片。
        val dedupe = ExpressDedupe()
        assertTrue(
            dedupe.shouldAccept(
                record(pickup = "17-5-8644", station = "菜鸟驿站(合肥南湖春城华韵古筝店)"),
                now,
            ),
        )
        assertFalse(
            dedupe.shouldAccept(
                record(pickup = "17-5-8644", station = "菜鸟驿站(合肥南湖春城店)"),
                now + 1000,
            ),
        )
    }

    @Test
    fun `取件码相同的不同运单号视为不同包裹`() {
        // 运单号全局唯一，两边都有且不同 → 确实是两件，哪怕取件码碰巧一样
        // （同一个货架格先后放过两件的情况真实存在）。
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(tracking = "SF111", pickup = "8-2-3021"), now))
        assertTrue(dedupe.shouldAccept(record(tracking = "SF222", pickup = "8-2-3021"), now + 1000))
    }

    @Test
    fun `运单号相同但取件码不同视为同一包裹`() {
        // 包裹被移到了别的货架格 —— 这是同一件，状态更新应当合并。
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(tracking = "SF111", pickup = "1-1-1111"), now))
        assertFalse(dedupe.shouldAccept(record(tracking = "SF111", pickup = "2-2-2222"), now + 1000))
    }

    @Test
    fun `运单号从无到有时不会重复计数`() {
        // 菜鸟的待取件通知常不带运单号，后续通知才带上 —— 这两条必须认成同一个包裹，
        // 否则同一个包裹会在去重表里占两个槽位、各自独立计时。
        val dedupe = ExpressDedupe()
        assertTrue(dedupe.shouldAccept(record(pickup = "8-2-3021"), now))
        assertFalse(dedupe.shouldAccept(record(tracking = "SF111", pickup = "8-2-3021"), now + 1000))
        assertEquals("主键变化后不该留下旧槽位", 1, dedupe.size())
    }

    @Test
    fun `超过 TTL 后重新放行`() {
        val dedupe = ExpressDedupe(ttlMillis = 1000L)
        val r = record(tracking = "SF123", status = ExpressStatus.IN_TRANSIT)
        assertTrue(dedupe.shouldAccept(r, now))
        assertTrue(dedupe.shouldAccept(r, now + 2000))
    }

    @Test
    fun `持续重复推送会刷新时间戳`() {
        val dedupe = ExpressDedupe(ttlMillis = 1000L)
        val r = record(tracking = "SF123", status = ExpressStatus.IN_TRANSIT)
        assertTrue(dedupe.shouldAccept(r, now))
        // 每 500ms 重复一次：每次都在刷新时间戳，所以哪怕累计跨过了 TTL 也一直判重复
        assertFalse(dedupe.shouldAccept(r, now + 500))
        assertFalse(dedupe.shouldAccept(r, now + 1000))
        assertFalse(dedupe.shouldAccept(r, now + 1500))
        // 最后一次刷新在 now+1500；now+3000 距它 1500ms 已超过 TTL → 过期清理后重新放行
        assertTrue(dedupe.shouldAccept(r, now + 3000))
    }

    @Test
    fun `超出容量时淘汰最旧`() {
        val dedupe = ExpressDedupe(maxEntries = 3)
        repeat(5) { i -> assertTrue(dedupe.shouldAccept(record(tracking = "SF$i"), now + i)) }
        assertTrue(dedupe.size() <= 3)
    }

    @Test
    fun `clear 清空`() {
        val dedupe = ExpressDedupe()
        dedupe.shouldAccept(record(tracking = "SF123"), now)
        dedupe.clear()
        assertEquals(0, dedupe.size())
    }

    @Test
    fun `dedupeKey 优先级 运单号 高于 取件码`() {
        val r = record(tracking = "SF123", pickup = "8-2-3021", station = "站")
        assertEquals("tn:SF123", r.dedupeKey)
    }

    @Test
    fun `无运单号无取件码时退到原文哈希`() {
        val r = record(raw = "您的包裹正在派送")
        assertTrue(r.dedupeKey.startsWith("raw:"))
    }
}

class CourierTest {

    @Test
    fun `长前缀优先匹配`() {
        // "J" 不该抢在 "JD" 前面把京东判成极兔；"JT" 才是极兔
        assertEquals(Courier.JD, Courier.fromTrackingNumber("JD1234567890"))
        assertEquals(Courier.JITU, Courier.fromTrackingNumber("JT1234567890"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(Courier.SHUNFENG, Courier.fromTrackingNumber("sf1234567890123"))
    }

    @Test
    fun `未知前缀返回 UNKNOWN`() {
        assertEquals(Courier.UNKNOWN, Courier.fromTrackingNumber("123456789012"))
        // 纯数字前缀（中通/申通的号段）刻意不入表 —— 号段会变，猜错比不猜更糟
        assertEquals(Courier.UNKNOWN, Courier.fromTrackingNumber("73112345678"))
    }

    @Test
    fun `null 与空串返回 UNKNOWN`() {
        assertEquals(Courier.UNKNOWN, Courier.fromTrackingNumber(null))
        assertEquals(Courier.UNKNOWN, Courier.fromTrackingNumber(""))
        assertEquals(Courier.UNKNOWN, Courier.fromTrackingNumber("   "))
    }

    @Test
    fun `EMS 多前缀`() {
        assertEquals(Courier.EMS, Courier.fromTrackingNumber("EA123456789CN"))
        assertEquals(Courier.EMS, Courier.fromTrackingNumber("KA123456789CN"))
    }
}

class ExpressStatusTest {

    @Test
    fun `状态推进判定`() {
        assertTrue(ExpressStatus.SIGNED.isAdvanceFrom(ExpressStatus.IN_TRANSIT))
        assertTrue(ExpressStatus.READY_FOR_PICKUP.isAdvanceFrom(ExpressStatus.ARRIVED_STATION))
    }

    @Test
    fun `同级视为推进`() {
        // 同状态但文案变了（取件码提醒升级）也值得让用户看到
        assertTrue(ExpressStatus.READY_FOR_PICKUP.isAdvanceFrom(ExpressStatus.READY_FOR_PICKUP))
    }

    @Test
    fun `状态倒退判定`() {
        assertFalse(ExpressStatus.IN_TRANSIT.isAdvanceFrom(ExpressStatus.SIGNED))
    }

    @Test
    fun `UNKNOWN 不推进任何已知状态`() {
        assertFalse(ExpressStatus.UNKNOWN.isAdvanceFrom(ExpressStatus.IN_TRANSIT))
    }
}
