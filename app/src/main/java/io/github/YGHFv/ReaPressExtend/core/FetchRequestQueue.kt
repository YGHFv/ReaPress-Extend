package io.github.YGHFv.ReaPressExtend.core

import java.util.concurrent.Executor

internal class FetchRequestQueue<Payload : Any>(
    private val executor: Executor,
    private val ledger: FetchRequestLedger,
    private val clock: () -> Long,
    private val pace: () -> Unit,
    private val isBlocked: () -> Boolean,
    private val onError: (Throwable) -> Unit,
) {
    fun request(
        key: String,
        recheck: Boolean = false,
        fetch: () -> Payload?,
        deliver: (Payload) -> Unit,
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        if (isBlocked()) {
            complete(onComplete, false)
            return false
        }
        val admission = ledger.begin(key, clock(), recheck)
        if (admission != FetchRequestLedger.Admission.ACCEPTED) {
            complete(onComplete, admission == FetchRequestLedger.Admission.SUCCEEDED)
            return false
        }
        return try {
            executor.execute { execute(key, fetch, deliver, onComplete) }
            true
        } catch (error: Throwable) {
            ledger.release(key)
            report(error)
            complete(onComplete, false)
            false
        }
    }

    private fun execute(
        key: String,
        fetch: () -> Payload?,
        deliver: (Payload) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        var attempted = false
        var success = false
        try {
            if (isBlocked()) return
            pace()
            if (isBlocked()) return
            attempted = true
            val payload = fetch() ?: return
            deliver(payload)
            success = true
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            report(error)
        } finally {
            if (attempted) {
                ledger.finish(key, clock(), success)
            } else {
                ledger.release(key)
            }
            complete(onComplete, success)
        }
    }

    private fun complete(callback: (Boolean) -> Unit, success: Boolean) {
        runCatching { callback(success) }.onFailure(::report)
    }

    private fun report(error: Throwable) {
        runCatching { onError(error) }
    }
}
