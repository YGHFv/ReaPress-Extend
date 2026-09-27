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
import android.content.IntentFilter
import android.os.Build
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/** 注册「模块 → 被注入进程」的反向请求接收器：Android 13+ 必须显式声明导出性（RECEIVER_EXPORTED），且必须要求发送方持有 PERMISSION_TRACE_REQUEST —— 漏了前者当场崩，漏了后者等于把登录态 / 系统身份开放给任意应用。注册失败只记日志返回 false。 */
internal object HostReceiverRegistrar {

    fun register(
        context: Context,
        receiver: BroadcastReceiver,
        vararg actions: String,
    ): Boolean {
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    ExpressRelay.PERMISSION_TRACE_REQUEST,
                    /* scheduler = */ null,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(
                    receiver,
                    filter,
                    ExpressRelay.PERMISSION_TRACE_REQUEST,
                    /* scheduler = */ null,
                )
            }
            true
        }.getOrElse {
            XposedBridge.logError("register reverse-request receiver failed", it)
            false
        }
    }
}
