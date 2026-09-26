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

/**
 * 宿主 App 进程（如菜鸟）里的 Context 来源。
 *
 * 与 [SystemContextHolder] 成对：那边是 system_server，没有现成 Context，只能从
 * `NotificationManagerService` 实例上反射 `mContext`；这边宿主本来就是个普通 App，拿到
 * Application 就够发广播了。
 *
 * ## 两条路，任何一条先到都算
 *
 * 1. **[installCapture]（主路）**：挂 `android.app.Application#onCreate`，在回调里把
 *    `thisObject` 缓存下来。这条路**只碰公开类**，没有任何隐藏 API 风险。
 * 2. **[acquire] 里的兜底**：`ActivityThread.currentApplication()`。
 *
 * 为什么不只用第 2 条：`android.app.ActivityThread` 是隐藏类，它上面的一切对普通 App 都算
 * 非 SDK 接口。`currentApplication` 目前只是「不受支持」级别（可调用、会记一条告警）而不是
 * 「禁止」，所以它大体能用 —— 但**一个靠灰名单活着的能力不该是唯一的路**，宿主换个 targetSdk
 * 或者 ROM 收紧一次就断了。何况它还有个时序问题：`onPackageReady` 早于 Application 创建时，
 * 那时 `mApp` 还是空的。
 *
 * 两条路互补：装得早就靠 onCreate 捕获，装得晚就靠 currentApplication() 现取。
 *
 * ## [onReady]：别让「拿不到 context」变成一个静默的分支（2026-09-26 加）
 *
 * 上面那两条路只解决了「能取到 context」，没解决「**什么时候**取到」。而有些初始化动作
 * 必须在 context 到手的那一刻做，晚一步就永远做不成 —— 反向索取通道（轨迹 / 身份码）就是：
 * 它们的注册点原本只有 `onPackageReady` 那一下，那时 `acquire()` 必然返回 null，
 * 于是**唯一的重试机会落在宿主查出包裹之后**（`deliver`）。
 *
 * 代价在真机上现形了：唤醒销把一个**从没打开过界面**的菜鸟拉起来（用户要的就是不用打开它），
 * 那个进程不会查包裹 → 索取通道从来没立起来 → 模块发的广播没人接，日志里**连一句错都没有**。
 * 「沉默」是最难查的故障形态，所以这里把 context 的到达变成一次**回调**：
 * 谁需要它，就在 [onReady] 上挂一笔，早到晚到都会被执行到（已经拿到就直接同步执行）。
 *
 * ## 为什么用 `currentApplication()` 而不是 `currentActivityThread().getApplication()`
 *
 * 两者等价，但前者是公开静态方法、一步到位；走 ActivityThread 实例还得先取实例再调方法，
 * 少一步反射就少一处版本差异。
 *
 * 全程 fail-soft：取不到只记一次日志、返回 null，由调用方降级（那一条富化数据丢掉，
 * 宿主进程照常运行 —— 被注入进程里的铁律）。
 */
internal object HostContextHolder {

    private const val APPLICATION_CLASS = "android.app.Application"
    private const val APPLICATION_CREATE = "onCreate"

    @Volatile private var cached: Context? = null

    /** 捕获 hook 只装一次。 */
    @Volatile private var captureInstalled = false

    /** 取不到 context 时只记一次日志，避免刷屏（这个路径会被反复调用）。 */
    @Volatile private var failureLogged = false

    /** [onReady] 挂上来的回调。context 到手时**一次性**全部执行并清空。 */
    private val readyLock = Any()

    private val readyListeners = mutableListOf<(Context) -> Unit>()

    /**
     * 注册一个「宿主 Context 可用时执行」的回调。
     *
     * - context 已经在了 → **当场同步执行**（调用方不必先自己判断时机）；
     * - 还没到 → 挂起，等 [publish] 来唤醒。
     *
     * ⚠️ 回调**只执行一次**，且可能跑在宿主的任意线程上（`Application#onCreate` 是主线程，
     * `acquire()` 的调用方可能在工作线程）。所以回调体必须自己保证线程安全，且**不许阻塞**
     * —— 它是宿主启动路径上的一环。
     */
    fun onReady(listener: (Context) -> Unit) {
        val immediate = synchronized(readyLock) {
            cached ?: run { readyListeners.add(listener); null }
        }
        if (immediate != null) runCatching { listener(immediate) }
    }

    /**
     * context 到手了：先落缓存（这样回调里再调 [acquire] 不会递归），再放回调。
     *
     * 先取快照再执行：回调里若又 [onReady] 挂新的，不该被这轮吞掉。
     */
    private fun publish(context: Context) {
        cached = context
        val pending = synchronized(readyLock) {
            readyListeners.toList().also { readyListeners.clear() }
        }
        pending.forEach { listener ->
            // 回调里的异常只记日志：它是宿主启动路径上的一环，绝不能反向影响宿主。
            runCatching { listener(context) }
                .onFailure { XposedBridge.logError("HostContextHolder: onReady listener failed", it) }
        }
    }

    /**
     * 装 `Application#onCreate` 捕获钩子。
     *
     * 必须在**创建 Application 之前**装上才有意义；装晚了也不会出错，只是相当于没装
     * （那种情况下 [acquire] 的兜底负责）。所以调用方不必判断时机，无脑调用即可。
     */
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
                        // 只读一个字段，不干预宿主生命周期；异常也绝不放出去。
                        runCatching { upgrade(param.thisObject as? Context) }
                    }
                },
            )
            XposedBridge.logAlways("host context capture hook installed: Application#$APPLICATION_CREATE")
        }.onFailure {
            XposedBridge.logError("host context capture hook failed (falling back to reflection)", it)
        }
    }

    /** 取宿主 Context；拿不到返回 null。 */
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
        // 走 publish 而不是直接赋值：兜底这条路上取到 context，同样要唤醒 [onReady] 的等待者。
        publish(context)
        return context
    }

    /**
     * 用调用方手上更可靠的 Context 覆盖缓存。
     *
     * 存在的理由与 [SystemContextHolder.upgrade] 一致：hook 有时能从参数上直接拿到 Context，
     * 那比反射可靠，拿到后让后续调用不必再走兜底路径。
     */
    fun upgrade(context: Context?) {
        if (context != null) publish(context)
    }
}
