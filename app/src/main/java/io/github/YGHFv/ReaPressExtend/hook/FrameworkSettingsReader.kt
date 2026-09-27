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

import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface

/** 在注入进程里读设置：只能在被注入进程加载（引用 XposedInterface，模块 App 进程触碰会 NoClassDefFoundError）；不缓存 —— getRemotePreferences 是快照语义，缓存了就再也看不到界面改动。 */
internal object FrameworkSettingsReader {

    fun read(framework: XposedInterface): ExpressSettingsSnapshot {
        return try {
            val prefs = framework.getRemotePreferences(ExpressSettingsKeys.GROUP)
            ExpressSettingsKeys.readFrom(prefs)
        } catch (t: Throwable) {
            // 框架不支持 remote 能力会抛 UnsupportedOperationException：退回默认值而不是崩掉 system_server。
            XposedBridge.log(
                "read remote settings failed, falling back to defaults: " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            ExpressSettingsSnapshot.DEFAULT
        }
    }

    fun read(): ExpressSettingsSnapshot {
        val framework = XposedBridge.framework() ?: return ExpressSettingsSnapshot.DEFAULT
        return read(framework)
    }
}
