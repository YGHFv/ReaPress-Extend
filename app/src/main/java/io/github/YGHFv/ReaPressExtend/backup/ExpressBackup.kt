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
import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.core.BackupBundle
import io.github.YGHFv.ReaPressExtend.core.BackupCrypto
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationRuleStore
import io.github.YGHFv.ReaPressExtend.relay.ModuleTraceFetcher
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieStore
import io.github.YGHFv.ReaPressExtend.relay.WatchState
import io.github.YGHFv.ReaPressExtend.ui.ExpressUiPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份模块全部本地数据（PREFS_NAMES 列出的整份 prefs；不备份日志、缓存与 files/ 下的快照），
 * 以及从备份文件恢复。恢复是覆盖性的，动手前必先写 pre-restore 快照，写不出就不覆盖。
 * 写回用 commit 而非 apply：恢复完成后用户很可能立刻切走甚至杀进程。
 */
internal object ExpressBackup {

    private const val TAG = "ReaPress"

    private const val SNAPSHOT_PREFIX = "pre-restore-"

    /** 备份清单是唯一来源；名字引用各 Store 自己的常量，不在这里抄字符串。 */
    val PREFS_NAMES: List<String> = listOf(
        ExpressRecordStore.PREFS,
        ExpressNotificationLog.PREFS,
        ExpressSettingsKeys.LOCAL_PREFS,
        ExpressStationRuleStore.PREFS,
        ExpressUiPrefs.PREFS,
        WatchState.PREFS,
        ModuleTraceFetcher.PREFS,
        // 免 root 的淘宝登录态，含凭据。
        TraceCookieStore.PREFS,
    )

