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

/**
 * 在被注入的进程里读设置。
 *
 * **这个类只能在注入进程里被加载**：它引用了 [XposedInterface]。模块 App 进程没有 libxposed，
 * 触碰这个类的字节码会 `NoClassDefFoundError`。所以设置读取分两条路：
 * 注入进程走这里，模块 App 进程走 [io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys.localPrefs]。
 *
 * ## 为什么不缓存
 *
 * `getRemotePreferences` 在 hooked 侧是**快照**语义 —— 拿到的是一份不可变 Map，缓存住就再也
 * 看不到用户在界面上改的值了。每次事件前读一次，代价是一次本地 Binder 调用。
 *
 * 这个代价可以接受，因为调用点已经收窄：只有**白名单内**的包才会走到读设置这一步
 * （见 [SystemServerHook] 里 `inspect` 的早退顺序）。系统通知每秒几十条，但菜鸟/PDD/淘宝的
 * 通知是分钟级的 —— 为后者打一次 Binder 完全划算，换来的是设置改动能立刻生效。
 */
internal object FrameworkSettingsReader {

    fun read(framework: XposedInterface): ExpressSettingsSnapshot {
        return try {
            val prefs = framework.getRemotePreferences(ExpressSettingsKeys.GROUP)
            ExpressSettingsKeys.readFrom(prefs)
        } catch (t: Throwable) {
            // 框架不支持 remote 能力时 getRemotePreferences 会抛 UnsupportedOperationException。
            // 退回默认值（拦截关闭、放行模式）—— 功能降级，但绝不因此崩掉 system_server。
            XposedBridge.log(
                "read remote settings failed, falling back to defaults: " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            ExpressSettingsSnapshot.DEFAULT
        }
    }

    /** 从 XposedBridge 持有的引用读；未 attach 时返回默认值（拦截关闭，最保守）。 */
    fun read(): ExpressSettingsSnapshot {
        val framework = XposedBridge.framework() ?: return ExpressSettingsSnapshot.DEFAULT
        return read(framework)
    }
}
