package io.github.YGHFv.ReaPressExtend.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

class BackupJobService : JobService() {
    private val active = mutableSetOf<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        synchronized(active) { active.add(params) }
        return try {
            BackupScheduler.runDueAsync(applicationContext, shouldRun = { synchronized(active) { params in active } }) {
                synchronized(active) {
                    if (active.remove(params)) jobFinished(params, false)
                }
            }.also { started -> if (!started) synchronized(active) { active.remove(params) } }
        } catch (error: Exception) {
            synchronized(active) { active.remove(params) }
            ModuleAndroidLog.error("ReaPress", "start backup job failed", error)
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(active) { active.remove(params) }
        // 下次闹钟/打开模块重新检查持久化冷却，不要求系统立即重试。
        return false
    }

    internal companion object {
        private const val JOB_ID = 4712

        @Synchronized
        fun schedule(context: Context): Boolean = runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return@runCatching false
            if (scheduler.getPendingJob(JOB_ID) != null) return@runCatching true
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
                .setMinimumLatency(0L)
                .build()) == JobScheduler.RESULT_SUCCESS
        }.onFailure { ModuleAndroidLog.error("ReaPress", "schedule backup job failed", it) }.getOrDefault(false)

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
