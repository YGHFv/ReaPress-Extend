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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.HostWakePin
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * system_server 侧的「代发唤醒销」服务（[ExpressRelay.ACTION_WAKE_REQUEST] 的执行者）。
 *
 * ## 它解决什么
 *
 * 模块进程直接投给菜鸟的那条唤醒广播，会被 ROM 按**发送方**拦掉（HyperOS 上实测：
 * 宿主 12 秒后仍无进程；而同一个 intent 以 system uid 投出去，8 秒内宿主就起来了）。
 * 我们在 system_server 里本来就有一半代码（[SystemServerHook]），它的 Context 就是系统
 * 身份 —— 于是模块把这件事委托过来，由这里替它把那条 intent 投出去。
 * 分工与实测数据见 [HostWakePin] 的类注释。
 *
 * ## 为什么注册是「带重试的一次性动作」
 *
 * 动态注册的接收器需要一个可用的 Context，而 system_server 里**唯一可靠的来源是运行期**
 * （NMS 实例的 `mContext`）；开机安装 hook 那一刻用
 * [SystemContextHolder.acquireFromActivityThread] 不一定拿得到（见那里的说明）。
 * 所以这里做成幂等的 `ensureRegistered`，由调用方按各自的时机反复调：
 *
 * - 开机回调链（[SystemServerHook] 的看门狗状态上报，本身就是 5 次递增重试）；
 * - 第一条被拦到的快递通知（那时 NMS 的 context 一定已经在手）。
 *
 * ## 安全边界（三条都不能少）
 *
 * 1. **权限闸**：注册时要求发送方持有 [ExpressRelay.PERMISSION_TRACE_REQUEST]
 *    （signature 级，只有本模块签得出）。没有它，设备上任何应用都能借 system uid 去拉起
 *    别的应用 —— 这是「把系统身份外借」，必须只借给持有该权限的那一个发送方。
 *    注册参数（含 Android 13+ 必需的导出性 flag）统一走 [HostReceiverRegistrar]。
 * 2. **只做一件事**：收到请求只发那一条固定的 intent（[HostWakePin.pinIntent]），
 *    请求里带的唯一东西是一句日志用的用途说明 —— **不接收、不转发任何别的 intent**。
 * 3. **异常一律吞掉**：`onReceive` 跑在 system_server 主线程上，未捕获异常会带走整个进程
 *    （即开机循环级事故）。所以从注册到执行，每一层都 `runCatching`。
 *
 * ## 可观测性
 *
 * 这一跳的成功与否，在模块日志里是 `host wake: …` 那两行（`ACTION_WAKE_REPORT`）：
 * 通道就绪一次一行、每次代发一行回执。宿主侧则继续看 `identity bridge ready` /
 * `host self query: rows=`。**注册失败是静默的**（system_server 里没人接那条广播），
 * 所以「模块日志里没有 `host wake bridge ready`」本身就是结论：这一路没立起来。
 */
internal object SystemWakeRelay {

    /** 注册是否成功。成功后不再重复注册（注册是进程级的，进程重启后自然归零）。 */
    @Volatile private var registered = false

    /**
     * 保证「代发通道」已注册。幂等；拿不到 context 或注册失败时返回 false（调用方稍后重试）。
     *
     * @param context system_server 的 Context（`SystemContextHolder` 那条路拿到的）。
     */
    fun ensureRegistered(context: Context?): Boolean {
        if (registered) return true
        val resolved = context ?: return false
        synchronized(this) {
            if (registered) return true
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    runCatching { relay(receiverContext, intent) }.onFailure {
                        XposedBridge.logError("system wake relay failed (fail-soft)", it)
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
            // 一次性播报：让模块侧知道「这一跳立起来了」。没有它，注册失败与「销发了但被拦」
            // 在模块日志里长得一模一样 —— 理由见 ACTION_WAKE_REPORT 的注释。
            report(resolved, "系统代发通道已就绪（uid=${Process.myUid()}）")
            return true
        }
    }

    /**
     * 收到模块的请求：以 system uid 把那条固定的「销」投给菜鸟。
     *
     * 只读一个 extra（用途说明，进日志）—— 请求方无法借此让系统代发任意 intent。
     */
    private fun relay(context: Context, intent: Intent) {
        val label = intent.getStringExtra(ExpressRelay.EXTRA_WAKE_REASON)
            ?.takeIf { it.isNotBlank() } ?: "未标注用途"
        val ok = runCatching {
            // 优先用手里的 system context；onReceive 给的那个是被包装过的（注册上下文相同，
            // 只是禁止注册新接收器），发广播两者都能用，所以拿不到 holder 时它也能兜住。
            val sender = SystemContextHolder.acquire() ?: context
            sender.sendBroadcastAsUser(HostWakePin.pinIntent(), Process.myUserHandle())
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

    /** 把结论送回模块进程（`files/module-log.txt` 是唯一一处 adb 读得出来的地方）。 */
    private fun report(context: Context, text: String) {
        ExpressRelaySender.sendWakeReport(context, text)
    }
}
