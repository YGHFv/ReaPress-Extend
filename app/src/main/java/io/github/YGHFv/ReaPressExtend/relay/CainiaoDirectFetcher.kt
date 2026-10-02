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
import io.github.YGHFv.ReaPressExtend.core.FetchRequestLedger
import io.github.YGHFv.ReaPressExtend.core.OrderDiscovery
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceFetcher
import io.github.YGHFv.ReaPressExtend.hook.TaobaoOrderApi
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.util.concurrent.Executors

/**
 * 不经过宿主进程的淘宝数据源：用淘宝登录态直接问订单，拿到运单号就交给 [CainiaoTraceFetcher]
 * 落库（请求数的闸门都在那边，它是这条通路唯一的发射口）。两个身份：宿主没接话（等 [GRACE_MS]
 * 仍无 ACTION_HOST_QUERY_REPORT 回执）时的兜底；免 root 时「发现新包裹」的主路（每轮轮查起点
 * + 免 root 页「立即同步」）。覆盖面只有淘宝/天猫订单，不替代宿主那两条路。
 * 请求数三道闸：只问在途订单、每次最多 [MAX_ORDERS] 个且订单间拉开 [MIN_INTERVAL_MS]、
 * 整条链路 [MIN_START_INTERVAL_MS] 内不重复启动且风控退避期一概不启动——每一次启动都是
 * 真金白银的请求数，风控按频率收网，宁可漏不可多。
 */
object CainiaoDirectFetcher {

    /** 调用方等宿主回执的宽限期。宿主活着时通常 1~3 秒就有回执。 */
    const val GRACE_MS = 5_000L

    private const val MIN_START_INTERVAL_MS = 10 * 60_000L

    /** force（免 root 页「立即同步」）时的节流地板，防连点：按一次就是一次真实请求，风控处罚是小时级的。 */
    private const val FORCE_MIN_INTERVAL_MS = 30_000L

    private const val MAX_ORDERS = 5

    private const val MIN_INTERVAL_MS = 2_500L

    private const val RETRY_COOLDOWN_MS = 10 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-cainiao-direct").apply { isDaemon = true }
    }

    private val orderRequests = FetchRequestLedger(RETRY_COOLDOWN_MS)

    private val discovery = OrderDiscovery(
        ledger = orderRequests,
        clock = System::currentTimeMillis,
        pace = ::pace,
        isBlocked = { CainiaoTraceApi.riskBlocked() },
        onError = { XposedBridge.logError("direct fetch 订单物流查询失败（已忽略）", it) },
    )

    @Volatile private var lastStartAt = 0L

    @Volatile private var lastHostReportAt = 0L

    @Volatile private var lastOutcome = "未启动"

    @Volatile private var lastOutcomeAt = 0L

    fun noteHostReport(now: Long = System.currentTimeMillis()) {
        lastHostReportAt = now
    }

    fun hostAnsweredSince(at: Long): Boolean = lastHostReportAt > at

    /**
     * 启动一次直连同步，幂等且廉价：被任何闸门挡下只记日志并返回 false（原因见 [describeOutcome]）。
     * force 只把节流窗口收到 [FORCE_MIN_INTERVAL_MS]；风控退避与 cookie 两道闸不跳——
     * 它们防的是账号处罚和空手发请求，与「用户不想等十分钟」不是一回事。
     * 返回 true = 真的排进了执行队列；false = 被闸门挡下。
     */
    fun start(context: Context, reason: String, force: Boolean = false): Boolean {
        val app = context.applicationContext
        ModuleLogBuffer.attach(app)
        TraceCookieCache.attach(app)

        val now = System.currentTimeMillis()
        val floor = if (force) FORCE_MIN_INTERVAL_MS else MIN_START_INTERVAL_MS
        if (now - lastStartAt < floor) {
            // force 是用户按了一次，必须留下「为什么没反应」的答案；非 force 是热路径，静默即可。
            if (force) note("跳过（刚同步过，${floor / 1_000} 秒内不重复）")
            return false
        }
        if (CainiaoTraceApi.riskBlocked(now)) {
            note("跳过（风控退避中）")
            return false
        }
        val cookie = TraceCookieCache.get()
        if (cookie.isNullOrBlank()) {
            note("跳过（模块手里没有淘宝登录态，去「免 root 模式」里登录一次）")
            return false
        }

        lastStartAt = now
        XposedBridge.logAlways("direct fetch 启动（$reason）")
        executor.execute {
            runCatching { fetch(app, cookie) }.onFailure {
                XposedBridge.logError("direct fetch 失败（已忽略）", it)
            }
        }
        return true
    }

    private fun fetch(app: Context, cookie: String) {
        if (CainiaoTraceApi.riskBlocked()) {
            note("跳过（风控退避中）")
            return
        }
        val orders = TaobaoOrderApi.listOrders(cookie)
        if (orders.isEmpty()) {
            note("订单列表为空或不可用（cookie 失效 / 接口变了 / 被风控）")
            return
        }
        XposedBridge.logAlways("direct fetch: 订单 ${orders.size} 条 → 本次最多问 $MAX_ORDERS 个的物流")
        val outcome = discovery.discover(
            orders = orders,
            limit = MAX_ORDERS,
            fetchParcel = { order -> TaobaoOrderApi.ssrParcel(cookie, order.orderId) },
            onParcel = { _, parcel, completed ->
                CainiaoTraceFetcher.requestFetch(
                    cookieProvider = { TraceCookieCache.get() },
                    tracking = parcel.mailNo,
                    onComplete = completed,
                    deliver = { record -> ModuleTraceFetcher.deliverLocally(app, record) },
                )
            },
        )
        when {
            outcome.blocked -> note("中途撞到风控，本次停止（已问 ${outcome.attempted} 个）")
            outcome.selected == 0 -> note("订单 ${orders.size} 条，没有可查询的在途订单（已完成、进行中或冷却中）")
            else -> note("问到 ${outcome.found} 个运单号（共试 ${outcome.attempted} 个订单）")
        }
    }

    private fun pace() {
        Thread.sleep(MIN_INTERVAL_MS)
    }

    private fun note(outcome: String) {
        lastOutcome = outcome
        lastOutcomeAt = System.currentTimeMillis()
        XposedBridge.logAlways("direct fetch: $outcome")
    }

    /** 上一次同步的结果（含何时），免 root 页面那一行——失败大多是静默的，这是用户点完唯一能看到的答案。 */
    fun describeOutcome(now: Long = System.currentTimeMillis()): String {
        val at = lastOutcomeAt
        if (at <= 0L) return "本次打开还没同步过"
        val age = (now - at).coerceAtLeast(0L)
        val whenText = when {
            age < 60_000L -> "刚刚"
            age < 3_600_000L -> "${age / 60_000L} 分钟前"
            else -> "${age / 3_600_000L} 小时前"
        }
        return "$lastOutcome · $whenText"
    }

    fun describe(): String {
        val state = orderRequests.snapshot()
        return "asked=${state.succeeded} inFlight=${state.inFlight} cooling=${state.cooling} last=$lastOutcome " +
            "riskBlocked=${CainiaoTraceApi.riskBlocked()}"
    }
}
