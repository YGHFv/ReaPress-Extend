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
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceFetcher
import io.github.YGHFv.ReaPressExtend.hook.TaobaoOrderApi
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.util.Collections
import java.util.concurrent.Executors

/**
 * **菜鸟不在时的兜底数据源** —— 用淘宝登录态直接问淘宝要订单，再借轨迹链路落库。
 *
 * ## 它补的那一格
 *
 * 「打开模块就看到最新数据」原本由两条路保证：唤醒销（把菜鸟拉起来）+ 刷新请求（请活着的
 * 菜鸟重查本地表）。两条都要求**菜鸟能被叫动**，而 HyperOS 可以把第三方发起的拉起拦掉
 * （真机实测，见 `HostWakePin`）——那时用户看到的就是「必须打开菜鸟才有新数据」。
 *
 * 这里是不依赖菜鸟进程的第三条：cookie 是现成的（`TraceCookieCache`，菜鸟 WebView 的
 * 淘宝登录态随 `ACTION_COOKIE_SYNC` 同步过一份），请求直接由模块进程发出。
 *
 * ⚠️ **覆盖面只有淘宝 / 天猫订单**（`TaobaoOrderApi` 的注释里写了原因）。它**不替代**上面
 * 两条 —— 那两条覆盖全部件（含拼多多与别人寄来的），只是要求菜鸟活着。
 *
 * ## 什么时候启动
 *
 * 只在「宿主没接话」时启动（调用方判：打开模块后等 [GRACE_MS] 仍没有
 * `ACTION_HOST_QUERY_REPORT` 回执）。宿主活着时它 1~3 秒就回执，那时这条完全是多余的 ——
 * 而每一次启动都是**真金白银的请求数**，风控按频率收网（2026-09-26 实测：连拉 5 单成功后
 * 第 6 单的预热就被拦，处罚是小时级余温）。所以宁可漏，不可多。
 *
 * ## 请求数怎么控制（三道）
 *
 * 1. **只问在途订单**（[io.github.YGHFv.ReaPressExtend.core.TaobaoOrder.isInTransit]）——
 *    已完成 / 关闭 / 未发货的订单不产生请求；
 * 2. **每次最多 [MAX_ORDERS] 个**、订单之间拉开 [MIN_INTERVAL_MS]，且同一订单号本进程只问一次；
 * 3. **整条链路 [MIN_START_INTERVAL_MS] 内不重复启动**，风控退避期内一概不启动。
 *
 * 运单号之后的轨迹拉取**不自己发** —— 交给 [CainiaoTraceFetcher]，它带着成功表 / 冷却表 /
 * 串行节拍，是这条数据通路上唯一的发射口。
 */
object CainiaoDirectFetcher {

    /** 调用方等宿主回执的宽限期。宿主活着时通常 1~3 秒就有 `host self query` 那一行。 */
    const val GRACE_MS = 5_000L

    /** 整条链路的启动节流。 */
    private const val MIN_START_INTERVAL_MS = 10 * 60_000L

    /** 每次最多问几个订单的物流页（每个 = 一次请求）。 */
    private const val MAX_ORDERS = 5

    /** 订单物流页之间的间隔，与轨迹那条的节拍一致（风控实证的临界就在 5~6 连发）。 */
    private const val MIN_INTERVAL_MS = 2_500L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-cainiao-direct").apply { isDaemon = true }
    }

    /** 本进程已经问过的订单号 —— 一次没拿到就不再问（订单不会因为多问几次就变成有运单号）。 */
    private val askedOrders = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var lastStartAt = 0L

    /** 最近一次收到宿主自查回执的时刻（[noteHostReport] 写、调用方读）。 */
    @Volatile private var lastHostReportAt = 0L

    @Volatile private var lastOutcome = "未启动"

    /** 宿主自查回执到了 —— 调用方（`ExpressRelayReceiver`）转告，用作「要不要直连」的判据。 */
    fun noteHostReport(now: Long = System.currentTimeMillis()) {
        lastHostReportAt = now
    }

    /** [at] 之后宿主有没有回过话。回过了就说明它还活着，不需要直连。 */
    fun hostAnsweredSince(at: Long): Boolean = lastHostReportAt > at

    /**
     * 启动一次直连兜底。**幂等且廉价**：被任何一道闸门挡下都只记一行日志。
     *
     * @param reason 只进日志 —— 排查时要能分清这一次是「打开模块触发的」还是别的来源。
     */
    fun start(context: Context, reason: String) {
        val app = context.applicationContext
        ModuleLogBuffer.attach(app)
        TraceCookieCache.attach(app)

        val now = System.currentTimeMillis()
        if (now - lastStartAt < MIN_START_INTERVAL_MS) {
            return
        }
        if (CainiaoTraceApi.riskBlocked(now)) {
            note("跳过（风控退避中）")
            return
        }
        val cookie = TraceCookieCache.get()
        if (cookie.isNullOrBlank()) {
            note("跳过（模块手里没有淘宝登录态，等 hook 侧同步）")
            return
        }

        lastStartAt = now
        XposedBridge.logAlways("direct fetch 启动（$reason）")
        executor.execute {
            runCatching { fetch(app, cookie) }.onFailure {
                // 兜底链路，任何异常都不该影响模块本身。
                XposedBridge.logError("direct fetch 失败（已忽略）", it)
            }
        }
    }

    private fun fetch(app: Context, cookie: String) {
        val orders = TaobaoOrderApi.listOrders(cookie)
        if (orders.isEmpty()) {
            note("订单列表为空或不可用（cookie 失效 / 接口变了 / 被风控）")
            return
        }
        val targets = orders.filter { it.isInTransit && askedOrders.add(it.orderId) }
            .take(MAX_ORDERS)
        if (targets.isEmpty()) {
            note("订单 ${orders.size} 条，没有新的在途订单")
            return
        }

        XposedBridge.logAlways("direct fetch: 订单 ${orders.size} 条 → 问 ${targets.size} 个的物流")
        var found = 0
        for ((index, order) in targets.withIndex()) {
            if (index > 0) pace()
            if (CainiaoTraceApi.riskBlocked()) {
                note("中途撞到风控，本次停止（已问 $index 个）")
                return
            }
            val parcel = TaobaoOrderApi.ssrParcel(cookie, order.orderId) ?: continue
            found++
            // 拿到运单号就交给轨迹链路 —— 落库 / 广播 / 节拍都在那边（见类注释）。
            CainiaoTraceFetcher.requestFetch(
                cookieProvider = { TraceCookieCache.get() },
                tracking = parcel.mailNo,
                deliver = { record -> ModuleTraceFetcher.deliverLocally(app, record) },
            )
        }
        note("问到 $found 个运单号（共试 ${targets.size} 个订单）")
    }

    /** 与上一次请求拉开 [MIN_INTERVAL_MS]。 */
    private fun pace() = runCatching { Thread.sleep(MIN_INTERVAL_MS) }

    private fun note(outcome: String) {
        lastOutcome = outcome
        XposedBridge.logAlways("direct fetch: $outcome")
    }

    /** 诊断串（设置页 / 日志用得上，也方便真机验证时一眼看出卡在哪一步）。 */
    fun describe(): String = "asked=${askedOrders.size} last=$lastOutcome " +
        "riskBlocked=${CainiaoTraceApi.riskBlocked()}"
}
