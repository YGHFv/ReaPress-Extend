package io.github.YGHFv.ReaPressExtend.notification

import io.github.YGHFv.ReaPressExtend.backup.ExpressBackup
import io.github.YGHFv.ReaPressExtend.core.BackupBundle
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.RelayCredential
import io.github.YGHFv.ReaPressExtend.relay.RelayCredentialStore
import io.github.YGHFv.ReaPressExtend.testing.MemoryPreferences
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import java.io.File
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

class ExpressRecordConcurrencyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `并发upsert两个新包裹时都保留`() {
        val storage = PreferencesContext()
        val first = record("SF000000000001")
        val second = record("SF000000000002")
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))

        interleave(pause,
            first = { assertTrue(ExpressRecordStore.upsert(storage.context, first)) },
            second = { assertTrue(ExpressRecordStore.upsert(storage.context, second)) },
        )

        assertEquals(setOf(first.trackingNumber, second.trackingNumber),
            ExpressRecordStore.load(storage.context).map { it.trackingNumber }.toSet())
    }

    @Test
    fun `通知写入与后台富化竞争时两条更新都保留`() {
        val storage = PreferencesContext()
        val first = record("SF000000000001")
        val second = record("SF000000000002")
        assertTrue(ExpressRecordStore.upsert(storage.context, first))
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))

        interleave(pause,
            first = { assertTrue(ExpressRecordStore.upsert(storage.context, second)) },
            second = { assertTrue(ExpressRecordStore.enrich(storage.context, first.copy(goodsName = "合成商品"))) },
        )

        val stored = ExpressRecordStore.load(storage.context)
        assertEquals(2, stored.size)
        assertEquals("合成商品", stored.single { it.trackingNumber == first.trackingNumber }.goodsName)
    }

    @Test
    fun `取件确认和轨迹富化同时发生时互不覆盖`() {
        val storage = PreferencesContext()
        val original = record("SF000000000001")
        assertTrue(ExpressRecordStore.upsert(storage.context, original))
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))
        val pickedAt = original.timestamp + 10_000L

        interleave(pause,
            first = { assertTrue(ExpressRecordStore.setPickedUp(storage.context, original.dedupeKey, pickedAt)) },
            second = { assertTrue(ExpressRecordStore.enrich(storage.context, original.copy(goodsName = "合成商品"))) },
        )

        val stored = ExpressRecordStore.load(storage.context).single()
        assertEquals(pickedAt, stored.pickedUpAt)
        assertEquals("合成商品", stored.goodsName)
    }

    @Test
    fun `清空不会被已经读到旧快照的更新复活`() {
        val storage = PreferencesContext()
        val original = record("SF000000000001")
        assertTrue(ExpressRecordStore.upsert(storage.context, original))
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))

        interleave(pause,
            first = { assertTrue(ExpressRecordStore.upsert(storage.context, record("SF000000000002"))) },
            second = { ExpressRecordStore.clear(storage.context) },
        )

        assertTrue(ExpressRecordStore.load(storage.context).isEmpty())
    }

    @Test
    fun `读取也必须等待进行中的记录事务`() {
        val storage = PreferencesContext()
        val original = record("SF000000000001")
        val pause = Pause()
        val seen = AtomicReference<List<ExpressRecord>>()

        interleave(pause,
            first = {
                ExpressRecordStore.withTransaction {
                    pause.awaitRelease()
                    assertTrue(ExpressRecordStore.upsert(storage.context, original))
                }
            },
            second = { seen.set(ExpressRecordStore.load(storage.context)) },
        )

        assertEquals(original.trackingNumber, seen.get().single().trackingNumber)
    }

    @Test
    fun `调和入口与其他修改使用同一个事务锁`() {
        val storage = PreferencesContext()
        assertTrue(ExpressRecordStore.upsert(storage.context, record("SF000000000001")))
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))

        interleave(pause,
            first = { ExpressRecordStore.reconcile(storage.context) },
            second = { assertTrue(ExpressRecordStore.upsert(storage.context, record("SF000000000002"))) },
        )

        assertEquals(2, ExpressRecordStore.load(storage.context).size)
    }

    @Test
    fun `写入异常后事务锁释放后续写入仍可用`() {
        val storage = PreferencesContext()
        val preferences = storage.preferences(ExpressRecordStore.PREFS)
        preferences.beforeWrite = { error("synthetic write failure") }

        assertFalse(ExpressRecordStore.upsert(storage.context, record("SF000000000001")))
        preferences.beforeWrite = {}
        assertTrue(ExpressRecordStore.upsert(storage.context, record("SF000000000002")))
        assertEquals("SF000000000002", ExpressRecordStore.load(storage.context).single().trackingNumber)
    }

    @Test
    fun `备份导出等待完整记录事务再取快照`() {
        val storage = PreferencesContext(temporary.newFolder())
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))
        val exported = AtomicReference<String>()

        interleave(pause,
            first = { assertTrue(ExpressRecordStore.upsert(storage.context, record("SF000000000001"))) },
            second = { exported.set(ExpressBackup.export(storage.context, 10_000L)) },
        )

        val payload = BackupBundle.decode(exported.get())
        val records = ExpressRecordStore.parse(payload.prefs.getValue(ExpressRecordStore.PREFS)["records"] as String)
        assertEquals("SF000000000001", records.single().trackingNumber)
    }

    @Test
    fun `恢复先保存修改完成后的快照再覆盖而不是让旧写入覆盖恢复结果`() {
        val storage = PreferencesContext(temporary.newFolder())
        val old = record("SF000000000001")
        val pending = record("SF000000000002")
        val restored = record("SF000000000003")
        assertTrue(ExpressRecordStore.upsert(storage.context, old))
        val backup = backupOf(restored)
        val pause = pauseAfterFirstRead(storage.preferences(ExpressRecordStore.PREFS))
        val snapshotName = AtomicReference<String>()

        interleave(pause,
            first = { assertTrue(ExpressRecordStore.upsert(storage.context, pending)) },
            second = {
                val outcome = ExpressBackup.restore(storage.context, backup, 20_000L)
                assertTrue(outcome.message, outcome.ok)
                snapshotName.set(outcome.snapshotName)
            },
        )

        assertEquals(restored.trackingNumber, ExpressRecordStore.load(storage.context).single().trackingNumber)
        val snapshot = BackupBundle.decode(File(storage.context.filesDir, snapshotName.get()).readText())
        val before = ExpressRecordStore.parse(snapshot.prefs.getValue(ExpressRecordStore.PREFS)["records"] as String)
        assertEquals(setOf(old.trackingNumber, pending.trackingNumber), before.map { it.trackingNumber }.toSet())
    }

    @Test
    fun `恢复快照到写回期间到达的通知等待恢复完成再追加`() {
        val storage = PreferencesContext(temporary.newFolder())
        val old = record("SF000000000001")
        val restored = record("SF000000000002")
        val incoming = record("SF000000000003")
        assertTrue(ExpressRecordStore.upsert(storage.context, old))
        val pause = Pause()
        val firstSnapshot = AtomicBoolean(true)
        storage.preferences(ExpressRecordStore.PREFS).afterSnapshot = {
            if (firstSnapshot.compareAndSet(true, false)) pause.awaitRelease()
        }

        interleave(pause,
            first = {
                val outcome = ExpressBackup.restore(storage.context, backupOf(restored), 30_000L)
                assertTrue(outcome.message, outcome.ok)
            },
            second = { assertTrue(ExpressRecordStore.upsert(storage.context, incoming)) },
        )

        assertEquals(setOf(restored.trackingNumber, incoming.trackingNumber),
            ExpressRecordStore.load(storage.context).map { it.trackingNumber }.toSet())
    }

    @Test
    fun `恢复快照失败时不覆盖原数据且后续事务可继续`() = withAndroidTestRuntime {
        val storage = PreferencesContext(temporary.newFile())
        val original = record("SF000000000001")
        assertTrue(ExpressRecordStore.upsert(storage.context, original))

        val outcome = ExpressBackup.restore(storage.context, backupOf(record("SF000000000002")), 40_000L)

        assertFalse(outcome.ok)
        assertEquals(original.trackingNumber, ExpressRecordStore.load(storage.context).single().trackingNumber)
        assertTrue(ExpressRecordStore.upsert(storage.context, record("SF000000000003")))
    }

    @Test
    fun `导出与恢复前快照都排除认证凭据且导入不能替换它`() = withAndroidTestRuntime {
        val storage = PreferencesContext(temporary.newFolder())
        val credential = RelayCredentialStore.ensure(storage.context)
        val exported = ExpressBackup.export(storage.context, 10_000L)
        assertFalse(exported.contains(credential!!))
        assertFalse(RelayCredential.LOCAL_PREFS in BackupBundle.decode(exported).prefs)
        val forged = BackupBundle.encode(BackupBundle.Payload(
            version = BackupBundle.FORMAT_VERSION,
            exportedAt = 10_000L,
            appVersion = "test",
            prefs = mapOf(RelayCredential.LOCAL_PREFS to mapOf(RelayCredential.KEY to RelayCredential.generate())),
        ))

        val outcome = ExpressBackup.restore(storage.context, forged, 20_000L)

        assertTrue(outcome.ok)
        assertEquals(0, outcome.restoredPrefs)
        assertEquals(credential, RelayCredentialStore.read(storage.context))
        val snapshot = File(storage.context.filesDir, requireNotNull(outcome.snapshotName)).readText()
        assertFalse(snapshot.contains(credential))
        assertFalse(RelayCredential.LOCAL_PREFS in BackupBundle.decode(snapshot).prefs)
    }

    private fun pauseAfterFirstRead(preferences: MemoryPreferences): Pause {
        val pause = Pause()
        val first = AtomicBoolean(true)
        preferences.afterRead = { key ->
            if (key == "records" && first.compareAndSet(true, false)) pause.awaitRelease()
        }
        return pause
    }

    @Suppress("DEPRECATION")
    private fun interleave(pause: Pause, first: () -> Unit, second: () -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        val contender = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            val firstJob = workers.submit { withAndroidTestRuntime { first() } }
            assertTrue("first operation did not reach the controlled pause", pause.reached.await(10, TimeUnit.SECONDS))
            val secondJob = workers.submit {
                withAndroidTestRuntime {
                    contender.set(Thread.currentThread())
                    started.countDown()
                    try {
                        second()
                    } finally {
                        finished.countDown()
                    }
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val threads = ManagementFactory.getThreadMXBean()
            val monitorIdentity = System.identityHashCode(ExpressRecordStore)
            var blocked = threads.getThreadInfo(contender.get().id)
            while (blocked?.lockInfo?.identityHashCode != monitorIdentity && finished.count > 0 && System.nanoTime() < deadline) {
                Thread.yield()
                blocked = threads.getThreadInfo(contender.get().id)
            }
            assertEquals("second operation must wait on the record store monitor", monitorIdentity, blocked?.lockInfo?.identityHashCode)
            assertEquals(Thread.State.BLOCKED, blocked?.threadState)
            pause.release.countDown()
            firstJob.get(10, TimeUnit.SECONDS)
            secondJob.get(10, TimeUnit.SECONDS)
        } finally {
            pause.release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private class Pause {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun awaitRelease() {
            reached.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }
    }

    private fun record(tracking: String) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "",
        trackingNumber = tracking,
        origin = ExpressOrigin.ENRICHMENT,
        status = ExpressStatus.IN_TRANSIT,
        timestamp = 1_790_000_000_000L,
    )

    private fun backupOf(record: ExpressRecord): String = BackupBundle.encode(BackupBundle.Payload(
        version = BackupBundle.FORMAT_VERSION,
        exportedAt = 10_000L,
        appVersion = "test",
        prefs = mapOf(ExpressRecordStore.PREFS to mapOf("records" to ExpressRecordStore.serialize(listOf(record)))),
    ))
}
