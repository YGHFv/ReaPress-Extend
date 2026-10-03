package io.github.YGHFv.ReaPressExtend.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.testing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

class BackupJobServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun actualJobFinishesOnlyAfterActualBackupWrite() = fixture { storage, service, params, queued ->
        assertTrue(service.onStartJob(params))
        verify(service, never()).jobFinished(params, false)
        assertEquals(0L, BackupSettings.load(storage.context).lastBackupAt)
        queued.single().run()
        assertTrue(BackupSettings.load(storage.context).lastBackupAt > 0L)
        assertEquals(1, BackupScheduler.defaultDir(storage.context).listFiles()!!.size)
        verify(service, times(1)).jobFinished(params, false)
    }

    @Test
    fun failedBackupFinishesAndDoesNotRequestImmediateRetry() = fixture { storage, service, params, queued ->
        BackupSettings.saveOptions(storage.context, BackupSettings.load(storage.context).copy(encrypt = true))
        assertTrue(service.onStartJob(params))
        queued.single().run()
        assertEquals(0L, BackupSettings.load(storage.context).lastBackupAt)
        assertTrue(BackupSettings.load(storage.context).nextDueAt > System.currentTimeMillis())
        verify(service).jobFinished(params, false)
        assertTrue(service.onStartJob(params))
        queued.last().run()
        verify(service, times(2)).jobFinished(params, false)
    }

    @Test
    fun firstJobInitializesDeadlineWithoutCreatingBackup() = fixture { storage, service, params, queued ->
        BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L))
        assertTrue(service.onStartJob(params))
        queued.single().run()
        assertEquals(0L, BackupSettings.load(storage.context).lastAttemptAt)
        assertTrue(BackupSettings.load(storage.context).nextDueAt > System.currentTimeMillis())
        verify(service).jobFinished(params, false)
    }

    @Test
    fun disabledTimerIsRecheckedWhenJobStarts() = fixture { storage, service, params, queued ->
        assertTrue(service.onStartJob(params))
        BackupSettings.saveOptions(storage.context, BackupConfig())
        queued.single().run()
        assertEquals(0L, BackupSettings.load(storage.context).lastAttemptAt)
        verify(service).jobFinished(params, false)
    }

    @Test
    fun unexpectedPreferencesReadFailureStillFinishesJob() = fixture { storage, service, params, queued ->
        assertTrue(service.onStartJob(params))
        storage.preferences(BackupSettings.PREFS).afterRead = { error("synthetic read failure") }
        queued.single().run()
        verify(service).jobFinished(params, false)
        storage.preferences(BackupSettings.PREFS).afterRead = {}
        assertTrue(BackupScheduler.backupNow(storage.context, "manual", System.currentTimeMillis()).ok)
    }

    @Test
    fun stoppingQueuedJobDoesNotStartIoOrFinishStoppedJob() = fixture { storage, service, params, queued ->
        assertTrue(service.onStartJob(params))
        assertFalse(service.onStopJob(params))
        queued.single().run()
        assertEquals(0L, BackupSettings.load(storage.context).lastAttemptAt)
        verify(service, never()).jobFinished(params, false)
    }

    @Test
    fun runningManualBackupAndRepeatedTriggersDoNotQueueMoreJobs() = fixture { storage, service, params, queued ->
        assertTrue(service.onStartJob(params))
        val another = mock(JobParameters::class.java)
        assertFalse(service.onStartJob(another))
        assertFalse(BackupScheduler.backupNow(storage.context, "manual", 10L).ok)
        assertEquals(1, queued.size)
        queued.single().run()
        verify(service).jobFinished(params, false)
        verify(service, never()).jobFinished(another, false)
    }

    @Test
    fun rejectedExecutorLeavesJobFinishedSynchronouslyAndReleasesGate() = fixture { _, service, params, queued ->
        ObjectStateScope().use { state ->
            val rejecting = mock(ExecutorService::class.java)
            doThrow(RejectedExecutionException()).`when`(rejecting).execute(any(Runnable::class.java))
            state.set(BackupScheduler, "executor", rejecting)
            assertFalse(service.onStartJob(params))
        }
        assertTrue(service.onStartJob(params))
        queued.single().run()
        verify(service, times(1)).jobFinished(params, false)
    }

    @Test
    fun alarmOnlySubmitsJobAndDoesNotReadOrWriteBackupData() = withAndroidTestRuntime {
        mockConstruction(ComponentName::class.java).use {
            mockConstruction(JobInfo.Builder::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
                `when`(builder.build()).thenReturn(mock(JobInfo::class.java))
            }.use {
                val storage = PreferencesContext()
                val scheduler = mock(JobScheduler::class.java)
                `when`(storage.context.getSystemService(JobScheduler::class.java)).thenReturn(scheduler)
                `when`(scheduler.schedule(any(JobInfo::class.java))).thenReturn(JobScheduler.RESULT_SUCCESS)
                val intent = mock(Intent::class.java)
                `when`(intent.action).thenReturn(BackupScheduler.ACTION_BACKUP_ALARM)
                BackupAlarmReceiver().onReceive(storage.context, intent)
                verify(scheduler).schedule(any(JobInfo::class.java))
                assertTrue(storage.names().isEmpty())
                `when`(scheduler.getPendingJob(anyInt())).thenReturn(mock(JobInfo::class.java))
                BackupAlarmReceiver().onReceive(storage.context, intent)
                verify(scheduler, times(1)).schedule(any(JobInfo::class.java))
                Unit
            }
        }
    }

    @Test
    fun unavailableOrRejectedSchedulerIsReportedAsFailure() = withAndroidTestRuntime {
        val storage = PreferencesContext()
        assertFalse(BackupJobService.schedule(storage.context))
        val scheduler = mock(JobScheduler::class.java)
        `when`(storage.context.getSystemService(JobScheduler::class.java)).thenReturn(scheduler)
        `when`(scheduler.getPendingJob(anyInt())).thenThrow(SecurityException("synthetic"))
        assertFalse(BackupJobService.schedule(storage.context))
    }

    private fun fixture(block: (PreferencesContext, BackupJobService, JobParameters, MutableList<Runnable>) -> Unit) =
        withAndroidTestRuntime {
            ObjectStateScope().use { state ->
                val queued = mutableListOf<Runnable>()
                val executor = mock(ExecutorService::class.java)
                doAnswer { queued.add(it.getArgument(0)); null }.`when`(executor).execute(any(Runnable::class.java))
                state.set(BackupScheduler, "executor", executor)
                val storage = PreferencesContext(temporary.newFolder())
                BackupSettings.save(storage.context, BackupConfig(intervalMs = 3_600_000L, nextDueAt = 1L))
                val service = mock(BackupJobService::class.java, CALLS_REAL_METHODS)
                state.set(service, "active", mutableSetOf<JobParameters>())
                doReturn(storage.context).`when`(service).applicationContext
                doNothing().`when`(service).jobFinished(any(JobParameters::class.java), org.mockito.ArgumentMatchers.anyBoolean())
                try {
                    block(storage, service, mock(JobParameters::class.java), queued)
                } finally {
                    val field = BackupScheduler::class.java.getDeclaredField("gate").apply { isAccessible = true }
                    (field.get(BackupScheduler) as BackupTaskGate).release()
                }
            }
        }
}
