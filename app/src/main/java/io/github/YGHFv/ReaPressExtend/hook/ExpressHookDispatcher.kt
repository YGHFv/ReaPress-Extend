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

import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 按包名把宿主进程分发给对应的 hook 安装器。去重键是「包名 + 进程名」：同一进程不能重复装，
 * 只看包名会让后到的进程全被跳过。进程名走 `/proc/self/cmdline`（`currentProcessName()` 在
 * `onPackageReady` 时还没准备好）。类加载路径（`loadClass` / `findClass`）上不要挂任何东西。
 */
object ExpressHookDispatcher {

    private const val CAINIAO = "com.cainiao.wireless"
    private const val PINDUODUO = "com.xunmeng.pinduoduo"
    private const val TAOBAO = "com.taobao.taobao"

    private val installed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun onPackageReady(packageName: String, classLoader: ClassLoader) {
        val process = currentProcessName()
        if (!installed.add("$packageName|$process")) {
            XposedBridge.logAlways("skip duplicate package: $packageName process=$process")
            return
        }
        XposedBridge.logAlways(
            "dispatch package: $packageName process=$process loader=${classLoader.javaClass.name}",
        )
        when (packageName) {
            CAINIAO -> {
                // 身份码桥接只在主进程装：非主进程也会回一个「取不到」的回执，会盖掉主进程刚取到的码。
                val ok = runCatching {
                    CainiaoPackageHook.install(classLoader, isMainProcess = process == CAINIAO)
                }.getOrElse { error ->
                    XposedBridge.logError("$packageName: cainiao package hook install threw", error)
                    false
                }
                XposedBridge.logAlways("$packageName: cainiao package hook installed=$ok")
            }
            TAOBAO -> {
                // 凭据采集要做：菜鸟没绑淘宝账号时它的 cookie 库里就没有 .taobao.com 域，淘宝 App 里必然有。
                val ok = runCatching {
                    TaobaoCredentialHook.install(classLoader, isMainProcess = process == TAOBAO)
                }.getOrElse { error ->
                    XposedBridge.logError("$packageName: taobao credential hook install threw", error)
                    false
                }
                XposedBridge.logAlways("$packageName: taobao credential hook installed=$ok")
            }
            // 拼多多本地不留包裹表，出口只能挂在网络回调的 JSON 出口 `CommonCallback#parseResponseString`。
            PINDUODUO -> {
                val ok = runCatching {
                    PddPackageHook.install(classLoader, isMainProcess = process == PINDUODUO)
                }.getOrElse { error ->
                    XposedBridge.logError("$packageName: pdd package hook install threw", error)
                    false
                }
                XposedBridge.logAlways("$packageName: pdd package hook installed=$ok")
            }
            else ->
                XposedBridge.logAlways("$packageName: no hook registered, ignoring")
        }
    }

    private fun currentProcessName(): String =
        runCatching { java.io.File(PROC_CMDLINE).readText() }
            .getOrNull()
            .orEmpty()
            .substringBefore('\u0000')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "?"

    private const val PROC_CMDLINE = "/proc/self/cmdline"
}
