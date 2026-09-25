package io.github.YGHFv.ReaPressExtend.logging

import android.content.Intent
import android.util.Log

internal enum class ModuleLogLevel {
    INFO,
    WARN,
    ERROR,
}

/**
 * 「简洁日志」判定：默认只放行 ERROR，INFO/WARN 只进诊断缓冲、不进 logcat。
 *
 * 模块常年跑在后台，全量日志会把 logcat 刷爆、也会把诊断缓冲的环形空间占满，
 * 真正要排查的错误反而被挤掉。
 */
internal fun shouldEmitModuleLog(
    conciseLogEnabled: Boolean,
    level: ModuleLogLevel,
): Boolean = !conciseLogEnabled || level == ModuleLogLevel.ERROR

/**
 * 从日志文案推断级别。
 *
 * 历史原因：模块里大量日志是「一句话描述」形式（`"xxx failed"` / `"xxx installed"`），
 * 调用方并没有传级别。这里按关键词推断，避免把「成功装了」也标成错误——
 * 那会让简洁日志下满屏都是假错误。
 */
internal fun legacyModuleLogLevel(message: String): ModuleLogLevel {
    val normalized = message.lowercase()
    if (
        normalized.contains("failed=0") ||
        normalized.contains("failure=0") ||
        normalized.contains("failures=0") ||
        (normalized.contains("failed") && normalized.contains("fallback to")) ||
        (
            normalized.contains("crash") &&
                listOf("installed", "switched", "cleared").any(normalized::contains)
        )
    ) {
        return ModuleLogLevel.INFO
    }
    return if (
        normalized.contains(" failed") ||
        normalized.startsWith("failed") ||
        normalized.contains(" failure") ||
        normalized.contains(" exception") ||
        normalized.contains(" crash") ||
        normalized.contains("onerror") ||
        normalized.contains("stacktrace")
    ) {
        ModuleLogLevel.ERROR
    } else {
        ModuleLogLevel.INFO
    }
}

internal object ModuleLogState {
    @Volatile
    var conciseLogEnabled: Boolean = true

    fun applyFromIntent(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_CONCISE_LOG_ENABLED) == true) {
            conciseLogEnabled = intent.getBooleanExtra(EXTRA_CONCISE_LOG_ENABLED, true)
        }
    }

    const val EXTRA_CONCISE_LOG_ENABLED = "io.github.YGHFv.ReaPressExtend.extra.CONCISE_LOG_ENABLED"
}

/**
 * 只写 logcat + 诊断缓冲的日志出口。
 *
 * **不能引用任何 libxposed 类型**：这个 object 会被模块自己的进程加载。
 */
internal object ModuleAndroidLog {
    fun info(tag: String, message: String, throwable: Throwable? = null) {
        emit(ModuleLogLevel.INFO, tag, message, throwable)
    }

    fun legacy(tag: String, message: String, throwable: Throwable? = null) {
        emit(legacyModuleLogLevel(message), tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        emit(ModuleLogLevel.ERROR, tag, message, throwable)
    }

    private fun emit(
        level: ModuleLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        // 先收进诊断缓冲再判过滤：被「简洁日志」吞掉的那些恰恰是排查时最想看的，
        // 模块主界面要能翻到它们（logcat 里看不到）。
        ModuleLogBuffer.record(level.name, tag, message)
        if (!shouldEmitModuleLog(ModuleLogState.conciseLogEnabled, level)) return
        when (level) {
            ModuleLogLevel.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            ModuleLogLevel.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
            ModuleLogLevel.INFO -> if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
        }
    }
}
