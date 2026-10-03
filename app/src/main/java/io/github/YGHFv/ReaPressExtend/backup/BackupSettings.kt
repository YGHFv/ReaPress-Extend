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

import android.content.Context

/**
 * 备份功能自己的设置（目录、开关、上限、密码），与模块功能设置分开存放：混进功能 prefs 会被
 * 「恢复备份」一起换掉，而目录授权与密码是恢复后立刻还要用的。这份文件刻意不在
 * [ExpressBackup.PREFS_NAMES] 清单里 —— 密码存这里，写进备份文件等于加密没做。
 * 密码明文存本机：自动 / 定时备份时没有人可以输入密码，它保护的是离开这台设备的那个文件，
 * 不是本机 prefs。
 */
internal object BackupSettings {

    /** 本地存储文件名。internal 给界面与调度层读，不要加进 [ExpressBackup.PREFS_NAMES]。 */
    internal const val PREFS = "reapress_backup"

    private const val KEY_DIR_URI = "dirUri"
    private const val KEY_ON_DATA_CHANGE = "onDataChange"
    private const val KEY_INTERVAL = "intervalMs"
    private const val KEY_RETENTION = "retention"
    private const val KEY_ENCRYPT = "encrypt"
    private const val KEY_PASSWORD = "password"
    private const val KEY_LAST_AT = "lastBackupAt"
    private const val KEY_LAST_ATTEMPT = "lastAttemptAt"
    private const val KEY_NEXT_DUE = "nextDueAt"
    private const val KEY_LAST_RESULT = "lastResult"

    /** 「定时备份」关着。 */
    const val INTERVAL_OFF = 0L

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    val INTERVAL_OPTIONS: List<Long> = listOf(INTERVAL_OFF, 6 * HOUR, 12 * HOUR, DAY, 3 * DAY, 7 * DAY)

    fun intervalLabel(ms: Long): String = when (ms) {
        INTERVAL_OFF -> "关闭"
        6 * HOUR -> "每 6 小时"
        12 * HOUR -> "每 12 小时"
        DAY -> "每天"
        3 * DAY -> "每 3 天"
        7 * DAY -> "每周"
        else -> if (ms < DAY) "每 ${ms / HOUR} 小时" else "每 ${ms / DAY} 天"
    }

    val RETENTION_OPTIONS: List<Int> = listOf(3, 5, 10, 20, 50)

    const val DEFAULT_RETENTION = 10

    @Synchronized
    fun load(context: Context): BackupConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BackupConfig(
            dirUri = prefs.getString(KEY_DIR_URI, null)?.takeIf { it.isNotBlank() },
            onDataChange = prefs.getBoolean(KEY_ON_DATA_CHANGE, false),
            intervalMs = prefs.getLong(KEY_INTERVAL, INTERVAL_OFF),
            retention = prefs.getInt(KEY_RETENTION, DEFAULT_RETENTION),
            encrypt = prefs.getBoolean(KEY_ENCRYPT, false),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            lastBackupAt = prefs.getLong(KEY_LAST_AT, 0L),
            lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT, 0L),
            nextDueAt = prefs.getLong(KEY_NEXT_DUE, 0L),
            lastResult = prefs.getString(KEY_LAST_RESULT, "").orEmpty(),
        )
    }

    @Synchronized
    fun save(context: Context, config: BackupConfig) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DIR_URI, config.dirUri)
            .putBoolean(KEY_ON_DATA_CHANGE, config.onDataChange)
            .putLong(KEY_INTERVAL, config.intervalMs)
            .putInt(KEY_RETENTION, config.retention)
            .putBoolean(KEY_ENCRYPT, config.encrypt)
            .putString(KEY_PASSWORD, config.password)
            .putLong(KEY_LAST_AT, config.lastBackupAt)
            .putLong(KEY_LAST_ATTEMPT, config.lastAttemptAt)
            .putLong(KEY_NEXT_DUE, config.nextDueAt)
            .putString(KEY_LAST_RESULT, config.lastResult)
            .commit()) { "Cannot persist backup settings" }
    }

    @Synchronized
    fun saveOptions(context: Context, config: BackupConfig) {
        val current = load(context)
        save(context, config.copy(
            lastBackupAt = current.lastBackupAt,
            lastAttemptAt = current.lastAttemptAt,
            lastResult = current.lastResult,
            nextDueAt = if (current.intervalMs == config.intervalMs) current.nextDueAt else 0L,
        ))
    }

    @Synchronized
    fun recordAttempt(context: Context, at: Long) {
        val current = load(context)
        save(context, current.copy(lastAttemptAt = at, nextDueAt = nextDue(at, current.intervalMs)))
    }

    @Synchronized
    fun initializeDue(context: Context, now: Long): BackupConfig {
        val current = load(context)
        if (current.intervalMs <= INTERVAL_OFF || current.nextDueAt > 0L) return current
        return current.copy(nextDueAt = nextDue(now, current.intervalMs)).also { save(context, it) }
    }

    @Synchronized
    fun recordResult(context: Context, message: String, successAt: Long?, now: Long) {
        val current = load(context)
        save(context, current.copy(
            lastBackupAt = successAt ?: current.lastBackupAt,
            lastResult = message,
            nextDueAt = nextDue(now, current.intervalMs),
        ))
    }

    private fun nextDue(now: Long, interval: Long): Long = when {
        interval <= INTERVAL_OFF -> 0L
        now > Long.MAX_VALUE - interval -> Long.MAX_VALUE
        else -> now + interval
    }
}

/** 备份设置快照。[dirUri] null = 用默认的应用专属外部目录；[password] 明文存本机（理由见 [BackupSettings]）。 */
internal data class BackupConfig(
    val dirUri: String? = null,
    val onDataChange: Boolean = false,
    val intervalMs: Long = BackupSettings.INTERVAL_OFF,
    val retention: Int = BackupSettings.DEFAULT_RETENTION,
    val encrypt: Boolean = false,
    val password: String = "",
    val lastBackupAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val nextDueAt: Long = 0L,
    val lastResult: String = "",
) {
    /** 开了加密却没设密码：自动备份会拒绝执行并说明原因，不静默存成明文。 */
    val encryptionReady: Boolean get() = !encrypt || password.isNotEmpty()

    val automaticOn: Boolean get() = onDataChange || intervalMs != BackupSettings.INTERVAL_OFF
}
