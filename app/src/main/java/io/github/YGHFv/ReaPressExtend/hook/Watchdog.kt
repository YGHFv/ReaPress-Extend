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
import io.github.YGHFv.ReaPressExtend.core.WatchdogStateMachine
import java.io.File

/** system_server 侧开机看门狗：连续失败即彻底停 hook 防开机循环；状态放 /data/system/（模块 App 读不到、只有 system_server 能写），停用后只能由用户在界面复位。 */
internal object Watchdog {

    private const val SURVIVAL_WINDOW_MILLIS = 90_000L

    private val candidates = listOf(
        File("/data/system/reapress_extend_watchdog.properties"),
        // /data/system 不可写时的退路。
        File("/data/local/tmp/reapress_extend_watchdog.properties"),
    )

    private val storage = WatchdogStateStore(candidates)

    @Synchronized
    fun beforeInstall(
        resetRequestId: String?,
        stateStore: WatchdogStateStore = storage,
    ): WatchdogStateMachine.Decision = runCatching {
        val previous = stateStore.read()
        val transition = WatchdogStateMachine.beforeInstall(previous, resetRequestId)
        if (transition.state != previous && !stateStore.write(transition.state)) {
            WatchdogStateMachine.Decision.Refuse("无法持久化看门狗状态，本次不安装 hook 以保留开机保护")
        } else {
            transition.decision
        }
    }.getOrElse {
        WatchdogStateMachine.Decision.Refuse("读取看门狗状态失败，本次不安装 hook（${it.javaClass.simpleName}）")
    }

    @Synchronized
    fun markBootSurvived(stateStore: WatchdogStateStore = storage): Boolean = runCatching {
        val state = WatchdogStateMachine.survived(stateStore.read())
        val saved = stateStore.write(state)
        if (saved) XposedBridge.log("watchdog: boot survived window, attempt=${state.attempt} marked ok")
        saved
    }.getOrDefault(false)

    fun describe(): String = runCatching {
        val state = storage.read()
        buildString {
            append("attempt=").append(state.attempt)
            append(" ok=").append(state.ok)
            append(" failures=").append(state.failures)
            append(" disabled=").append(state.disabled)
            if (state.reason.isNotBlank()) append(" reason=").append(state.reason)
        }
    }.getOrElse {
        "state unavailable (${it.javaClass.simpleName})"
    }

    val survivalWindowMillis: Long get() = SURVIVAL_WINDOW_MILLIS
}
