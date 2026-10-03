package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.*
import org.junit.Test

class PackageSyncSessionTest {
    private class Port : PackageSyncSession.Port {
        var state = PackageSyncSession.Snapshot("account", "1.3", "1")
        var target = "3"
        var pulls = 0
        var apply = true
        var next: () -> String = { (state.sequence.toInt() + 1).toString() }
        override fun snapshot() = state
        override fun remoteSequence(snapshot: PackageSyncSession.Snapshot) = target
        override fun pullAndApply(snapshot: PackageSyncSession.Snapshot): String {
            pulls++
            return next().also { if (apply) state = state.copy(sequence = it) }
        }
    }

    @Test fun unchangedServerCursorDoesNotPullData() {
        val p = Port().apply { target = "1" }
        assertEquals(0, PackageSyncSession(p).run())
        assertEquals(0, p.pulls)
    }
    @Test fun incrementalPagesStopAtServerCursor() {
        val p = Port()
        assertEquals(2, PackageSyncSession(p).run())
        assertEquals("3", p.state.sequence)
    }
    @Test fun unconfirmedDatabaseUpdateCannotSucceed() {
        val p = Port().apply { apply = false }
        assertThrows(IllegalStateException::class.java) { PackageSyncSession(p).run() }
        assertEquals(1, p.pulls)
    }
    @Test fun noProgressDoesNotLoop() {
        val p = Port().apply { next = { "1" } }
        assertThrows(IllegalStateException::class.java) { PackageSyncSession(p).run() }
        assertEquals(1, p.pulls)
    }
    @Test fun pageBudgetIsHardBound() {
        val p = Port().apply { target = "99" }
        assertThrows(IllegalStateException::class.java) { PackageSyncSession(p).run() }
        assertEquals(3, p.pulls)
    }
    @Test fun switchedAccountAbortsWithoutNextRequest() {
        val p = Port().apply { next = { state = state.copy(account = "other"); "2" } }
        assertThrows(IllegalStateException::class.java) { PackageSyncSession(p).run() }
        assertEquals(1, p.pulls)
    }
    @Test fun schemaChangeAborts() {
        val p = Port().apply { next = { state = state.copy(version = "2"); "2" } }
        assertThrows(IllegalStateException::class.java) { PackageSyncSession(p).run() }
    }
    @Test fun failurePropagatesWithoutAutomaticRetry() {
        val p = Port().apply { next = { error("synthetic") } }
        assertThrows(IllegalStateException::class.java) { PackageSyncSession(p).run() }
        assertEquals(1, p.pulls)
    }
}
