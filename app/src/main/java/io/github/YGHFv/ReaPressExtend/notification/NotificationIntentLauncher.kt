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
import io.github.YGHFv.ReaPressExtend.core.NotificationIntentSnapshot
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 「打开原通知」的执行者，两条路：令牌在手就 send()——系统替宿主投递它自己的令牌，启动来源
 * 就是宿主，等价于点原通知本身；只剩快照串就退到打开宿主 App 主入口，绝不重建 Intent 硬闯
 * 宿主内部组件——推送落地页（如菜鸟 EntrustDialogActivity）要靠「谁启动的它」
 * （getLaunchedFromPackage）决定干不干活，外部启动永远给不出那个身份，真机实证冷热启动都
 * 停在启动页卡死。闯进去是卡死，比「只打开首页」更糟。
 *
 * 令牌寿命上限是设备本次开机（寄存在 system_server，记录里的 tokenId 可由 [IntentTokenFetcher]
 * 取回）；重启后只剩快照路。[isFaithful] / [canOpen] 读的是活状态，令牌预热到货
 * （ACTION_INTENT_TOKEN_READY）会变 true——调用方不能在 remember 里只算一次。
 */
object NotificationIntentLauncher {

    private const val TAG = "ReaPress"

    /** 这条记录现在能不能打开，界面据此决定按钮显不显示。 */
    fun canOpen(entry: ExpressNotificationLog.Entry): Boolean =
        NotificationIntentCache.get(entry.id) != null || entry.intentUri.isNotBlank()

    /** true = 能真正回到那条通知的页面（令牌在手）；读活状态，令牌到货后变 true，不能只算一次。 */
    fun isFaithful(entry: ExpressNotificationLog.Entry): Boolean =
        NotificationIntentCache.get(entry.id) != null

    /** 打开原通知指向的界面。返回 null = 成功；否则是给用户看的失败原因。 */
    fun open(context: Context, entry: ExpressNotificationLog.Entry): String? {
        // 路 1：内存里的令牌还活着 —— 这一下就是「点原通知」本身。
        NotificationIntentCache.get(entry.id)?.let { token ->
            return sendToken(token)
        }
        // 路 2：只剩快照 —— 退到「打开那个 App」，理由见类注释。
        val uri = entry.intentUri.takeIf { it.isNotBlank() }
            ?: return "这条记录没有留下跳转信息"
        val pkg = NotificationIntentSnapshot.targetPackageOf(entry.sourcePackage, uri)
            ?: return "这条记录的跳转信息不完整"
        return openApp(context, pkg, uri)
    }

    /**
     * 打开宿主 App 主入口。用 getLaunchIntentForPackage：系统给出该 App 声明的入口，
     * 还顺带处理了没有 launcher 图标的 App。包可见性靠 manifest 的 <queries>。
     */
    private fun openApp(context: Context, pkg: String, uri: String): String? = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            ModuleAndroidLog.legacy(TAG, "原通知跳转：打开 $pkg 主入口（快照复刻不了宿主内部组件）")
            return@runCatching null
        }
        // 罕见：这个包没有 launcher 入口（纯插件/推送服务包），快照重建是唯一还能试的。
        ModuleAndroidLog.legacy(TAG, "原通知跳转：$pkg 没有主入口，退回快照重建")
        val intent = parse(uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        ModuleAndroidLog.legacy(TAG, "原通知跳转：按快照重建（${describe(intent)}）")
        null
    }.getOrElse { error ->
        ModuleAndroidLog.error(TAG, "原通知跳转失败", error)
        error.message ?: error.javaClass.simpleName
    }

    private fun sendToken(token: PendingIntent): String? = runCatching {
        // 令牌创建者是宿主，AMS 按它的身份放行——模块进程在后台也能发，且启动来源就是宿主。
        token.send()
        ModuleAndroidLog.legacy(TAG, "原通知跳转：用内存里的令牌 send()")
        null
    }.getOrElse { error ->
        ModuleAndroidLog.error(TAG, "原通知跳转失败（令牌 send）", error)
        error.message ?: error.javaClass.simpleName
    }

    /**
     * URI 是我们自己从宿主通知抠出来、存在私有目录的，不是外部输入，API 30+ 用
     * URI_ALLOW_UNSAFE——不加的话带 FLAG_GRANT_* 的 URI 被拒，表现为「点了没反应」最难查。
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
