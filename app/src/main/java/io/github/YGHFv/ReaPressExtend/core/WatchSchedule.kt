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
 * 自动轮查的排程规则 —— 全部是**纯函数**，不读时钟、不碰系统状态。
 *
 * 抽出来单独成文件、单独测，是因为这一族的判据全是「边界」：
 * 22:00 开始的夜间窗口到底含不含 22:00 那一记、跨零点怎么算、刚好等于 8:00 时该不该恢复 ——
 * 这些在服务里靠真机等一小时是测不出来的，写成纯函数却可以逐条钉死。
 * 真正读时钟的那一处只有 `AutoWatchService` 里的 `System.currentTimeMillis()`。
 */
object WatchSchedule {

    /**
     * 相邻两个包裹之间的**默认**基准间隔。
     *
     * 3 分钟不是随手取的：9-26 实测「1.5s 五连发到第 6 个就被淘宝 RGV587 拦」，
     * 而按需拉取时单发一直没事 —— 说明风控看的是**连发的形状**，不是总量。
     * 3 分钟一件的速度对人是「一直在动」，对服务端只是零星的正常查询。
     *
     * 2026-09-27 起用户可在设置里改成别的值（[MIN_GAP_MINUTES]..[MAX_GAP_MINUTES]），
     * 所以下面这些函数都收参数；常量只剩「默认值」一种身份。默认值必须与
     * `ExpressSettingsKeys.DEFAULT_WATCH_*` 一致，两边漂了会让「没动过设置」的用户
     * 在界面上看到与实际不符的节奏。
     */
    const val DEFAULT_BASE_GAP_MS = 3 * 60_000L

    /** 间隔的抖动幅度（±1 分钟）。固定 3 分钟整的请求序列是个很好认的机器指纹。 */
    const val DEFAULT_GAP_JITTER_MS = 60_000L

    /**
     * 一轮跑完之后等多久再开下一轮。
     *
     * 30 分钟：件在路上时的变化尺度是小时级（揽收 → 转运 → 派送），比这更密问的是同一句
     * 话；比这更疏又会让「已到站」这类要用户动手的事晚半小时才知道。
     */
    const val DEFAULT_CYCLE_WAIT_MS = 30 * 60_000L

    /**
     * 件间隔的允许范围（分钟）。
     *
     * 下限 1 分钟：再密就回到「连发」的形状上了（上面那条 RGV587 的实证）。
     * 上限 30 分钟：比 30 分钟更疏的档配「一轮还要再等 30 分钟」就等于半天才问一次，
     * 那已经不是轮查而是「偶尔看一眼」，用别的开关更合适。
     */
    const val MIN_GAP_MINUTES = 1
    const val MAX_GAP_MINUTES = 30

    /** 轮间隔的允许范围（分钟）。下限 5 分钟 —— 比一件的间隔还短的「轮间隔」是个矛盾的配置。 */
    const val MIN_CYCLE_MINUTES = 5
    const val MAX_CYCLE_MINUTES = 6 * 60

    /** 从设置里读出来的分钟数一律夹进合法区间（手改 XML / 旧版本残留的值都从这里过）。 */
    fun clampGapMinutes(minutes: Int): Int = minutes.coerceIn(MIN_GAP_MINUTES, MAX_GAP_MINUTES)

    fun clampCycleMinutes(minutes: Int): Int = minutes.coerceIn(MIN_CYCLE_MINUTES, MAX_CYCLE_MINUTES)


