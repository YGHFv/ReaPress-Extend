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
