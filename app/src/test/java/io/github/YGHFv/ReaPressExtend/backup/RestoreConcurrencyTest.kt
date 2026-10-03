package io.github.YGHFv.ReaPressExtend.backup

import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.core.BackupBundle
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationRuleStore
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieStore
import io.github.YGHFv.ReaPressExtend.relay.WatchState
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import io.github.YGHFv.ReaPressExtend.ui.ExpressUiPrefs
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreConcurrencyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `恢复设置期间局部更新等待完整写回再合并`() = withAndroidTestRuntime {
        isolated { storage ->
            restoreWithWriter(storage, ExpressSettingsKeys.LOCAL_PREFS, mapOf(ExpressSettingsKeys.KEY_SOURCE_CAINIAO to false)) {
                assertTrue(ExpressSettings.update(storage.context) { it.copy(sourceSms = false) })
            }
            assertFalse(ExpressSettings.read(storage.context).sourceCainiao)
            assertFalse(ExpressSettings.read(storage.context).sourceSms)
        }
    }

    @Test
    fun `恢复Cookie和刷新内存期间新登录态写入等待后保留`() = withAndroidTestRuntime {
        isolated { storage ->
            TraceCookieCache.attach(storage.context)
            val now = System.currentTimeMillis()
            restoreWithWriter(storage, TraceCookieStore.PREFS, mapOf("cookie" to "restored", "ua" to "restored-ua", "at" to now)) {
                TraceCookieCache.put("newer-login", "newer-ua")
            }
            assertEquals("newer-login", TraceCookieCache.get())
            assertEquals("newer-login", TraceCookieStore.read(storage.context)?.cookie)
            assertEquals("newer-ua", CainiaoTraceApi.preferredUa)
        }
    }

    @Test
    fun `恢复期间规则编辑不能读到半份快照`() = withAndroidTestRuntime {
        isolated { storage ->
            restoreWithWriter(storage, ExpressStationRuleStore.PREFS, mapOf("renames" to "{\"original\":\"restored\"}")) {
                ExpressStationRuleStore.setRename(storage.context, listOf("incoming"), "new rule")
            }
            val rules = ExpressStationRuleStore.load(storage.context)
            assertEquals("restored", rules.renames["original"])
            assertEquals("new rule", rules.renames["incoming"])
        }
    }

    @Test
    fun `恢复通知记录期间新审计等待之后再追加`() = withAndroidTestRuntime {
        isolated { storage ->
            val record = ExpressRecord(sourcePackage = "synthetic", rawText = "synthetic parcel", trackingNumber = "TEST001")
            ExpressNotificationLog.record(storage.context, record, false)
            val restored = storage.preferences(ExpressNotificationLog.PREFS).all
            restoreWithWriter(storage, ExpressNotificationLog.PREFS, restored) {
                ExpressNotificationLog.record(storage.context, record.copy(trackingNumber = "TEST002"), true)
            }
            assertEquals(2, ExpressNotificationLog.snapshot(storage.context).size)
        }
    }

    @Test
    fun `恢复UI偏好期间开关写入在恢复后生效`() = withAndroidTestRuntime {
        isolated { storage ->
            val prefs = ExpressUiPrefs.of(storage.context)
            restoreWithWriter(storage, ExpressUiPrefs.PREFS, mapOf("themeMode" to ExpressUiPrefs.THEME_DARK)) {
                prefs.hideFromRecents = true
            }
            assertEquals(ExpressUiPrefs.THEME_DARK, prefs.themeMode)
            assertTrue(prefs.hideFromRecents)
        }
    }

    @Test
    fun `恢复轮查状态期间追加已查单号保留恢复的集合`() = withAndroidTestRuntime {
        isolated { storage ->
            restoreWithWriter(storage, WatchState.PREFS, mapOf("round_done" to "RESTORED")) {
                WatchState.markRoundDone(storage.context, "INCOMING")
            }
            assertEquals(setOf("RESTORED", "INCOMING"), WatchState.roundDone(storage.context))
        }
    }

    @Test
    fun `并发局部修改设置不会互相覆盖`() = withAndroidTestRuntime {
        isolated { storage ->
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            interleave(reached, release,
                first = {
                    ExpressSettings.update(storage.context) {
                        reached.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        it.copy(sourceCainiao = false)
                    }
                },
                second = { assertTrue(ExpressSettings.update(storage.context) { it.copy(sourceSms = false) }) },
            )
            assertFalse(ExpressSettings.read(storage.context).sourceCainiao)
            assertFalse(ExpressSettings.read(storage.context).sourceSms)
        }
    }

    private fun restoreWithWriter(storage: PreferencesContext, name: String, values: Map<String, Any?>, writer: () -> Unit) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        storage.preferences(name).beforeWrite = {
            if (first.compareAndSet(true, false)) {
                reached.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val backup = BackupBundle.encode(BackupBundle.Payload(
            version = BackupBundle.FORMAT_VERSION, exportedAt = 10_000L, appVersion = "test", prefs = mapOf(name to values),
        ))
        interleave(reached, release,
            first = {
                val outcome = ExpressBackup.restore(storage.context, backup, 10_000L)
                assertTrue(outcome.message, outcome.ok)
            },
            second = writer,
        )
    }

    @Suppress("DEPRECATION")
    private fun interleave(reached: CountDownLatch, release: CountDownLatch, first: () -> Unit, second: () -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        val contender = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        try {
            val firstJob = workers.submit { withAndroidTestRuntime { first() } }
            assertTrue(reached.await(10, TimeUnit.SECONDS))
            val secondJob = workers.submit {
                withAndroidTestRuntime {
                    contender.set(Thread.currentThread())
                    started.countDown()
                    second()
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val threads = ManagementFactory.getThreadMXBean()
            val monitor = System.identityHashCode(ExpressRecordStore)
            var blocked = threads.getThreadInfo(contender.get().id)
            while (blocked?.lockInfo?.identityHashCode != monitor && !secondJob.isDone && System.nanoTime() < deadline) {
                Thread.yield()
                blocked = threads.getThreadInfo(contender.get().id)
            }
            assertEquals(monitor, blocked?.lockInfo?.identityHashCode)
            assertEquals(Thread.State.BLOCKED, blocked?.threadState)
            release.countDown()
            firstJob.get(10, TimeUnit.SECONDS)
            secondJob.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun isolated(block: (PreferencesContext) -> Unit) {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            state.set(TraceCookieCache, "cookie", null)
            state.set(TraceCookieCache, "hostUa", null)
            state.set(TraceCookieCache, "syncedAt", 0L)
            state.set(TraceCookieCache, "store", null)
            state.set(TraceCookieCache, "restored", false)
            state.set(CainiaoTraceApi, "preferredUa", null)
            block(PreferencesContext(temporary.newFolder()))
        }
    }
}
