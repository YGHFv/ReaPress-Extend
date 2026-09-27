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

package io.github.YGHFv.ReaPressExtend.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 自动轮查的排程规则，全是纯函数（不读时钟、不碰系统状态），边界（跨零点、窗口端点）写成可单测的判据。
 * 真正读时钟的只有 `AutoWatchService` 里的 `System.currentTimeMillis()`。
 */
object WatchSchedule {

    /**
     * 默认件间 3 分钟：9-26 实测 1.5s 五连发第 6 个就被淘宝 RGV587 拦，风控看的是连发的形状。
     * 默认值必须与 `ExpressSettingsKeys.DEFAULT_WATCH_*` 一致，两边漂了会让「没动过设置」的
     * 用户看到与实际不符的节奏。
     */
    const val DEFAULT_BASE_GAP_MS = 3 * 60_000L

    /** 抖动 ±1 分钟：固定 3 分钟整的请求序列是很好认的机器指纹。 */
    const val DEFAULT_GAP_JITTER_MS = 60_000L

    /** 一轮跑完等 30 分钟：件在路上是小时级变化，更密问的是同一句话。 */
    const val DEFAULT_CYCLE_WAIT_MS = 30 * 60_000L

    /** 件间隔范围：下限 1 分钟再密就回到连发形状；上限 30 分钟再疏就成「偶尔看一眼」。 */
    const val MIN_GAP_MINUTES = 1
    const val MAX_GAP_MINUTES = 30
    const val MIN_CYCLE_MINUTES = 5
    const val MAX_CYCLE_MINUTES = 6 * 60

    /** 从设置读出的分钟数一律夹进合法区间（手改 XML / 旧版本残留的值都从这里过）。 */
    fun clampGapMinutes(minutes: Int): Int = minutes.coerceIn(MIN_GAP_MINUTES, MAX_GAP_MINUTES)

    fun clampCycleMinutes(minutes: Int): Int = minutes.coerceIn(MIN_CYCLE_MINUTES, MAX_CYCLE_MINUTES)

    /** 签收/失败已闭环、UNKNOWN 没进物流，问它们白花请求额度。 */
    private val NEVER_WATCHED = setOf(
        ExpressStatus.UNKNOWN,
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    /** 人在等但件已不在路上的状态，只在范围放宽到「未完成」时算进来（默认只查在途）。 */
    private val ARRIVED_ONLY = setOf(
        ExpressStatus.CREATED,
        ExpressStatus.PICKED_UP,
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
    )

    /** 用户自己标记已取的件不查——再问物流只会得到与现实不符的答案。 */
    fun isWatchable(status: ExpressStatus, pickedUp: Boolean, allUnfinished: Boolean): Boolean {
        if (pickedUp) return false
        if (status in NEVER_WATCHED) return false
        return if (status in ARRIVED_ONLY) allUnfinished else true
    }

    /**
     * 整点小时是否落在暂停窗口 [startHour, endHour) 里。窗口跨零点（22→8）是常态。
     * start == end 视为没有窗口而不是全天——判成全天等于轮查永远不跑，而界面上看不出为什么。
     */
    fun inQuietHours(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }

    fun isQuietAt(
        atMillis: Long,
        startHour: Int,
        endHour: Int,
        quiet: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!quiet) return false
        return inQuietHours(ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone).hour,
            startHour, endHour)
    }

    /** 还要等多少毫秒走出暂停窗口（不在窗口返回 0）。跨零点时 end 在次日，补一整天。 */
    fun millisUntilQuietEnd(
        atMillis: Long,
        startHour: Int,
        endHour: Int,
        quiet: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        if (!isQuietAt(atMillis, startHour, endHour, quiet, zone)) return 0L
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        var target = now.withHour(endHour).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli() - atMillis
    }

    /**
     * 件与件之间的实际间隔：基准 ± 抖动。绝不返回负数或极小值——负的 delay 等于「立刻再来一发」，
     * 正是风控最敏感的形状。jitterMs 用 [jitterFor]：固定 ±1 分钟配 1 分钟基准会有一半概率抖到 0 以下。
     */
    fun nextGapMillis(
        jitter: Float,
        baseMs: Long = DEFAULT_BASE_GAP_MS,
        jitterMs: Long = jitterFor(baseMs),
    ): Long {
        val base = baseMs.coerceAtLeast(1_000L)
        val offset = (jitter.coerceIn(-1f, 1f) * jitterMs.coerceIn(0L, base / 2)).toLong()
        return (base + offset).coerceAtLeast(1_000L)
    }

    /** 抖动不超过基准的一半：件间隔 1 分钟时 ±1 分钟会让一半取样被夹成 1 秒，等于连发两发。 */
    fun jitterFor(baseMs: Long): Long = minOf(DEFAULT_GAP_JITTER_MS, baseMs / 2)

    /** 距离下一次该动手还有多少毫秒（已过返回 0）。存在理由见 WatchState：轮查节奏要跨进程重启成立，不能只活在协程的 delay 里。 */
    fun millisUntilDue(dueAtMillis: Long, nowMillis: Long): Long =
        (dueAtMillis - nowMillis).coerceAtLeast(0L)

    /**
     * 用户改小间隔后，把已排好的到期时刻往前拉到 now + intervalMillis。只在「新时刻更早」时改——
     * 改大间隔不该把已排好的那次往后推。dueAt <= 0 原样返回：那时该立刻动手，
     * 写一个新时刻等于凭空推迟一个间隔（首次运行会白等一轮）。
     */
    fun pullDueEarlier(dueAtMillis: Long, nowMillis: Long, intervalMillis: Long): Long {
        if (dueAtMillis <= 0L) return dueAtMillis
        val target = nowMillis + intervalMillis.coerceAtLeast(0L)
        return if (dueAtMillis > target) target else dueAtMillis
    }
}
