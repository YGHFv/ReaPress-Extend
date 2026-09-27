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

/**
 * 「打开原通知」这件事的**执行者**：两条路，能忠实就忠实。
 *
 * ## 两条路及其顺序（2026-09-27 修订）
 *
 * | 顺序 | 来源 | 保真度 | 寿命 |
 * |---|---|---|---|
 * | 1 | 内存里的 `PendingIntent`，直接 `send()` | **完整** —— 就是原通知那一下 | 模块进程的寿命 |
 * | 2 | 记录里存的快照串，`Intent.parseUri` 重建后 `startActivity` | 基础参数级（Parcelable extras / grant flag 会丢） | **永久**（在记录里） |
 *
 * 先试 1：它是原通知的令牌，连「由谁启动、带什么权限」都是对的。
 * 1 没有了（进程重启过）才走 2。
 *
 * ## 为什么之前只有第 1 条（用户的疑问）
 *
 * 见 [NotificationIntentReader] 的类注释：`PendingIntent` 确实不能落盘，但**它里面的 Intent 可以**
 * —— `Intent.toUri(URI_INTENT_SCHEME)` 是系统自己用的那套编码。之前只看到「令牌不能存」
 * 就停下了，漏掉了「令牌的内容能存」。现在快照随记录一起落盘，重启后按钮不再消失。
 */
object NotificationIntentLauncher {

    private const val TAG = "ReaPress"

    /** 这条记录现在能不能打开原通知。界面据此决定按钮显不显示。 */
    fun canOpen(entry: ExpressNotificationLog.Entry): Boolean =
        NotificationIntentCache.get(entry.id) != null || entry.intentUri.isNotBlank()

    /**
     * 打开原通知指向的界面。
     *
     * @return null = 成功；否则是给用户看的一句失败原因（直接显示在详情页上）。
     */
    fun open(context: Context, entry: ExpressNotificationLog.Entry): String? {
        // 路 1：内存里的令牌还活着。
        NotificationIntentCache.get(entry.id)?.let { token ->
            return sendToken(token)
        }
        // 路 2：快照重建。
        val uri = entry.intentUri.takeIf { it.isNotBlank() }
            ?: return "这条记录没有留下跳转信息"
        return runCatching {
            val intent = parse(uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ModuleAndroidLog.legacy(TAG, "原通知跳转：按快照重建（${describe(intent)}）")
            null
        }.getOrElse { error ->
            ModuleAndroidLog.error(TAG, "原通知跳转失败（快照重建）", error)
            error.message ?: error.javaClass.simpleName
        }
    }

    private fun sendToken(token: PendingIntent): String? = runCatching {
        // 令牌的创建者是宿主，AMS 按它的身份放行 —— 模块进程在后台也能把它发起来。
        token.send()
        ModuleAndroidLog.legacy(TAG, "原通知跳转：用内存里的令牌 send()")
        null
    }.getOrElse { error ->
        ModuleAndroidLog.error(TAG, "原通知跳转失败（令牌 send）", error)
        error.message ?: error.javaClass.simpleName
    }

    /**
     * `Intent.parseUri` 的宽松档。
     *
     * 这份 URI 是我们自己从宿主通知里抠出来、存在自己私有目录里的，不来自外部输入，
     * 所以用 `URI_ALLOW_UNSAFE`（API 30+）——不加的话带 `FLAG_GRANT_*` 之类的 URI 会被
     * 系统拒掉，而那种拒绝的表现是「点了没反应」，最难查。
     */
    private fun parse(uri: String): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent.parseUri(uri, Intent.URI_ALLOW_UNSAFE)
        } else {
            Intent.parseUri(uri, 0)
        }

    /** 只用于日志，不记参数值（可能有订单号）。 */
    private fun describe(intent: Intent): String =
        "${intent.component?.packageName ?: intent.`package` ?: "隐式"} / ${intent.action ?: "-"}"
}
