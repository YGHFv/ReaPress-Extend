/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import java.io.File
import java.util.concurrent.Executors

/**
 * 自动备份的两条路（[onDataChanged]「有变更就备份」：去抖 [DEBOUNCE_MS] + 最小间隔
 * [AUTO_MIN_INTERVAL_MS]，贴着数据走；[ensureScheduled] 定时备份：贴着时间走，兜的是
 * 开关坏了、链路没生效这类静默失败）与共同执行体。定时用 setInexactRepeating，不申请精确闹钟权限，
 * 且不把闹钟当唯一保障——[maybeRunDue] 在每次打开模块时补一次到期判定，闹钟被 ROM 吞了也只会晚到。
 * [prune] 只删本模块前缀的备份（[BackupStore.list] 保证），用户把目录指到整个 Download 也安全。
 */
internal object BackupScheduler {

    private const val TAG = "ReaPress"

    /** 闹钟广播的 action，清单里的接收器靠它认领。 */
    internal const val ACTION_BACKUP_ALARM = "io.github.YGHFv.ReaPressExtend.action.BACKUP_ALARM"

    private const val ALARM_REQUEST = 4711

    /** 「有变更」的去抖窗口：等一批数据安静下来再动手，一次刷新不触发多遍序列化。 */
    private const val DEBOUNCE_MS = 60_000L

    /** 自动备份最小间隔：挡的是读全部 prefs + 序列化，以及上限裁剪把当天备份删光。 */
    private const val AUTO_MIN_INTERVAL_MS = 10 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-backup").apply { isDaemon = true }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 同一时刻只跑一次备份：定时闹钟与「有变更」撞在一起是常态。 */
    @Volatile private var running = false

    private val debounced = Runnable {
        val context = applicationContext ?: return@Runnable
        val config = BackupSettings.load(context)
        if (!config.onDataChange) return@Runnable
        val now = System.currentTimeMillis()
        // 刚备过就不必再备 —— 定时那份、或者用户手动点的那份同样能顶。
        if (now - config.lastBackupAt < AUTO_MIN_INTERVAL_MS) return@Runnable
        runAsync("有变更") { backupNow(context, "有变更", now, config) }
    }

    /** 只留 application context：备份与界面无关（闹钟那条路没有界面），持 Activity 会泄漏。 */
    @Volatile private var applicationContext: Context? = null

    /** 默认目录：应用专属外部目录（无需权限，自动备份开箱可用）。 */
    fun defaultDir(context: Context): File =
        context.getExternalFilesDir("backup") ?: File(context.filesDir, "backup")

    /** 按配置挑出这次要用的目录。URI 解析失败时退回默认目录 —— 宁可放在默认位置，也别丢一次备份。 */
    fun storeOf(context: Context, config: BackupConfig): BackupStore {
        val parsed = config.dirUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return if (parsed == null) {
            AppDirBackupStore(defaultDir(context))
        } else {
            SafBackupStore(context.applicationContext, parsed)
        }
    }

    /** 立刻同步写一份备份（调用方决定线程）；不做节流——「我现在就要一份」的语义，节流在 [onDataChanged] 一侧。 */
    fun backupNow(
        context: Context,
        reason: String,
        at: Long,
        config: BackupConfig = BackupSettings.load(context),
    ): BackupResult {
        if (!config.encryptionReady) {
            // 开了加密却没设密码不能退回明文——用户勾那个开关图的就是别留明文。
            val message = "开了加密备份但还没设置密码，这次没有备份。"
            remember(context, config, message, now = System.currentTimeMillis())
            ModuleAndroidLog.info(TAG, "backup skipped (no password): reason=$reason")
            return BackupResult(ok = false, message = message)
        }

        val store = storeOf(context, config)
        val password = config.password.takeIf { config.encrypt }
        return runCatching {
            val text = ExpressBackup.export(context, at, password)
            val name = ExpressBackup.suggestFileName(at)
            store.write(name, text)
            val pruned = prune(store, config.retention)
            val sizeKb = text.toByteArray(Charsets.UTF_8).size / 1024
            val message = buildString {
                append("已备份（约 $sizeKb KB）")
                if (config.encrypt) append("，已加密")
                append("。")
                if (pruned > 0) append("按上限清掉了 $pruned 份最旧的。")
            }
            BackupScheduler.remember(context, config, message, lastAt = at, now = at)
            ModuleAndroidLog.info(
                TAG,
                "backup ok: reason=$reason name=$name kb=$sizeKb pruned=$pruned encrypted=${config.encrypt}",
            )
            BackupResult(ok = true, message = message, name = name, sizeBytes = text.length.toLong())
        }.getOrElse { e ->
            ModuleAndroidLog.error(TAG, "backup failed: reason=$reason", e)
            val message = "备份失败：${e.message ?: e::class.java.simpleName}"
            remember(context, config, message, now = System.currentTimeMillis())
            BackupResult(ok = false, message = message)
        }
    }

