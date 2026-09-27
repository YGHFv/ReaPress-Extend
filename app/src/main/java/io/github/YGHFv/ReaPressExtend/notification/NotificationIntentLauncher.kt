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
 * 「打开原通知」这件事的**执行者**：两条路，能忠实就忠实，不能就退到还能用的那一步。
 *
 * | 顺序 | 来源 | 做到什么 | 寿命 |
 * |---|---|---|---|
 * | 1 | [NotificationIntentCache] 里的 `PendingIntent`，直接 `send()` | **等价于点原通知本身** —— 连「由宿主发起」这个身份都对 | 设备本次开机 |
 * | 2 | 记录里存的快照串 → 打开宿主 **App 主入口** | 回到那个 App（**不是**那条通知的页面） | 永久（在记录里） |
 *
 * ## 路 1 那块令牌从哪来（2026-09-27 增强）
 *
 * 两条来路，都落进同一张 [NotificationIntentCache]：
 *
 * - **投递广播顺手带来的**（hook 侧 `ExpressRelaySender` 把令牌作为 Parcelable extra 发出）
 *   —— 零往返，但只在那一次投递时有效；
 * - **向 system_server 取回来的**（记录里的 `Entry.tokenId` → `IntentTokenFetcher`，
 *   页面打开时预热）—— 要一趟跨进程往返，但**模块进程被回收之后还能取**。
 *
 * 后者是这次补的：令牌没法落盘（binder 句柄），却能寄存在 system_server（它跟设备同寿），
 * 于是它的寿命上限从「模块进程」变成「**设备本次开机**」。设备一重启两边一起归零，
 * 那时就只剩路 2 —— 这也是界面上那句说明文字的来历。
 *
 * ## 🔴 路 2 为什么不能「重建 Intent 直接 startActivity」（2026-09-27 真机定位）
 *
 * 原来的实现是 `Intent.parseUri(快照)` → `startActivity`，对普通 Activity 没问题，
 * 但对**推送落地页**会卡死。真机实证（菜鸟那条包裹推送）：
 *
 * ```
 * 快照 component = com.cainiao.wireless/.mvp.activities.EntrustDialogActivity
 * （它的 intent-filter 是 agoo://com.cainiao.wireless/thirdpush 与 guoguo://go/entrust_dialog
 *   —— 阿里云推送的第三方落地页）
 *
 * 外部启动后：Activity 栈里只有它，topResumedActivity，画面永久停在菜鸟启动页
 * （等了几分钟不动）；同一栈里正常启动的菜鸟首页则是 WelcomeActivity → HomePageActivity
 * 启动来源对照：card 住的落地页 launchedFromPackage=com.android.shell（不是宿主自己）
 *               正常启动的菜鸟首页 launchedFromPackage=com.cainiao.wireless
 * ```
 *
 * 这个组件要等宿主自己的推送服务把消息喂过来才继续，而「谁启动的它」就是它判断要不要干活的
 * 依据之一（`Activity#getLaunchedFromPackage()`）。**外部启动永远给不出那个身份** ——
 * 冷启动、热启动、`am start` 复刻快照三种都停在同一屏，所以这不是时序问题，是这条路本身走不通。
 *
 * 而路 1 的 `send()` 是系统替**宿主**投递它自己的令牌，启动来源就是宿主 —— 这正是「点原通知」
 * 那一下。**令牌优先不是偏好，是唯一忠实的做法。**
 *
 * 令牌在**模块进程**里没有了，就**退到「打开那个 App」**，不再往宿主内部组件里闯：闯进去是
 * **卡死**，而卡死比「只打开首页」更糟 —— 用户既回不到原页面，也退不出来。
 * 界面据此换措辞（[isFaithful]），不让「打开原通知」这四个字许诺一个做不到的页面。
 *
 * ⚠️ 「模块进程里没有」**不等于**「取不到了」：记录里那个 [ExpressNotificationLog.Entry.tokenId]
 * 还能把它从 system_server 取回来（见上面的「路 1」）。取回是页面打开时的**预热**，
 * 到货之后由 `ACTION_INTENT_TOKEN_READY` 叫界面重算 —— 所以这里读到的令牌表是**活的**，
 * 别把它当成一个只读一次的快照（那正是加 [canOpen] / [isFaithful] 时最容易踩的坑：
 * 想在 `remember` 里算一次就够，而它其实会变）。
 */
object NotificationIntentLauncher {

    private const val TAG = "ReaPress"

    /** 这条记录现在能不能打开。界面据此决定按钮显不显示。 */
    fun canOpen(entry: ExpressNotificationLog.Entry): Boolean =
        NotificationIntentCache.get(entry.id) != null || entry.intentUri.isNotBlank()

    /**
     * 这次点击能不能真正**回到那条通知的页面**。
     *
     * `true`  = 令牌在手（投递时拿到的，或刚向 system_server 取回的）→ `send()` = 点原通知本身；
     * `false` = 只剩快照，只能打开宿主的 App（原因见类注释）。
     *
     * ⚠️ **它读的是活状态**：令牌预热到货后会变成 true（界面靠 `ACTION_INTENT_TOKEN_READY`
     * 重算）。所以调用方**不能在 `remember` 里只算一次**（见类注释末尾那条提醒）。
     */
    fun isFaithful(entry: ExpressNotificationLog.Entry): Boolean =
        NotificationIntentCache.get(entry.id) != null

    /**
     * 打开原通知指向的界面。
     *
     * @return null = 成功；否则是给用户看的一句失败原因（直接显示在详情页上）。
     */
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
     * 打开宿主 App 的主入口。
     *
     * 用 `getLaunchIntentForPackage` 而不是自己拼 `MAIN/LAUNCHER`：前者由系统给出该 App
     * 声明的入口，还顺带处理了「没有 launcher 图标的 App」。包可见性靠 manifest 的
     * `<queries>`（点名的三个来源 + 一条通用 LAUNCHER 查询）。
     */
    private fun openApp(context: Context, pkg: String, uri: String): String? = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            ModuleAndroidLog.legacy(TAG, "原通知跳转：打开 $pkg 主入口（快照复刻不了宿主内部组件）")
            return@runCatching null
        }
        // 罕见：这个包没有 launcher 入口（纯插件/推送服务包）。此时快照重建是唯一还能试的东西，
        // 卡不卡只能看宿主 —— 总比什么都不做强。
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
        // 令牌的创建者是宿主，AMS 按它的身份放行 —— 模块进程在后台也能把它发起来，
        // 而且启动来源就是宿主自己（这正是宿主内部组件肯干活的前提，见类注释）。
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
     *
     * 只剩「宿主连主入口都没有」这一条兜底路径会用到它了。
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
