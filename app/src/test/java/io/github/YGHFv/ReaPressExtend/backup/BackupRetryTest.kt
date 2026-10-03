package io.github.YGHFv.ReaPressExtend.backup

import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.app.AlarmManager
import android.app.PendingIntent
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*

class BackupRetryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test
    fun `过去成功但本次失败时保留成功时刻并把重试时间推进未来`() = withAndroidTestRuntime {
        val storage = PreferencesContext()
        val now = System.currentTimeMillis()
        val oldSuccess = now - 7_200_000L
        val config = BackupConfig(intervalMs = 3_600_000L, lastBackupAt = oldSuccess, nextDueAt = now - 1, encrypt = true)
        BackupSettings.save(storage.context, config)

        val outcome = BackupScheduler.backupNow(storage.context, "synthetic failure", now)

        assertFalse(outcome.ok)
        val saved = BackupSettings.load(storage.context)
        assertEquals(oldSuccess, saved.lastBackupAt)
        assertTrue("retry must be later than this attempt", saved.nextDueAt > now)
    }

    @Test
    fun firstFailureAlsoDefersRetry() = withAndroidTestRuntime {
        val storage = PreferencesContext()
        val now = System.currentTimeMillis()
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L, encrypt = true))
        assertFalse(BackupScheduler.backupNow(storage.context, "test", now).ok)
        val saved = BackupSettings.load(storage.context)
        assertEquals(0L, saved.lastBackupAt)
        assertEquals(now, saved.lastAttemptAt)
        assertTrue(saved.nextDueAt >= now + 3_600_000L)
    }

    @Test
    fun realFileWriteFailureDefersRetry() = withAndroidTestRuntime {
        val directory = temporary.newFolder()
        File(directory, "backup").writeText("not a directory")
        val storage = PreferencesContext(directory)
        val now = System.currentTimeMillis()
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L, lastBackupAt = 10L))
        assertFalse(BackupScheduler.backupNow(storage.context, "test", now).ok)
        val saved = BackupSettings.load(storage.context)
        assertEquals(10L, saved.lastBackupAt)
        assertTrue(saved.nextDueAt > now)
    }

    @Test
    fun successWritesFileAndRecordsSeparateAttemptAndSuccess() = withAndroidTestRuntime {
        val directory = temporary.newFolder()
        val storage = PreferencesContext(directory)
        val now = System.currentTimeMillis()
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L))
        val result = BackupScheduler.backupNow(storage.context, "test", now)
        assertTrue(result.message, result.ok)
        assertTrue(File(directory, "backup/${result.name}").isFile)
        val saved = BackupSettings.load(storage.context)
        assertEquals(now, saved.lastBackupAt)
        assertEquals(now, saved.lastAttemptAt)
        assertTrue(saved.nextDueAt > now)
    }

    @Test
    fun failedAutomaticAttemptUsesTenMinuteCooldownEvenWithoutTimer() = withAndroidTestRuntime {
        val storage = PreferencesContext()
        val now = System.currentTimeMillis()
        BackupSettings.save(storage.context, BackupConfig(onDataChange = true, encrypt = true))
        BackupScheduler.backupNow(storage.context, "test", now)
        val saved = BackupSettings.load(storage.context)
        assertEquals(0L, saved.nextDueAt)
        assertFalse(BackupScheduler.canRunOnChange(saved, now + 599_999L))
        assertFalse(BackupScheduler.canRunOnChange(saved, now - 1L))
        assertTrue(BackupScheduler.canRunOnChange(saved, now + 600_000L))
    }

    @Test
    fun resultDoesNotOverwriteSettingsChangedDuringBackup() = withAndroidTestRuntime {
        val storage = PreferencesContext()
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L))
        BackupSettings.recordAttempt(storage.context, 100L)
        BackupSettings.saveOptions(storage.context, BackupConfig(intervalMs = 0L, password = "new", retention = 5))
        BackupSettings.recordResult(storage.context, "done", 100L, 200L)
        val saved = BackupSettings.load(storage.context)
        assertEquals("new", saved.password)
        assertEquals(5, saved.retention)
        assertEquals(0L, saved.nextDueAt)
        assertEquals(100L, saved.lastAttemptAt)
    }

    @Test
    fun staleUiSnapshotCannotEraseLatestAttempt() = withAndroidTestRuntime {
        val storage = PreferencesContext()
        val stale = BackupConfig(intervalMs = 3_600_000L)
        BackupSettings.save(storage.context, stale)
        BackupSettings.recordAttempt(storage.context, 100L)
        BackupSettings.recordResult(storage.context, "done", 100L, 200L)
        BackupSettings.saveOptions(storage.context, stale.copy(retention = 3))
        val saved = BackupSettings.load(storage.context)
        assertEquals(100L, saved.lastAttemptAt)
        assertEquals(100L, saved.lastBackupAt)
        assertEquals(3_600_200L, saved.nextDueAt)
        assertEquals("done", saved.lastResult)
    }

    @Test
    fun failedAttemptCommitPreventsFileIoAndReleasesGate() = withAndroidTestRuntime {
        val directory = temporary.newFolder()
        val storage = PreferencesContext(directory)
        val prefs = storage.preferences(BackupSettings.PREFS)
        prefs.commitSucceeds = false
        assertFalse(BackupScheduler.backupNow(storage.context, "test", 100L).ok)
        assertFalse(File(directory, "backup").exists())
        prefs.commitSucceeds = true
        assertTrue(BackupScheduler.backupNow(storage.context, "test", 101L).ok)
    }

    @Test
    fun dueJobRechecksPersistedStateBeforeWriting() = withAndroidTestRuntime {
        val directory = temporary.newFolder()
        val storage = PreferencesContext(directory)
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L, nextDueAt = Long.MAX_VALUE))
        val finished = CountDownLatch(1)
        assertTrue(BackupScheduler.runDueAsync(storage.context) { finished.countDown() })
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(File(directory, "backup").exists())
        assertEquals(0L, BackupSettings.load(storage.context).lastAttemptAt)
    }

    @Test
    fun dueTimeAdditionDoesNotOverflow() {
        val storage = PreferencesContext()
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L))
        BackupSettings.recordAttempt(storage.context, Long.MAX_VALUE - 10L)
        assertEquals(Long.MAX_VALUE, BackupSettings.load(storage.context).nextDueAt)
    }

    @Test
    fun failedBackupReanchorsAlarmToRetryDeadline() = withAndroidTestRuntime {
        val pending = mock(PendingIntent::class.java)
        mockStatic(PendingIntent::class.java, org.mockito.stubbing.Answer { pending }).use {
            val storage = PreferencesContext()
            val manager = mock(AlarmManager::class.java)
            `when`(storage.context.getSystemService(AlarmManager::class.java)).thenReturn(manager)
            BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L, nextDueAt = 1L, encrypt = true))
            assertFalse(BackupScheduler.backupNow(storage.context, "test", System.currentTimeMillis()).ok)
            verify(manager).setInexactRepeating(AlarmManager.RTC_WAKEUP,
                BackupSettings.load(storage.context).nextDueAt, 3_600_000L, pending)
        }
    }

    @Test
    fun repeatedSchedulingKeepsStoredDeadlineInsteadOfPostponingIt() = withAndroidTestRuntime {
        val pending = mock(PendingIntent::class.java)
        mockStatic(PendingIntent::class.java, org.mockito.stubbing.Answer { pending }).use {
            val storage = PreferencesContext()
            val manager = mock(AlarmManager::class.java)
            `when`(storage.context.getSystemService(AlarmManager::class.java)).thenReturn(manager)
            val due = System.currentTimeMillis() + 1_800_000L
            BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L, nextDueAt = due))
            repeat(2) { BackupScheduler.ensureScheduled(storage.context) }
            verify(manager, times(2)).setInexactRepeating(AlarmManager.RTC_WAKEUP, due, 3_600_000L, pending)
        }
    }
}
