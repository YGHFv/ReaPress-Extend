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
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.CainiaoIdentity
import io.github.YGHFv.ReaPressExtend.core.CainiaoIdentityResult
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import kotlinx.coroutines.delay

/**
 * 模块进程侧的身份码入口 —— 身份码弹窗唯一要打交道的东西。
 *
 * ## 它做的是「问菜鸟要」，不是「自己去取」
 *
 * 模块**不能**自己发这个 MTOP 请求（2026-09-26 两轮真机实证）：身份码那条接口在 H5 通道
 * 上是关着的，预热连 token 都不给就回 `FAIL_SYS_SESSION_EXPIRED`，而同一时刻同一份 cookie
 * 拉轨迹成功 —— 不是登录态的问题。所以流程变成：
 *
 * ```
 * 发 ACTION_IDENTITY_REQUEST ──► 菜鸟进程（用宿主自己的会话取码）
 *        ▲                              │
 *        └── ACTION_IDENTITY_SYNC ◄─────┘   （码 / 或取不到的原因）
 * ```
 *
 * ## 四个设计取舍
 *
 * 1. **`latest` 与 `lastResult` 两个槽位**。`lastResult` 是「本次等待的答案」，`latest` 是
 *    「最近一次拿到的有效码」。分开是因为它们回答的是两个不同的问题：前者决定这一次显示什么，
 *    后者在**宿主连不上时**兜底 —— 菜鸟不在后台时广播会被系统静默丢弃（小米上的实测行为），
 *    那一刻手里若还留着一份没过期的码，比显示「连不上菜鸟」有用得多。
 * 2. **每次打开弹窗都重新要一次**，不复用 `latest` 直接显示。身份码是服务端定时刷的短凭据，
 *    省那一次请求换来的是「显示一个已经作废的码」——用户在柜台前扫不出来，最糟的失败形态。
 *    `latest` 只在**要不到新的**时候出场。
 * 3. **轮询而不是给回执单独开一条通道**。回执落在 [submitFromHost]（由广播接收器调用），
 *    这里只是每 200ms 看一眼 —— 与登录态那条等待同一套写法，不为一个最多十几秒的等待
 *    再引入一个协程信号量。
 * 4. **先叫醒菜鸟，并且反复要、不指望第一次**（2026-09-26 加）。索取的那条广播是菜鸟进程
 *    **动态注册**的接收者 —— 菜鸟不在时它没人接，被系统直接丢掉。所以每 [RE_REQUEST_MS]
 *    重发一次「唤醒销 + 索取」。**重发不是「重试」**，是因为冷启动那几秒里发出去的广播注定丢失：
 *    菜鸟要先起来、LSPosed 装 hook、`Application` 创建、桥才注册得上接收者。第一次几乎必丢。
 *    宿主侧对重复索取天然免疫 —— 桥在单线程 executor 上串行处理，第二发会命中第一发填好的缓存。
 * 5. **「通道立好了」要能看见**（2026-09-26 加，真机吃过亏）。桥注册好接收者时会给模块发一条
 *    纯状态播报，模块记成 `identity bridge ready: …`。判据从此是
 *    「`identity wake` 之后有没有 `identity bridge ready`」：有 = 销把宿主叫醒了、通道也立好了，
 *    剩下的失败都是取码本身的失败（会有回执）；**没有 = 销没叫醒宿主**。没有这条播报时，
 *    「通道压根没注册」和「广播被系统丢了」在日志里长得一模一样，只能靠猜。
 *
 * ## 为什么不做磁盘缓存
 *
 * 身份码是**取件凭据**（能让别人替你出库）。日志不写它、磁盘不存它、离开模块进程的内存即销毁。
 * 代价是模块进程被杀后要重新问一次菜鸟 —— 那个代价是可以接受的，而凭据泄漏不是。
 */
object IdentityCodeFetcher {

    private const val LOG_TAG = "ReaPress"

