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

package io.github.YGHFv.ReaPressExtend.core

/**
 * 免 root 方案的能力模型：判定与文案都收在这里（纯函数、可单测，界面只负责画）。
 * 本文件不碰 Android 类型（core 层要能在 JVM 单测里跑），取授权状态那一步在 `noroot/NoRootAccess`。
 */
object NoRootPlan {

    /**
     * 当前数据来源的三态。与「免 root 采集开关」不是一回事：两条链路可以同时在跑，
     * 那时主来源仍是 hook（覆盖更全）——界面上要用 [SourceStatus.supplementActive] 说出来，
     * 否则用户看到「宿主 hook」会以为采集开关没生效。
     */
    enum class SourceMode(val displayName: String) {
        /** LSPosed 框架服务连着：通知拦截、身份码、宿主自查都走这条。 */
        HOST_HOOK("宿主 hook（LSPosed）"),

        /** 框架不在，但通知使用权拿到了：模块自己采集。 */
        NO_ROOT("免 root 采集"),

        /** 两条都不通：只有界面上的历史数据可用。 */
        NONE("未启用"),
    }

    /** mode 是主来源，supplementActive 表示另一条也在跑。 */
    data class SourceStatus(
        val mode: SourceMode,
        val supplementActive: Boolean,
    ) {
        val detail: String
            get() = when {
                mode == SourceMode.HOST_HOOK && supplementActive ->
                    "宿主 hook 在线；免 root 采集同时开着，宿主不在时由它兜底。"
                mode == SourceMode.HOST_HOOK ->
                    "宿主 hook 在线：通知拦截、身份码与宿主自查都可用。"
                mode == SourceMode.NO_ROOT ->
                    "宿主 hook 不在，靠免 root 采集：通知使用权 + 自登录淘宝。身份码与宿主自查不可用。"
                else ->
                    "两条链路都没通。装 LSPosed 启用本模块，或授予通知使用权后开启免 root 采集。"
            }
    }

    /** listenerCollecting 必须是「此刻真的在跑」（开关+授权+模式非关闭三条件），只判开关会显示在采实际没采。 */
    fun resolveSourceMode(frameworkAvailable: Boolean, listenerCollecting: Boolean): SourceStatus =
        when {
            frameworkAvailable -> SourceStatus(SourceMode.HOST_HOOK, supplementActive = listenerCollecting)
            listenerCollecting -> SourceStatus(SourceMode.NO_ROOT, supplementActive = false)
            else -> SourceStatus(SourceMode.NONE, supplementActive = false)
        }

    /**
     * 免 root 采集「真的在跑」吗，三个条件缺一不可。「关闭」语义与 hook 侧同一套——
     * 另起一套「免 root 总开关」会出现界面说开着、实际不判。mode 传布尔是 core 不依赖 config。
     */
    fun isListenerCollecting(
        listenerEnabled: Boolean,
        accessGranted: Boolean,
        modeOff: Boolean,
    ): Boolean = listenerEnabled && accessGranted && !modeOff

    /**
     * 判断本包有没有被授予通知使用权。串格式是 ComponentName.flattenToString 用:拼接。
     * 必须按 / 切出包名精确比较——本应用类名以包名开头，contains 会在任何类名上命中
     * （判断恒真，用户没授权时界面也说已授予）。
     */
    fun isListenerEnabled(flattened: String?, packageName: String): Boolean {
        if (flattened.isNullOrBlank() || packageName.isBlank()) return false
        return flattened.split(':').any { entry ->
            val component = entry.trim()
            if (component.isEmpty()) return@any false
            val pkg = if (component.contains('/')) component.substringBefore('/') else component
            pkg == packageName
        }
    }

    data class Capability(
        val id: Id,
        val title: String,
        val ok: Boolean,
        val detail: String,
    ) {
        enum class Id {
            HOST_HOOK,
            LISTENER_ACCESS,
            NOTIFY_PERMISSION,
            TAOBAO_LOGIN,
            IDENTITY_CODE,
        }
    }

