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

package io.github.YGHFv.ReaPressExtend.noroot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.NoRootPlan
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache

/** 免 root 的能力探测：只读系统事实、开设置页，判定与文案全部交给 [NoRootPlan]（纯函数）。 */
internal object NoRootAccess {

    /** AOSP 稳定契约的 Settings.Secure 键名。 */
    private const val KEY_ENABLED_LISTENERS = "enabled_notification_listeners"

    /** 免 root 唯一必须的外部授权。解析在 [NoRootPlan.isListenerEnabled] —— 切包名要精确比，不能按前缀匹配。 */
    fun isListenerAccessGranted(context: Context): Boolean = runCatching {
        val flattened = Settings.Secure.getString(context.contentResolver, KEY_ENABLED_LISTENERS)
        NoRootPlan.isListenerEnabled(flattened, context.packageName)
    }.getOrDefault(false)

    /** 跳系统「通知使用权」页；ROM 没这个入口时退回应用详情页。false = 两个都没打开成。 */
    fun openListenerSettings(context: Context): Boolean {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (opened) return true
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /** 「采集是否生效」全项目只有 [NoRootPlan.isListenerCollecting] 这一份判定，界面与服务守卫共用。 */
    fun isCollecting(context: Context): Boolean {
        val settings = ExpressSettings.read(context)
        return NoRootPlan.isListenerCollecting(
            listenerEnabled = settings.noRootListener,
            accessGranted = isListenerAccessGranted(context),
            modeOff = settings.mode == ExpressSettingsKeys.MODE_OFF,
        )
    }

    fun sourceStatus(context: Context): NoRootPlan.SourceStatus =
        NoRootPlan.resolveSourceMode(
            frameworkAvailable = ExpressSettings.isServiceAvailable(),
            listenerCollecting = isCollecting(context),
        )

    /** 读的是本进程事实（服务有没有被系统叫过），不是权限。 */
    fun listenerServiceLabel(): String = NoRootPlan.listenerServiceLabel(NoRootListenerState.hasCallback())

    /** null = 没有。读之前先把落盘那份接回内存。 */
    fun taobaoAgeMs(context: Context): Long? {
        val app = context.applicationContext
        TraceCookieCache.attach(app)
        return TraceCookieCache.ageMs()
    }

    fun capabilities(context: Context): List<NoRootPlan.Capability> = NoRootPlan.checklist(
        frameworkAvailable = ExpressSettings.isServiceAvailable(),
        listenerAccessGranted = isListenerAccessGranted(context),
        notifyPermissionGranted = ExpressNotificationPoster.hasPermission(context),
        taobaoAgeMs = taobaoAgeMs(context),
    )

    /** 措辞与「全是关的」边界在 [NoRootPlan.sourceSummary]。 */
    fun sourceSummary(context: Context): String {
        val rule = ExpressSettings.read(context).toRule()
        return NoRootPlan.sourceSummary(
            sources = rule.sourcePackages,
            handleSms = rule.handleSms,
            nameOf = { ExpressSettingsSnapshot.displayName(it) },
        )
    }
}
