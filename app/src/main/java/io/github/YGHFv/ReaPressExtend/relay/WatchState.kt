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
import androidx.core.content.edit
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore

/**
 * 自动轮查的跨进程存续状态：节奏必须落盘 —— 模块进程重启是常态，否则每次重启都从头问一遍、不走间隔。
 * 两个字段都只是节流建议值，脏值最坏只是早问一次或多等一轮，不丢数据。
 */
internal object WatchState {

    /** `internal` 是为了让备份清单引用同一份来源。 */
    internal const val PREFS = "reapress_watch_state"
    private const val KEY_NEXT_DUE_AT = "next_due_at"
    private const val KEY_ROUND_DONE = "round_done"

    /** 防异常大的件数把字符串撑爆；超了整体清空（代价是重问一轮）。 */
    private const val MAX_ROUND_DONE = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 下次允许发请求的时刻；没记录过（首次运行）返回 0 = 立刻可以开始。 */
    fun nextDueAt(context: Context): Long =
        ExpressRecordStore.withTransaction {
            runCatching { prefs(context).getLong(KEY_NEXT_DUE_AT, 0L) }.getOrDefault(0L)
        }

    fun setNextDueAt(context: Context, atMillis: Long): Unit = ExpressRecordStore.withTransaction {
        runCatching { prefs(context).edit { putLong(KEY_NEXT_DUE_AT, atMillis) } }
    }

    /** 换行拼的字符串。 */
    fun roundDone(context: Context): Set<String> =
        ExpressRecordStore.withTransaction {
            runCatching {
                prefs(context).getString(KEY_ROUND_DONE, null)
                    .orEmpty()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }.getOrDefault(emptySet())
        }

    /** 用 `apply()`：晚几毫秒落盘无所谓，不值得为它阻塞拉取循环。 */
    fun markRoundDone(context: Context, tracking: String): Unit = ExpressRecordStore.withTransaction {
        val updated = (roundDone(context) + tracking).take(MAX_ROUND_DONE)
        runCatching { prefs(context).edit { putString(KEY_ROUND_DONE, updated.joinToString("\n")) } }
    }

    fun clearRoundDone(context: Context): Unit = ExpressRecordStore.withTransaction {
        runCatching { prefs(context).edit { remove(KEY_ROUND_DONE) } }
    }

    fun describe(context: Context): String =
        "nextDueIn=${(nextDueAt(context) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000}s " +
            "roundDone=${roundDone(context).size}"
}
