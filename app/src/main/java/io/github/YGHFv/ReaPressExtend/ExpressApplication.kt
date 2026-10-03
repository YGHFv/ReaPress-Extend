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

package io.github.YGHFv.ReaPressExtend

import android.app.Application
import io.github.YGHFv.ReaPressExtend.backup.BackupScheduler
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.core.RelayCredential
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.relay.RelayCredentialStore
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** 模块 App 的 Application：唯一职责是连上 Xposed 框架服务，让设置写进框架托管的 RemotePreferences 供被注入进程读取；框架不在线时静默降级、不重试不轮询。 */
class ExpressApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ModuleLogBuffer.attach(this)
        if (RelayCredentialStore.ensure(this) == null) {
            ModuleAndroidLog.error(LOG_TAG, "relay credential initialization failed")
        }
        registerXposedService()
        rescheduleBackup()
    }

    /** 每次进程启动都重排定时备份闹钟：国产 ROM 会清掉第三方闹钟，被强停后闹钟也会消失。 */
    private fun rescheduleBackup() {
        runCatching { BackupScheduler.reschedule(this) }
            .onFailure { ModuleAndroidLog.error(LOG_TAG, "reschedule backup failed", it) }
    }

    private fun registerXposedService() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    ExpressSettings.attachService(this@ExpressApplication, service)
                    runCatching {
                        check(
                            RelayCredentialStore.publish(
                                this@ExpressApplication,
                                service.getRemotePreferences(RelayCredential.REMOTE_GROUP),
                            ),
                        )
                    }.onFailure {
                        ModuleAndroidLog.error(LOG_TAG, "relay credential publication failed", it)
                    }
                    ModuleAndroidLog.legacy(
                        LOG_TAG,
                        "xposed service bound: api=${service.apiVersion} " +
                            "framework=${service.frameworkName} ${service.frameworkVersion}",
                    )
                }

                override fun onServiceDied(service: XposedService) {
                    ExpressSettings.detachService(service)
                    ModuleAndroidLog.error(LOG_TAG, "xposed service died — settings sync disabled")
                }
            })
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "register xposed service listener failed", it)
        }
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
    }
}
