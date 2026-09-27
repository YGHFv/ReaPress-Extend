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

package io.github.YGHFv.ReaPressExtend.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay

/** 把记录里那份跳转令牌从 system_server 取回模块进程：模块进程被回收后 [NotificationIntentCache] 就废了，由常驻的 system_server 揣着（hook 侧 `IntentTokenStore`），令牌寿命 = 设备本次开机。每次索取与结果都记一行日志 —— 唯一能把「system_server 说它没有」和「通道没立起来」分开的地方。 */
object IntentTokenFetcher {

    private const val LOG_TAG = "ReaPress"

    /** 幂等、可重复调（取回不消费）；记录没有句柄或令牌已在手就直接返回。 */
    fun request(context: Context, entry: ExpressNotificationLog.Entry) {
        val tokenId = entry.tokenId.takeIf { it.isNotBlank() } ?: return
        if (NotificationIntentCache.get(entry.id) != null) return
        runCatching {
            // 隐式广播：system_server 不是一个包，没法 setPackage 寻址，靠接收侧的权限闸认人。
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_INTENT_RESOLVE_REQUEST)
                    .putExtra(ExpressRelay.EXTRA_INTENT_TOKEN, tokenId)
                    .putExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID, entry.id),
            )
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "intent token: 索取广播发送失败", it)
        }
    }

    /** system_server 的应答，由 `ExpressRelayReceiver` 转进来；空手也是有效答复。收到后发进程内广播叫界面重算 —— 详情页早已按「没有令牌」渲染完。 */
    fun submitFromSystemServer(app: Context, intent: Intent) {
        val entryId = intent.getStringExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID)
            ?.takeIf { it.isNotBlank() }
        if (entryId == null) {
            ModuleAndroidLog.error(LOG_TAG, "intent token: 应答里没有记录标识，丢弃")
            return
        }
        val token = readToken(intent)
        if (token != null) NotificationIntentCache.remember(entryId, token)
        ModuleAndroidLog.legacy(
            LOG_TAG,
            if (token != null) {
                "intent token: 已从 system_server 取回（entry=${entryId.take(8)}）"
            } else {
                "intent token: system_server 已无这条令牌（entry=${entryId.take(8)}，LRU 淘汰或设备重启过）"
            },
        )
        runCatching {
            app.sendBroadcast(
                Intent(ExpressRelay.ACTION_INTENT_TOKEN_READY).setPackage(app.packageName),
            )
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "intent token: 通知界面刷新失败", it)
        }
    }

    /** 与 `ExpressRelayReceiver.readContentIntent` 是同一 extra、同一套版本分支，读不出吞成 null。 */
    private fun readToken(intent: Intent): PendingIntent? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT) as? PendingIntent
        }
    }.getOrNull()
}
