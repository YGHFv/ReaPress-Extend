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

/**
 * 把记录里那份跳转令牌**从 system_server 取回模块进程**。
 *
 * ## 它在补哪一段
 *
 * 「打开原通知」的令牌是 binder 句柄，只能活在某个进程的内存里。模块侧那张表
 * （[NotificationIntentCache]）由投递广播顺手填满，但它随**模块进程**消失 —— 而这个模块
 * 常年没有界面，被系统回收是家常便饭。于是用户过一阵再打开记录页，「打开原通知（菜鸟）」
 * 已经退化成「打开菜鸟」了，明明什么都没做错。
 *
 * system_server 跟设备同时在线，让它替我们揣着（hook 侧 `IntentTokenStore`），
 * 模块进程需要的那一刻去取一次 —— 令牌寿命就从「模块进程」变成「设备本次开机」。
 *
 * ## 时机：页面打开时**预热**，而不是点击时现取
 *
 * 取回要经一次跨进程往返（毫秒级，但不保证）。如果放在点击那一刻，`open()` 就得变成异步的，
 * 界面要加「正在打开…」状态、要处理超时 —— 而且**失败时已经点下去了**，用户等了一秒才被
 * 告知「打不开」。
 *
 * 改成页面一打开就悄悄取：等用户真的去点，令牌多半已经在手，点击走的还是原来那条**同步**路径
 * （`NotificationIntentCache.get()` → `send()`）。取不到也没什么可做的，界面照旧按「只能打开
 * 那个 App」渲染（见 `NotificationIntentLauncher.isFaithful`）。
 *
 * ## 可观测性
 *
 * 每次索取与结果都记一行模块日志（`intent token: …`）。这是唯一能把
 * 「system_server 说它没有」和「通道没立起来、请求没人接」分开的地方 —— 两者在界面上
 * 结果相同（都退到快照），但前者只能等设备重启，后者是 hook 侧的问题。
 */
object IntentTokenFetcher {

    private const val LOG_TAG = "ReaPress"

    /**
     * 向 system_server 索取这条记录的令牌。
     *
     * **幂等、可重复调**：已经拿到令牌就直接返回，不会白跑一趟；重复索取在 system_server 侧
     * 只是一次查表 + 一次广播（取回不消费，见 `IntentTokenStore.get`）。
     *
     * 三条早退：记录没有句柄（旧记录 / 那条通知没有 contentIntent）、令牌已在手 —— 都不必发。
     * 发失败（极罕见）只记日志：这只影响「这条记录能不能跳回原页面」，不该冒泡到界面。
     */
    fun request(context: Context, entry: ExpressNotificationLog.Entry) {
        val tokenId = entry.tokenId.takeIf { it.isNotBlank() } ?: return
        // 已在手就别再跑一趟 —— 这是**常态**（进程刚投递过这条通知，令牌本来就在内存里）。
        if (NotificationIntentCache.get(entry.id) != null) return
        runCatching {
            // 隐式广播：system_server 不是一个包，没法 setPackage 寻址；靠接收侧的权限闸认人
            // （见 ExpressRelay.ACTION_INTENT_RESOLVE_REQUEST）。载荷只有两个 UUID。
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_INTENT_RESOLVE_REQUEST)
                    .putExtra(ExpressRelay.EXTRA_INTENT_TOKEN, tokenId)
                    .putExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID, entry.id),
            )
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "intent token: 索取广播发送失败", it)
        }
    }

    /**
     * system_server 的应答（[ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED]）—— 由
     * [io.github.YGHFv.ReaPressExtend.relay.ExpressRelayReceiver] 转进来。
     *
     * **空手也是有效答复**：那句日志分得清「token 已经不在了」与「通道没人接」
     * （后者连这行都不会有）。
     *
     * 收到之后发一条**进程内**广播叫界面重算（[ExpressRelay.ACTION_INTENT_TOKEN_READY]）：
     * 令牌是异步到的，而详情页早已经按「没有令牌」渲染完了 —— 不发这一步，用户就要一直看着
     * 过时的措辞，明明现在能跳回原页面。
     */
    fun submitFromSystemServer(app: Context, intent: Intent) {
        val entryId = intent.getStringExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID)
            ?.takeIf { it.isNotBlank() }
        if (entryId == null) {
            ModuleAndroidLog.error(LOG_TAG, "intent token: 应答里没有记录标识，丢弃")
            return
        }
        val token = readToken(intent)
        if (token != null) NotificationIntentCache.remember(entryId, token)
        // 只记前 8 位：那是个 UUID，全记没有信息量；这里要的只是「同一个 entryId 对得上」。
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

    /**
     * 应答里那块令牌。
     *
     * 与 `ExpressRelayReceiver.readContentIntent` 是同一件事（同一个 extra、同一套版本分支）——
     * 没有抽成公共函数，是因为那边包在 receiver 的私有方法里、语义是「这条投递带来的令牌」，
     * 这边是「应答带来的令牌」。两处各一次 `runCatching`，比为了共用而在包之间开一个口子划算。
     *
     * 读不出来只该让这一次取回落空（界面继续按「没有令牌」渲染），所以异常吞成 null。
     */
    private fun readToken(intent: Intent): PendingIntent? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT) as? PendingIntent
        }
    }.getOrNull()
}
