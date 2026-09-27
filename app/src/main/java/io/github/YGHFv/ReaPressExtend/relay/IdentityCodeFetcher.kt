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
 * 模块进程侧的身份码入口。模块不能自己发这个 MTOP 请求（身份码接口在 H5 通道是关的，预热连 token
 * 都不给就回 `FAIL_SYS_SESSION_EXPIRED`，2026-09-26 真机实证），只能请菜鸟进程用它自己的会话取。
 * `lastResult` 是本次等待的答案，`latest` 是最近一次有效码（宿主连不上时兜底）；每次打开弹窗都重新要。
 * 身份码是取件凭据：不落盘、不写日志内容、离开模块进程内存即销毁。
 */
object IdentityCodeFetcher {

    private const val LOG_TAG = "ReaPress"

    /** 15s 要装下冷启动全链（桥注册约 3–6s），且必须大于宿主侧最坏耗时 9s。 */
    const val WAIT_MS = 15_000L

    private const val POLL_MS = 200L

    /** 第一次索取几乎必落在宿主起来之前（广播没人接），2.5s 后重发才通常生效。 */
    private const val RE_REQUEST_MS = 2_500L

    /** 本次等待的答案。`null` = 还没回。 */
    @Volatile private var lastResult: CainiaoIdentityResult? = null

    /** 最近一次拿到的有效码（应答或推送）。只在「要不到新的」时出场。 */
    @Volatile private var latest: CainiaoIdentity? = null

    /** 请菜鸟取身份码并等它回来；必须在协程里调（内部有 delay）。超时也保证返回结果。 */
    suspend fun fetch(context: Context): CainiaoIdentityResult {
        // 兜底要在清槽之前取：清槽之后的 15 秒里若一直没人回，手里就只剩这一份了。
        val fallback = latest?.takeIf { it.isValidAt(System.currentTimeMillis()) }

        // 清槽必须在循环之前：留着上轮的槽位会当场返回旧值（什么都不做、不发请求），
        // 上轮留下的失败回执还会毒住模块直到进程重启。
        lastResult = null

        var waited = 0L
        var nextRequestAt = 0L
        while (waited < WAIT_MS) {
            lastResult?.let { return it }
            if (waited >= nextRequestAt) {
                // 唤醒销每次重发都带一记（它活着时是空操作）：第一记可能落在广播被丢的时刻。
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

    private fun request(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_IDENTITY_REQUEST).setPackage(ExpressRelay.HOST_PACKAGE),
            )
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "identity request failed", it) }
    }

    /** 收到宿主的身份码（或回执）—— 由 ExpressRelayReceiver 调。三种来路同入口。 */
    fun submitFromHost(intent: Intent) {
        // 状态播报（「索取通道立好了」）：既没有码也没有错，先判掉免得被误读。
        intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS)
            ?.takeIf { it.isNotBlank() }
            ?.let { status ->
                ModuleAndroidLog.legacy(LOG_TAG, "identity bridge ready: $status")
                return
            }

        val code = intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_CODE)?.takeIf { it.isNotBlank() }
        if (code == null) {
            // 没有码 = 回执。照原话记下来：它是「宿主说它取不到」的唯一证据。
            val error = intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_ERROR)?.takeIf { it.isNotBlank() }
            val reason = error ?: "宿主回了身份码广播但没带码也没带原因"
            ModuleAndroidLog.legacy(LOG_TAG, "identity sync 收到宿主回执：$reason")
            // 失败不许盖掉已经到手的成功：菜鸟多个进程都注册了 receiver，回执可能同时到。
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
        // 只记长度与来源，绝不记内容。
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "identity synced: ${code.length} 位 from=" +
                "${intent.getStringExtra(ExpressRelay.EXTRA_IDENTITY_PROVENANCE).orEmpty()}",
        )
    }

    /** 失败时给用户看的一句话。三句话对应的动作完全不同，不能合并成「获取失败请重试」。 */
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
