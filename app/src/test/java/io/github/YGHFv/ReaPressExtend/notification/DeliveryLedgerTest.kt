package io.github.YGHFv.ReaPressExtend.notification

import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeliveryLedgerTest {
    @Test
    fun failedPostReleasesClaim() {
        val ledger = DeliveryLedger()
        assertEquals(DeliveryLedger.Result.FAILED, ledger.deliver("event") { false })
        assertEquals(0, ledger.size())
        assertEquals(DeliveryLedger.Result.POSTED, ledger.deliver("event") { true })
    }

    @Test
    fun exceptionReleasesClaim() {
        val ledger = DeliveryLedger()
        assertThrows(IllegalStateException::class.java) { ledger.deliver("event") { error("synthetic") } }
        assertEquals(0, ledger.size())
        assertEquals(DeliveryLedger.Result.POSTED, ledger.deliver("event") { true })
    }

    @Test
    fun inFlightIsNotSuccessAndDoesNotExpireWhilePosting() {
        var now = 1L
        val ledger = DeliveryLedger(clock = { now })
        ledger.deliver("event") {
            now += 120_000L
            assertEquals(DeliveryLedger.Result.IN_FLIGHT, ledger.deliver("event") { error("duplicate") })
            true
        }
        assertEquals(DeliveryLedger.Result.ALREADY_POSTED, ledger.deliver("event") { error("duplicate") })
    }

    @Test
    fun successWindowStartsAtCompletionAndExpires() {
        var now = 100L
        val ledger = DeliveryLedger(clock = { now })
        ledger.deliver("event") { now = 100_000L; true }
        now += 59_999L
        assertEquals(DeliveryLedger.Result.ALREADY_POSTED, ledger.deliver("event") { error("duplicate") })
        now++
        assertEquals(DeliveryLedger.Result.POSTED, ledger.deliver("event") { true })
    }

    @Test
    fun clockRollbackAllowsNewDelivery() {
        var now = 100L
        val ledger = DeliveryLedger(clock = { now })
        ledger.deliver("event") { true }
        now--
        assertEquals(DeliveryLedger.Result.POSTED, ledger.deliver("event") { true })
    }

    @Test
    fun boundedSuccessCacheDoesNotEvictInFlight() {
        val ledger = DeliveryLedger(maxEntries = 1)
        ledger.deliver("pending") {
            ledger.deliver("other") { true }
            ledger.deliver("another") { true }
            assertEquals(DeliveryLedger.Result.IN_FLIGHT, ledger.deliver("pending") { error("duplicate") })
            true
        }
        assertEquals(1, ledger.size())
    }

    @Test
    fun concurrentChainSeesInFlightThenSuccess() {
        val ledger = DeliveryLedger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit<DeliveryLedger.Result> {
                ledger.deliver("event") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(DeliveryLedger.Result.IN_FLIGHT, ledger.deliver("event") { error("duplicate") })
            release.countDown()
            assertEquals(DeliveryLedger.Result.POSTED, task.get(5, TimeUnit.SECONDS))
            assertEquals(DeliveryLedger.Result.ALREADY_POSTED, ledger.deliver("event") { error("duplicate") })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun differentTextWithSameJavaHashIsNotTheSameEvent() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val record = ExpressRecord(sourcePackage = "test", trackingNumber = "77300000001", rawText = "Aa")
        assertNotEquals(ExpressDeliveryLedger.keyOf(record), ExpressDeliveryLedger.keyOf(record.copy(rawText = "BB")))
    }
}
