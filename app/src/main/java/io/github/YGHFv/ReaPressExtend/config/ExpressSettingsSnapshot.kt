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
 * 某一时刻生效的设置快照：system_server 一次事件处理里要判好几次（来源白名单、关键词、阈值），
 * 中途用户改设置会让同一次判定用上不一致的规则——读一次、判一次。
 * 刻意不含看门狗状态：熔断标记存在 system_server 的地盘，复位走独立请求 ID，
 * 状态由 system_server 每次开机广播回来。
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
    /** 旧版布尔复位标志，仅用于迁移；普通设置保存不写回。 */
    val hookForceEnabled: Boolean = false,
    val traceFetchMode: String = ExpressSettingsKeys.DEFAULT_TRACE_FETCH_MODE,
    val archiveMode: String = ExpressSettingsKeys.DEFAULT_ARCHIVE_MODE,
    /** 用户选择直接吞掉的通知分类，默认空集（与加这组开关之前的行为一致）。 */
    val interceptedCategories: Set<NotificationCategory> = emptySet(),
    /** 自动轮查，默认关——它需要一条常驻通知（见 AutoWatchService）。 */
    val autoWatch: Boolean = false,
    val watchScope: String = ExpressSettingsKeys.DEFAULT_WATCH_SCOPE,
    val watchQuiet: Boolean = true,
    val watchQuietStart: Int = ExpressSettingsKeys.DEFAULT_WATCH_QUIET_START,
    val watchQuietEnd: Int = ExpressSettingsKeys.DEFAULT_WATCH_QUIET_END,
    val watchGapMinutes: Int = ExpressSettingsKeys.DEFAULT_WATCH_GAP_MIN,
    val watchCycleMinutes: Int = ExpressSettingsKeys.DEFAULT_WATCH_CYCLE_MIN,
    /** 语义不是「有没有通知」——Android 不允许前台服务没通知；关 = 降成最低优先级 + 静默。 */
    val watchNotification: Boolean = true,
    /** 免 root 采集总开关，只是三个门槛之一；「现在到底在不在采集」由 NoRootPlan.isListenerCollecting 统一判定。 */
    val noRootListener: Boolean = false,
) {
    val isTraceAutoFetch: Boolean
        get() = traceFetchMode == ExpressSettingsKeys.MODE_TRACE_AUTO

    val isArchiveOnSign: Boolean
        get() = archiveMode == ExpressSettingsKeys.MODE_ARCHIVE_ON_SIGN
    val isEnabled: Boolean get() = mode != ExpressSettingsKeys.MODE_OFF

    val isInterceptMode: Boolean get() = mode == ExpressSettingsKeys.MODE_INTERCEPT

    val isWatchUnfinished: Boolean
        get() = watchScope == ExpressSettingsKeys.MODE_WATCH_UNFINISHED

    /** 夜间暂停窗口 `"22:00–08:00"` 这种给人看的写法，设置页与日志共用。 */
    fun quietWindowLabel(): String =
        "%02d:00–%02d:00".format(watchQuietStart, watchQuietEnd)

    val watchGapMillis: Long get() = watchGapMinutes * 60_000L

    /** 抖动跟随基准走（见 [WatchSchedule.jitterFor]），不是固定 ±1 分钟。 */
    val watchJitterMillis: Long get() = WatchSchedule.jitterFor(watchGapMillis)

    val watchCycleMillis: Long get() = watchCycleMinutes * 60_000L

    /** 当前节奏的一句话说明。界面说明必须由它生成——以前写死 3 分钟/30 分钟，用户改完间隔就「说一套做一套」。 */
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

    /** 转成判定规则。来源白名单按开关裁剪而不是另建一份，关掉的来源自然被放行。 */
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
        append(" intercepted=[")
        append(
            interceptedCategories.filter { it.toggleable }
                .map { it.displayName }
                .sorted()
                .joinToString(","),
        )
        append("]")
        if (autoWatch) {
            append(" watch=[")
            append(if (isWatchUnfinished) "未完成" else "在途")
            append(" 件${watchGapMinutes}分钟/轮${watchCycleMinutes}分钟")
            if (watchQuiet) append(" 停${quietWindowLabel()}")
            if (!watchNotification) append(" 通知关")
            append("]")
        }
        if (noRootListener) append(" noroot=listener")
    }

    companion object {
        val DEFAULT = ExpressSettingsSnapshot()

        /** Debug 构建放行 com.android.shell，adb shell cmd notification post 能造通知走完整链路；Release 为空集。 */
        val debugAllowedSources: Set<String> =
            if (BuildConfig.DEBUG) setOf("com.android.shell") else emptySet()

        /** 诊断展示用：来源包名 → 中文名。 */
        fun displayName(packageName: String): String = when (packageName) {
            "com.cainiao.wireless" -> "菜鸟"
            "com.xunmeng.pinduoduo" -> "拼多多"
            "com.taobao.taobao" -> "淘宝"
            "com.android.mms" -> "短信"
            "com.android.shell" -> "测试通知"
            else -> packageName
        }
    }
}
