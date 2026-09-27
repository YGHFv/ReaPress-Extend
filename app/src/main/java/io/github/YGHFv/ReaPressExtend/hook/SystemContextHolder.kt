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
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers

/** system_server 里的 Context 来源：运行期优先从 NMS 实例的 mContext 反射取，启动期用 ActivityThread.currentActivityThread().getSystemContext() —— 不能用 systemMain()，它每次调用都会新建 ActivityThread 并 attach，属严重副作用。全程 fail-soft。 */
internal object SystemContextHolder {

    @Volatile private var cached: Context? = null

    fun upgrade(nmsInstance: Any?) {
        if (nmsInstance == null) return
        val context = runCatching {
            XposedHelpers.getObjectField(nmsInstance, "mContext") as? Context
        }.getOrNull() ?: return
        cached = context
    }

    fun acquireFromActivityThread(): Context? {
        cached?.let { return it }
        val context = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                ?: return@runCatching null
            XposedHelpers.callMethod(thread, "getSystemContext") as? Context
        }.onFailure {
            XposedBridge.log(
                "SystemContextHolder: currentActivityThread route failed: ${it.javaClass.simpleName}",
            )
        }.getOrNull()
        if (context != null) cached = context
        return context
    }

    fun acquire(): Context? = cached
}
