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

package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.io.File
import java.util.Properties

/** system_server 侧开机看门狗：连续失败即彻底停 hook 防开机循环；状态放 /data/system/（模块 App 读不到、只有 system_server 能写），停用后只能由用户在界面复位。 */
internal object Watchdog {

    private const val SURVIVAL_WINDOW_MILLIS = 90_000L

    /** 第 1 次连续失败仍放行，第 2 次停用。 */
    private const val MAX_CONSECUTIVE_FAILURES = 1

    private val candidates = listOf(
        File("/data/system/reapress_extend_watchdog.properties"),
        // /data/system 不可写时的退路。
        File("/data/local/tmp/reapress_extend_watchdog.properties"),
    )

    private const val KEY_ATTEMPT = "attempt"
    private const val KEY_OK = "ok"
    private const val KEY_FAILURES = "failures"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_REASON = "reason"

    sealed interface Decision {
        data class Install(val attempt: Int) : Decision

        data class Refuse(val reason: String) : Decision
    }

    fun beforeInstall(forceEnabled: Boolean): Decision {
        val state = read()
        if (state.disabled && !forceEnabled) {
            return Decision.Refuse(state.reason.ifBlank { "看门狗已熔断" })
        }

        // attempt > ok 说明上一次装了 hook 的启动没能活到标记点
        val previousFailed = state.attempt > state.ok
        val failures = if (previousFailed) state.failures + 1 else 0

        if (previousFailed && failures > MAX_CONSECUTIVE_FAILURES) {
            val reason = "连续 $failures 次启动在安装 hook 后未能正常完成，已自动停用 hook 以防开机循环"
            write(state.copy(failures = failures, disabled = true, reason = reason))
            return Decision.Refuse(reason)
        }

        val attempt = state.attempt + 1
        write(state.copy(attempt = attempt, failures = failures, disabled = false))
        return Decision.Install(attempt)
    }

    fun markBootSurvived() {
        val state = read()
        write(state.copy(ok = state.attempt, failures = 0))
        XposedBridge.log("watchdog: boot survived window, attempt=${state.attempt} marked ok")
    }

    fun describe(): String {
        val state = read()
        return buildString {
            append("attempt=").append(state.attempt)
            append(" ok=").append(state.ok)
            append(" failures=").append(state.failures)
            append(" disabled=").append(state.disabled)
            if (state.reason.isNotBlank()) append(" reason=").append(state.reason)
        }
    }

    private data class State(
        val attempt: Int = 0,
        val ok: Int = 0,
        val failures: Int = 0,
        val disabled: Boolean = false,
        val reason: String = "",
    )

    private fun read(): State {
        val file = candidates.firstOrNull { it.isFile } ?: return State()
        return runCatching {
            val props = Properties()
            file.inputStream().use(props::load)
            State(
                attempt = props.getProperty(KEY_ATTEMPT, "0").toIntOrNull() ?: 0,
                ok = props.getProperty(KEY_OK, "0").toIntOrNull() ?: 0,
                failures = props.getProperty(KEY_FAILURES, "0").toIntOrNull() ?: 0,
                disabled = props.getProperty(KEY_DISABLED, "false").toBoolean(),
                reason = props.getProperty(KEY_REASON, ""),
            )
        }.getOrElse {
            XposedBridge.log("watchdog: read failed: ${it.javaClass.simpleName}: ${it.message}")
            State()
        }
    }

    private fun write(state: State) {
        val props = Properties().apply {
            setProperty(KEY_ATTEMPT, state.attempt.toString())
            setProperty(KEY_OK, state.ok.toString())
            setProperty(KEY_FAILURES, state.failures.toString())
            setProperty(KEY_DISABLED, state.disabled.toString())
            setProperty(KEY_REASON, state.reason)
        }
        var written = false
        for (file in candidates) {
            val result = runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { props.store(it, "ReaPress Express watchdog state") }
            }
            if (result.isSuccess) {
                written = true
                break
            }
        }
        if (!written) {
            // 两个位置都写不了：不因此拒绝安装 hook（核心功能仍在），只记日志。
            XposedBridge.logError(
                "watchdog: cannot persist state to any candidate path " +
                    candidates.joinToString { it.absolutePath } +
                    " — bootloop protection is INACTIVE",
            )
        }
    }

    val survivalWindowMillis: Long get() = SURVIVAL_WINDOW_MILLIS
}
