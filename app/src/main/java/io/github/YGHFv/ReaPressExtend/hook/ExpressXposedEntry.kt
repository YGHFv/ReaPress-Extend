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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口。libxposed 在每个被注入进程里各实例化一次本类（META-INF/xposed/java_init.list），
 * 所以生命周期回调必须自己判「我在哪个进程里」：system_server 走 [onSystemServerStarting]
 * （取代首个包加载阶段），普通 App 走 [onPackageReady]；两个回调都调 [XposedBridge.attachFramework]，它幂等。
 */
class ExpressXposedEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedBridge.attachFramework(this)
        XposedBridge.logAlways(
            "entry loaded: process=${param.processName} systemServer=${param.isSystemServer} " +
                capabilitySummary(),
        )
    }

    /**
     * 全项目风险最高的入口，装 hook 出问题就是开机循环：先问 [Watchdog]，可能直接拒绝安装；
     * 安装失败不抛异常、只记日志；默认 observeOnly=true 只记日志不拦截，切拦截要等真机验证过观察模式。
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        XposedBridge.attachFramework(this)
        XposedBridge.logAlways("system_server starting: ${capabilitySummary()}")

        if (!hasSystemCapability()) {
            XposedBridge.logError(
                "framework lacks PROP_CAP_SYSTEM — global notification interception unavailable",
            )
            return
        }

        runCatching {
            // 不传 context：安装阶段拿不到可靠的 system context，为此触发 ActivityThread 的静态初始化在 system_server 里风险太高（见 SystemContextHolder）。
            SystemServerHook.install(param.classLoader)
        }.onFailure {
            XposedBridge.logError("system_server hook install threw (device left untouched)", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        XposedBridge.attachFramework(this)
        // system_server 里首个包回调已被 onSystemServerStarting 取代；真走到说明 ROM 行为不同，记一笔但不重复装 hook。
        if (isSystemServerProcess()) {
            XposedBridge.log("system_server packageReady: pkg=${param.packageName}")
            return
        }
        ExpressHookDispatcher.onPackageReady(param.packageName, param.classLoader)
    }

    private fun hasSystemCapability(): Boolean =
        runCatching {
            (this as XposedInterface).frameworkProperties and XposedInterface.PROP_CAP_SYSTEM != 0L
        }.getOrDefault(false)

    private fun isSystemServerProcess(): Boolean =
        runCatching { android.os.Process.myUid() == android.os.Process.SYSTEM_UID }.getOrDefault(false)

    /** 能力摘要：system=全局通知拦截前提，remote=设置走 RemotePreferences，rtProtection=框架禁止反射访问 API。 */
    private fun capabilitySummary(): String = runCatching {
        val framework = this as XposedInterface
        val props = framework.frameworkProperties
        buildString {
            append("api=").append(framework.apiVersion)
            append(" framework=").append(framework.frameworkName)
            append(' ').append(framework.frameworkVersion)
            append("(").append(framework.frameworkVersionCode).append(")")
            append(" system=").append(props and XposedInterface.PROP_CAP_SYSTEM != 0L)
            append(" remote=").append(props and XposedInterface.PROP_CAP_REMOTE != 0L)
            append(" rtProtection=").append(props and XposedInterface.PROP_RT_API_PROTECTION != 0L)
        }
    }.getOrElse { "capability read failed: ${it.javaClass.simpleName}" }
}
