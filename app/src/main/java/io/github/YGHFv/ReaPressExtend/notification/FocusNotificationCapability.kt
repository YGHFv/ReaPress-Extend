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
 * 设备「能不能发小米焦点通知 / 超级岛」的运行时探测（三个查询接口均来自小米官方《开发指南》）。
 * 模块发的替换通知替代了被吞掉的原通知，没有任何退路 —— 附加参数必须探到系统认这套参数
 * 且本应用有权限（[Snapshot.canAttachFocusParam]）才做，不能「先发了再说」。
 * 探测结果进程内缓存只信一次：canShowFocus 是一次 Binder 调用，不挂每条通知的发送路径。
 */
object FocusNotificationCapability {

    private const val TAG = "ReaPress"

    private const val KEY_PROTOCOL = "notification_focus_protocol"

    private const val PROP_ISLAND = "persist.sys.feature.island"

    private val FOCUS_URI = Uri.parse("content://miui.statusbar.notification.public")

    /**
     * 一次探测的结果。[protocol] 0 = 不支持（非小米 / 老系统）；[islandSupported] OS3 才为 true；
     * [canShowFocus] 本应用是否已获得焦点通知权限。
     */
    data class Snapshot(
        val protocol: Int,
        val islandSupported: Boolean,
        val canShowFocus: Boolean,
    ) {
        /** 能不能附加 `miui.focus.param`：系统认得参数且本应用有权限，缺一不可 —— 没许可时参数可能被系统过滤，而替换通知丢一条就是全没了。 */
        val canAttachFocusParam: Boolean get() = protocol > 0 && canShowFocus
    }

    @Volatile
    private var cached: Snapshot? = null

    /** 探测（进程内只探一次），结果写一次日志 —— 它是「为什么没上岛」的第一手答案。 */
    fun probe(context: Context): Snapshot = cached ?: synchronized(this) {
        cached ?: detect(context).also {
            cached = it
            ModuleAndroidLog.legacy(
                TAG,
                "focus capability: protocol=${it.protocol} island=${it.islandSupported} " +
                    "canShowFocus=${it.canShowFocus} -> attach=${it.canAttachFocusParam}",
            )
        }
    }

    /** 丢掉缓存；当前没有调用方，留给后续的「立即重试」。 */
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

    /** 反射读 `@hide` 的 SystemProperties（官方文档给的办法）；读不到一律当不支持。 */
    private fun readIslandProp(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val getBoolean = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        getBoolean.invoke(null, PROP_ISLAND, false) as? Boolean ?: false
    }.getOrDefault(false)

    /** provider 对不是自己 UID 的包名会抛 SecurityException（真机核对），只能查自己。 */
    private fun queryCanShowFocus(context: Context): Boolean = runCatching {
        val extras = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver
            .call(FOCUS_URI, METHOD_CAN_SHOW_FOCUS, null, extras)
            ?.getBoolean(RESULT_CAN_SHOW_FOCUS) == true
    }.getOrDefault(false)

    private const val METHOD_CAN_SHOW_FOCUS = "canShowFocus"
    private const val RESULT_CAN_SHOW_FOCUS = "canShowFocus"
}
