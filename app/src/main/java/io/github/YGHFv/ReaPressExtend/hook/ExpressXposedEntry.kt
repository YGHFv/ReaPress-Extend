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
 * 模块入口。
 *
 * libxposed API 102 的约定：[META-INF/xposed/java_init.list] 里列出本类全限定名，框架在**每个**
 * 被注入的进程里各实例化一次。所以生命周期回调必须自己判「我在哪个进程里」——
 * system_server 走一条路，宿主 App 走另一条路，两者能拿到的类完全不同。
 *
 * 分流点有两个，都要处理：
 * - [onSystemServerStarting]：system_server 独有，**取代**了首个包加载阶段
 * - [onPackageReady]：普通 App 进程
 *
 * 两个回调都调 [XposedBridge.attachFramework]：它是幂等的（内部 `AtomicReference.set`），
 * 重复调用只是覆盖同一个引用。
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
     * system_server 分支。
     *
     * 这是全项目风险最高的入口 —— 在这里装 hook 一旦出问题就是开机循环。所以：
     * - 先问 [Watchdog]，它可能直接拒绝安装（连续失败过）
     * - 安装失败不抛异常，只记日志（模块不工作好过设备不能用）
     * - 默认 `observeOnly = true`：**只记日志不拦截**。切到拦截要等真机验证过观察模式。
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        XposedBridge.attachFramework(this)
        XposedBridge.logAlways("system_server starting: ${capabilitySummary()}")

        if (!hasSystemCapability()) {
            // 框架不支持 system_server hook（PROP_CAP_SYSTEM 未置位）。装了也是白装，
            // 而且可能在不支持的实现上引发未定义行为，所以直接跳过。
            XposedBridge.logError(
                "framework lacks PROP_CAP_SYSTEM — global notification interception unavailable",
            )
            return
        }

        runCatching {
            // 不传 context：安装阶段拿不到可靠的 system context，而为了它去触发
            // ActivityThread 的静态初始化在 system_server 里风险太高（详见 SystemContextHolder）。
            // 看门狗熔断时因此只能写日志、无法广播 —— 那是可接受的降级。
            SystemServerHook.install(param.classLoader)
        }.onFailure {
            XposedBridge.logError("system_server hook install threw (device left untouched)", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        XposedBridge.attachFramework(this)
        // system_server 里首个包回调被 onSystemServerStarting 取代，正常不会走到这；
        // 真走到了说明 ROM 行为不同，记一笔但不重复装 hook。
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

    /**
     * 框架能力摘要。
     *
     * 三个能力决定了三件事能不能做：
     * - `PROP_CAP_SYSTEM`：作用域里能不能挂 `system`（全局通知拦截的前提）
     * - `PROP_CAP_REMOTE`：设置能不能走 RemotePreferences（否则要降级）
     * - `PROP_RT_API_PROTECTION`：框架是否禁止反射访问 API（影响我们的调试手段）
     */
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
