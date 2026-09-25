package io.github.YGHFv.ReaPressExtend

import android.app.Application
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 的 Application。
 *
 * 唯一的职责是**连上 Xposed 框架服务** —— 设置要写进框架托管的 RemotePreferences，
 * 被注入的进程（system_server、宿主 App）才读得到。连上之后 [ExpressSettings] 就有出口了。
 *
 * 用 Application 而不是 Activity：模块常年没有界面，而设置同步不该等用户打开界面才建立。
 * Application 在每个进程启动时都会跑（包括接收器被拉起时），是最早的稳定时机。
 *
 * 框架不在线时（LSPosed 没启用模块、模块不在作用域内）静默降级：设置只落本地，
 * 界面上会显示「框架未连接」。**不重试、不轮询** —— 框架服务是通过 ContentProvider 的
 * `call` 递过来的 binder，只在进程启动那一刻来一次；之后框架重启会走 `onServiceDied`。
 */
class ExpressApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ModuleLogBuffer.attach(this)
        registerXposedService()
    }

    private fun registerXposedService() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    ExpressSettings.attachService(service)
                    ModuleAndroidLog.legacy(
                        LOG_TAG,
                        "xposed service bound: api=${service.apiVersion} " +
                            "framework=${service.frameworkName} ${service.frameworkVersion}",
                    )
                }

                override fun onServiceDied(service: XposedService) {
                    ExpressSettings.attachService(null)
                    ModuleAndroidLog.error(LOG_TAG, "xposed service died — settings sync disabled")
                }
            })
        }.onFailure {
            // 拿不到框架服务不影响模块界面与本地设置，只是同步不到被注入侧。
            ModuleAndroidLog.error(LOG_TAG, "register xposed service listener failed", it)
        }
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
    }
}
