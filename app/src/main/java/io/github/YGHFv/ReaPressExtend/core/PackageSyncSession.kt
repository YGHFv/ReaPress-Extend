package io.github.YGHFv.ReaPressExtend.core

/** One bounded incremental sync. Opaque account/cursor values never belong in diagnostics. */
internal class PackageSyncSession(private val port: Port, private val maxPages: Int = 3) {
    init { require(maxPages > 0) }
    data class Snapshot(val account: String, val version: String, val sequence: String)
    interface Port {
        fun snapshot(): Snapshot
        fun remoteSequence(snapshot: Snapshot): String
        fun pullAndApply(snapshot: Snapshot): String
    }

    fun run(): Int {
        val initial = port.snapshot()
        check(initial.account.isNotBlank() && initial.version.isNotBlank() && initial.sequence.isNotBlank())
        val target = port.remoteSequence(initial)
        check(target.isNotBlank())
        var pages = 0
        while (true) {
            val current = port.snapshot()
            check(current.account == initial.account && current.version == initial.version) { "session_changed" }
            if (current.sequence == target) return pages
            check(pages < maxPages) { "page_limit" }
            val next = port.pullAndApply(current)
            check(next.isNotBlank() && next != current.sequence) { "no_progress" }
            val applied = port.snapshot()
            check(applied.account == initial.account && applied.version == initial.version && applied.sequence == next) { "apply_unconfirmed" }
            pages++
        }
    }
}
