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

package io.github.YGHFv.ReaPressExtend.config

import android.content.Context
import android.content.SharedPreferences
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 模块 App 进程侧的设置读写。
 *
 * ## 两处存储，各有分工
 *
 * - **本地 SharedPreferences**：模块界面直接读写，永远可用。界面上的值以它为准。
 * - **RemotePreferences**（框架提供）：被注入的进程（system_server、宿主 App）读的是这一份。
 *   它由框架托管，模块 App 通过 `XposedService.getRemotePreferences(group)` 写入。
 *
 * 为什么不用一份：被注入的进程与模块 App 是两个 uid，**读不到对方的私有目录**。
 * 框架的 RemotePreferences 是唯一能让两边共享数据的通道，但它依赖 `PROP_CAP_REMOTE` 能力，
 * 且需要框架服务在线 —— 都不能假定。所以本地那份是权威副本，RemotePreferences 是投影：
 * 每次写设置时尽力同步过去，同步失败只记日志（界面照常工作，只是被注入侧读到的还是旧值）。
 *
 * ## 为什么读设置也要走本地
 *
 * 界面显示的是「用户设了什么」，本地 prefs 是唯一不会因为框架离线而失真的来源。
 * 如果读 RemotePreferences，框架一断界面就显示成默认值，用户会以为设置丢了。
 */
object ExpressSettings {

    private const val TAG = "ReaPress"

    /**
     * 框架服务连接器。
     *
     * 由模块 App 进程在启动时注册（见 `ExpressApplication`）。为空说明框架没连上 ——
     * 通常是 LSPosed 没启用模块、或者模块不在作用域里。
     */
    @Volatile
    private var service: Any? = null

    /**
     * 绑定框架服务。
     *
     * 参数类型是 `Any?` 而不是 `XposedService`：`io.github.libxposed.service` 虽然是
     * `implementation` 依赖（会打进 APK），但把它写进签名会让这个类在缺少该依赖的场景下
     * 直接 `NoClassDefFoundError`。用 Any + 反射调用，把依赖软化成可选。
     */
    fun attachService(xposedService: Any?) {
        service = xposedService
    }

    fun isServiceAvailable(): Boolean = service != null

    /** 读设置（本地权威副本）。 */
    fun read(context: Context): ExpressSettingsSnapshot =
        ExpressSettingsKeys.readFrom(ExpressSettingsKeys.localPrefs(context))

    /**
     * 写设置：先落本地，再尽力同步给框架。
     *
     * @return true 表示本地已写入（同步失败不影响返回值，只记日志）
     */
    fun write(context: Context, snapshot: ExpressSettingsSnapshot): Boolean {
        val local = ExpressSettingsKeys.localPrefs(context)
        return runCatching {
            ExpressSettingsKeys.writeTo(local, snapshot)
            syncToFramework(context, snapshot)
            true
        }.getOrElse {
            ModuleAndroidLog.error(TAG, "write settings failed", it)
            false
        }
    }

    /** 局部更新：读出来改一个字段再写回。 */
    fun update(context: Context, block: (ExpressSettingsSnapshot) -> ExpressSettingsSnapshot): Boolean =
        write(context, block(read(context)))

    /**
     * 只重投影当前的本地设置到框架。
     *
     * 用于「复位看门狗」这类**直接改了本地 prefs 里的单个一次性标志**的场景 ——
     * 它不在 [ExpressSettingsSnapshot] 里（见 [ExpressSettingsKeys.writeTo] 的注释），
     * 所以不能通过 [write] 投影，只能整个快照重投一遍。
     */
    fun syncToFrameworkNow(context: Context) {
        runCatching { syncToFramework(context, read(context)) }
    }

    /**
     * 把设置投影到框架的 RemotePreferences。
     *
     * 走反射而不是直接调 `XposedService.getRemotePreferences`：见 [attachService] 的注释。
     * 失败只记日志 —— 界面上的设置已经存好了，只是被注入侧暂时读不到新值。
     */
    private fun syncToFramework(context: Context, snapshot: ExpressSettingsSnapshot) {
        val target = service
        if (target == null) {
            ModuleAndroidLog.legacy(
                TAG,
                "framework service unavailable — settings saved locally but not propagated",
            )
            return
        }
        runCatching {
            val prefs = target.javaClass
                .getMethod("getRemotePreferences", String::class.java)
                .invoke(target, ExpressSettingsKeys.GROUP) as? SharedPreferences
            if (prefs == null) {
                ModuleAndroidLog.error(TAG, "framework returned null RemotePreferences")
                return
            }
            ExpressSettingsKeys.writeTo(prefs, snapshot)
            // 复位请求是独立的一次性标志（不在 snapshot 里），必须单独带过去 ——
            // 少了这行，用户点「复位」后 system_server 永远收不到请求。
            val forceEnabled = ExpressSettingsKeys.localPrefs(context)
                .getBoolean(ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED, false)
            if (forceEnabled) {
                ExpressSettingsKeys.requestHookForceEnable(prefs, true)
            }
            ModuleAndroidLog.legacy(TAG, "settings propagated to framework")
        }.onFailure {
            ModuleAndroidLog.error(
                TAG,
                "propagate settings to framework failed (local copy is intact)",
                it,
            )
        }
    }

    /** 诊断用：设置来源是否可用。 */
    fun describe(context: Context): String = buildString {
        append(read(context).summary())
        append(" service=").append(if (isServiceAvailable()) "bound" else "unbound")
    }
}
