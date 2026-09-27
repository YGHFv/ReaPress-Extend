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

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 把原通知的 `PendingIntent` 拆成**能落盘的字符串**。
 *
 * ## 为什么（2026-09-27 用户问：为什么别的通知记录软件过很久还能打开）
 *
 * 之前「打开原通知」只揣着 `PendingIntent` 本体 —— 它是个 binder 令牌，**没有可落盘的表示**，
 * 模块进程一重启按钮就没了。但那个令牌**内部装的 Intent** 是可序列化的：
 * `Intent.toUri(Intent.URI_INTENT_SCHEME)` 是系统自己在用的那套字符串编码
 * （配 `Intent.parseUri` 还原），存下来就能在任意时刻重建一个普通 Intent 去启动同一个页面。
 *
 * 差别只在保真度：重建的是「Intent」而不是「PendingIntent」，所以
 * - 基础类型 extras（String / int / long / boolean 及各数组）能还原；
 * - `Parcelable` / `Serializable` extras 与 `FLAG_GRANT_*` 会丢。
 *
 * 对通知的 contentIntent（绝大多数就是「打开某个 Activity + 一两个参数」）来说这够用，
 * 所以现在的策略是**两条路并存**：令牌还在内存里就 `send()`（最忠实），
 * 不在了就用这份快照重建（够用且跨进程重启有效）。
 *
 * ## 谁在这里跑
 *
 * 被注入的 system_server（[SystemServerHook] 拦通知时）—— 那里读 hidden API 不受限制，
 * 而 `PendingIntent.getIntent()` 是 `@UnsupportedAppUsage` 的公开方法，反射能拿到。
 *
 * ## 🔴 清掉 binder 调用身份这一步不能省（2026-09-27 真机定位）
 *
 * **症状**：记录页里每一条都写着「没有可用的跳转信息」，没有任何一条能跳 ——
 * 不是偶尔失败，是**一条都没成过**。
 *
 * **根因**：AOSP 里 `PendingIntent#getIntent()` 的实现是
 * `ActivityManager.getService().getIntentForIntentSender(mTarget)`，而 AMS 那个方法做的第一件事是
 * `enforceCallingPermission(GET_INTENT_SENDER_INTENT)` —— 一个 **signature 级**权限。
 * `enforceCallingPermission` 检查的是 `Binder.getCallingUid()`，即**当前这个 binder 事务的发起方**。
 * 我们的调用点跑在 `NotificationManagerService#enqueueNotificationInternal` 的投递主路径上
 * （见 [SystemServerHook.deliver] / [SystemServerHook.reportIntercepted]），
 * 此刻那个 uid 是**发通知的那个应用**（菜鸟 / 淘宝 / 拼多多 / 短信），它当然没有签名权限 → 必被拒：
 *
 * ```
 * SecurityException: Permission Denial: getIntentForIntentSender() from pid=…, uid=…
 *     requires android.permission.GET_INTENT_SENDER_INTENT
 *   at ActivityManagerService.enforceCallingPermission
 *   at ActivityManagerService.getIntentForIntentSender
 *   at android.app.PendingIntent.getIntent
 * ```
 *
 * 更坏的是这个异常被底下的 `runCatching` 吞掉了，现场只剩一句「取不到内部 Intent」——
 * 看上去像 ROM 结构变了，于是往错的方向找。
 *
 * **修法**（framework 内部处理这件事的标准做法，见 AOSP 各 binder 回调）：
 * `Binder.clearCallingIdentity()` 把身份换成 system_server 自己（uid 1000），
 * `ActivityManager#checkComponentPermission` 对 `SYSTEM_UID` 直接放行，取完立刻 `restore`。
 * 设备 `framework.jar` 里 `GET_INTENT_SENDER_INTENT` 与 `getIntentForIntentSender` 两个串都在
 * （`grep -a` 各命中 2 处），说明这条权限闸确实在这台机器上生效。
 *
 * ⚠️ **改动这个文件之后必须重启设备**：hook 只在注入时装载，换 APK 不会重新注入 system_server。
 */
