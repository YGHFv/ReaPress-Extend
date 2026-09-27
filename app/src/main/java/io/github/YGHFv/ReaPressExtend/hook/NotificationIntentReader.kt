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
 * 拿不到时退一步读 `mIntent` 字段（不同 ROM 上两者各自可能缺席）。
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
     * @return 可落盘的快照串；拿不到（没有令牌 / 反射失败 / 太长）时返回 null。
     *
     * 全过程 `runCatching`：调用点在 system_server 的通知投递主路径上，
     * 这里失败只该让「记录页少一个按钮」，绝不能让异常冒到 NMS 的栈上。
     */
    fun snapshot(pendingIntent: PendingIntent?): String? = runCatching {
        val pi = pendingIntent ?: return null
        val intent = intentOf(pi) ?: run {
            XposedBridge.log("notification intent snapshot: 取不到内部 Intent（ROM 结构变了？）")
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

    /** 两条取法：公开的 `getIntent()`，退而求其次读 `mIntent` 字段。 */
    private fun intentOf(pendingIntent: PendingIntent): Intent? {
        runCatching {
            PendingIntent::class.java.getMethod("getIntent").invoke(pendingIntent) as? Intent
        }.getOrNull()?.let { return it }
        return runCatching {
            PendingIntent::class.java
                .getDeclaredField("mIntent")
                .apply { isAccessible = true }
                .get(pendingIntent) as? Intent
        }.getOrNull()
    }
}
