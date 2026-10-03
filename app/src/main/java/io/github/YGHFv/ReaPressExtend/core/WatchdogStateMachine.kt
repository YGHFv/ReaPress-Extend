package io.github.YGHFv.ReaPressExtend.core

internal object WatchdogStateMachine {
    data class State(
        val attempt: Int = 0,
        val ok: Int = 0,
        val failures: Int = 0,
        val disabled: Boolean = false,
        val reason: String = "",
        val consumedResetId: String = "",
    )

    sealed interface Decision {
        data class Install(val attempt: Int) : Decision
        data class Refuse(val reason: String) : Decision
    }

    data class Transition(val state: State, val decision: Decision)

    fun beforeInstall(previous: State, resetRequestId: String?): Transition {
        val newReset = resetRequestId?.takeIf {
            it.isNotBlank() && it.length <= 128 && it != previous.consumedResetId
        }
        val state = if (newReset != null) State(consumedResetId = newReset) else previous
        if (state.disabled) {
            return Transition(state, Decision.Refuse(state.reason.ifBlank { "看门狗已熔断" }))
        }
        val previousFailed = state.attempt > state.ok
        val failures = if (previousFailed) state.failures.coerceIn(0, 1) + 1 else 0
        if (failures >= 2) {
            val reason = "连续 $failures 次启动在安装 hook 后未能正常完成，已自动停用 hook 以防开机循环"
            return Transition(state.copy(failures = failures, disabled = true, reason = reason), Decision.Refuse(reason))
        }
        val overflow = state.attempt == Int.MAX_VALUE
        val attempt = if (overflow) 1 else state.attempt.coerceAtLeast(0) + 1
        return Transition(
            state.copy(attempt = attempt, ok = if (overflow) 0 else state.ok, failures = failures, reason = ""),
            Decision.Install(attempt),
        )
    }

    fun survived(state: State): State =
        if (state.disabled) state else state.copy(ok = state.attempt, failures = 0, reason = "")
}
