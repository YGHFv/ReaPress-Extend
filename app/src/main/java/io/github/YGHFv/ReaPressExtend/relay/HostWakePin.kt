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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 「叫醒菜鸟」：广播投递给 `exported="true"` 的清单接收者时，投递本身会把目标进程拉起来（AOSP 行为）。
 * 接收者选 `...agoo.NotificationDismissReceiver`：无权限门槛、`id` 为空时零副作用 —— 只借「进程会被拉起」，
 * 换接收者必须重核这一点。直投 + 请 system_server 代发两条都发（ROM 可拦直投，HyperOS 实测）。
 * 成功与否不看返回值，看后续有没有 `identity bridge ready` 或 `host self query: rows=`。
 */
object HostWakePin {

    private const val LOG_TAG = "ReaPress"

    private const val PIN_CLASS = "com.cainiao.wireless.components.agoo.NotificationDismissReceiver"

    private const val PIN_ACTION = "com.cainiao.wireless.notification_dismiss"

    /** 发一记唤醒广播：直投 + 请 system_server 代发，两条都发。返回值只表示发出去了，不表示菜鸟醒了。 */
    fun wake(context: Context, reason: String): Boolean {
        val direct = runCatching {
            context.sendBroadcast(pinIntent())
            ModuleAndroidLog.legacy(LOG_TAG, "host wake（$reason）: 唤醒销已直投（$PIN_CLASS）")
            true
        }.getOrElse { error ->
            ModuleAndroidLog.error(LOG_TAG, "host wake（$reason）: 直投失败", error)
            false
        }
        val relayed = requestSystemRelay(context, reason)
        return direct || relayed
    }

    /** 请 system_server 替我们投那条「销」：用隐式广播（system_server 不是包），接收侧靠 action + signature 级权限认人。 */
    private fun requestSystemRelay(context: Context, reason: String): Boolean = runCatching {
        context.sendBroadcast(
            Intent(ExpressRelay.ACTION_WAKE_REQUEST)
                .putExtra(ExpressRelay.EXTRA_WAKE_REASON, reason),
        )
        ModuleAndroidLog.legacy(LOG_TAG, "host wake（$reason）: 已请系统代发")
        true
    }.getOrElse { error ->
        ModuleAndroidLog.error(LOG_TAG, "host wake（$reason）: 请求系统代发失败", error)
        false
    }

    /** 两条路径共用的「销」intent：接收者无 intent-filter，用显式组件；FLAG_INCLUDE_STOPPED_PACKAGES 捞回 stopped 应用。 */
    internal fun pinIntent(): Intent = Intent(PIN_ACTION)
        .setComponent(ComponentName(ExpressRelay.HOST_PACKAGE, PIN_CLASS))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
}
