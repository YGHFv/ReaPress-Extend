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
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceFetcher
import io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.util.Collections

/**
 * 模块进程侧的轨迹拉取入口。拉取已整体收拢到模块进程（2026-09-26）：cookie 由 hook 侧经
 * `ACTION_COOKIE_SYNC` 同步进 [TraceCookieCache]（内存 + 模块私有目录），引擎复用 [CainiaoTraceFetcher]
 * （闸门、串行、风控退避都在那）。四条路：[onDemand] 点击时拉当前单号；[maybeAutoFetch] 富化到达时
 * 主动批量拉；[watchFetch] 自动轮查（`AutoWatchService` 后台 3 分钟一件，前两条都需要外部触发，
 * 轮查补的是宿主几小时不刷新的那一格）；[foregroundRefresh] 打开模块/下拉时对首页该刷的件自动做一遍
 * 「点开详情」—— 打开模块只让宿主重读它自己的本地表，那张表不会因此变新。
 */
object ModuleTraceFetcher {

    /** 自动/保底拉取覆盖的状态。「运输中」2026-09-27 才纳入：原先在等的那件反倒是唯一永远不会自己更新的。 */
    private val AUTO_STATUSES = setOf(
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
        ExpressStatus.DELIVERING,
        ExpressStatus.IN_TRANSIT,
    )

    /** 保底最小间隔：富化成批到来，不挡的话一批就是一串请求（14:33 那波风控）。 */
    private const val BACKSTOP_MIN_INTERVAL_MS = 3 * 60_000L

    @Volatile private var lastBackstopAt = 0L

    /** 前台刷新的单号级节流（5 分钟）；用单号做键，容量天然被记录数封顶。 */
    private val lastForegroundAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 一次最多 6 件：真机实证 1.5s 间隔五连发第 6 个吃到风控；打开模块是高频动作，宁可多开几轮。 */
    private const val FOREGROUND_BATCH = 6

    /**
     * 同一个单号两次「前台刷新」之间的最小间隔。
     *
     * 5 分钟：这是**体验与风控额度的折中**，取值理由见 [lastForegroundAt]。
     */
    private const val FOREGROUND_MIN_INTERVAL_MS = 5 * 60_000L

    /**
     * 两次「前台刷新」批次的最小间隔（用户主动下拉用 [FOREGROUND_FORCED_GAP_MS]）。
     * `onResume` 一次启动可能跑不止一次，per-单号节流挡不住「第二批挑另外 6 件」的翻倍，所以加批次闸门；
     * 自动与主动各记各的（2026-09-28）：用户下拉是明确指令，不能被模块刚发的自动批次吞掉。
     */
    private const val FOREGROUND_BATCH_GAP_MS = 60_000L

    /** 用户主动下拉时的批次间隔，比自动那条短。 */
    private const val FOREGROUND_FORCED_GAP_MS = 30_000L

    @Volatile private var lastAutoBatchAt = 0L

    @Volatile private var lastForcedBatchAt = 0L

    /** 本地存储文件名。`internal` 是为了让备份清单引用同一份来源（同 `ExpressRecordStore.PREFS`）。 */
    internal const val PREFS = "trace_fetch"
    internal const val KEY_RISK_UNTIL = "riskBlockedUntil"

    @Volatile private var riskRestored = false

    /** 已记过的「没发起拉取」原因。必须去重（热路径，否则一次首页刷新就刷满环形缓冲）；用 `logAlways`（模块 INFO 受「简洁日志」开关抑制）。 */
    private val skipNotes = Collections.synchronizedSet(HashSet<String>())

    private fun noteSkip(reason: String) {
        if (!skipNotes.add(reason)) return
        XposedBridge.logAlways("trace 未发起：$reason")
    }

    /** 把风控退避接上持久化：进程被杀退避就归零、退避期内照发不误（15:32 实证）。hook 进程不走这里（无模块 prefs 可写）。 */
    private fun ensureRiskPersisted(context: Context): Boolean = ExpressRecordStore.withTransaction {
        if (riskRestored) return@withTransaction true
        runCatching { reloadRiskAfterRestore(context) }.onFailure {
            XposedBridge.logError("trace risk persistence unavailable; fetch skipped")
        }.isSuccess
    }

