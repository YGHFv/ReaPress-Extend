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

package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 看门狗状态回传。`/data/system` 模块 App 连目录都进不去，状态只能由 system_server 主动广播推过来；
 * 推送时机是每次开机安装 hook 之后（「被拒绝安装」这个最关键的信息正好在那时产生）。
 */
internal object WatchdogReporter {

    const val ACTION_WATCHDOG_STATUS = "io.github.YGHFv.ReaPressExtend.WATCHDOG_STATUS"

    const val EXTRA_INSTALLED = "installed"
    const val EXTRA_DISABLED = "disabled"
    const val EXTRA_REASON = "reason"
    const val EXTRA_DESCRIBE = "describe"

    /** 把本次开机的看门狗决定推给模块 App 进程。 */
    fun reportBoot(context: Context?, installed: Boolean, describe: String) {
        if (context == null) {
            // 拿不到 context 只影响 UI 显示，不影响 hook。
            XposedBridge.log("watchdog report skipped: no system context available")
            return
        }
        val disabled = !installed
        val reason = if (disabled) {
            "hook 未安装（看门狗熔断或框架不支持）"
        } else {
            ""
        }
        runCatching {
            val intent = Intent(ACTION_WATCHDOG_STATUS).apply {
                // 模块 App 可能还没启动过（stopped），没有这个 flag 收不到。
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                putExtra(EXTRA_INSTALLED, installed)
                putExtra(EXTRA_DISABLED, disabled)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_DESCRIBE, describe)
            }
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            // logAlways：这行是「状态有没有推出去」的唯一证据，不能被简洁日志吞掉。
            XposedBridge.logAlways("watchdog state pushed to module app: installed=$installed $describe")
        }.onFailure {
            XposedBridge.logError("watchdog report failed", it)
        }
    }

    /**
     * 模块 App 侧：把收到的状态落进本地 prefs（模块进程随时可能被回收）。
     * 不写 `KEY_HOOK_DISABLED_BY_WATCHDOG`：广播分不清看门狗熔断与 ROM 不兼容，
     * 后者被标成熔断会让用户去复位一个没熔断的东西；真熔断由 describe 里的 `disabled=true` 体现。
     */
    fun persistLocally(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG_STATUS) return
        val installed = intent.getBooleanExtra(EXTRA_INSTALLED, false)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val describe = intent.getStringExtra(EXTRA_DESCRIBE).orEmpty()
        runCatching {
            ExpressSettingsKeys.localPrefs(context).edit()
                .putBoolean(KEY_LAST_BOOT_HOOK_INSTALLED, installed)
                .putString(KEY_LAST_BOOT_REASON, reason)
                .putString(KEY_LAST_BOOT_DESCRIBE, describe)
                .putLong(KEY_LAST_BOOT_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** 最近一次开机时 hook 是否装上。未收到过任何广播时为 null（未知）。 */
    fun lastBootInstalled(context: Context): Boolean? {
        val prefs = ExpressSettingsKeys.localPrefs(context)
        if (!prefs.contains(KEY_LAST_BOOT_AT)) return null
        return prefs.getBoolean(KEY_LAST_BOOT_HOOK_INSTALLED, false)
    }

    /** 看门狗是否真的熔断了（从 system_server 推来的 describe 里解析）。 */
    fun watchdogTripped(context: Context): Boolean =
        lastBootDescribe(context).contains("disabled=true")

    fun lastBootAt(context: Context): Long =
        ExpressSettingsKeys.localPrefs(context).getLong(KEY_LAST_BOOT_AT, 0L)

    fun lastBootReason(context: Context): String =
        ExpressSettingsKeys.localPrefs(context).getString(KEY_LAST_BOOT_REASON, "").orEmpty()

    fun lastBootDescribe(context: Context): String =
        ExpressSettingsKeys.localPrefs(context).getString(KEY_LAST_BOOT_DESCRIBE, "").orEmpty()

    private const val KEY_LAST_BOOT_HOOK_INSTALLED = "last_boot_hook_installed"
    private const val KEY_LAST_BOOT_REASON = "last_boot_reason"
    private const val KEY_LAST_BOOT_DESCRIBE = "last_boot_describe"
    private const val KEY_LAST_BOOT_AT = "last_boot_at"
}
