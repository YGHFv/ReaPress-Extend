package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceFetcher
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.util.Collections

/**
 * 模块进程侧的轨迹拉取入口。
 *
 * 轨迹拉取已整体收拢到模块进程（2026-09-26）：cookie 由 hook 侧经 `ACTION_COOKIE_SYNC`
 * 同步进 [TraceCookieCache]（内存 + 模块私有目录），拉取引擎复用 [CainiaoTraceFetcher]（闸门、串行、
 * 风控退避都在那）。两个进程各自调 [CainiaoTraceFetcher.requestFetch]，互不知晓、互不重复
 * —— 闸门（成功表 / 冷却表）是**进程内**的，但同一次拉取只有一个进程会发起（见下）。
 *
 * ## 两种模式怎么分工
 *
 * - **点击时获取**（默认）：[onDemand] —— 详情页点开 / 下拉时拉当前单号。
 *   cookie 缓存在 → 模块进程静默直拉（**菜鸟不在后台也行**，这正是收拢到模块的原因）；
 *   没有缓存 → 发 `ACTION_TRACE_REQUEST` 给菜鸟进程兜底（活着时它自己拉 + 顺带同步 cookie）。
 * - **自动更新**：[maybeAutoFetch] —— 模块收到宿主富化时，对到站 / 派送中的件主动批量拉。
 *   内部闸门和间隔保证「批量」也是 2.5s 一发、风控退避全局生效。
 */
object ModuleTraceFetcher {

    /** 自动更新只拉这些状态的件：用户在等答案的。与旧 hook 侧批量拉的范围一致。 */
    private val AUTO_STATUSES = setOf(
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
        ExpressStatus.DELIVERING,
    )

    /**
     * 保底拉取的最小间隔。保底的定位是「菜鸟运行时数据自己慢慢补齐」——富化是**成批**
     * 到来的（首页刷新一次十几条），不挡的话一批就又是一串请求，14:33 那波风控就是这么来的。
     * 3 分钟一件：一小时内自然补 20 件上限，正常首页刷新频率（几分钟一次）下感知不到延迟，
     * 详情页数据多半在用户点开之前就已经在了。
     */
    private const val BACKSTOP_MIN_INTERVAL_MS = 3 * 60_000L

    @Volatile private var lastBackstopAt = 0L

    private const val PREFS = "trace_fetch"
    private const val KEY_RISK_UNTIL = "riskBlockedUntil"

    /** 退避状态恢复只做一次（prefs 读一次、回调装一次）。 */
    @Volatile private var riskRestored = false

    /**
     * 诊断：已经记过的那几种「没发起拉取」的原因。
     *
     * 三条静默 return 曾经让整条链路不可观测 —— 用户在菜鸟里没登录过淘宝账号时，模块侧
     * 的表现是「点开详情转几秒、然后显示暂无轨迹」，和「风控退避中」「广播没送到」完全同形。
     * 加了日志之后这三种才分得开。
     *
     * **必须去重**：这些判断在热路径上（[maybeBackstopFetch] 每次富化都过一遍），不去重的话
     * 一次首页刷新就能把环形缓冲刷满，把真正有用的行挤出去。
     *
     * 用 `logAlways` 而非 `log`：模块进程的 INFO 受「简洁日志」开关抑制（默认开），
     * 而这恰恰是排查时唯一想看的那一段。
     */
    private val skipNotes = Collections.synchronizedSet(HashSet<String>())

    private fun noteSkip(reason: String) {
        if (!skipNotes.add(reason)) return
        XposedBridge.logAlways("trace 未发起：$reason")
    }

    /**
     * 把风控退避状态接上持久化（模块进程首次用到拉取时调用）。
     *
     * 没有这一步，退避只活在内存里 —— 模块进程被杀（装新包 / 划掉后台 / 系统回收）就归零，
     * 下次点详情立刻再撞一次线。15:32 的日志抓到过实证：上一进程 15:16 撞线进了 20 分钟
     * 退避，新进程在退避期内照发不误。hook 进程不走这里（它没有模块 prefs 可写，且兜底
     * 路径很少触发）—— 那边保持纯内存。
     */
    private fun ensureRiskPersisted(context: Context) {
        if (riskRestored) return
        riskRestored = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CainiaoTraceApi.restoreRisk(prefs.getLong(KEY_RISK_UNTIL, 0L))
        CainiaoTraceApi.onRiskMarked = { until ->
            prefs.edit().putLong(KEY_RISK_UNTIL, until).apply()
        }
    }

