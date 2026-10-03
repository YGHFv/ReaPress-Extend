package io.github.YGHFv.ReaPressExtend.relay

import android.content.Intent
import android.os.Handler
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.testing.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class HostRefreshRequesterTest {
    @Test fun repeatedRequestsShareOneBoundedColdStartSequence() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        val id = active()
        repeat(4) { HostRefreshRequester.request(f.storage.context) }
        assertEquals(listOf(0L, 3000L, 8000L, 75000L), f.tasks.map { it.first })
        assertEquals(id, active())
    }
    @Test fun wrongOrLateRequestIdCannotClaimSuccess() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        HostRefreshRequester.onReport(f.storage.context, report("other", "synced"))
        assertNotNull(active())
        assertFalse(HostRefreshRequester.describe().contains("完成"))
    }
    @Test fun busyDuplicateDoesNotFinishOriginalRequest() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        val id = active()!!
        HostRefreshRequester.onReport(f.storage.context, report(id, "busy"))
        assertEquals(id, active())
    }
    @Test fun confirmedReportFinishesAndRetainsCooldown() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        HostRefreshRequester.onReport(f.storage.context, report(active()!!, "synced"))
        assertNull(active())
        assertTrue(HostRefreshRequester.describe().contains("完成"))
        HostRefreshRequester.request(f.storage.context)
        assertEquals(4, f.tasks.size)
    }
    @Test fun timeoutIsNotReportedAsNetworkSuccess() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        f.tasks.last().second.run()
        assertNull(active())
        assertTrue(HostRefreshRequester.describe().contains("超时"))
    }
    @Test fun persistedRiskPreventsSchedulingAndWake() = fixture { f ->
        f.storage.preferences(ModuleTraceFetcher.PREFS).edit().putLong(ModuleTraceFetcher.KEY_RISK_UNTIL, System.currentTimeMillis() + 600_000).commit()
        HostRefreshRequester.request(f.storage.context)
        assertTrue(f.tasks.isEmpty())
        assertTrue(HostRefreshRequester.describe().contains("退避"))
    }
    @Test fun failedCommitCannotStartNetworkRequest() = fixture { f ->
        f.storage.preferences("reapress_package_sync").commitSucceeds = false
        HostRefreshRequester.request(f.storage.context)
        assertTrue(f.tasks.isEmpty())
        assertNull(active())
    }
    @Test fun offModeDoesNotRequestHostSync() = fixture { f ->
        ExpressSettings.write(f.storage.context, ExpressSettingsSnapshot(mode = ExpressSettingsKeys.MODE_OFF))
        HostRefreshRequester.request(f.storage.context)
        assertTrue(f.tasks.isEmpty())
    }
    @Test fun riskReportAlsoProtectsModuleTraceRequests() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        val until = System.currentTimeMillis() + 3600_000
        val report = report(active()!!, "risk")
        `when`(report.getLongExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT, 0L)).thenReturn(until)
        HostRefreshRequester.onReport(f.storage.context, report)
        assertTrue(CainiaoTraceApi.riskBlocked())
        assertEquals(until, f.storage.preferences(ModuleTraceFetcher.PREFS).getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0))
    }
    @Test fun reportPayloadSchemaRejectsWrongTypes() {
        val payload = mapOf(ExpressRelay.EXTRA_PACKAGE_SYNC_ID to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS to "synced", ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT to 1L)
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_PACKAGE_SYNC_REPORT, payload))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_PACKAGE_SYNC_REPORT, payload + (ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT to "tomorrow")))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_PACKAGE_SYNC_REPORT, payload + (ExpressRelay.EXTRA_PACKAGE_SYNC_ID to "a".repeat(36))))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_PACKAGE_SYNC_REPORT, payload + (ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS to "arbitrary")))
    }
    @Test fun failedRiskCommitRetainsInMemoryBlockAndReportsWarning() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        f.storage.preferences(ModuleTraceFetcher.PREFS).commitSucceeds = false
        val incoming = report(active()!!, "risk")
        val until = System.currentTimeMillis() + 3600_000
        `when`(incoming.getLongExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT, 0L)).thenReturn(until)
        HostRefreshRequester.onReport(f.storage.context, incoming)
        assertTrue(CainiaoTraceApi.riskBlocked())
        assertTrue(HostRefreshRequester.describe().contains("未能保存"))
    }
    @Test fun unauthenticatedSyncReportIsRejectedAtRelayIngress() = fixture { f ->
        HostRefreshRequester.request(f.storage.context)
        val id = active()!!
        val incoming = report(id, "synced")
        `when`(incoming.action).thenReturn(ExpressRelay.ACTION_PACKAGE_SYNC_REPORT)
        ExpressRelayReceiver().onReceive(f.storage.context, incoming)
        assertEquals(id, active())
        assertFalse(HostRefreshRequester.describe().contains("完成"))
    }

    private fun report(id: String, status: String) = mock(Intent::class.java).also {
        `when`(it.getStringExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_ID)).thenReturn(id)
        `when`(it.getStringExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS)).thenReturn(status)
    }
    private fun active(): String? = HostRefreshRequester::class.java.getDeclaredField("activeId").apply { isAccessible = true }.get(HostRefreshRequester) as? String

    private fun fixture(block: (Fixture) -> Unit) = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            state.set(CainiaoTraceApi, "riskBlockedUntil", 0L)
            state.set(HostRefreshRequester, "activeId", null)
            state.set(HostRefreshRequester, "status", "菜鸟取件码：尚未联网同步")
            state.set(HostRefreshRequester, "statusAt", 0L)
            val handler = mock(Handler::class.java)
            state.set(HostRefreshRequester, "handler", handler)
            val f = Fixture()
            `when`(handler.postDelayed(any(Runnable::class.java), anyLong())).thenAnswer {
                f.tasks += it.getArgument<Long>(1) to it.getArgument<Runnable>(0)
                true
            }
            ExpressSettings.write(f.storage.context, ExpressSettingsSnapshot(mode = ExpressSettingsKeys.MODE_PASSTHROUGH))
            block(f)
        }
    }
    private class Fixture {
        val storage = PreferencesContext()
        val tasks = mutableListOf<Pair<Long, Runnable>>()
    }
}
