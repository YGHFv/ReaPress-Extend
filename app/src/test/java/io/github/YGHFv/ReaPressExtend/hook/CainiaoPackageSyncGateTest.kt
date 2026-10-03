package io.github.YGHFv.ReaPressExtend.hook

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.testing.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

class CainiaoPackageSyncGateTest {
    private val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    @Test fun malformedRequestNeverQueuesWork() = fixture { f ->
        f.request("-".repeat(36))
        assertTrue(f.tasks.isEmpty())
    }
    @Test fun duplicateQueuedRequestsDoNotCreateMoreWork() = fixture { f ->
        f.request(id)
        f.request(id)
        assertEquals(1, f.tasks.size)
        assertTrue(busy())
    }
    @Test fun unsupportedVersionDoesNotStartNetworkAndReleasesGate() = fixture { f ->
        f.version.versionName = "new-unsupported"
        f.request(id)
        f.tasks.single().run()
        assertFalse(busy())
        assertEquals(0L, f.storage.preferences("reapress_package_sync").getLong("next_attempt", 0))
    }
    @Test fun persistedCooldownDoesNotLoadHostNetworkClasses() = fixture { f ->
        f.storage.preferences("reapress_package_sync").edit().putLong("next_attempt", System.currentTimeMillis() + 600_000).commit()
        f.request(id)
        f.tasks.single().run()
        assertEquals(0, f.loader.loads)
        assertFalse(busy())
    }
    @Test fun repeatedCompletedIdReplaysResultWithoutQueueing() = fixture { f ->
        f.version.versionName = "unsupported"
        f.request(id)
        f.tasks.single().run()
        f.request(id)
        assertEquals(1, f.tasks.size)
    }
    @Test fun riskRemainsInMemoryWhenPersistenceFails() = fixture { f ->
        f.storage.preferences("reapress_package_sync").commitSucceeds = false
        f.request(id, System.currentTimeMillis() + 600_000)
        f.tasks.single().run()
        assertEquals(0, f.loader.loads)
        assertTrue(CainiaoTraceApi.riskBlocked())
        assertFalse(busy())
    }
    @Test fun failedAttemptCommitDoesNotLoadHostOrLeaveBusy() = fixture { f ->
        f.storage.preferences("reapress_package_sync").commitSucceeds = false
        f.request(id)
        f.tasks.single().run()
        assertEquals(0, f.loader.loads)
        assertFalse(busy())
    }
    @Test fun rejectedExecutorReleasesGate() = fixture { f ->
        doThrow(RejectedExecutionException()).`when`(f.executor).execute(any(Runnable::class.java))
        f.request(id)
        assertFalse(busy())
    }

    private fun busy(): Boolean = (CainiaoPackageSync::class.java.getDeclaredField("busy").apply { isAccessible = true }
        .get(CainiaoPackageSync) as AtomicBoolean).get()

    private fun fixture(block: (Fixture) -> Unit) = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(CainiaoPackageSync, "completed", null)
            state.set(CainiaoPackageSync, "blockedUntil", 0L)
            state.set(CainiaoTraceApi, "riskBlockedUntil", 0L)
            val f = Fixture()
            state.set(CainiaoPackageSync, "executor", f.executor)
            val occupied = CainiaoPackageSync::class.java.getDeclaredField("busy").apply { isAccessible = true }
                .get(CainiaoPackageSync) as AtomicBoolean
            val previous = occupied.getAndSet(false)
            try { block(f) } finally { occupied.set(previous) }
        }
    }

    private class RejectingLoader : ClassLoader(null) {
        var loads = 0
        override fun loadClass(name: String): Class<*> { loads++; throw AssertionError("Must not load host classes") }
    }
    private class Fixture {
        val storage = PreferencesContext()
        val executor = mock(ExecutorService::class.java)
        val tasks = mutableListOf<Runnable>()
        val loader = RejectingLoader()
        val version = mock(PackageInfo::class.java).apply { versionName = CainiaoPackageSyncClient.SUPPORTED_VERSION }
        init {
            val manager = mock(PackageManager::class.java)
            `when`(storage.context.packageManager).thenReturn(manager)
            @Suppress("DEPRECATION")
            `when`(manager.getPackageInfo(ExpressRelay.HOST_PACKAGE, 0)).thenReturn(version)
            doAnswer { tasks += it.getArgument<Runnable>(0); null }.`when`(executor).execute(any(Runnable::class.java))
        }
        fun request(id: String, risk: Long = 0L) = CainiaoPackageSync.request(storage.context, loader, id, risk)
    }
}