    internal fun reloadRiskAfterRestore(context: Context) = ExpressRecordStore.withTransaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CainiaoTraceApi.restoreRisk(prefs.getLong(KEY_RISK_UNTIL, 0L))
        check(prefs.edit().putLong(KEY_RISK_UNTIL, CainiaoTraceApi.riskBlockedUntil).commit())
        CainiaoTraceApi.onRiskMarked = { until ->
            ExpressRecordStore.withTransaction {
                prefs.edit { putLong(KEY_RISK_UNTIL, maxOf(until, prefs.getLong(KEY_RISK_UNTIL, 0L))) }
            }
        }
        riskRestored = true
    }

    /** 详情页点开/下拉拉当前单号。@return false 表示本地没有 cookie，调用方应改发 `ACTION_TRACE_REQUEST` 给菜鸟进程兜底。 */
    fun onDemand(context: Context, tracking: String): Boolean {
        // 先绑缓存：进程重启后内存是空的，要把磁盘上那份登录态读回来。
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("模块手里没有登录态（已回退给菜鸟进程拉；宿主到底有没有登录态，看有没有 cookie sync 回执）")
            // 凭据可能在淘宝那边（菜鸟没绑淘宝账号时正是如此），顺手各要一次，下次点开就能自己拉。
            HostCredentialRequester.requestFromHosts(context)
            return false
        }
        if (!ensureRiskPersisted(context)) return true
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            deliver = { record -> deliverLocally(context, record) },
        )
        return true
    }

    /** 保底拉取：每 3 分钟捎带拉一件还没拉过的（范围 [AUTO_STATUSES]）。与自动更新同受引擎闸门约束，叠加也不重复请求。 */
    fun maybeBackstopFetch(context: Context, record: ExpressRecord) {
        if (record.status !in AUTO_STATUSES) return
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() } ?: return
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("保底拉取跳过：模块手里没有登录态")
            return
        }
        if (!ensureRiskPersisted(context)) return
        val now = System.currentTimeMillis()
        // 占坑在判断之后：间隔未到不更新时刻，否则批内第一条之后的永远没机会。
        if (now - lastBackstopAt < BACKSTOP_MIN_INTERVAL_MS) return
        lastBackstopAt = now
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            deliver = { enriched -> deliverLocally(context, enriched) },
        )
    }

    /** 自动轮查的一次拉取。与 [onDemand] 唯一区别是 `recheck = true`：被成功表挡住的话轮查第二轮起会安静地什么都不做；风控由轮查自己的节奏兜。范围过滤在调用方（排程规则）。 */
    fun watchFetch(context: Context, tracking: String) {
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("轮查跳过：模块手里没有登录态（等一次宿主回执，或先在关于页确认菜鸟里登录过淘宝）")
            return
        }
        if (!ensureRiskPersisted(context)) return
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            recheck = true,
            deliver = { enriched -> deliverLocally(context, enriched) },
        )
    }

    /**
     * 打开模块/首页下拉时的前台刷新：替用户把「点开详情」那一击对首页上该刷的件自动做一遍
     * （2026-09-27 用户报「必须点开轨迹详情才有最新信息」——叫醒宿主不会让它去问服务端）。
     * 只碰 [AUTO_STATUSES]；每单号 [FOREGROUND_MIN_INTERVAL_MS] 内一次、一次至多 [FOREGROUND_BATCH] 件
     * （最久没问优先）；批次闸门见 [FOREGROUND_BATCH_GAP_MS]；最后仍受引擎闸门（2.5s 串行、
     * 风控退避）约束，不绕过风控。[force] 是用户主动刷新，不受单号级节流挡，引擎闸门照旧。
     */
    fun foregroundRefresh(
        context: Context,
        records: List<ExpressRecord>,
        reason: String,
        force: Boolean = false,
    ): Int {
        val candidates = records
            .filter { it.status in AUTO_STATUSES }
            .mapNotNull { it.trackingNumber?.takeIf { tn -> tn.isNotBlank() } }
            .distinct()
        if (candidates.isEmpty()) return 0

        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("前台刷新跳过：模块手里没有登录态（等一次宿主回执，或先在免 root 页登录淘宝）")
            // 顺手要一次：凭据可能在淘宝那边（菜鸟没绑淘宝账号时正是如此），下一次打开就能自己拉了。
            HostCredentialRequester.requestFromHosts(context)
            return 0
        }
        if (CainiaoTraceApi.riskBlocked()) {
            // 退避期内不占节流坑，否则退避结束时所有件都被记成「刚问过」，反而一件都问不了。
            noteSkip(
                "前台刷新跳过：正在风控退避中，约 " +
                    "${((CainiaoTraceApi.riskBlockedUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)} " +
                    "分钟后可再试",
            )
            return 0
        }
        if (!ensureRiskPersisted(context)) return 0

        val now = System.currentTimeMillis()
        // 批次闸门排在 per-单号 节流之前：先决定这批做不做。被挡时不占单号的坑，
        // 否则重复触发把件白白记成「刚问过」；自动与主动各查各的时刻。
        val previousBatchAt = if (force) lastForcedBatchAt else lastAutoBatchAt
        val batchGap = if (force) FOREGROUND_FORCED_GAP_MS else FOREGROUND_BATCH_GAP_MS
        if (previousBatchAt > 0L && now - previousBatchAt < batchGap) {
            // 文案不带秒数：noteSkip 按整串去重，带上变化的数字会让这条每次都记一遍。
            val gapSeconds = batchGap / 1000L
            noteSkip("前台刷新跳过：刚刷过一批（批次间隔 $gapSeconds 秒）")
            return 0
        }

        val due = candidates
            .filter { tn -> force || now - (lastForegroundAt[tn] ?: 0L) >= FOREGROUND_MIN_INTERVAL_MS }
            // 最久没问过的排前面，固定的「前 N 件」会让后面的件永远排不上。
            .sortedBy { lastForegroundAt[it] ?: 0L }
            .take(FOREGROUND_BATCH)
        if (due.isEmpty()) return 0

        if (force) lastForcedBatchAt = now else lastAutoBatchAt = now
        due.forEach { tn ->
            lastForegroundAt[tn] = now
            CainiaoTraceFetcher.requestFetch(
                cookieProvider = { TraceCookieCache.get() },
                tracking = tn,
                // recheck = true：默认 false 会被成功表挡住（那是「点开详情不重复请求」用的）。
                recheck = true,
                deliver = { enriched -> deliverLocally(context, enriched) },
            )
        }
        XposedBridge.logAlways(
            "trace 前台刷新（$reason）：发起 ${due.size} 件 / 候选 ${candidates.size} 件" +
                if (force) "（用户主动）" else "",
        )
        return due.size
    }

    /** 自动更新：富化到达时对到站/派送中的件主动拉（有 cookie 才拉，没有等下次同步）。 */
    fun maybeAutoFetch(context: Context, record: ExpressRecord) {
        if (record.status !in AUTO_STATUSES) return
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() } ?: return
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("自动更新跳过：模块手里没有登录态")
            return
        }
        if (!ensureRiskPersisted(context)) return
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            deliver = { enriched -> deliverLocally(context, enriched) },
        )
    }

    /**
     * 拉取结果直接落库（同进程，走 [ExpressRecordStore.enrich] 按运单号配对、只填空）再发通知让 UI 重读。
     * 两条广播都要发：[ExpressRelay.ACTION_TRACE_ARRIVED] 给详情页，[ExpressChangeNotifier]（内部发
     * `ACTION_RECORDS_CHANGED`）给首页 —— 拉取多半发生在后台，只发前者的话首页要等下一次 onResume
     * 才更新，两条接收语义不同不能合并。后者交给 [ExpressChangeNotifier]：免 root 采集也发那条，
     * 「怎么发、怎么节流」必须只有一份实现。
     * `internal` 给直连兜底（`CainiaoDirectFetcher`）共用同一份实现：抄一份会漏发 `RECORDS_CHANGED`，
     * 表现是「数据其实到了，首页就是不动」。
     */
    internal fun deliverLocally(context: Context, record: ExpressRecord) {
        val applied = ExpressRecordStore.enrich(context, record)
        if (!applied) return
        runCatching {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_TRACE_ARRIVED).setPackage(context.packageName),
            )
        }
        ExpressChangeNotifier.notify(context)
    }
}
