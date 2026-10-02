package io.github.YGHFv.ReaPressExtend.core

internal class FetchRequestLedger(
    private val cooldownMillis: Long,
    private val successLimit: Int = Int.MAX_VALUE,
) {
    enum class Admission { ACCEPTED, IN_FLIGHT, SUCCEEDED, COOLING }

    data class Snapshot(val inFlight: Int, val succeeded: Int, val cooling: Int)

    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val succeeded = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    init {
        require(cooldownMillis >= 0L)
        require(successLimit > 0)
    }

    fun isEligible(key: String, now: Long, recheck: Boolean = false): Boolean =
        synchronized(lock) { admission(key, now, recheck) == Admission.ACCEPTED }

    fun lastFailureAt(key: String): Long? = synchronized(lock) { failedAt[key] }

    fun begin(key: String, now: Long, recheck: Boolean = false): Admission = synchronized(lock) {
        val result = admission(key, now, recheck)
        if (result == Admission.ACCEPTED) inFlight.add(key)
        result
    }

    fun finish(key: String, now: Long, success: Boolean) {
        synchronized(lock) {
            if (key !in inFlight) return
            if (success) {
                if (key !in succeeded && succeeded.size >= successLimit) succeeded.clear()
                succeeded.add(key)
                failedAt.remove(key)
            } else {
                succeeded.remove(key)
                failedAt[key] = now
            }
            inFlight.remove(key)
        }
    }

    fun release(key: String) {
        synchronized(lock) { inFlight.remove(key) }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(inFlight.size, succeeded.size, failedAt.size)
    }

    private fun admission(key: String, now: Long, recheck: Boolean): Admission = when {
        key in inFlight -> Admission.IN_FLIGHT
        !recheck && key in succeeded -> Admission.SUCCEEDED
        failedAt[key]?.let { now - it < cooldownMillis } == true -> Admission.COOLING
        else -> Admission.ACCEPTED
    }
}