    fun checklist(
        frameworkAvailable: Boolean,
        listenerAccessGranted: Boolean,
        notifyPermissionGranted: Boolean,
        taobaoAgeMs: Long?,
    ): List<Capability> = listOf(
        Capability(
            id = Capability.Id.HOST_HOOK,
            title = "LSPosed 框架",
            ok = frameworkAvailable,
            detail = if (frameworkAvailable) {
                "已连接：通知投递前拦截、身份码、宿主自查都可用。"
            } else {
                "未连接。装 LSPosed 并启用本模块、勾上作用域即可；不装也能用免 root 采集。"
            },
        ),
        Capability(
            id = Capability.Id.LISTENER_ACCESS,
            title = "通知使用权",
            ok = listenerAccessGranted,
            detail = if (listenerAccessGranted) {
                "已授予：模块能读到白名单应用的通知原文，这是免 root 下唯一的「发现新包裹」来源。"
            } else {
                "未授予：去系统设置的「通知使用权」里勾上本模块。这一项不需要 root。"
            },
        ),
        Capability(
            id = Capability.Id.NOTIFY_PERMISSION,
            title = "通知权限",
            ok = notifyPermissionGranted,
            detail = if (notifyPermissionGranted) {
                "已授予：模块能发出替换通知。"
            } else {
                "未授予：模块发不出替换通知，数据仍会记录。"
            },
        ),
        Capability(
            id = Capability.Id.TAOBAO_LOGIN,
            title = "淘宝登录（免 root 取轨迹）",
            ok = taobaoAgeMs != null,
            detail = when {
                taobaoAgeMs == null ->
                    "未登录。登录一次之后模块自己就能拉订单与全轨迹，不必打开菜鸟、也不必勾作用域。"
                else -> "已登录（${describeAge(taobaoAgeMs)}）。轨迹、商品图、驿站地址都由这条链路拉取。"
            },
        ),
        Capability(
            id = Capability.Id.IDENTITY_CODE,
            title = "身份码",
            ok = frameworkAvailable,
            detail = if (frameworkAvailable) {
                "需要菜鸟进程内的会话，当前可用。"
            } else {
                "免 root 下不可用：该接口只在 MTOP APP 通道开放，自登录的 H5 通道取不到。" +
                    "这一条不会悄悄回退到别的平台。"
            },
        ),
    )

    /**
     * 「哪些来源会被采集」的人话摘要。直接用生效的规则渲染，不另抄一份包名表——
     * 否则用户关掉拼多多后这页还说会采集拼多多；界面说的与实际做的必须是同一份数据。
     */
    fun sourceSummary(
        sources: Collection<String>,
        handleSms: Boolean,
        nameOf: (String) -> String,
    ): String {
        val names = LinkedHashSet<String>()
        sources.forEach { if (it.isNotBlank()) names.add(nameOf(it)) }
        if (handleSms) names.add(nameOf(ExpressClassifier.SMS_PACKAGE))
        return if (names.isEmpty()) {
            "全部来源都已关闭（设置 →「来源」）"
        } else {
            names.joinToString("、")
        }
    }

    /**
     * 「采集服务」那一行。服务有没有被系统绑定只有服务自己知道——这是「开关授权都给了
     * 却什么都没发生」唯一能分辨的状态。措辞写「尚未收到系统回调」：刚授权完的那几秒本来就没有。
     */
    fun listenerServiceLabel(seenCallback: Boolean): String = if (seenCallback) {
        "在线（已收到系统通知回调）"
    } else {
        "尚未收到系统回调（刚授权时正常；一直不动就重新开关一次授权或重启手机）"
    }

    /** 把年龄说成人话（分钟以内「刚刚」，往上按分钟/小时/天）。负数（时钟被改）也退化到「刚刚」。 */
    fun describeAge(ageMs: Long): String = when {
        ageMs < 60_000L -> "刚刚"
        ageMs < 3_600_000L -> "${ageMs / 60_000L} 分钟前"
        ageMs < 24 * 3_600_000L -> "${ageMs / 3_600_000L} 小时前"
        else -> "${ageMs / (24 * 3_600_000L)} 天前"
    }
}
