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

import android.content.Context
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback

/** 宿主 Context：主路挂 Application#onCreate（只碰公开类），兜底反射 ActivityThread.currentApplication()（灰名单，宿主收紧 targetSdk 即断）；全程 fail-soft。 */
internal object HostContextHolder {

    private const val APPLICATION_CLASS = "android.app.Application"
    private const val APPLICATION_CREATE = "onCreate"

    @Volatile private var cached: Context? = null

    @Volatile private var captureInstalled = false

    @Volatile private var failureLogged = false

    private val readyLock = Any()

    private val readyListeners = mutableListOf<(Context) -> Unit>()

    /** 注册「context 到手那一刻执行」的回调：有些初始化晚了就永远做不成（如反向索取通道注册）。已就绪则当场执行，否则等 [publish]；回调只执行一次、可能跑在任意线程，须自保证线程安全且不阻塞。 */
    fun onReady(listener: (Context) -> Unit) {
        val immediate = synchronized(readyLock) {
            cached ?: run { readyListeners.add(listener); null }
        }
        if (immediate != null) runCatching { listener(immediate) }
    }

    /** 先落缓存再快照执行回调；执行期间新挂的不会被这轮吞掉（会当场走已就绪路径）。 */
    private fun publish(context: Context) {
        cached = context
        val pending = synchronized(readyLock) {
            readyListeners.toList().also { readyListeners.clear() }
        }
        pending.forEach { listener ->
            runCatching { listener(context) }
                .onFailure { XposedBridge.logError("HostContextHolder: onReady listener failed", it) }
        }
    }

    /** 须在 Application 创建前装才有意义，装晚只相当于没装。 */
    fun installCapture(classLoader: ClassLoader) {
        if (captureInstalled) return
        captureInstalled = true
        runCatching {
            val applicationClass = XposedHelpers.findClass(APPLICATION_CLASS, classLoader)
            XposedBridge.hookAllMethods(
                applicationClass,
                APPLICATION_CREATE,
                object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        runCatching { upgrade(param.thisObject as? Context) }
                    }
                },
            )
            XposedBridge.logAlways("host context capture hook installed: Application#$APPLICATION_CREATE")
        }.onFailure {
            XposedBridge.logError("host context capture hook failed (falling back to reflection)", it)
        }
    }

    fun acquire(): Context? {
        cached?.let { return it }
        val context = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Context
        }.getOrNull()

        if (context == null) {
            if (!failureLogged) {
                failureLogged = true
                XposedBridge.logError(
                    "HostContextHolder: no host context yet — " +
                        "enrichment events will be dropped until it becomes available",
                )
            }
            return null
        }
        publish(context)
        return context
    }

    fun upgrade(context: Context?) {
        if (context != null) publish(context)
    }
}
