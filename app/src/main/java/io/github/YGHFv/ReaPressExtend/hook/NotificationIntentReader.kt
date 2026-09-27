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
 * 把原通知的 PendingIntent 拆成能落盘的字符串（Intent.toUri(URI_INTENT_SCHEME)，配
 * Intent.parseUri 还原）。令牌本体是 binder 句柄没法落盘；快照重建会丢 Parcelable extras 与
 * FLAG_GRANT_*，但「打开 Activity + 一两个参数」对通知 contentIntent 够用。
 *
 * 调用点在被注入的 system_server 的通知投递主路径上：PendingIntent.getIntent() 是
 * @UnsupportedAppUsage 的公开方法，反射能拿；其内部走 AMS getIntentForIntentSender，会
 * enforceCallingPermission(GET_INTENT_SENDER_INTENT)（signature 级），而此刻 binder 调用身份是
 * 发通知的应用，必被 SecurityException 拒掉且被 runCatching 吞掉——所以取 Intent 全程必须在
 * Binder.clearCallingIdentity()/restore 之间执行（身份换成 system_server 即放行）。
 *
 * 改动本文件之后必须重启设备：hook 只在注入时装载，换 APK 不会重新注入 system_server。
 */
internal object NotificationIntentReader {

    /** 快照串长度上限。超了就不存——宁可没有，也不要存一个被截断后解不开的串。 */
    private const val MAX_URI_LENGTH = 5000

    /** 热路径失败只播报一次：失败原因是结构性的，每次都打会把 LSPosed 日志刷没。 */
    @Volatile private var getIntentFailureLogged = false

    /** 可落盘的快照串；拿不到返回 null。全程 runCatching：失败只该让记录页少一个按钮，绝不能让异常冒到 NMS 栈上。 */
    fun snapshot(pendingIntent: PendingIntent?): String? = runCatching {
        val pi = pendingIntent ?: return null
        val intent = intentOf(pi) ?: run {
            XposedBridge.log("notification intent snapshot: 取不到内部 Intent（调用身份没清干净？ROM 结构变了？）")
            return null
        }
        val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
        if (uri.length > MAX_URI_LENGTH) {
            // 只记长度不记内容——里面可能有订单号之类的用户数据。
            XposedBridge.log("notification intent snapshot: too long (${uri.length}), skipped")
            return null
        }
        uri
    }.getOrElse {
        XposedBridge.logError("notification intent snapshot failed", it)
        null
    }

    /** 取令牌内部的 Intent。整段在清掉调用身份的状态下执行（原因见类注释）。 */
    private fun intentOf(pendingIntent: PendingIntent): Intent? {
        val identity = Binder.clearCallingIdentity()
        return try {
            fromGetIntent(pendingIntent) ?: fromLegacyIntentField(pendingIntent)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * AOSP 的正常路径：PendingIntent#getIntent()（@UnsupportedAppUsage、@hide）。
     * 必须在清掉调用身份的状态下调用，否则抛 SecurityException 且被吞成 null，现场看不出是被权限拒的。
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
     * 兜底：直接读 mIntent 字段。AOSP 的 PendingIntent 没有这个字段（它只有 mTarget）；
     * 留着它是因为个别 OEM 改过的 framework 可能保留过，真正的主力是 [fromGetIntent]。
     */
    private fun fromLegacyIntentField(pendingIntent: PendingIntent): Intent? = runCatching {
        PendingIntent::class.java
            .getDeclaredField("mIntent")
            .apply { isAccessible = true }
            .get(pendingIntent) as? Intent
    }.getOrNull()
}