    /**
     * 这些状态**从来不在**轮查范围里：要么已经闭环（签收 / 投递失败的结果不会再变），
     * 要么压根没进入物流（UNKNOWN）。问它们纯属白花请求额度。
     */
    private val NEVER_WATCHED = setOf(
        ExpressStatus.UNKNOWN,
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    /**
     * 这些是「人还在等，但件已经不在路上」的状态，只有把范围放宽到「未完成」时才算进来。
     *
     * 到站待取件放进来是有代价的：它往往占着手里一半的件，一瞬间就把每轮的请求数翻倍。
     * 好处也实在 —— 取件码 / 驿站动态同样是「早点知道就好」的信息。所以它是个选项，
     * 默认不选（用户 2026-09-27 明确要求的默认值：只查在途）。
     */
    private val ARRIVED_ONLY = setOf(
        ExpressStatus.CREATED,
        ExpressStatus.PICKED_UP,
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
    )

    /**
     * 这件要不要进这一轮。
     *
     * @param allUnfinished 轮查范围是否放宽到「未完成」（含到站待取件）。
     * @param pickedUp 用户自己标记过「已取件」的件不查 —— 那件已经在用户手里了，
     *   再问物流只会得到「还没签收」这种和现实不符的答案，白占一次请求。
     */
    fun isWatchable(status: ExpressStatus, pickedUp: Boolean, allUnfinished: Boolean): Boolean {
        if (pickedUp) return false
        if (status in NEVER_WATCHED) return false
        return if (status in ARRIVED_ONLY) allUnfinished else true
    }

    /**
     * 整点小时 [hour] 是否落在暂停窗口 [startHour, endHour) 里。
     *
     * 窗口**跨零点**是常态（22 → 8）：`start > end` 时判据是「不早于 start **或** 早于 end」。
     * `start == end` 视为**没有窗口**而不是「全天」—— 「22 点到 22 点」的直觉读法是
     * 「不留暂停时间」，判成全天等于轮查永远不跑，而界面上又看不出为什么。
     */
    fun inQuietHours(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }

    /** [atMillis] 那一刻是否在暂停窗口里。时区显式传入，便于单测钉住边界。 */
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

    /**
     * 还要等多少毫秒才走出暂停窗口（不在窗口里返回 0）。
     *
     * 返回的是「到 [endHour] 整点」的距离 —— 醒来时窗口刚好结束，不必再醒一次确认。
     * 窗口跨零点时 end 落在**次日**：直接拿当天的 end 减会得到负数，
     * 那种情况下补一整天。
     */
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
     * 件与件之间的实际间隔：基准 ± 抖动。
     *
     * @param jitter `-1f..1f`（调用方给随机数）。越界值夹回区间，绝不返回负数 ——
     *   一个负的 delay 在协程里等于「立刻再来一发」，正是风控最敏感的形状。
     * @param baseMs 基准间隔（来自设置，默认 3 分钟）。
     * @param jitterMs 抖动幅度。**不要直接传 [DEFAULT_GAP_JITTER_MS]** —— 用 [jitterFor]：
     *   固定 ±1 分钟配一个 1 分钟的基准，有一半概率抖到 0 以下。
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

    /**
     * 给定基准间隔，抖动该取多大。
     *
     * 默认取「±1 分钟」——但**不超过基准的一半**：件间隔调到 1 分钟时，±1 分钟的抖动会让
     * 一半的取样落到 0 或负数上（被夹成 1 秒），那就等于连着发两发，正是要避免的形状。
     */
    fun jitterFor(baseMs: Long): Long = minOf(DEFAULT_GAP_JITTER_MS, baseMs / 2)

    /**
     * 距离下一次该动手还有多少毫秒（已经过了返回 0）。
     *
     * 存在的理由见 `WatchState`：轮查的节奏要**跨进程重启**成立。以前「下次什么时候问」
     * 只活在协程里（一个 `delay`），换包 / 划掉后台 / 重启之后全归零，服务一起来就立刻
     * 从头问一遍 —— 用户报的「每次更新都会重新开始、不走间隔」就是它。
     */
    fun millisUntilDue(dueAtMillis: Long, nowMillis: Long): Long =
        (dueAtMillis - nowMillis).coerceAtLeast(0L)

    /**
     * 用户改了间隔之后，把已排好的到期时刻**往前**拉到 `now + intervalMillis`。
     *
     * 三条规则，缺一条都会出问题：
     *
     * - `dueAtMillis <= 0`（没排过 / 已经到点）**原样返回**：那时该立刻动手，
     *   写一个新时刻进去等于凭空推迟一个间隔 —— 首次运行会因此白等一轮；
     * - 只在**新时刻更早**时才改：改大间隔不该把已经排好的那次往后推
     *   （往后退等于「调大了间隔，于是每件都多扣一段时间」，且到点该问的件被无端推迟）；
     * - 间隔为负按 0 处理：手改 XML 写个负数进来时，最坏结果也应该是「立刻可以做」，
     *   而不是一个过去的时刻把等待算成 0 后再被别处误读。
     */
    fun pullDueEarlier(dueAtMillis: Long, nowMillis: Long, intervalMillis: Long): Long {
        if (dueAtMillis <= 0L) return dueAtMillis
        val target = nowMillis + intervalMillis.coerceAtLeast(0L)
        return if (dueAtMillis > target) target else dueAtMillis
    }
}
