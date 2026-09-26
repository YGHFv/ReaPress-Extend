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

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 设备「能不能发小米焦点通知 / 超级岛」的运行时探测。
 *
 * ## 依据
 *
 * 三个查询接口全部来自小米官方《开发指南》（`dev.mi.com/xiaomihyperos/documentation/detail?pId=2131`
 * 「五、查询接口」）：
 * 1. `Settings.System` 的 `notification_focus_protocol` → 1 = OS1 焦点通知模板、2 = OS2、
 *    3 = OS3 超级岛。**0 表示这套系统压根没有焦点通知能力**（比如非小米设备、MIUI 12 以前）。
 * 2. `persist.sys.feature.island` → 是否支持**岛**。真机核对（2026-09-26，HyperOS 1.0 /
 *    Android 14）：该属性**为空**，同时 protocol = 1 —— 两者一致，说明「不是 OS3 就没有岛」
 *    这条规律在本机成立。
 * 3. `content://miui.statusbar.notification.public` 的 `canShowFocus` → **本应用**有没有被授予
 *    焦点通知权限。官方文档明确要求业务方「根据以上查询结果，选择是否发送焦点通知或者岛通知」——
 *    所以这里不是「探测到了就发」，而是**探测到本应用有权限才附参数**。
 *
 * ## 为什么要探测而不是直接发
 *
 * 模块发的替换通知替代了被吞掉的原通知（拦截模式下原通知不再出现），所以「这条通知到底
 * 显不显示」完全没有退路 —— 一旦附加了系统解析不了的参数、而系统又把它过滤掉，用户会
 * 什么都看不到。附加参数这件事因此必须**有明确许可才做**，而不是「先发了再说」。
 *
 * 探测结果只信一次，进程内缓存（见 [probe]）：它由系统设置与用户授权决定，一次运行里不会变，
 * 而 `canShowFocus` 是一次 Binder 调用，不该挂在每一条通知的发送路径上。
 */
object FocusNotificationCapability {

    private const val TAG = "ReaPress"

    /** 焦点通知协议版本所在的设置项（`Settings.System` 命名空间）。 */
    private const val KEY_PROTOCOL = "notification_focus_protocol"

    /** 「是否支持超级岛」的系统属性。 */
    private const val PROP_ISLAND = "persist.sys.feature.island"

    private val FOCUS_URI = Uri.parse("content://miui.statusbar.notification.public")

    /**
     * 一次探测的结果。
     *
     * @param protocol 焦点通知协议版本；0 = 不支持（非小米 / 老系统）
     * @param islandSupported 系统是否支持超级岛（OS3 才为 true）
     * @param canShowFocus 本应用是否已获得焦点通知权限
     */
    data class Snapshot(
        val protocol: Int,
        val islandSupported: Boolean,
        val canShowFocus: Boolean,
    ) {
        /**
         * 这次发通知能不能附加 `miui.focus.param`。
         *
         * 两个条件缺一不可：[protocol] > 0（系统认得这套参数）且 [canShowFocus]（本应用被允许用）。
         * 只有拿到许可才附加 —— 没有许可时这份参数可能被系统直接过滤掉，而模块的通知
         * 替代的正是被吞掉的原通知，丢一条就是全没了。
         */
        val canAttachFocusParam: Boolean get() = protocol > 0 && canShowFocus
    }

    @Volatile
    private var cached: Snapshot? = null

    /** 探测（进程内只探一次）。 */
    fun probe(context: Context): Snapshot = cached ?: synchronized(this) {
        cached ?: detect(context).also {
            cached = it
            // 探测结果只写一次日志：它是「为什么没上岛」这个问题的第一手答案，
            // 而模块的日志是用户唯一能拿到的东西（模块界面看不到这些值）。
            ModuleAndroidLog.legacy(
                TAG,
                "focus capability: protocol=${it.protocol} island=${it.islandSupported} " +
                    "canShowFocus=${it.canShowFocus} -> attach=${it.canAttachFocusParam}",
            )
        }
    }

    /** 丢掉缓存。设置页改动后重新探测用；当前没有调用方，留给后续的「立即重试」。 */
    fun invalidate() {
        cached = null
    }

    private fun detect(context: Context): Snapshot = runCatching {
        Snapshot(
            protocol = Settings.System.getInt(context.contentResolver, KEY_PROTOCOL, 0),
            islandSupported = readIslandProp(),
            canShowFocus = queryCanShowFocus(context),
        )
    }.getOrElse {
        ModuleAndroidLog.error(TAG, "focus capability probe failed", it)
        Snapshot(protocol = 0, islandSupported = false, canShowFocus = false)
    }

    /**
     * 读 `persist.sys.feature.island`。
     *
     * 走反射读 `android.os.SystemProperties` —— 官方文档给的就是这个办法（它自己也是 `@hide`）。
     * 读不到（隐藏 API 拦截、非小米 ROM 没有这个类）一律当**不支持**：岛的字段我们只会
     * 在确认支持时才写，猜错了比不做更糟。
     */
    private fun readIslandProp(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val getBoolean = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        getBoolean.invoke(null, PROP_ISLAND, false) as? Boolean ?: false
    }.getOrDefault(false)

    /**
     * 查本应用有没有焦点通知权限。
     *
     * provider 对**不是自己 UID 的包名**会抛 `SecurityException`（真机核对：`content call` 传别的
     * 包名时如此），所以这里只能查自己（`context.packageName`）—— 这也正是官方示例的用法。
     */
    private fun queryCanShowFocus(context: Context): Boolean = runCatching {
        val extras = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver
            .call(FOCUS_URI, METHOD_CAN_SHOW_FOCUS, null, extras)
            ?.getBoolean(RESULT_CAN_SHOW_FOCUS) == true
    }.getOrDefault(false)

    private const val METHOD_CAN_SHOW_FOCUS = "canShowFocus"
    private const val RESULT_CAN_SHOW_FOCUS = "canShowFocus"
}