    /**
     * 导出全部数据，返回可直接写进文件的文本。加密在明文之后做：解密产物就是普通明文备份，解析路径不分叉。
     *
     * @param password 非空 = 加密导出
     */
    fun export(context: Context, at: Long, password: String? = null): String {
        val prefs = ExpressRecordStore.withTransaction {
            PREFS_NAMES.associateWith { name -> snapshotOf(context, name) }
        }
        val plain = BackupBundle.encode(
            BackupBundle.Payload(
                version = BackupBundle.FORMAT_VERSION,
                exportedAt = at,
                appVersion = BuildConfig.VERSION_NAME,
                prefs = prefs,
            ),
        )
        if (password.isNullOrEmpty()) return plain
        return BackupCrypto.encrypt(
            plain = plain,
            password = password,
            at = at,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    /** 建议的备份文件名，例如 `reapress-backup-20260927-2243.json`（时刻进文件名，多份可分）。 */
    fun suggestFileName(at: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
        return BackupBundle.FILE_PREFIX + stamp + BackupBundle.FILE_SUFFIX
    }

    /**
     * 从备份文本恢复。任何失败都以 ok=false 返回、不抛异常（选错文件等是用户要看见的正常结果）。
     * 加密备份没密码 / 密码不对都返回 needsPassword，不能落成「文件无法识别」。
     */
    fun restore(context: Context, raw: String?, at: Long, password: String? = null): RestoreOutcome {
        val text = if (BackupCrypto.isEncrypted(raw)) {
            if (password.isNullOrEmpty()) {
                return RestoreOutcome(
                    ok = false,
                    message = "这份备份是加密的，需要密码才能恢复。",
                    needsPassword = true,
                )
            }
            try {
                BackupCrypto.decrypt(raw!!, password)
            } catch (e: BackupCrypto.BadPasswordException) {
                return RestoreOutcome(
                    ok = false,
                    message = e.message ?: "密码不对",
                    needsPassword = true,
                )
            } catch (e: Exception) {
                ModuleAndroidLog.error(TAG, "backup decrypt failed", e)
                return RestoreOutcome(
                    ok = false,
                    message = "解密失败：${e.message ?: e::class.java.simpleName}",
                )
            }
        } else {
            raw
        }

        val payload = try {
            BackupBundle.decode(text)
        } catch (e: BackupBundle.BackupFormatException) {
            return RestoreOutcome(ok = false, message = e.message ?: "备份文件无法识别")
        } catch (e: Exception) {
            ModuleAndroidLog.error(TAG, "backup decode failed", e)
            return RestoreOutcome(ok = false, message = "备份文件无法识别：${e.message ?: e::class.java.simpleName}")
        }

        val outcome = ExpressRecordStore.withTransaction {
            val restored = restorePayload(context, payload, at)
            if (restored.changedPrefs.isNotEmpty()) {
                restored.withWarnings(BackupRestoreRuntime.refreshCaches(context, restored.changedPrefs))
            } else {
                restored
            }
        }
        return if (outcome.dataChanged) {
            outcome.withWarnings(BackupRestoreRuntime.resume(context, outcome.changedPrefs))
        } else {
            outcome
        }
    }

    private fun restorePayload(context: Context, payload: BackupBundle.Payload, at: Long): RestoreOutcome {
        // 先落快照，再动数据 —— 顺序不能反；快照写不出来就不去覆盖用户数据。
        val snapshot = try {
            snapshotBeforeRestore(context, at)
        } catch (e: Exception) {
            ModuleAndroidLog.error(TAG, "pre-restore snapshot failed", e)
            return RestoreOutcome(ok = false, message = "无法写入恢复前快照，已取消恢复（原数据未改动）")
        }

        val targets = payload.prefs.filterKeys { it in PREFS_NAMES }
        val previous = try {
            targets.keys.associateWith { name -> snapshotOf(context, name) }
        } catch (_: Exception) {
            return RestoreOutcome(ok = false, message = "无法读取恢复前状态，已取消恢复", snapshotName = snapshot)
        }
        val attempted = linkedSetOf<String>()
        var restoredEntries = 0
        for ((name, values) in targets) {
            attempted.add(name)
            if (!writeBack(context, name, values)) {
                val rollbackFailures = attempted.filterNot { writeBack(context, it, previous.getValue(it)) }.toSet()
                val message = if (rollbackFailures.isEmpty()) {
                    "恢复写入失败，已回滚原数据（已有风控退避不缩短）。恢复前快照为 $snapshot"
                } else {
                    "恢复写入失败，${rollbackFailures.size} 份数据未能确认回滚；请从恢复前快照 $snapshot 重试恢复"
                }
                return RestoreOutcome(
                    ok = false,
                    message = message,
                    snapshotName = snapshot,
                    dataChanged = rollbackFailures.isNotEmpty(),
                    changedPrefs = attempted,
                )
            }
            restoredEntries += values.keys.count { name != ExpressSettingsKeys.LOCAL_PREFS || it !in ExpressSettingsKeys.HOOK_RESET_KEYS }
        }
        val skipped = payload.prefs.keys - targets.keys
        val message = buildString {
            append("已恢复 ${targets.size} 份数据、共 $restoredEntries 项")
            if (skipped.isNotEmpty()) {
                append("；跳过 ${skipped.size} 份不认识的存储")
            }
            append("。恢复前的数据已自动存为 $snapshot")
        }

        ModuleAndroidLog.info(
            TAG,
            "backup restored: prefs=${targets.size} entries=$restoredEntries snapshot=$snapshot",
        )

        return RestoreOutcome(
            ok = true,
            message = message,
            restoredPrefs = targets.size,
            restoredEntries = restoredEntries,
            snapshotName = snapshot,
            dataChanged = targets.isNotEmpty(),
            changedPrefs = targets.keys,
        )
    }

    private fun snapshotOf(context: Context, name: String): Map<String, Any?> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.filterKeys {
            name != ExpressSettingsKeys.LOCAL_PREFS || it !in ExpressSettingsKeys.HOOK_RESET_KEYS
        }

    /** 整体替换（先 clear）：留着备份里没有的键会变成两份数据的混合体。 */
    private fun writeBack(context: Context, name: String, values: Map<String, Any?>): Boolean =
        runCatching {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val kept = if (name == ExpressSettingsKeys.LOCAL_PREFS) {
                prefs.all.filterKeys { it in ExpressSettingsKeys.HOOK_RESET_KEYS }
            } else {
                emptyMap()
            }
            var restored = values.filterKeys { name != ExpressSettingsKeys.LOCAL_PREFS || it !in ExpressSettingsKeys.HOOK_RESET_KEYS } + kept
            if (name == ModuleTraceFetcher.PREFS) {
                val key = ModuleTraceFetcher.KEY_RISK_UNTIL
                restored = restored + (key to maxOf(
                    (restored[key] as? Long) ?: 0L,
                    prefs.getLong(key, 0L),
                    io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi.riskBlockedUntil,
                ))
            }
            val editor = prefs.edit()
            editor.clear()
            restored.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
            editor.commit()
        }.getOrElse { e ->
            ModuleAndroidLog.error(TAG, "restore prefs failed: $name", e)
            false
        }

    /** 把当前全量数据写到私有目录，供恢复出错时捞回来。返回文件名。 */
    private fun snapshotBeforeRestore(context: Context, at: Long): String {
        val file = File(context.filesDir, SNAPSHOT_PREFIX + at + BackupBundle.FILE_SUFFIX)
        file.writeText(export(context, at))
        return file.name
    }

    /** 本机留着的「恢复前快照」，最新的在前；与备份目录里的备份是两条独立的退路。 */
    fun localSnapshots(context: Context): List<BackupEntry> =
        (context.filesDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(SNAPSHOT_PREFIX) && it.name.endsWith(BackupBundle.FILE_SUFFIX) }
            .map { BackupEntry(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.name }

    /** 读一份本机快照。 */
    fun readLocalSnapshot(context: Context, name: String): String = File(context.filesDir, name).readText()

    /** 只在用户明确点了删除时才调：这些快照是恢复出错后的退路，模块自己不清。 */
    fun deleteLocalSnapshot(context: Context, name: String) {
        runCatching { File(context.filesDir, name).delete() }
            .onFailure { ModuleAndroidLog.error(TAG, "delete snapshot failed: $name", it) }
    }
}

/**
 * 一次恢复的结果。needsPassword 也是结果的一部分（缺密码/密码不对），不是异常。
 *
 * @param snapshotName 恢复前自动快照的文件名（在 files/ 下）
 */
internal data class RestoreOutcome(
    val ok: Boolean,
    val message: String,
    val restoredPrefs: Int = 0,
    val restoredEntries: Int = 0,
    val snapshotName: String? = null,
    val needsPassword: Boolean = false,
    val dataChanged: Boolean = false,
    internal val changedPrefs: Set<String> = emptySet(),
    val runtimeWarnings: List<String> = emptyList(),
) {
    fun withWarnings(warnings: List<String>): RestoreOutcome = if (warnings.isEmpty()) this else copy(
        message = message + "。运行状态提示：" + warnings.joinToString("；"),
        runtimeWarnings = runtimeWarnings + warnings,
    )
}
