package io.github.YGHFv.ReaPressExtend.backup

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class BackupTaskGateTest {
    @Test
    fun reservesBeforeQueueingAndCompletesOnlyAfterWork() {
        val gate = BackupTaskGate()
        val queued = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val executor = Executor { queued.add(it) }
        assertTrue(gate.submit(executor, { events.add("write") }, { events.add("finish") }))
        repeat(10) { assertFalse(gate.submit(executor, { error("duplicate") }, { error("duplicate finish") })) }
        assertEquals(1, queued.size)
        assertTrue(events.isEmpty())
        queued.single().run()
        assertEquals(listOf("write", "finish"), events)
        assertTrue(gate.acquire())
        gate.release()
    }

    @Test
    fun thrownWorkStillReleasesAndFinishesOnce() {
        val gate = BackupTaskGate()
        val queued = mutableListOf<Runnable>()
        var finishes = 0
        gate.submit(Executor { queued.add(it) }, { error("synthetic") }, { finishes++ })
        assertThrows(IllegalStateException::class.java) { queued.single().run() }
        assertEquals(1, finishes)
        assertTrue(gate.acquire())
    }

    @Test
    fun rejectedSubmissionDoesNotLeakReservationOrCallCompletion() {
        val gate = BackupTaskGate()
        assertThrows(RejectedExecutionException::class.java) {
            gate.submit(Executor { throw RejectedExecutionException() }, {}, { error("not started") })
        }
        assertTrue(gate.acquire())
    }
}
