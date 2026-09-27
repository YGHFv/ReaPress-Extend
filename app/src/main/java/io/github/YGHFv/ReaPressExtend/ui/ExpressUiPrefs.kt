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

package io.github.YGHFv.ReaPressExtend.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * 界面偏好：只影响模块自己的界面，被注入侧完全不需要知道 —— 这是与 ExpressSettings 的分界线
 * （那边要跨进程投影到 RemotePreferences，system_server 每次通知事件都要读）。
 * 写盘用 commit() 而非 apply()：用户刚切完开关就退出时，异步落盘可能随进程回收丢设置。
 */
internal class ExpressUiPrefs private constructor(private val prefs: SharedPreferences) {

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_FOLLOW_SYSTEM)
            .coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)
        set(value) {
            prefs.edit()
                .putInt(KEY_THEME_MODE, value.coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK))
                .commit()
        }

    var blurBars: Boolean
        get() = prefs.getBoolean(KEY_BLUR_BARS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLUR_BARS, value).commit()
        }

    var floatingNavBar: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_NAV_BAR, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FLOATING_NAV_BAR, value).commit()
        }

    var liquidGlass: Boolean
        get() = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).commit()
        }

    /** 双击到站卡片上的取件码那行 = 确认取件，再双击撤销；默认开。撤销通道完整：卡片移出「到站包裹」后仍能在「已签收 / 异常」里双击退回。 */
    var doubleTapPickup: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_PICKUP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DOUBLE_TAP_PICKUP, value).commit()
        }

    /**
     * 不出现在系统「最近任务 / 后台」列表，默认关。这里存的只是意图：真正生效靠运行时调
     * ActivityManager.AppTask#setExcludeFromRecents（见 ExpressMainActivity）—— 清单属性与
     * 启动期 flag 都是一次性的，装完改不了，做不成开关。
     */
    var hideFromRecents: Boolean
        get() = prefs.getBoolean(KEY_HIDE_FROM_RECENTS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_FROM_RECENTS, value).commit()
        }

    fun resolveDark(context: Context): Boolean = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        /** 与功能设置分开的独立文件；internal 供备份清单引用同一来源。 */
        internal const val PREFS = "express_ui_prefs"

        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_BLUR_BARS = "themeBlurBars"
        private const val KEY_FLOATING_NAV_BAR = "themeFloatingNavBar"
        private const val KEY_LIQUID_GLASS = "themeLiquidGlass"
        private const val KEY_DOUBLE_TAP_PICKUP = "doubleTapPickup"
        private const val KEY_HIDE_FROM_RECENTS = "hideFromRecents"

        const val THEME_FOLLOW_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        val THEME_LABELS = listOf("跟随系统", "日间主题", "夜间主题")

        fun of(context: Context): ExpressUiPrefs =
            ExpressUiPrefs(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

/** 窗口底色：首帧之前系统栏区域显示的就是它，透明会让部分 ROM 露黑边。 */
internal const val LIGHT_WINDOW_BG = 0xFFF4F5F7.toInt()
internal const val DARK_WINDOW_BG = 0xFF111318.toInt()

internal const val BAR_BLUR_RADIUS = 25f
internal const val BAR_TINT_ALPHA = 0.87f
