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

package io.github.YGHFv.ReaPressExtend.xposed

import android.util.Log
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogLevel
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogSink
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogState
import io.github.YGHFv.ReaPressExtend.logging.legacyModuleLogLevel
import io.github.YGHFv.ReaPressExtend.logging.shouldEmitModuleLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicReference

/**
 * libxposed API 102 的薄封装。
 *
 * 两件事：
 * 1. 持有框架接口引用（[attachFramework] 由入口类调用）
 * 2. 把 API 102 的**拦截器链**适配成旧式 `before/after` 形状（[XC_MethodHook]），
 *    这样 hook 代码写法统一，不必每个调用点都手写 `chain.proceed()`
 */
object XposedBridge {
    private const val LOG_TAG = "ReaPress"
    private val frameworkRef = AtomicReference<XposedInterface?>()

    /**
     * 日志出口。
     *
     * **不能直接用 `frameworkRef` 打日志**：`libxposed` 是 `compileOnly` 依赖，只存在于被
     * 注入的进程里。模块**自己的进程**（主界面、接收器）里没有这个类，一旦执行到引用
     * `XposedInterface` 的字节码就会 `NoClassDefFoundError`。
     * 所以日志走这个只用自有类型的出口：模块进程里它是 null，永远不会碰到 libxposed。
     */
    private val logSinkRef = AtomicReference<ModuleLogSink?>()

    fun attachFramework(framework: XposedInterface) {
        frameworkRef.set(framework)
        logSinkRef.set(FrameworkLogSink(framework))
        log(
            Log.INFO,
            "LibXposed framework attached: api=${framework.apiVersion}, " +
                "framework=${framework.frameworkName} ${framework.frameworkVersion}(${framework.frameworkVersionCode}), " +
                "props=0x${framework.frameworkProperties.toString(16)}",
            null,
        )
    }

    /** 已注入进程里可用；模块自身进程为 null。 */
    fun framework(): XposedInterface? = frameworkRef.get()

    fun log(text: String) {
        val priority = if (legacyModuleLogLevel(text) == ModuleLogLevel.ERROR) Log.ERROR else Log.INFO
        log(priority, text, null)
    }

    fun logError(text: String, throwable: Throwable? = null) {
        log(Log.ERROR, text, throwable)
    }

    fun log(throwable: Throwable) {
        log(Log.ERROR, Log.getStackTraceString(throwable), throwable)
    }

    /**
     * 不受「简洁日志」开关影响的日志。
     *
     * 只给启动自检汇总这类「必须能看到」的单行输出用：它在设置还没 attach 时打印，
     * 那时 [ModuleLogState.conciseLogEnabled] 仍是默认的 true，走普通 log 会被整条吞掉。
     */
    fun logAlways(text: String) {
        val sink = logSinkRef.get()
        if (sink != null) {
            sink.log(Log.INFO, LOG_TAG, text, null)
        } else {
            Log.println(Log.INFO, LOG_TAG, text)
            ModuleLogBuffer.record(ModuleLogLevel.INFO.name, LOG_TAG, text)
        }
    }

    /**
     * 挂一个方法。
     *
     * 异常处理固定为 `PROTECTIVE`：hook 里抛出的任何异常都被框架吞掉并记日志，调用按「没有这个
     * hook」继续。**这是 system_server 侧的硬要求** —— hook 抛异常绝不能冒泡到
     * NotificationManagerService 的调用栈上。
     */
    fun hookMethod(method: Executable, callback: XC_MethodHook): XposedInterface.HookHandle? {
        method.isAccessible = true
        val framework = frameworkRef.get()
            ?: throw IllegalStateException("LibXposed framework is not attached")
        return framework.hook(method)
            .setPriority(callback.priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                interceptForTest(
                    callback,
                    HookChain(
                        executable = chain.executable,
                        thisObject = chain.thisObject,
                        args = chain.args.toTypedArray(),
                        proceed = { args -> chain.proceed(args) },
                    ),
                )
            }
    }

