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
import io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier
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
        val prefs = LinkedHashMap<String, Map<String, Any?>>()
        PREFS_NAMES.forEach { name -> prefs[name] = snapshotOf(context, name) }
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

        // 先落快照，再动数据 —— 顺序不能反；快照写不出来就不去覆盖用户数据。
        val snapshot = try {
            snapshotBeforeRestore(context, at)
        } catch (e: Exception) {
            ModuleAndroidLog.error(TAG, "pre-restore snapshot failed", e)
            return RestoreOutcome(ok = false, message = "无法写入恢复前快照，已取消恢复（原数据未改动）")
        }

        var restoredPrefs = 0
        var restoredEntries = 0
        var failedPrefs = 0
        val skipped = mutableListOf<String>()

        payload.prefs.forEach { (name, values) ->
            if (name !in PREFS_NAMES) {
                skipped += name
                return@forEach
            }
            if (writeBack(context, name, values)) {
                restoredPrefs++
                restoredEntries += values.size
            } else {
                failedPrefs++
            }
        }
        ExpressChangeNotifier.notify(context)

        val message = buildString {
            if (failedPrefs > 0) {
                append("恢复未完全成功：$restoredPrefs 份已写入，$failedPrefs 份写入失败")
            } else {
                append("已恢复 $restoredPrefs 份数据、共 $restoredEntries 项")
            }
            if (skipped.isNotEmpty()) {
                append("；跳过 ${skipped.size} 份不认识的存储")
            }
            append("。恢复前的数据已自动存为 $snapshot")
        }

        ModuleAndroidLog.info(
            TAG,
            "backup restored: prefs=$restoredPrefs entries=$restoredEntries failed=$failedPrefs snapshot=$snapshot",
        )

        return RestoreOutcome(
            ok = failedPrefs == 0,
            message = message,
            restoredPrefs = restoredPrefs,
            restoredEntries = restoredEntries,
            snapshotName = snapshot,
        )
    }

    private fun snapshotOf(context: Context, name: String): Map<String, Any?> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()

    /** 整体替换（先 clear）：留着备份里没有的键会变成两份数据的混合体。 */
    private fun writeBack(context: Context, name: String, values: Map<String, Any?>): Boolean =
        runCatching {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            values.forEach { (key, value) ->
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
internal class RestoreOutcome(
    val ok: Boolean,
    val message: String,
    val restoredPrefs: Int = 0,
    val restoredEntries: Int = 0,
    val snapshotName: String? = null,
    val needsPassword: Boolean = false,
)
