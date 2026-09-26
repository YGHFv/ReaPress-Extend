package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 在宿主进程里注册「模块 → 宿主」的反向请求接收器。
 *
 * 抽出来的唯一理由是**注册参数有两处版本相关的坑**，而被注入的宿主不止一个（菜鸟 / 淘宝）：
 *
 * 1. Android 13（API 33）起 `registerReceiver` 必须显式声明导出性，否则直接抛
 *    `SecurityException`；
 * 2. 必须要求发送方持有 [ExpressRelay.PERMISSION_TRACE_REQUEST]（signature 级，只有模块
 *    APK 签得出）—— 没有这道闸，设备上任何应用都能索要用户的登录态。
 *
 * 这两条抄一份到第二个宿主里，迟早会改一处漏一处：漏了第 1 条是当场崩，漏了第 2 条是
 * **静默地把登录态开放给任意应用**。所以宁可变成一个只有十几行的类。
 *
 * 注册失败只记日志、返回 false —— 反向索要失效不等于宿主不能用，
 * 主动同步那条路（[ExpressRelaySender.sendCookieSync]）仍然工作。
 */
internal object HostReceiverRegistrar {

    fun register(
        context: Context,
        receiver: BroadcastReceiver,
        vararg actions: String,
    ): Boolean {
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    ExpressRelay.PERMISSION_TRACE_REQUEST,
                    /* scheduler = */ null,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(
                    receiver,
                    filter,
                    ExpressRelay.PERMISSION_TRACE_REQUEST,
                    /* scheduler = */ null,
                )
            }
            true
        }.getOrElse {
            XposedBridge.logError("register reverse-request receiver failed", it)
            false
        }
    }
}