    /**
     * 详情页点开 / 下拉：拉**当前**这一个单号。
     *
     * @return true 表示本进程接下了这个请求（结果会经 `ACTION_TRACE_ARRIVED` 通知 UI）；
     *   false 表示本地没有 cookie，调用方应改发 `ACTION_TRACE_REQUEST` 给菜鸟进程兜底。
     */
    fun onDemand(context: Context, tracking: String): Boolean {
        // 先绑一次缓存：进程重启后内存是空的，得把上次落到模块私有目录的那份登录态读回来。
        // 没有这一步，「模块重启 → 磁盘里明明有 cookie 却当没有」会一直存在（2026-09-26 修）。
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("模块手里没有登录态（已回退给菜鸟进程拉；宿主到底有没有登录态，看有没有 cookie sync 回执）")
            // 顺手向两个宿主各要一次：这一条由调用方转给菜鸟代拉，但**下一次**点开模块就该
            // 能自己拉了 —— 而凭据可能在淘宝那边（菜鸟没绑淘宝账号时正是如此）。
            HostCredentialRequester.requestFromHosts(context)
            return false
        }
        ensureRiskPersisted(context)
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            deliver = { record -> deliverLocally(context, record) },
        )
        return true
    }

    /**
     * 保底拉取（**点击时获取**模式下的静默补充）：菜鸟运行时富化不断到来，每 3 分钟
     * 捎带拉一件还没拉过的到站 / 派送件 —— 详情页的数据多半在用户点开前就已就位。
     *
     * 「自动更新」模式不走这里（[maybeAutoFetch] 已经覆盖）；两者都受同一套引擎闸门
     * （成功表 / 冷却 / 风控退避）约束，所以就算叠加也不会重复请求。
     */
    fun maybeBackstopFetch(context: Context, record: ExpressRecord) {
        if (record.status !in AUTO_STATUSES) return
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() } ?: return
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("保底拉取跳过：模块手里没有登录态")
            return
        }
        ensureRiskPersisted(context)
        val now = System.currentTimeMillis()
        // 占坑在判断之后：间隔未到直接返回，不更新时刻（否则批内第一条之后的永远没机会）。
        if (now - lastBackstopAt < BACKSTOP_MIN_INTERVAL_MS) return
        lastBackstopAt = now
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            deliver = { enriched -> deliverLocally(context, enriched) },
        )
    }

    /** 自动更新：富化到达时对到站 / 派送中的件主动拉（有 cookie 才拉，没有等下次同步）。 */
    fun maybeAutoFetch(context: Context, record: ExpressRecord) {
        if (record.status !in AUTO_STATUSES) return
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() } ?: return
        TraceCookieCache.attach(context)
        if (TraceCookieCache.get() == null) {
            noteSkip("自动更新跳过：模块手里没有登录态")
            return
        }
        ensureRiskPersisted(context)
        CainiaoTraceFetcher.requestFetch(
            cookieProvider = { TraceCookieCache.get() },
            tracking = tracking,
            deliver = { enriched -> deliverLocally(context, enriched) },
        )
    }

    /**
     * 拉取结果直接落库（同进程，不用绕广播），再发内部通知让 UI 重读。
     *
     * 落库走 [ExpressRecordStore.enrich]：按运单号配对、「只填空」合并 —— 与广播通道
     * 完全同一套规则，只是少了一次进程间跳转。
     */
    private fun deliverLocally(context: Context, record: ExpressRecord) {
        val applied = ExpressRecordStore.enrich(context, record)
        if (!applied) return
        // 拉取多半发生在后台（详情页可能在别处等），内部广播让 Activity 自己重读。
        runCatching {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_TRACE_ARRIVED).setPackage(context.packageName),
            )
        }
    }
}
