package io.github.YGHFv.ReaPressExtend.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchRequestLedgerTest {
    private val start = 10_000L
    private val cooldown = 1_000L

    @Test
    fun `进行中的请求不能重复占位包括强制复查`() {
        val ledger = FetchRequestLedger(cooldown)

        assertTrue(ledger.isEligible("parcel", start))
        assertEquals(FetchRequestLedger.Admission.ACCEPTED, ledger.begin("parcel", start))
        assertFalse(ledger.isEligible("parcel", start, recheck = true))
        assertEquals(FetchRequestLedger.Admission.IN_FLIGHT, ledger.begin("parcel", start, recheck = true))
        assertEquals(FetchRequestLedger.Snapshot(1, 0, 0), ledger.snapshot())
    }

    @Test
    fun `失败释放占位且冷却到期后可正常重试`() {
        val ledger = FetchRequestLedger(cooldown)
        ledger.begin("parcel", start)
        ledger.finish("parcel", start, success = false)

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), ledger.snapshot())
        assertEquals(FetchRequestLedger.Admission.COOLING, ledger.begin("parcel", start + cooldown - 1, recheck = true))
        assertEquals(FetchRequestLedger.Admission.ACCEPTED, ledger.begin("parcel", start + cooldown))
        ledger.finish("parcel", start + cooldown, success = true)
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), ledger.snapshot())
    }

    @Test
    fun `已成功的单号只有显式复查才重新请求`() {
        val ledger = FetchRequestLedger(cooldown)
        ledger.begin("parcel", start)
        ledger.finish("parcel", start, success = true)

        assertEquals(FetchRequestLedger.Admission.SUCCEEDED, ledger.begin("parcel", start + cooldown))
        assertEquals(FetchRequestLedger.Admission.ACCEPTED, ledger.begin("parcel", start + cooldown, recheck = true))
    }

    @Test
    fun `成功后的复查失败不能被旧成功标记永久挡住`() {
        val ledger = FetchRequestLedger(cooldown)
        ledger.begin("parcel", start)
        ledger.finish("parcel", start, success = true)
        ledger.begin("parcel", start, recheck = true)
        ledger.finish("parcel", start, success = false)

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), ledger.snapshot())
        assertEquals(FetchRequestLedger.Admission.ACCEPTED, ledger.begin("parcel", start + cooldown))
    }

    @Test
    fun `未执行的请求释放后无需等待冷却`() {
        val ledger = FetchRequestLedger(cooldown)
        ledger.begin("parcel", start)
        ledger.release("parcel")

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), ledger.snapshot())
        assertEquals(FetchRequestLedger.Admission.ACCEPTED, ledger.begin("parcel", start))
    }

    @Test
    fun `没有占位的重复完成回执不改变结果`() {
        val ledger = FetchRequestLedger(cooldown)
        ledger.begin("parcel", start)
        ledger.finish("parcel", start, success = true)
        ledger.finish("parcel", start, success = false)
        ledger.finish("unknown", start, success = true)

        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), ledger.snapshot())
    }

    @Test
    fun `成功缓存保留容量上限且复查已有键不清空其他成功`() {
        val ledger = FetchRequestLedger(cooldown, successLimit = 2)
        listOf("first", "second").forEach { key ->
            ledger.begin(key, start)
            ledger.finish(key, start, success = true)
        }
        ledger.begin("second", start, recheck = true)
        ledger.finish("second", start, success = true)
        assertEquals(FetchRequestLedger.Snapshot(0, 2, 0), ledger.snapshot())

        ledger.begin("third", start)
        ledger.finish("third", start, success = true)
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), ledger.snapshot())
        assertTrue(ledger.isEligible("first", start))
        assertFalse(ledger.isEligible("third", start))
    }

    @Test
    fun `多线程同时请求同一单号只有一个占位成功`() {
        val ledger = FetchRequestLedger(cooldown)
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val release = CountDownLatch(1)
        try {
            val attempts = (1..8).map {
                workers.submit<FetchRequestLedger.Admission> {
                    ready.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    ledger.begin("parcel", start)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it == FetchRequestLedger.Admission.ACCEPTED })
            assertEquals(7, results.count { it == FetchRequestLedger.Admission.IN_FLIGHT })
            assertEquals(FetchRequestLedger.Snapshot(1, 0, 0), ledger.snapshot())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