    /**
     * 等宿主回执的上限。
     *
     * 必须**大于**宿主侧的最坏耗时（`CainiaoIdentityBridge` 的
     * `HOST_TIMEOUT_MS + MAIN_POST_TIMEOUT_MS` = 9 秒）：否则宿主还在等它自己的网络回调，
     * 模块这边已经先超时了 —— 那条「宿主取码超时」的回执回来时已经没有人在听，
     * 日志里看起来就会像「广播没送到」，而这两种故障的处置完全不同。
     *
     * 15 秒（2026-09-26 由 12 秒放宽）要装下的是**冷启动**那一整条：
     *
     * ```
     * 唤醒销广播 → 菜鸟进程创建 → LSPosed 注入 → Application#onCreate
     *   → 桥注册索取通道（约 3–6 秒，真机量过）→ 我们那记重发被接住
     *   → 宿主发 MTOP 取码（约 1 秒）→ 回执
     * ```
     *
     * 12 秒对「宿主已经在跑」够，对冷启动就压在边界上 —— 而冷启动恰恰是这个功能的主场景
     * （用户要的就是不打开菜鸟）。多等 3 秒的代价只在**真正拿不到**时才被用满，那时用户看到的是
     * 明确的失败文案而不是无限转圈（弹窗上还有「关闭」）。
     */
    const val WAIT_MS = 15_000L

    /** 轮询间隔。200ms：人对「点开就出结果」的容忍在几百毫秒级。 */
    private const val POLL_MS = 200L

    /**
     * 两次索取之间有隔多久重发一次。
     *
     * 2.5 秒是照着「菜鸟冷启动 + LSPosed 装 hook + 桥注册接收者」那段耗时定的：
     * 第一次索取几乎必然落在菜鸟起来之前（那条广播本来就没人接），第二次才是通常生效的那次。
     * 再密一点没意义（早发还是没人接），再疏一点会让「菜鸟本来是活的」这种情况白等。
     */
    private const val RE_REQUEST_MS = 2_500L

    /** 本次等待的答案。`null` = 还没回。 */
    @Volatile private var lastResult: CainiaoIdentityResult? = null

    /** 最近一次拿到的有效码（应答或推送）。只在「要不到新的」时出场。 */
    @Volatile private var latest: CainiaoIdentity? = null

    /**
     * 请菜鸟取一份身份码，并等它回来。
     *
     * **必须在协程里调用**（内部有 `delay`）。
     *
     * @return 一定会返回一个结果 —— 超时也会给 [CainiaoIdentityResult.HostUnavailable]
     *   （或手里那份还没过期的旧码），绝不会让界面卡在「获取中」。
     */
    suspend fun fetch(context: Context): CainiaoIdentityResult {
        // 兜底要在清槽之前取：清槽之后的 15 秒里若一直没人回，手里就只剩这一份了。
        val fallback = latest?.takeIf { it.isValidAt(System.currentTimeMillis()) }

        // ⚠️ **必须在循环**之前**清槽**（2026-09-26 真机踩到，是这一轮自己引入的回归）。
        // 循环体的顺序是「先看答案、再发请求」，槽里只要还留着上一轮的东西，第一轮就会
        // 当场返回它 —— 于是第二次点开弹窗**什么都不做**：不发唤醒销、不发索取、日志里
        // 一个字都没有。更糟的是上一轮留下的若是**失败回执**，这个失败会一直毒住模块
        // 直到进程重启（用户点多少次都看到同一句话）。
        // 放在循环外就没有这个洞：清完之后唯一会进来的答案必然属于本轮。
        lastResult = null

        var waited = 0L
        var nextRequestAt = 0L
        while (waited < WAIT_MS) {
            lastResult?.let { return it }
            if (waited >= nextRequestAt) {
                // 唤醒销**每次重发都带一记**（它活着时是空操作，见 HostWakePin）：
                // 第一记可能落在宿主被系统拦下、或广播被丢掉的时刻，重发是唯一能补救的。
                HostWakePin.wake(context, "取身份码")
                request(context)
                nextRequestAt = waited + RE_REQUEST_MS
            }
            delay(POLL_MS)
            waited += POLL_MS
        }

        if (fallback != null) {
            ModuleAndroidLog.legacy(LOG_TAG, "identity: 宿主没回，改用手里那份还没过期的码")
            return CainiaoIdentityResult.Success(fallback)
        }
        return CainiaoIdentityResult.HostUnavailable
    }