internal object NotificationIntentReader {

    /**
     * 快照串的长度上限。超了就**不存**（宁可没有，也不要存一个被截断后解不开的串）。
     *
     * 5000 是量出来的量级上限：正常通知的 contentIntent 只有 component + action +
     * 一两个字符串参数，几十到几百字符；能撑到几千的通常是塞了长文本的深链。
     */
    private const val MAX_URI_LENGTH = 5000

    /**
     * 正常路径失败只播报一次。
     *
     * 这个函数跑在每条被识别的通知上，成了热路径 —— 每次都打会把 LSPosed 日志刷没
     * （项目约定：热路径日志按原因去重）。失败原因在同一次进程里是稳定的
     * （权限被拒 / 方法不存在都是结构性的），一次足够定位。
     */
    @Volatile private var getIntentFailureLogged = false

    /**
     * @return 可落盘的快照串；拿不到（没有令牌 / 反射失败 / 太长）时返回 null。
     *
     * 全过程 `runCatching`：调用点在 system_server 的通知投递主路径上，
     * 这里失败只该让「记录页少一个按钮」，绝不能让异常冒到 NMS 的栈上。
     */
    fun snapshot(pendingIntent: PendingIntent?): String? = runCatching {
        val pi = pendingIntent ?: return null
        val intent = intentOf(pi) ?: run {
            XposedBridge.log("notification intent snapshot: 取不到内部 Intent（调用身份没清干净？ROM 结构变了？）")
            return null
        }
        val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
        if (uri.length > MAX_URI_LENGTH) {
            // 只记长度，不记内容 —— 里面可能有订单号之类的用户数据。
            XposedBridge.log("notification intent snapshot: too long (${uri.length}), skipped")
            return null
        }
        uri
    }.getOrElse {
        XposedBridge.logError("notification intent snapshot failed", it)
        null
    }

    /**
     * 取令牌内部的 `Intent`。**整段在清掉调用身份的状态下执行**（原因见类注释）。
     */
    private fun intentOf(pendingIntent: PendingIntent): Intent? {
        val identity = Binder.clearCallingIdentity()
        return try {
            fromGetIntent(pendingIntent) ?: fromLegacyIntentField(pendingIntent)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * AOSP 的正常路径：`PendingIntent#getIntent()`（`@UnsupportedAppUsage`，`@hide`）。
     *
     * 它的实现是 `ActivityManager.getService().getIntentForIntentSender(mTarget)`，
     * 所以**必须**在清掉调用身份的状态下调用（见 [intentOf]）——否则抛
     * `SecurityException`，而异常会被这里吞成 null，现场看不出是被权限拒的。
     */
    private fun fromGetIntent(pendingIntent: PendingIntent): Intent? {
        val result = runCatching {
            PendingIntent::class.java.getMethod("getIntent").invoke(pendingIntent) as? Intent
        }
        result.exceptionOrNull()?.let { error ->
            if (!getIntentFailureLogged) {
                getIntentFailureLogged = true
                XposedBridge.logError("PendingIntent#getIntent() failed, falling back", error)
            }
        }
        return result.getOrNull()
    }

    /**
     * 兜底路径：直接读 `mIntent` 字段。
     *
     * ⚠️ **AOSP 的 `PendingIntent` 没有这个字段**（核对 master 源码：它只有
     * `private final IIntentSender mTarget`；`mIntent` 只出现在内部类 `FinishedDispatcher` 上）。
     * 留着它是因为个别 OEM 改过的 framework 可能保留过这个字段，而代价只是一次失败的反射；
     * 但**别指望它在 AOSP 上救场** —— 真正的主力是 [fromGetIntent]。
     */
    private fun fromLegacyIntentField(pendingIntent: PendingIntent): Intent? = runCatching {
        PendingIntent::class.java
            .getDeclaredField("mIntent")
            .apply { isAccessible = true }
            .get(pendingIntent) as? Intent
    }.getOrNull()
}
