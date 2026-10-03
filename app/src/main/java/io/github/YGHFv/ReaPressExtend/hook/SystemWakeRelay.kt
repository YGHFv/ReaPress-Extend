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

package io.github.YGHFv.ReaPressExtend.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.Binder
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.HostWakePin
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * system_server 侧的「代发唤醒销」服务（[ExpressRelay.ACTION_WAKE_REQUEST] 的执行者）。
 * 模块进程直接投的唤醒广播会被 ROM 按**发送方**拦掉（HyperOS 实测：直投 12 秒宿主仍无进程，
 * system uid 投出 8 秒内就起来），于是委托给 system_server 代发。
 * 注册是幂等的「带重试的一次性动作」：动态注册需要运行期 Context（NMS 实例的 mContext），
 * 由开机回调链与第一条被拦通知两个时机反复调 [ensureRegistered]。
 * 安全边界三条不能少：权限闸（发送方须持 signature 级 [ExpressRelay.PERMISSION_TRACE_REQUEST]，
 * 否则等于把系统身份外借给任何应用）、只发那条固定的 [HostWakePin.pinIntent] 不接不转任何别的
 * intent、onReceive 异常一律吞掉（跑在 system_server 主线程，未捕获异常会带走整个进程）。
 * 注册失败是静默的：模块日志里没有 `host wake bridge ready` 本身就是结论。
 */
internal object SystemWakeRelay {

    @Volatile private var registered = false

    /** 幂等；拿不到 context 或注册失败返回 false（调用方稍后重试）。 */
    fun ensureRegistered(context: Context?): Boolean {
        if (Process.myUid() != Process.SYSTEM_UID) return false
        if (registered) return true
        val resolved = context ?: return false
        synchronized(this) {
            if (registered) return true
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    runCatching { relay(receiverContext, intent) }.onFailure {
                        runCatching { XposedBridge.logError("system wake relay failed (fail-soft)", it) }
                    }
                }
            }
            val ok = runCatching {
                HostReceiverRegistrar.register(resolved, receiver, ExpressRelay.ACTION_WAKE_REQUEST)
            }.getOrElse {
                XposedBridge.logError("system wake relay registration threw", it)
                false
            }
            if (!ok) {
                XposedBridge.logError("system wake relay NOT registered — module-side wake falls back to direct only")
                return false
            }
            registered = true
            XposedBridge.logAlways("system wake relay registered (uid=${Process.myUid()})")
            // 一次性播报：没有它，注册失败与「销发了但被拦」在模块日志里长得一模一样。
            report(resolved, "系统代发通道已就绪（uid=${Process.myUid()}）")
            return true
        }
    }

    private fun relay(context: Context, intent: Intent) {
        if (Process.myUid() != Process.SYSTEM_UID || intent.action != ExpressRelay.ACTION_WAKE_REQUEST) return
        val label = intent.getStringExtra(ExpressRelay.EXTRA_WAKE_REASON)
            ?.take(256)?.takeIf { it.isNotBlank() } ?: "未标注用途"
        val ok = runCatching {
            // 优先用 system context；onReceive 给的那个被包装过但发广播也能用，拿不到 holder 时兜底。
            val sender = SystemContextHolder.acquire() ?: context
            sendPinFromSystem(sender)
            true
        }.getOrElse {
            XposedBridge.logError("relaying wake pin failed", it)
            false
        }
        XposedBridge.logAlways(
            "wake request from module ($label) — pin relayed as system uid=$ok",
        )
        report(context, if (ok) "系统代发唤醒销（$label）" else "系统代发失败（$label）")
    }

    // This endpoint only sends the fixed pin in system_server, never a caller-supplied Intent/user.
    @SuppressLint("MissingPermission")
    private fun sendPinFromSystem(context: Context) {
        check(Process.myUid() == Process.SYSTEM_UID)
        val identity = Binder.clearCallingIdentity()
        try {
            context.sendBroadcastAsUser(HostWakePin.pinIntent(), Process.myUserHandle())
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun report(context: Context, text: String) {
        ExpressRelaySender.sendWakeReport(context, text)
    }
}
