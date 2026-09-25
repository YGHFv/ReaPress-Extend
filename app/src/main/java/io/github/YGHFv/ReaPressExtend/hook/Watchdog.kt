package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.io.File
import java.util.Properties

/**
 * system_server 侧的开机看门狗。
 *
 * **为什么必须有**：hook 装在 system_server 里，一旦在启动早期把它搞崩，设备会反复重启，
 * 用户除了刷机没有别的办法。虽然 libxposed 的 `ExceptionMode.PROTECTIVE` 已经兜住了
 * hook 抛出的异常，但 `Error`（NoClassDefFoundError / StackOverflowError）、
 * 死锁、以及 hook 本身引发的框架状态错乱都不在那个保护范围内。
 *
 * **机制**：用一个跨启动的计数器记录「装过 hook 的启动次数」与「活到 N 秒的启动次数」。
 * 下次启动时若发现上一次没活到标记点，说明装 hook 很可能就是崩因 —— 连续两次就把 hook
 * 彻底停掉，让设备能正常开机。停掉后只能由用户在界面上手动复位。
 *
 * **为什么状态存在 `/data/system/`**：system_server 是 `system` uid，只能写自己的地盘；
 * 模块 App 进程（uid 10xxx）读不到这里，所以它通过广播接收看门狗的状态通知（见
 * [io.github.YGHFv.ReaPressExtend.relay.ExpressRelay]）。
 *
 * 文件用 Java Properties 格式而不是 JSON：不需要引任何 JSON 库，system_server 里少一个依赖
 * 就少一个出错点。
 */
internal object Watchdog {

    /** 活到这个时长才算「这次启动是好的」。太短会漏判（崩溃可能发生在稍晚），太长会拖慢熔断。 */
    private const val SURVIVAL_WINDOW_MILLIS = 90_000L

    /**
     * 连续失败多少次后熔断。
     *
     * 取 1：第 1 次失败仍放行（可能是用户自己重启、OTA 之类与 hook 无关的崩溃），
     * 第 2 次连续失败就熔断。用户实际体验是「坏两次启动，第三次自动恢复」——
     * 这个时长可以接受，而再放宽会让卡在开机循环里的人多等好几轮。
     *
     * 宁可偶尔误熔断（用户在界面上点一下就能复位），也不要让设备多转几圈 ——
     * 误熔断是可恢复的，开不了机不是。
     */
    private const val MAX_CONSECUTIVE_FAILURES = 1

    private val candidates = listOf(
        File("/data/system/reapress_extend_watchdog.properties"),
        // /data/system 不可写时的退路。两个都写不了就只能放弃看门狗（见 write 里的注释）。
        File("/data/local/tmp/reapress_extend_watchdog.properties"),
    )

    private const val KEY_ATTEMPT = "attempt"
    private const val KEY_OK = "ok"
    private const val KEY_FAILURES = "failures"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_REASON = "reason"

    /** 看门狗决定。 */
    sealed interface Decision {
        /** 允许安装 hook。 */
        data class Install(val attempt: Int) : Decision

        /** 拒绝安装。设备能正常开机，但模块不工作 —— 由用户在界面上决定是否复位。 */
        data class Refuse(val reason: String) : Decision
    }

    /**
     * 在安装 hook 之前调用。
     *
     * @param forceEnabled 用户在界面上显式要求重新启用（复位熔断）时为 true，跳过 disabled 判定
     */
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

    /**
     * 启动存活确认。由 [SystemServerHook] 在 hook 装好之后延迟调用。
     *
     * 走到这里就说明「装了 hook 的这次启动活够久了」，把 ok 推到当前 attempt。
     */
    fun markBootSurvived() {
        val state = read()
        write(state.copy(ok = state.attempt, failures = 0))
        XposedBridge.log("watchdog: boot survived window, attempt=${state.attempt} marked ok")
    }

    /** 供诊断显示。 */
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
            // 两个位置都写不了。**不因此拒绝安装 hook**：写不了文件说明这次也没法检测
            // 下次启动，但模块的核心价值（拦截快递通知）还在，而 PROTECTIVE 异常模式
            // 才是真正的安全网。这里只把问题记清楚，方便用户排查。
            XposedBridge.logError(
                "watchdog: cannot persist state to any candidate path " +
                    candidates.joinToString { it.absolutePath } +
                    " — bootloop protection is INACTIVE",
            )
        }
    }

    /** 存活窗口时长，供 [SystemServerHook] 排定延迟任务。 */
    val survivalWindowMillis: Long get() = SURVIVAL_WINDOW_MILLIS
}
