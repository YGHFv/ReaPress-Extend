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

/** 模块日志出口：把「往 libxposed 框架打日志」与 XposedBridge 解耦 —— libxposed 是 compileOnly 依赖，只存在于被注入进程，模块自己进程加载不到 XposedInterface，XposedBridge 只持有这个自有类型的出口。 */
internal interface ModuleLogSink {
    fun log(priority: Int, tag: String, text: String, throwable: Throwable?)
}
