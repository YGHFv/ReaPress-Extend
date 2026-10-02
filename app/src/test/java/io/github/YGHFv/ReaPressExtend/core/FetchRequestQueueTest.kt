package io.github.YGHFv.ReaPressExtend.core

import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchRequestQueueTest {
    @Test
    fun `空结果进入失败冷却但不会永久占用进行中状态`() {
        val fixture = Fixture()
        assertTrue(fixture.request(fetch = { null }))
        fixture.executor.runNext()

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        assertEquals(listOf(false), fixture.completions)
        assertFalse(fixture.request(recheck = true))
        fixture.now += 1_000L
        assertTrue(fixture.request())
        fixture.executor.runNext()
        assertEquals(listOf("payload"), fixture.delivered)
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
    }

    @Test
    fun `请求异常释放占位并允许冷却后重试`() {
        val fixture = Fixture()
        val failure = IllegalStateException("synthetic fetch failure")
        fixture.request(fetch = { throw failure })
        fixture.executor.runNext()

        assertEquals(listOf(failure), fixture.errors)
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        fixture.now += 1_000L
        assertTrue(fixture.request())
        fixture.executor.runNext()
        assertEquals(listOf(false, true), fixture.completions)
    }

    @Test
    fun `投递抛异常时不能登记为成功`() {
        val fixture = Fixture()
        fixture.request(deliver = { error("synthetic delivery failure") })
        fixture.executor.runNext()

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        assertEquals(listOf(false), fixture.completions)
        fixture.now += 1_000L
        assertTrue(fixture.request())
        fixture.executor.runNext()
        assertEquals(listOf("payload"), fixture.delivered)
    }

    @Test
    fun `成功去重有完成回执且显式复查仍可发起`() {
        val fixture = Fixture()
        fixture.request()
        fixture.executor.runNext()
        assertFalse(fixture.request())
        assertEquals(0, fixture.executor.size)
        assertEquals(listOf(true, true), fixture.completions)

        assertTrue(fixture.request(recheck = true))
        fixture.executor.runNext()
        assertEquals(listOf("payload", "payload"), fixture.delivered)
    }

    @Test
    fun `重复排队不提前声明成功也不提交第二个任务`() {
        val fixture = Fixture()
        assertTrue(fixture.request())
        assertFalse(fixture.request(recheck = true))
        assertEquals(1, fixture.executor.size)
        assertEquals(listOf(false), fixture.completions)

        fixture.executor.runNext()
        assertEquals(listOf(false, true), fixture.completions)
        assertEquals(listOf("payload"), fixture.delivered)
    }

    @Test
    fun `入队前风控不占位也不建立失败冷却`() {
        val fixture = Fixture()
        fixture.blocked = true

        assertFalse(fixture.request())
        assertEquals(0, fixture.executor.size)
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
        assertEquals(listOf(false), fixture.completions)
    }

    @Test
    fun `排队期间触发风控则释放未执行任务`() {
        val fixture = Fixture()
        fixture.request(fetch = { error("must not fetch while blocked") })
        fixture.blocked = true
        fixture.executor.runNext()

        assertTrue(fixture.errors.isEmpty())
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
        fixture.blocked = false
        assertTrue(fixture.request())
        fixture.executor.runNext()
        assertEquals(listOf(false, true), fixture.completions)
    }

    @Test
    fun `等待间隔期间触发风控也不能发送请求`() {
        val fixture = Fixture()
        fixture.onPace = { fixture.blocked = true }
        fixture.request(fetch = { error("must not fetch after pacing risk") })
        fixture.executor.runNext()

        assertTrue(fixture.errors.isEmpty())
        assertEquals(listOf(false), fixture.completions)
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
    }

    @Test
    fun `执行器拒绝提交后立即释放占位`() {
        val fixture = Fixture()
        fixture.executor.reject = true

        assertFalse(fixture.request())
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
        assertTrue(fixture.errors.single() is RejectedExecutionException)
        fixture.executor.reject = false
        assertTrue(fixture.request())
        fixture.executor.runNext()
        assertEquals(listOf(false, true), fixture.completions)
    }

    @Test
    fun `间隔等待被中断时释放占位并保留线程中断标记`() {
        val fixture = Fixture()
        fixture.onPace = { throw InterruptedException("synthetic interruption") }
        try {
            fixture.request(fetch = { error("must not fetch after interruption") })
            fixture.executor.runNext()

            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
            assertEquals(listOf(false), fixture.completions)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `请求被取消时清理状态并通知失败`() {
        val fixture = Fixture()
        fixture.request(fetch = { throw CancellationException("synthetic cancellation") })
        fixture.executor.runNext()

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        assertTrue(fixture.errors.single() is CancellationException)
        assertEquals(listOf(false), fixture.completions)
    }

    @Test
    fun `冷却从失败完成时刻而不是排队时刻开始`() {
        val fixture = Fixture()
        fixture.request(fetch = { fixture.now += 5_000L; null })
        fixture.executor.runNext()
        fixture.now += 999L
        assertFalse(fixture.request())
        fixture.now++
        assertTrue(fixture.request())
    }

    @Test
    fun `完成回调和诊断异常不破坏已完成的请求状态`() {
        val fixture = Fixture()
        val queue = FetchRequestQueue<String>(
            executor = fixture.executor,
            ledger = fixture.ledger,
            clock = { fixture.now },
            pace = {},
            isBlocked = { false },
            onError = { error("synthetic log failure") },
        )
        queue.request(
            key = "parcel",
            fetch = { "payload" },
            deliver = {},
            onComplete = { error("synthetic callback failure") },
        )
        fixture.executor.runNext()

        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
    }

    private class Fixture {
        var now = 10_000L
        var blocked = false
        var onPace: () -> Unit = {}
        val executor = QueuedExecutor()
        val ledger = FetchRequestLedger(cooldownMillis = 1_000L)
        val delivered = mutableListOf<String>()
        val completions = mutableListOf<Boolean>()
        val errors = mutableListOf<Throwable>()
        private val queue = FetchRequestQueue<String>(
            executor = executor,
            ledger = ledger,
            clock = { now },
            pace = { onPace() },
            isBlocked = { blocked },
            onError = { errors.add(it) },
        )

        fun request(
            recheck: Boolean = false,
            fetch: () -> String? = { "payload" },
            deliver: (String) -> Unit = { delivered.add(it) },
        ): Boolean = queue.request(
            key = "parcel",
            recheck = recheck,
            fetch = fetch,
            deliver = deliver,
            onComplete = { completions.add(it) },
        )
    }

    private class QueuedExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()
        var reject = false
        val size: Int get() = pending.size

        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("synthetic rejection")
            pending.addLast(command)
        }

        fun runNext() = pending.removeFirst().run()
    }
}
