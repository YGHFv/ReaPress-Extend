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
 * system_server 侧的「令牌寄存处窗口」（[ExpressRelay.ACTION_INTENT_RESOLVE_REQUEST] 的执行者）：
 * 模块进程被回收后跳转令牌就没了（见 [IntentTokenStore]），由 system_server 替我们揣着、随时来取。
 * 模块侧 `NotificationIntentCache` 仍是主路径，这里只是它会丢时的兜底。与 [SystemWakeRelay]
 * 同模式但刻意不合并：语义不同（一个代发固定 intent，一个把寄存物还给正主）、注册时机不同
 * （唤醒销必须开机链上尽早注册，这条晚一点无所谓）。安全边界与 [SystemWakeRelay] 三条一样：
 * 权限闸 signature 级必设 —— 讨到的那块令牌 `send()` 出去就是以宿主身份启动宿主内部页面；
 * 只查表回执、不执行任何动作；onReceive 异常一律吞掉。
 */
internal object IntentTokenRelay {

    @Volatile private var registered = false

    /** 幂等；调用点与 [SystemWakeRelay.ensureRegistered] 挨着（开机重试链 + 第一条拦到的通知）。 */
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

    /** 查一次寄存表把结果回执出去（空手也回）；没有 entry id 就没法归到记录，记一行走人。 */
    private fun relay(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID)
            ?.takeIf { it.isNotBlank() }
        if (entryId == null) {
            XposedBridge.logAlways("intent token request without entry id, ignored")
            return
        }
        val tokenId = intent.getStringExtra(ExpressRelay.EXTRA_INTENT_TOKEN)
        val token = IntentTokenStore.get(tokenId)
        val sender = SystemContextHolder.acquire() ?: context
        ExpressRelaySender.sendIntentToken(sender, entryId, token)
        XposedBridge.logAlways("intent token request served: found=${token != null}")
    }
}