    /**
     * 发一次索取请求。
     *
     * 槽位不在这里清 —— 由 [fetch] 在**整轮开始之前**清一次。理由见那边的注释：
     * 在循环里清，晚了一步（第一轮的「看答案」在它前面）。
     */
    private fun request(context: Context) {
        runCatching {
            // 只发给菜鸟：身份码是菜鸟账号的东西，淘宝 / 拼多多进程里没有这条链路。
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_IDENTITY_REQUEST).setPackage(ExpressRelay.HOST_PACKAGE),
            )
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "identity request failed", it) }
    }

    /**
     * 收到宿主的身份码（或回执）—— 由 `ExpressRelayReceiver` 在拿 `ACTION_IDENTITY_SYNC` 时调。
     *
     * 三种来路（应答 / 主动推送 / 失败回执）走同一个入口，因为接收侧对它们**没有不同的处理**：
     * 都是「一份宿主取到的码」，或者「一句它取不到的原因」。
     */
    fun submitFromHost(intent: Intent) {
        // ① 状态播报（宿主说「索取通道立好了」）—— 只记日志，不改变等待状态。
        //    它在最前面判：这条消息既没有码也没有错，落到下面任何一个分支都会被误读。
        intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS)
            ?.takeIf { it.isNotBlank() }
            ?.let { status ->
                ModuleAndroidLog.legacy(LOG_TAG, "identity bridge ready: $status")
                return
            }

        val code = intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_CODE)?.takeIf { it.isNotBlank() }
        if (code == null) {
            // 没有码 = 回执。**照原话记下来**：它是「宿主说它取不到」的唯一证据，
            // 缺了它，这条链路就只能区分「有码」和「什么都没有」（后者既可能是宿主失败，
            // 也可能是广播被系统丢了 —— 两者的处置完全不同）。
            val error = intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_ERROR)?.takeIf { it.isNotBlank() }
            val reason = error ?: "宿主回了身份码广播但没带码也没带原因"
            ModuleAndroidLog.legacy(LOG_TAG, "identity sync 收到宿主回执：$reason")
            // ⚠️ **失败不许盖掉已经到手的成功**。同一个包里的多个进程都注册了那个 receiver
            // （菜鸟有主进程 + `:channel` + `:render_proc0` + `:tools`），一次广播可能被投递
            // 多次 —— 主进程取到了码、某个非主进程取不到，两边同时回执时谁后到谁说话。
            // 不设这道闸，用户会看到一个明明已经拿到的码被判成失败。
            if (lastResult !is CainiaoIdentityResult.Success) {
                lastResult = CainiaoIdentityResult.HostFailed(reason)
            }
            return
        }

        val identity = CainiaoIdentity(
            code = code,
            // 0 表示宿主没给有效期（见 ExpressRelay.EXTRA_IDENTITY_EXPIRE_AT）。
            expireAt = intent.getLongExtra(ExpressRelay.EXTRA_IDENTITY_EXPIRE_AT, 0L)
                .takeIf { it > 0L },
            offline = intent.getBooleanExtra(ExpressRelay.EXTRA_IDENTITY_OFFLINE, false),
        )
        latest = identity
        lastResult = CainiaoIdentityResult.Success(identity)
        // ⚠️ 只记长度与来源，**绝不记内容**。
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "identity synced: ${code.length} 位 from=" +
                "${intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_PROVENANCE).orEmpty()}",
        )
    }

    /**
     * 失败时给用户看的一句话。
     *
     * 与 [CainiaoIdentityResult] 一一对应 —— 三句话对应的**动作**完全不同，所以不能合并成
     * 「获取失败，请重试」：连不上菜鸟时重试一万次也是一样的（广播根本没人接），
     * 而风控时重试只会把处罚窗口撞长。
     */
    fun describe(result: CainiaoIdentityResult): String = when (result) {
        is CainiaoIdentityResult.Success -> ""
        is CainiaoIdentityResult.HostFailed -> when {
            result.riskBlocked ->
                "菜鸟那边被风控暂时拦住了。稍后再试 —— 现在连续点不会更快。"
            else ->
                "菜鸟没能给出身份码：${result.ret}。" +
                    "可以在菜鸟里打开一次「身份码」页面再重试。"
        }
        CainiaoIdentityResult.HostUnavailable ->
            "没能叫醒菜鸟（它没在运行，系统的「关联启动」限制可能拦住了我们拉它起来）。" +
                "打开一次菜鸟再回来即可；在菜鸟里打开一次「身份码」更稳。"
    }
}
