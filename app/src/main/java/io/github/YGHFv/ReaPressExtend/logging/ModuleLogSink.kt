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

package io.github.YGHFv.ReaPressExtend.logging

/**
 * 模块日志出口。
 *
 * 存在的意义是把「往 libxposed 框架打日志」这件事和 [io.github.YGHFv.ReaPressExtend.xposed.XposedBridge] 解耦：
 * `libxposed` 是 `compileOnly` 依赖，只存在于被注入的进程里，模块**自己的进程**（主界面、接收器）
 * 加载不到 `XposedInterface`。只要 `XposedBridge.log` 的字节码里出现对该类的引用，模块进程一执行
 * 日志就 `NoClassDefFoundError` 崩溃。
 *
 * 所以 `XposedBridge` 只持有这个**自有类型**的出口；真正引用 `XposedInterface` 的实现类只在
 * 被注入的进程里被实例化、被加载。
 */
internal interface ModuleLogSink {
    fun log(priority: Int, tag: String, text: String, throwable: Throwable?)
}
