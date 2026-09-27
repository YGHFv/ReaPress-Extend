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

package io.github.YGHFv.ReaPressExtend.logging

import android.content.Intent
import android.util.Log

internal enum class ModuleLogLevel {
    INFO,
    WARN,
    ERROR,
}

internal fun shouldEmitModuleLog(
    conciseLogEnabled: Boolean,
    level: ModuleLogLevel,
): Boolean = !conciseLogEnabled || level == ModuleLogLevel.ERROR

/** 从日志文案推断级别：大量历史日志是「一句话描述」没传级别，误判成 ERROR 会让简洁日志下满屏假错误。 */
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

/** 只写 logcat + 诊断缓冲的日志出口：不能引用任何 libxposed 类型（模块自己的进程也会加载它）。 */
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
        // 先收进诊断缓冲再判过滤：被简洁日志吞掉的恰恰是排查时最想看的。
        ModuleLogBuffer.record(level.name, tag, message)
        if (!shouldEmitModuleLog(ModuleLogState.conciseLogEnabled, level)) return
        when (level) {
            ModuleLogLevel.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
            ModuleLogLevel.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
            ModuleLogLevel.INFO -> if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
        }
    }
}
