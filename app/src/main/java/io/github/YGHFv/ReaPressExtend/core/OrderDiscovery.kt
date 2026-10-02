package io.github.YGHFv.ReaPressExtend.core

import java.util.concurrent.CancellationException

internal class OrderDiscovery(
    private val ledger: FetchRequestLedger,
    private val clock: () -> Long,
    private val pace: () -> Unit,
    private val isBlocked: () -> Boolean,
    private val onError: (Throwable) -> Unit,
) {
    data class Outcome(val selected: Int, val attempted: Int, val found: Int, val blocked: Boolean)

    fun discover(
        orders: List<TaobaoOrder>,
        limit: Int,
        fetchParcel: (TaobaoOrder) -> SsrParcel?,
        onParcel: (TaobaoOrder, SsrParcel, (Boolean) -> Unit) -> Unit,
    ): Outcome {
        val targets = orders.asSequence()
            .filter { it.isInTransit }
            .distinctBy { it.orderId }
            .filter { ledger.isEligible(it.orderId, clock()) }
            .map { it to ledger.lastFailureAt(it.orderId) }
            .sortedBy { it.second ?: Long.MIN_VALUE }
            .take(limit)
            .map { it.first }
            .toList()
        var attempted = 0
        var found = 0
        var blocked = false
        for (order in targets) {
            var reserved = false
            var handedOff = false
            try {
                if (isBlocked()) {
                    blocked = true
                    break
                }
                if (attempted > 0) pace()
                if (isBlocked()) {
                    blocked = true
                    break
                }
                if (ledger.begin(order.orderId, clock()) != FetchRequestLedger.Admission.ACCEPTED) continue
                reserved = true
                attempted++
                val parcel = fetchParcel(order) ?: continue
                found++
                onParcel(order, parcel) { success ->
                    ledger.finish(order.orderId, clock(), success)
                }
                handedOff = true
            } catch (error: Throwable) {
                if (error is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                if (error is CancellationException) throw error
                runCatching { onError(error) }
            } finally {
                if (reserved && !handedOff) ledger.finish(order.orderId, clock(), false)
            }
        }
        return Outcome(targets.size, attempted, found, blocked)
    }
}