    /** 挂一个类的全部同名方法（含重载）。返回实际挂上的 handle。 */
    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): List<XposedInterface.HookHandle> {
        return clazz.declaredMethods
            .asSequence()
            .filter { it.name == methodName }
            .mapNotNull { hookMethod(it, callback) }
            .toList()
    }

    /**
     * 同 [hookAllMethods]，但连**继承来的**同名方法一起挂。
     *
     * 只在明确需要的地方用：多数调用点的目标类自己声明了该方法，换成这个只会顺带把父类的同名
     * 方法也挂上，可能影响别处。之所以需要它，是因为调用方的前置校验常常写成
     * `methods + declaredMethods`（能查到继承方法），而 [hookAllMethods] 只扫 `declaredMethods`
     * ——方法若是继承来的，校验通过、却**一个都没挂上**，日志里还打印"安装成功"。
     */
    fun hookAllMethodsIncludingInherited(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): List<XposedInterface.HookHandle> {
        return (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
            .distinct()
            .filter { it.name == methodName }
            .mapNotNull { hookMethod(it, callback) }
            .toList()
    }

    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): List<XposedInterface.HookHandle> {
        return clazz.declaredConstructors
            .asSequence()
            .mapNotNull { hookMethod(it, callback) }
            .toList()
    }

    /**
     * 把拦截器链的一步展开成 before → proceed → after。
     *
     * 语义要点：
     * - before 里 `setResult(x)` / `setThrowable(t)` 即「短路」，原方法不再执行
     * - proceed 抛出的异常回填到 param，after 仍会被调用（after 能看到异常并改写）
     * - 最后按 param 的 throwable/result 决定实际行为
     */
    internal fun interceptForTest(callback: XC_MethodHook, chain: HookChain): Any? {
        val param = XC_MethodHook.MethodHookParam(
            chain.executable,
            chain.thisObject,
            chain.args,
        )

        runCatching {
            callback.callBeforeHookedMethod(param)
        }.onFailure {
            log(Log.ERROR, "beforeHookedMethod failed: ${it.stackTraceToString()}", it)
        }

        if (!param.isReturnEarly) {
            runCatching {
                chain.proceed(param.args ?: emptyArray())
            }.onSuccess {
                param.setResultFromOriginal(it)
            }.onFailure {
                param.setThrowableFromOriginal(it)
            }
        }

        runCatching {
            callback.callAfterHookedMethod(param)
        }.onFailure {
            log(Log.ERROR, "afterHookedMethod failed: ${it.stackTraceToString()}", it)
        }

        param.throwable?.let { throw it }
        return param.result
    }

    private fun log(priority: Int, text: String, throwable: Throwable?) {
        val level = when {
            priority >= Log.ERROR -> ModuleLogLevel.ERROR
            priority >= Log.WARN -> ModuleLogLevel.WARN
            else -> ModuleLogLevel.INFO
        }
        val sink = logSinkRef.get()
        // sink 为空说明这是模块**自己的进程**（没有 libxposed 注入）。这里的日志只进
        // logcat，而模块进程的 INFO 日志受「简洁日志」抑制、logcat 里根本看不到——所以同时收进
        // 诊断缓冲，模块主界面能直接翻到。宿主进程里 sink 非空，不会走这条。
        if (sink == null) {
            ModuleLogBuffer.record(level.name, LOG_TAG, text)
        }
        if (!shouldEmitModuleLog(ModuleLogState.conciseLogEnabled, level)) return
        if (sink != null) {
            sink.log(priority, LOG_TAG, text, throwable)
        } else {
            Log.println(priority, LOG_TAG, text)
        }
    }

    internal class HookChain(
        val executable: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
        private val proceed: (Array<Any?>) -> Any?,
    ) {
        fun proceed(args: Array<Any?>): Any? = proceed.invoke(args)
    }
}

/**
 * 把日志转给 libxposed 框架。
 *
 * 单独一个类，**只有被注入的进程**才会加载它（仅 [XposedBridge.attachFramework] 里实例化）：
 * 模块自身进程没有 libxposed，凡是引用到 [XposedInterface] 的字节码一执行就
 * `NoClassDefFoundError`，所以这些引用必须隔离在"非注入进程绝不触碰"的类里。
 */
private class FrameworkLogSink(private val framework: XposedInterface) : ModuleLogSink {
    override fun log(priority: Int, tag: String, text: String, throwable: Throwable?) {
        if (throwable != null) {
            framework.log(priority, tag, text, throwable)
        } else {
            framework.log(priority, tag, text)
        }
    }
}