    /** 「数据有变更」通知点（[io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier] 调用）；只安排延迟执行，判断在 [debounced]。 */
    fun onDataChanged(context: Context) {
        applicationContext = context.applicationContext
        if (!BackupSettings.load(context).onDataChange) return
        handler.removeCallbacks(debounced)
        handler.postDelayed(debounced, DEBOUNCE_MS)
    }

    /**
     * 到期就备一份（闹钟、打开模块、开机三处调用）。第一次只把到期时刻排出来不当场备——
     * 否则用户一勾「每天」就立刻多一份。
     */
    fun maybeRunDue(context: Context, reason: String) {
        applicationContext = context.applicationContext
        val config = BackupSettings.load(context)
        if (config.intervalMs <= BackupSettings.INTERVAL_OFF) return
        val now = System.currentTimeMillis()
        if (config.nextDueAt <= 0L) {
            BackupSettings.save(context, config.copy(nextDueAt = now + config.intervalMs))
            return
        }
        if (now < config.nextDueAt) return
        runAsync(reason) { backupNow(context, reason, now) }
    }

    /** 把定时闹钟摆到与设置一致。用重复闹钟：ROM 吞掉一次后，不重复的话就再没有下一次。 */
    fun ensureScheduled(context: Context) {
        val config = BackupSettings.load(context)
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        if (config.intervalMs <= BackupSettings.INTERVAL_OFF) {
            manager.cancel(pending)
            return
        }
        runCatching {
            manager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + config.intervalMs,
                config.intervalMs,
                pending,
            )
        }.onFailure { ModuleAndroidLog.error(TAG, "schedule backup alarm failed", it) }
    }

    fun reschedule(context: Context) {
        ensureScheduled(context)
    }

    /** 按上限裁剪，只删本模块前缀的备份，从最旧开始。返回实际删掉的份数。 */
    private fun prune(store: BackupStore, retention: Int): Int {
        if (retention <= 0) return 0
        val files = store.list()
        if (files.size <= retention) return 0
        val doomed = files.drop(retention)
        doomed.forEach { store.delete(it.name) }
        return doomed.size
    }

    /** 把结果写进设置，让界面下次打开就能看到（自动备份没有界面可以即时回报）。 */
    private fun remember(
        context: Context,
        config: BackupConfig,
        message: String,
        lastAt: Long = config.lastBackupAt,
        now: Long,
    ) {
        val next = when {
            config.intervalMs <= BackupSettings.INTERVAL_OFF -> 0L
            // 成功与否都往前推：否则一次失败会让「到期」永远成立，每次打开模块都重试。
            // lastAt 为 0 时以「现在」起算，否则下次到期落在一九七零年。
            lastAt > 0L -> lastAt + config.intervalMs
            else -> now + config.intervalMs
        }
        runCatching {
            BackupSettings.save(
                context,
                config.copy(lastBackupAt = lastAt, lastResult = message, nextDueAt = next),
            )
        }.onFailure { ModuleAndroidLog.error(TAG, "save backup state failed", it) }
    }

    private fun runAsync(reason: String, block: () -> Unit) {
        executor.execute {
            if (running) {
                ModuleAndroidLog.info(TAG, "backup skipped (already running): reason=$reason")
                return@execute
            }
            running = true
            try {
                block()
            } finally {
                running = false
            }
        }
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, BackupAlarmReceiver::class.java)
            .setAction(ACTION_BACKUP_ALARM)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 一次备份的结果（ok / message / name / sizeBytes，失败时 name 为空、sizeBytes 为 0）。 */
internal class BackupResult(
    val ok: Boolean,
    val message: String,
    val name: String? = null,
    val sizeBytes: Long = 0L,
)
