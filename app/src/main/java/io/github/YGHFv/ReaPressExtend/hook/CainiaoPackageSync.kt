package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.YGHFv.ReaPressExtend.core.PackageSyncSession
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object CainiaoPackageSync {
    private val busy = AtomicBoolean()
    private var executor = Executors.newSingleThreadExecutor { Thread(it, "reapress-package-sync").apply { isDaemon = true } }
    private const val PREFS = "reapress_package_sync"
    private const val NEXT = "next_attempt"
    private const val RISK = "risk_until"
    private const val INTERVAL = 5 * 60_000L
    private const val FAILURE_INTERVAL = 10 * 60_000L
    private const val RISK_INTERVAL = 60 * 60_000L
    @Volatile private var blockedUntil = 0L
    private data class Completed(val id: String, val status: String, val retryAt: Long)
    @Volatile private var completed: Completed? = null

    fun request(context: Context, loader: ClassLoader, id: String, callerRiskUntil: Long) {
        if (!id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) return
        completed?.takeIf { it.id == id }?.let { report(context, id, it.status, it.retryAt); return }
        if (!busy.compareAndSet(false, true)) { report(context, id, "busy"); return }
        try {
            executor.execute {
                var status = "failed"
                var retryAt = 0L
                try {
                    completed?.takeIf { it.id == id }?.let {
                        status = it.status
                        retryAt = it.retryAt
                        return@execute
                    }
                    @Suppress("DEPRECATION")
                    val version = context.packageManager.getPackageInfo(ExpressRelay.HOST_PACKAGE, 0).versionName
                    if (version != CainiaoPackageSyncClient.SUPPORTED_VERSION) throw CainiaoPackageSyncClient.Failure("unsupported")
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    val risk = maxOf(prefs.getLong(RISK, 0), callerRiskUntil, CainiaoTraceApi.riskBlockedUntil)
                    if (risk > now) { retryAt = risk; throw CainiaoPackageSyncClient.Failure("risk") }
                    retryAt = maxOf(prefs.getLong(NEXT, 0), blockedUntil)
                    if (retryAt > now) throw CainiaoPackageSyncClient.Failure("cooling")
                    retryAt = now + INTERVAL
                    check(prefs.edit().putLong(NEXT, retryAt).commit())
                    blockedUntil = retryAt
                    val client = CainiaoPackageSyncClient(
                        load = { Class.forName(it, true, loader) },
                        allowed = { !CainiaoTraceApi.riskBlocked() && System.currentTimeMillis() >= callerRiskUntil },
                        dispatch = { task -> check(Handler(Looper.getMainLooper()).post { task() }) },
                        pace = { Thread.sleep(2_500L) },
                    )
                    for (attempt in 0 until 5) {
                        try { client.snapshot(); break } catch (failure: CainiaoPackageSyncClient.Failure) {
                            if (failure.kind !in setOf("not_ready", "login_required") || attempt == 4) throw failure
                            Thread.sleep(2_000L)
                        }
                    }
                    val account = client.snapshot().account
                    val observedAt = System.currentTimeMillis()
                    PackageSyncSession(client).run()
                    if (client.snapshot().account != account) throw CainiaoPackageSyncClient.Failure("session_changed")
                    check(CainiaoPackageHook.collectSyncedSnapshot(loader, observedAt) { client.snapshot().account == account })
                    status = "synced"
                } catch (error: Throwable) {
                    status = (error as? CainiaoPackageSyncClient.Failure)?.kind ?: "failed"
                    if (status !in setOf("cooling", "unsupported")) {
                        retryAt = maxOf(retryAt, System.currentTimeMillis() + if (status == "risk") RISK_INTERVAL else FAILURE_INTERVAL)
                        blockedUntil = maxOf(blockedUntil, retryAt)
                        if (status == "risk") CainiaoTraceApi.restoreRisk(retryAt)
                        runCatching {
                            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(NEXT, retryAt)
                            if (status == "risk") {
                                edit.putLong(RISK, retryAt)
                            }
                            check(edit.commit())
                        }
                    }
                } finally {
                    completed = Completed(id, status, retryAt)
                    try { report(context, id, status, retryAt) } finally { busy.set(false) }
                }
            }
        } catch (_: Throwable) {
            busy.set(false)
            report(context, id, "failed")
        }
    }

    private fun report(context: Context, id: String, status: String, retryAt: Long = 0L) {
        runCatching {
            AuthenticatedRelaySender.send(context, Intent(ExpressRelay.ACTION_PACKAGE_SYNC_REPORT)
                .putExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_ID, id)
                .putExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS, status)
                .putExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT, retryAt.coerceAtLeast(0L)))
        }
    }
}
