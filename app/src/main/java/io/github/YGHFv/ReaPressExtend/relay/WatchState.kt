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

/**
 * 自动轮查的**跨进程存续状态**：下次该动手的时刻、以及本轮已经问过哪些单号。
 *
 * ## 为什么必须有它（2026-09-27 用户报的「每次更新都会重新开始、不走间隔」）
 *
 * 以前「下次什么时候问」只活在协程的一个 `delay` 里，`round` 的进度只在内存里。于是
 * **每一次模块进程重启**（装新包、划掉后台、系统回收、重启手机）都让节奏归零：
 * 服务一起来就立刻从头问一遍 —— 明明上一轮 1 分钟前刚问完，间隔完全没被尊重。
 *
 * 而模块进程被重启是**常态**，不是异常：调试期每装一次包就一次，平时也会被系统回收。
 * 所以节奏必须落盘，这是它唯一的正确位置。
 *
 * ## 两个字段
 *
 * - [nextDueAt]：下一次允许发请求的**绝对时刻**。写在每次等待开始**之前**（`now + 间隔`），
 *   重启后读出来接着等剩下的那段 —— 而不是从头等一个完整的间隔，也不是立刻动手；
 * - [roundDone]：本轮已经问过的单号集合。一轮的语义是「把手里每个在途件都问一遍」，
 *   所以重启后应该**接着问剩下的**，而不是从第一个重新问（那会让排在前面的件被问两遍）。
 *
 * 两个字段都只是**节流用的建议值**，不参与任何判定：读出来是脏值时最坏情况是「早问一次」
 * 或「多等一轮」，不会丢数据、也不会漏拉。
 */
internal object WatchState {

    private const val PREFS = "reapress_watch_state"
    private const val KEY_NEXT_DUE_AT = "next_due_at"
    private const val KEY_ROUND_DONE = "round_done"

    /**
     * 本轮已问过的单号上限。
     *
     * 正常一轮几十件（用户手里的在途件量级），上限只是防「load 出来的件数异常大」时
     * 把这个字符串写成一个巨大的值 —— 存不下就整体清空（代价是重问一轮）。
     */
    private const val MAX_ROUND_DONE = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 下次允许发请求的时刻；没记录过（首次运行）返回 0 = 立刻可以开始。 */
    fun nextDueAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_NEXT_DUE_AT, 0L) }.getOrDefault(0L)

    fun setNextDueAt(context: Context, atMillis: Long) {
        runCatching { prefs(context).edit().putLong(KEY_NEXT_DUE_AT, atMillis).apply() }
    }

    /** 本轮已问过的单号（换行拼的字符串，与设置里其他集合的存法一致）。 */
    fun roundDone(context: Context): Set<String> =
        runCatching {
            prefs(context).getString(KEY_ROUND_DONE, null)
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrDefault(emptySet())

    /**
     * 记下「这个单号本轮问过了」。
     *
     * 用 `apply()`（异步落盘）而不是 `commit()`：这里晚几毫秒落盘无所谓（下一次读的是
     * 内存里那份），而它跑在拉取循环里，不值得为它阻塞。
     */
    fun markRoundDone(context: Context, tracking: String, already: Set<String>) {
        val updated = (already + tracking).take(MAX_ROUND_DONE)
        runCatching { prefs(context).edit().putString(KEY_ROUND_DONE, updated.joinToString("\n")).apply() }
    }

    /** 一轮跑完（或没有可问的件）时清空，下一轮从零开始。 */
    fun clearRoundDone(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ROUND_DONE).apply() }
    }

    /** 诊断用的一行。 */
    fun describe(context: Context): String =
        "nextDueIn=${(nextDueAt(context) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000}s " +
            "roundDone=${roundDone(context).size}"
}
