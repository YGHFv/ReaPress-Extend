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
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * system_server 侧的「令牌寄存处窗口」（[ExpressRelay.ACTION_INTENT_RESOLVE_REQUEST] 的执行者）。
 *
 * ## 它解决什么
 *
 * 模块进程被回收之后，记录页里那份跳转令牌就没了（详见 [IntentTokenStore]）。而 system_server
 * 和我们同时在线 —— 让它替我们揣着，模块进程随时来取。模块侧那份表（`NotificationIntentCache`）
 * 还是主路径（同一次广播就送到，零往返），这里只是**它会丢的时候的兜底**。
 *
 * ## 与 [SystemWakeRelay] 的关系：同一个模式，两条独立通道
 *
 * 两个类几乎同构（都在 system_server 里动态注册一个带权限闸的接收器、都只做一件固定的事），
 * 但**刻意不合并**：
 *
 * - 语义不同 —— 一个代发一条固定的 intent，一个只是把寄存的东西还给正主；
 * - 注册时机不同 —— 唤醒销那条**必须**尽早（开机重试链上就注册，它是拉活宿主的前提），
 *   这条只在**用户真的要跳转**时才有人发请求，晚一点无所谓。
 *
 * 合并的唯一好处是少一个类，代价是两件事的注册条件与失败处置被绑在一起 —— 不划算。
 *
 * ## 安全边界（与 [SystemWakeRelay] 三条一样，一条都不能少）
 *
 * 1. **权限闸**：注册经 [HostReceiverRegistrar]，要求发送方持有
 *    [ExpressRelay.PERMISSION_TRACE_REQUEST]（signature 级，只有本模块签得出）。
 *    没有它，设备上任何应用都能来讨令牌 —— 而讨到的那块令牌 `send()` 出去就是**以宿主身份
 *    启动宿主的内部页面**，等于把宿主的部分身份外借。
 * 2. **只做一件事**：收到请求只查一次表、把结果回执出去。请求方给的两个值
 *    （令牌句柄、记录 id）都只是查表键与回传标记，**无法借此让 system_server 执行任何动作**。
 * 3. **异常一律吞掉**：`onReceive` 跑在 system_server 主线程，未捕获异常会带走整个进程。
 *
 * ## 可观测性
 *
 * 模块日志里那行 `intent token: …`（模块侧收到回执时记的）就是这一跳的判据：
 * 取回了令牌 / system_server 已经没有了。**通道没注册上也是静默的**（请求没人接），
 * 表现只是「令牌一直没到货」—— 与「system_server 说它没有」在界面上结果相同（都退到快照），
 * 所以那行日志是唯一能把两者分开的地方。
 */
internal object IntentTokenRelay {

    /** 注册是否成功。成功后不再重复注册（进程级的，system_server 重启后归零）。 */
    @Volatile private var registered = false

    /**
     * 保证通道已注册。幂等；拿不到 context 或注册失败时返回 false。
     *
     * 调用点与 [SystemWakeRelay.ensureRegistered] 挨在一起（开机的重试链 + 第一条拦到的通知）
     * —— 那两个时刻是 system_server 里唯一保证拿得到 Context 的地方，见 `SystemContextHolder`。
     */
    fun ensureRegistered(context: Context?): Boolean {
        if (registered) return true
        val resolved = context ?: return false
        synchronized(this) {
            if (registered) return true
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    runCatching { relay(receiverContext, intent) }.onFailure {
                        XposedBridge.logError("intent token relay failed (fail-soft)", it)
                    }
                }
            }
            val ok = runCatching {
                HostReceiverRegistrar.register(
                    resolved,
                    receiver,
                    ExpressRelay.ACTION_INTENT_RESOLVE_REQUEST,
                )
            }.getOrElse {
                XposedBridge.logError("intent token relay registration threw", it)
                false
            }
            if (!ok) {
                XposedBridge.logError("intent token relay NOT registered — module falls back to snapshot only")
                return false
            }
            registered = true
            XposedBridge.logAlways("intent token relay registered (uid=${Process.myUid()})")
            return true
        }
    }

    /**
     * 收到模块的索取：查一次寄存表，把结果回执出去（**空手也回**）。
     *
     * 回执由 [ExpressRelaySender.sendIntentToken] 直接构造并发出 —— 这里不碰 Context 之外的东西。
     */
    private fun relay(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID)
            ?.takeIf { it.isNotBlank() }
        if (entryId == null) {
            // 没有回传标记就没法把令牌归到哪条记录上 —— 记一行就走，不回执。
            XposedBridge.logAlways("intent token request without entry id, ignored")
            return
        }
        val tokenId = intent.getStringExtra(ExpressRelay.EXTRA_INTENT_TOKEN)
        val token = IntentTokenStore.get(tokenId)
        // 回执优先用手里的 system context；onReceive 给的那个也能发广播（只是禁止注册新接收器）。
        val sender = SystemContextHolder.acquire() ?: context
        ExpressRelaySender.sendIntentToken(sender, entryId, token)
        // 只记「有没有」——句柄本身是随机 UUID，记出来也没信息量。
        XposedBridge.logAlways("intent token request served: found=${token != null}")
    }
}
