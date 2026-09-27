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

package io.github.YGHFv.ReaPressExtend.config

import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.core.ExpressRule
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.core.WatchSchedule

/**
 * 某一时刻生效的设置快照。
 *
 * 为什么要快照而不是到处读 prefs：system_server 侧一次事件处理里要判好几次
 * （来源白名单、关键词、阈值），中途用户改设置会让同一次判定用上不一致的规则。
 * 读一次、判一次，语义干净。
 *
 * **刻意不包含看门狗状态**。熔断标记存在 `/data/system/`（system_server 的地盘），
 * 模块 App 写不了、被注入的进程读得到但没必要 — 走 [KEY_HOOK_FORCE_ENABLED] 单向请求复位，
 * 状态本身由 system_server 每次开机广播回来（见 `WatchdogReporter`）。
 */
data class ExpressSettingsSnapshot(
    val sourceCainiao: Boolean = true,
    val sourcePinduoduo: Boolean = true,
    val sourceTaobao: Boolean = true,
    val sourceSms: Boolean = true,
    val mode: String = ExpressSettingsKeys.DEFAULT_MODE,
    val extraKeywords: Set<String> = emptySet(),
    val excludeKeywords: Set<String> = emptySet(),
    val confidenceThreshold: Int = ExpressSettingsKeys.DEFAULT_CONFIDENCE_THRESHOLD,
    /** 界面请求复位看门狗熔断。system_server 消费后清回 false（一次性语义）。 */
    val hookForceEnabled: Boolean = false,
    /** 包裹详情（轨迹）的获取模式：自动更新 / 点击时获取。见 [ExpressSettingsKeys]。 */
    val traceFetchMode: String = ExpressSettingsKeys.DEFAULT_TRACE_FETCH_MODE,
    /**
     * 用户选择**直接吞掉**的通知分类（设置 → 通知拦截）。
     *
     * 默认空集 —— 装完什么都不吞，与加这组开关之前的行为一致。用户勾上的那些分类
     * 连原通知带模块通知一起消失，判据见 [io.github.YGHFv.ReaPressExtend.core.ExpressClassifier]。
     */
    val interceptedCategories: Set<NotificationCategory> = emptySet(),
    /**
     * 自动轮查。开着时模块在后台按固定节奏自己拉在途件的轨迹（见 `AutoWatchService`），
     * 不依赖宿主刷新、也不依赖用户点开详情。默认关 —— 它需要一条常驻通知。
     */
    val autoWatch: Boolean = false,
    /** 轮查范围：在途 / 未完成。见 [ExpressSettingsKeys.WATCH_SCOPE_OPTIONS]。 */
    val watchScope: String = ExpressSettingsKeys.DEFAULT_WATCH_SCOPE,
    /** 夜间暂停轮查。 */
    val watchQuiet: Boolean = true,
    /** 夜间暂停起（整点小时）。 */
    val watchQuietStart: Int = ExpressSettingsKeys.DEFAULT_WATCH_QUIET_START,
    /** 夜间暂停止（整点小时）。 */
    val watchQuietEnd: Int = ExpressSettingsKeys.DEFAULT_WATCH_QUIET_END,
    /** 件与件之间的基准间隔（分钟）。见 [ExpressSettingsKeys.KEY_WATCH_GAP_MIN]。 */
    val watchGapMinutes: Int = ExpressSettingsKeys.DEFAULT_WATCH_GAP_MIN,
    /** 一轮跑完之后等多久（分钟）。见 [ExpressSettingsKeys.KEY_WATCH_CYCLE_MIN]。 */
    val watchCycleMinutes: Int = ExpressSettingsKeys.DEFAULT_WATCH_CYCLE_MIN,
    /**
     * 轮查时是否显示那条前台服务通知（关 = 降成最低优先级 + 静默）。
     *
     * 语义上**不是**「有没有通知」—— Android 不允许前台服务没有通知，
     * 详见 [ExpressSettingsKeys.KEY_WATCH_NOTIFICATION]。
     */
    val watchNotification: Boolean = true,
) {
    /** 是否「自动更新」轨迹（false = 只在点开详情 / 下拉时按需拉）。 */
    val isTraceAutoFetch: Boolean
        get() = traceFetchMode == ExpressSettingsKeys.MODE_TRACE_AUTO
    /** 模块是否在工作。关掉时 hook 直接放行，不做判定也不投递。 */
    val isEnabled: Boolean get() = mode != ExpressSettingsKeys.MODE_OFF

    /** 是否处于「拦截并替换」模式（false = 放行原通知，只额外发一条）。 */
    val isInterceptMode: Boolean get() = mode == ExpressSettingsKeys.MODE_INTERCEPT

    /** 轮查是否连到站待取件一起问（false = 只问还在路上的）。 */
    val isWatchUnfinished: Boolean
        get() = watchScope == ExpressSettingsKeys.MODE_WATCH_UNFINISHED

    /** 轮查的夜间暂停窗口，`"22:00–08:00"` 这种给人看的写法。设置页与日志共用一份。 */
    fun quietWindowLabel(): String =
        "%02d:00–%02d:00".format(watchQuietStart, watchQuietEnd)

    /** 件间隔的实际毫秒数（基准，不含抖动）。 */
    val watchGapMillis: Long get() = watchGapMinutes * 60_000L

    /** 抖动的毫秒数。跟随基准走（见 [WatchSchedule.jitterFor]），不是固定 ±1 分钟。 */
    val watchJitterMillis: Long get() = WatchSchedule.jitterFor(watchGapMillis)

    /** 轮间隔的毫秒数。 */
    val watchCycleMillis: Long get() = watchCycleMinutes * 60_000L

    /**
     * 当前节奏的一句话说明，如 `件与件 3 分钟 ±1 分钟、一轮跑完等 30 分钟`。
     *
     * 界面上的说明文字必须由它来生成：以前那句话是写死的 3 分钟 / 30 分钟，
     * 用户改完间隔之后界面就会**说一套做一套** —— 这比没有说明更糟。
     */
    fun watchCadenceLabel(): String {
        val jitter = watchJitterMillis / 60_000L
        val jitterPart = if (jitter > 0) " ±$jitter 分钟" else ""
        val cyclePart = if (watchCycleMinutes % 60 == 0) {
            "${watchCycleMinutes / 60} 小时"
        } else {
            "$watchCycleMinutes 分钟"
        }
        return "件与件 $watchGapMinutes 分钟$jitterPart、一轮跑完等 $cyclePart"
    }

    /**
     * 转成判定规则。
     *
     * 来源白名单**按开关裁剪**而不是另建一份 —— 关掉的来源直接从白名单里去掉，
     * [io.github.YGHFv.ReaPressExtend.core.ExpressClassifier.isSourceAllowed] 自然就把它放行了。
     */
    fun toRule(): ExpressRule {
        val sources = buildSet {
            if (sourceCainiao) add("com.cainiao.wireless")
            if (sourcePinduoduo) add("com.xunmeng.pinduoduo")
            if (sourceTaobao) add("com.taobao.taobao")
        }
        return ExpressRule(
            sourcePackages = sources,
            extraKeywords = extraKeywords,
            excludeKeywords = excludeKeywords,
            extraAllowedSources = debugAllowedSources,
            confidenceThreshold = confidenceThreshold,
            handleSms = sourceSms,
            interceptedCategories = interceptedCategories,
        )
    }

    /** 诊断用的一行摘要。 */
    fun summary(): String = buildString {
        append("mode=").append(
            ExpressSettingsKeys.MODE_OPTIONS.firstOrNull { it.first == mode }?.second ?: mode,
        )
        append(" sources=[")
        append(
            listOfNotNull(
                "菜鸟".takeIf { sourceCainiao },
                "拼多多".takeIf { sourcePinduoduo },
                "淘宝".takeIf { sourceTaobao },
                "短信".takeIf { sourceSms },
            ).joinToString(","),
        )
        append("] threshold=").append(confidenceThreshold)
        // 拦了哪几类也要能看到：这组开关的后果是「通知**没出现**」，事后从结果反推不出来。
        // 这里用 displayName 而不是存的枚举名 —— 这一行是给人看的（关于页 / 日志）。
        append(" intercepted=[")
        append(
            interceptedCategories.filter { it.toggleable }
                .map { it.displayName }
                .sorted()
                .joinToString(","),
        )
        append("]")
        // 轮查只在开着时占位：它的存在感来自「通知栏那条常驻通知」，
        // 关着时这一行写「watch=off」只是噪音。
        if (autoWatch) {
            append(" watch=[")
            append(if (isWatchUnfinished) "未完成" else "在途")
            // 节奏也进摘要：改过间隔之后「界面说的」和「实际跑的」要是同一份数据，
            // 这一行就是事后核对用的（关于页）。
            append(" 件${watchGapMinutes}分钟/轮${watchCycleMinutes}分钟")
            if (watchQuiet) append(" 停${quietWindowLabel()}")
            if (!watchNotification) append(" 通知关")
            append("]")
        }
    }

    companion object {
        /** 默认快照：模块关闭（[ExpressSettingsKeys.MODE_OFF]）、四源全开。装完不做任何事。 */
        val DEFAULT = ExpressSettingsSnapshot()

        /**
         * Debug 构建下额外放行的来源。
         *
         * 唯一目的是真机验证：`adb shell cmd notification post` 发的通知 pkg 是
         * `com.android.shell`，把它放进来就能造一条快递通知走完整链路，不必等真实推送。
         * Release 构建为空集 —— 用户的 shell 通知不该被无谓地过一遍。
         */
        val debugAllowedSources: Set<String> =
            if (BuildConfig.DEBUG) setOf("com.android.shell") else emptySet()

        /** 诊断展示用：把来源包名映射成中文名。 */
        fun displayName(packageName: String): String = when (packageName) {
            "com.cainiao.wireless" -> "菜鸟"
            "com.xunmeng.pinduoduo" -> "拼多多"
            "com.taobao.taobao" -> "淘宝"
            "com.android.mms" -> "短信"
            // 用 `adb shell cmd notification post` 造的测试通知来源就是这个包名。
            // 给出人话名字，省得每次验证时都以为是模块抓错了包。
            "com.android.shell" -> "测试通知"
            else -> packageName
        }
    }
}
