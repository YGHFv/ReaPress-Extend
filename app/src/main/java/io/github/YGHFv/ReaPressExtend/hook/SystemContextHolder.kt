package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers

/**
 * system_server 里的 Context 来源。
 *
 * ## 两条路，用在不同时机
 *
 * 1. **运行期（首选）**：从 `NotificationManagerService` 实例的 `mContext` 字段反射取。
 *    最可靠 —— NMS 本来就是 hook 的目标，`param.thisObject` 直接给到实例。
 *    但只有 hook 被调用之后才有。
 *
 * 2. **启动期**：`ActivityThread.currentActivityThread().getSystemContext()`。
 *    安装 hook 时要广播「装上了 / 被看门狗拒绝」就靠它 —— 那时还没有 NMS 实例。
 *
 * ## 为什么是 `currentActivityThread()` 而不是 `systemMain()`
 *
 * `ActivityThread.systemMain()` 每次调用都会新建一个 ActivityThread 并 `attach()`；
 * 在 system_server 里再调一次属于严重副作用。`currentActivityThread()` 只是读
 * `sCurrentActivityThread` 静态字段，纯读取。
 *
 * system_server 启动流程里已经调过 `systemMain()`，所以在 `onSystemServerStarting`
 * （LSPosed 在 system_server 就绪后回调）时该字段应当已就绪。拿不到也只是「状态广播发不出去」，
 * 不影响 hook 本身 —— 所以全程 fail-soft。
 */
internal object SystemContextHolder {

    @Volatile private var cached: Context? = null

    /** 从 NMS 实例升级缓存。运行期的首选来源。 */
    fun upgrade(nmsInstance: Any?) {
        if (nmsInstance == null) return
        val context = runCatching {
            XposedHelpers.getObjectField(nmsInstance, "mContext") as? Context
        }.getOrNull() ?: return
        cached = context
    }

    /** 启动期获取；已有缓存时直接返回缓存。 */
    fun acquireFromActivityThread(): Context? {
        cached?.let { return it }
        val context = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                ?: return@runCatching null
            XposedHelpers.callMethod(thread, "getSystemContext") as? Context
        }.onFailure {
            XposedBridge.log(
                "SystemContextHolder: currentActivityThread route failed: ${it.javaClass.simpleName}",
            )
        }.getOrNull()
        if (context != null) cached = context
        return context
    }

    /** 取缓存（可能为 null —— 还没拿到过）。 */
    fun acquire(): Context? = cached
}
