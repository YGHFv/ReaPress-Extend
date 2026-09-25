package io.github.YGHFv.ReaPressExtend.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * 界面偏好。
 *
 * 装两块：**外观**（主题、模糊、底栏形态）与**界面交互行为**（双击取件码确认取件）。
 * 两者的共同点是**只影响模块自己的界面**，被注入侧（system_server、宿主进程）完全不需要知道 ——
 * 这正是它们和 [io.github.YGHFv.ReaPressExtend.config.ExpressSettings] 的分界线。
 *
 * 混在一起的坏处很实际：功能设置要投影到 RemotePreferences，改一次就是一次跨进程写入，
 * 而 system_server 侧每次通知事件都要读一遍 —— 界面上的开关不该给它加这种负担。
 *
 * 写盘用 `commit()` 而不是 `apply()`：用户可能刚切完开关就按返回退出，`apply()` 的异步落盘
 * 在进程被立即回收时可能丢设置。这些开关很小，同步写的代价可以忽略。
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

    /**
     * 双击到站卡片上取件码那**一行** = 确认取件；再双击撤销。
     *
     * **默认开**：这个动作必须是「故意双击」才触发，本身不构成误操作风险；而关掉的话
     * 用户装完这个功能却看不出它在哪 —— 一个只能靠翻设置才知道存在的功能等于没有。
     *
     * 撤销通道是完整的：整站取完把卡片移出「到站包裹」之后，仍然能在「已签收 / 异常」
     * 里双击那几张卡片退回去（见 `ExpressHomePage.ParcelCard`）—— 单件驿站误触一下就是
     * 直接移档，没有灰态窗口可以退，不补这条路等于一次误触永久生效。
     */
    var doubleTapPickup: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_PICKUP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DOUBLE_TAP_PICKUP, value).commit()
        }

    /**
     * 解析当前该用深色还是浅色。
     *
     * 0 跟随系统，1 强制日间，2 强制夜间。
     */
    fun resolveDark(context: Context): Boolean = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        /** 界面偏好单独一份文件，与功能设置的 `express_local_settings` 分开。 */
        private const val PREFS = "express_ui_prefs"

        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_BLUR_BARS = "themeBlurBars"
        private const val KEY_FLOATING_NAV_BAR = "themeFloatingNavBar"
        private const val KEY_LIQUID_GLASS = "themeLiquidGlass"
        private const val KEY_DOUBLE_TAP_PICKUP = "doubleTapPickup"

        const val THEME_FOLLOW_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        val THEME_LABELS = listOf("跟随系统", "日间主题", "夜间主题")

        fun of(context: Context): ExpressUiPrefs =
            ExpressUiPrefs(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

/** 窗口底色。首帧之前系统栏区域显示的就是它，透明会让部分 ROM 露黑边。 */
internal const val LIGHT_WINDOW_BG = 0xFFF4F5F7.toInt()
internal const val DARK_WINDOW_BG = 0xFF111318.toInt()

/** 顶栏/底栏的模糊参数，与参考实现保持一致（过度调参只会让玻璃感变假）。 */
internal const val BAR_BLUR_RADIUS = 25f
internal const val BAR_TINT_ALPHA = 0.87f
