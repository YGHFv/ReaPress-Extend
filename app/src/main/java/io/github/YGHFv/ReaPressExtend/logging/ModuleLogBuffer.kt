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

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class ModuleLogEntry(
    val at: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/** 模块进程的诊断日志缓冲：INFO 被「简洁日志」抑制、logcat 里看不到，这里先收进内存环形缓冲，并落到 files/module-log.txt（记录发生在过滤之前；落盘走单线程后台队列，不阻塞调用方）。 */
object ModuleLogBuffer {
    private const val MAX_MEMORY_ENTRIES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val FILE_NAME = "module-log.txt"

    private val lock = Any()
    private val entries = ArrayDeque<ModuleLogEntry>()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReaPressLogWriter").apply { isDaemon = true }
    }

    @Volatile private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun attach(context: Context) {
        if (logFile != null) return
        synchronized(lock) {
            if (logFile == null) {
                logFile = runCatching { File(context.applicationContext.filesDir, FILE_NAME) }.getOrNull()
            }
        }
    }

    fun record(level: String, tag: String, message: String) {
        val entry = ModuleLogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_MEMORY_ENTRIES) entries.removeFirst()
        }
        val target = logFile ?: return
        val line = "${timeFormat.format(Date(entry.at))} $level/$tag: $message"
        runCatching { io.execute { appendLine(target, line) } }
    }

    fun snapshot(): List<ModuleLogEntry> = synchronized(lock) { entries.toList() }.asReversed()

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
        val target = logFile ?: return
        runCatching { io.execute { runCatching { target.delete() } } }
    }

    fun filePath(): String? = logFile?.takeIf { it.exists() }?.absolutePath

    fun formatTime(at: Long): String = timeFormat.format(Date(at))

    private fun appendLine(target: File, line: String) {
        runCatching {
            // 先裁剪再追加：日志文件长期只增不减会把用户存储吃满。
            if (target.isFile && target.length() > MAX_FILE_BYTES) {
                val kept = target.readLines().takeLast(MAX_MEMORY_ENTRIES)
                target.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            target.appendText(line + "\n")
        }.onFailure {
            Log.w(LOG_TAG, "module log write failed: ${it.message}")
        }
    }

    private const val LOG_TAG = "ReaPressLog"
}
