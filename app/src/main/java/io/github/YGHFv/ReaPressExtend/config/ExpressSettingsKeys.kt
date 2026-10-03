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

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.core.WatchSchedule

/**
 * 设置键与默认值，一份定义三处用：模块 UI（写）、system_server 侧 hook（读）、宿主进程 hook（读）。
 * 键名是跨进程契约：加键可以，改键名等于破坏兼容。
 */
object ExpressSettingsKeys {

    /** RemotePreferences 的组名。 */
    const val GROUP = "express_settings"

    /** 本地 SharedPreferences 文件名（模块 App 进程自己的降级存储）。 */
    const val LOCAL_PREFS = "express_local_settings"

    // ---- 工作模式（唯一的开关）----

    /**
     * 工作模式，模块唯一的启停控制（取代旧「拦截总开关 + 模式」两字段，那会出现自相矛盾的状态）。三态：
     * [MODE_OFF] 完全放行；[MODE_PASSTHROUGH] 判定并附加一条，原通知不动；[MODE_INTERCEPT] 吞原通知只留模块那条。
     */
    const val KEY_MODE = "intercept_mode"

    /** 旧版的独立总开关。只读不写，用于迁移老安装的设置，避免升级后行为突变。 */
    private const val KEY_LEGACY_INTERCEPT_ENABLED = "intercept_enabled"

    /**
     * 请求复位看门狗熔断。看门狗的 disabled 标记在 /data/system/，模块 App（uid 10xxx）写不了，
     * 旧布尔标志只用于迁移；新请求使用稳定 ID，system_server 在自己的状态文件登记消费。
     * 被注入进程的 RemotePreferences 只读，不能靠清除框架标志保证一次性。
     */
    const val KEY_HOOK_FORCE_ENABLED = "hook_force_enabled"
    const val KEY_HOOK_RESET_REQUEST_ID = "hook_reset_request_id"

    internal val HOOK_RESET_KEYS = setOf(KEY_HOOK_FORCE_ENABLED, KEY_HOOK_RESET_REQUEST_ID)

    // ---- 分源开关 ----

    const val KEY_SOURCE_CAINIAO = "source_cainiao"
    const val KEY_SOURCE_PINDUODUO = "source_pinduoduo"
    const val KEY_SOURCE_TAOBAO = "source_taobao"
    const val KEY_SOURCE_SMS = "source_sms"

    /** 用户自定义关键词，换行分隔。 */
    const val KEY_EXTRA_KEYWORDS = "extra_keywords"

    /** 用户排除关键词，换行分隔。 */
    const val KEY_EXCLUDE_KEYWORDS = "exclude_keywords"

    /** 置信度阈值 0..100。 */
    const val KEY_CONFIDENCE_THRESHOLD = "confidence_threshold"

    /** 用户勾选直接吞掉的通知分类（设置 → 通知拦截），换行分隔的 [NotificationCategory.name]。用字符串而非 StringSet：三个读取方走同一份 writeTo，少一处实现差异；认不出/不可拦截的被 [NotificationCategory.parse] 丢掉。 */
    const val KEY_INTERCEPTED_CATEGORIES = "intercepted_categories"

    // ---- 值 ----

    const val MODE_OFF = "off"
    const val MODE_INTERCEPT = "intercept"
    const val MODE_PASSTHROUGH = "passthrough"

    /** 默认关闭：拦截模式下「模块发不出通知」直接等于「用户什么都看不到」，这种风险不该由默认值替用户承担。 */
    const val DEFAULT_MODE = MODE_OFF

    /** 界面上模式选项的展示顺序与文案，UI 与快照共用一份，避免两处各写一遍对不上。 */
    val MODE_OPTIONS: List<Pair<String, String>> = listOf(
        MODE_OFF to "关闭",
        MODE_PASSTHROUGH to "放行并附加",
        MODE_INTERCEPT to "拦截并替换",
    )

    const val DEFAULT_CONFIDENCE_THRESHOLD = 50

    const val DEFAULT_SOURCE_ENABLED = true

    // ---- 包裹详情（轨迹）的获取模式 ----

    const val KEY_TRACE_FETCH_MODE = "trace_fetch_mode"

    /** 「自动更新」：富化到达时对到站/派送中的件主动批量拉（引擎内仍 2.5s 串行 + 风控退避）。 */
    const val MODE_TRACE_AUTO = "trace_auto"

    /** 「点击时获取」（默认）：只在点开详情/下拉时拉当前单号，节奏与人手一致，风控压力最小。 */
    const val MODE_TRACE_ON_DEMAND = "trace_on_demand"

    const val DEFAULT_TRACE_FETCH_MODE = MODE_TRACE_ON_DEMAND

    // ---- 归档 ----

    /** 归档时机，只影响模块 UI 的列表切分。铁律：签收时刻以物流为准（轨迹末节点/arrivalAt）；手动标记取件不算签收。 */
    const val KEY_ARCHIVE_MODE = "archive_mode"

    const val MODE_ARCHIVE_ON_SIGN = "archive_on_sign"

    const val MODE_ARCHIVE_AFTER_SIGN_7D = "archive_sign_7d"

    const val DEFAULT_ARCHIVE_MODE = MODE_ARCHIVE_AFTER_SIGN_7D

    val ARCHIVE_MODE_OPTIONS: List<Pair<String, String>> = listOf(
        MODE_ARCHIVE_ON_SIGN to "签收后归档",
        MODE_ARCHIVE_AFTER_SIGN_7D to "签收7天后归档",
    )

    // ---- 免 root 方案 ----

    /** 免 root 采集总开关（通知使用权那条路），默认关（要读系统所有通知，该由用户点头）。它不是唯一门槛：还要通知使用权已授予且工作模式非关闭，三条件判定只有一份（`NoRootPlan.isListenerCollecting`）。 */
    const val KEY_NOROOT_LISTENER = "noroot_listener"

    // ---- 自动轮查 ----
    // 与「获取模式」的分工：那个决定被触发时怎么拉，这组决定模块要不要自己定时拉。

    /** 自动轮查总开关，默认关：它必须常驻一条前台服务通知（撤不掉），这种看得见的后台常驻得用户自己开。 */
    const val KEY_AUTO_WATCH = "auto_watch"

    const val KEY_WATCH_SCOPE = "watch_scope"

    /** 夜间暂停，默认开：半夜没有快递在动，那些请求纯属白花。 */
    const val KEY_WATCH_QUIET = "watch_quiet"

    /** 夜间暂停的起止（整点小时 0..23）。 */
    const val KEY_WATCH_QUIET_START = "watch_quiet_start"

    const val KEY_WATCH_QUIET_END = "watch_quiet_end"

    const val MODE_WATCH_TRANSIT = "watch_transit"

    const val MODE_WATCH_UNFINISHED = "watch_unfinished"

    const val DEFAULT_WATCH_SCOPE = MODE_WATCH_TRANSIT

    const val DEFAULT_WATCH_QUIET_START = 22
    const val DEFAULT_WATCH_QUIET_END = 8

    /** 件与件之间的基准间隔（分钟），2026-09-27 起可调；夹取在 [WatchSchedule.clampGapMinutes]。 */
    const val KEY_WATCH_GAP_MIN = "watch_gap_min"

    /** 一轮跑完之后等多久（分钟）。 */
    const val KEY_WATCH_CYCLE_MIN = "watch_cycle_min"

    /** 轮查的前台服务通知，默认开。关掉不等于通知消失：Android 要求前台服务必须有通知否则被杀，这里只能降最低优先级 + 静默；界面说明必须讲清楚。 */
    const val KEY_WATCH_NOTIFICATION = "watch_notification"

    const val DEFAULT_WATCH_GAP_MIN = 3
    const val DEFAULT_WATCH_CYCLE_MIN = 30

    val WATCH_GAP_OPTIONS: List<Pair<Int, String>> = listOf(
        1 to "1 分钟",
        2 to "2 分钟",
        3 to "3 分钟",
        5 to "5 分钟",
        10 to "10 分钟",
        20 to "20 分钟",
    )

    val WATCH_CYCLE_OPTIONS: List<Pair<Int, String>> = listOf(
        5 to "5 分钟",
        15 to "15 分钟",
        30 to "30 分钟",
        60 to "1 小时",
        120 to "2 小时",
        360 to "6 小时",
    )

    val WATCH_SCOPE_OPTIONS: List<Pair<String, String>> = listOf(
        MODE_WATCH_TRANSIT to "在途",
        MODE_WATCH_UNFINISHED to "未完成",
    )

    /** 从 SharedPreferences 读出全部设置；system_server 与模块 UI 共用同一份解释。 */
    fun readFrom(prefs: SharedPreferences?): ExpressSettingsSnapshot {
        if (prefs == null) return ExpressSettingsSnapshot()
        return ExpressSettingsSnapshot(
            sourceCainiao = prefs.getBoolean(KEY_SOURCE_CAINIAO, DEFAULT_SOURCE_ENABLED),
            sourcePinduoduo = prefs.getBoolean(KEY_SOURCE_PINDUODUO, DEFAULT_SOURCE_ENABLED),
            sourceTaobao = prefs.getBoolean(KEY_SOURCE_TAOBAO, DEFAULT_SOURCE_ENABLED),
            sourceSms = prefs.getBoolean(KEY_SOURCE_SMS, DEFAULT_SOURCE_ENABLED),
            mode = readMode(prefs),
            extraKeywords = splitKeywords(prefs.getString(KEY_EXTRA_KEYWORDS, null)),
            excludeKeywords = splitKeywords(prefs.getString(KEY_EXCLUDE_KEYWORDS, null)),
            confidenceThreshold = prefs.getInt(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD),
            interceptedCategories = NotificationCategory.parse(
                splitKeywords(prefs.getString(KEY_INTERCEPTED_CATEGORIES, null)),
            ),
            hookForceEnabled = prefs.getBoolean(KEY_HOOK_FORCE_ENABLED, false),
            traceFetchMode = prefs.getString(KEY_TRACE_FETCH_MODE, DEFAULT_TRACE_FETCH_MODE)
                ?.takeIf { it == MODE_TRACE_AUTO || it == MODE_TRACE_ON_DEMAND }
                ?: DEFAULT_TRACE_FETCH_MODE,
            archiveMode = prefs.getString(KEY_ARCHIVE_MODE, DEFAULT_ARCHIVE_MODE)
                ?.takeIf { it == MODE_ARCHIVE_ON_SIGN || it == MODE_ARCHIVE_AFTER_SIGN_7D }
                ?: DEFAULT_ARCHIVE_MODE,
            autoWatch = prefs.getBoolean(KEY_AUTO_WATCH, false),
            watchScope = prefs.getString(KEY_WATCH_SCOPE, DEFAULT_WATCH_SCOPE)
                ?.takeIf { it == MODE_WATCH_TRANSIT || it == MODE_WATCH_UNFINISHED }
                ?: DEFAULT_WATCH_SCOPE,
            watchQuiet = prefs.getBoolean(KEY_WATCH_QUIET, true),
            // 小时数夹到 0..23：算出 25 点会让排程永远等一个不存在的时刻（表现是「轮查再也没跑过」）。
            watchQuietStart = prefs.getInt(KEY_WATCH_QUIET_START, DEFAULT_WATCH_QUIET_START)
                .coerceIn(0, 23),
            watchQuietEnd = prefs.getInt(KEY_WATCH_QUIET_END, DEFAULT_WATCH_QUIET_END)
                .coerceIn(0, 23),
            // 间隔夹取：手改 XML 写个 0 或 9999 进去会变成「连发」或「再也不跑」。
            watchGapMinutes = WatchSchedule.clampGapMinutes(
                prefs.getInt(KEY_WATCH_GAP_MIN, DEFAULT_WATCH_GAP_MIN),
            ),
            watchCycleMinutes = WatchSchedule.clampCycleMinutes(
                prefs.getInt(KEY_WATCH_CYCLE_MIN, DEFAULT_WATCH_CYCLE_MIN),
            ),
            watchNotification = prefs.getBoolean(KEY_WATCH_NOTIFICATION, true),
            noRootListener = prefs.getBoolean(KEY_NOROOT_LISTENER, false),
        )
    }

    /** 读工作模式并迁移旧版 `intercept_enabled`。只往保守方向迁：旧开关开着也只给 PASSTHROUGH，绝不升级成拦截——旧开关在旧版里没参与判定。 */
    private fun readMode(prefs: SharedPreferences): String {
        val stored = prefs.getString(KEY_MODE, null)
        if (stored == MODE_OFF || stored == MODE_PASSTHROUGH || stored == MODE_INTERCEPT) {
            return stored
        }
        val legacyEnabled = prefs.getBoolean(KEY_LEGACY_INTERCEPT_ENABLED, false)
        return if (legacyEnabled) MODE_PASSTHROUGH else MODE_OFF
    }

    fun splitKeywords(raw: String?): Set<String> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun joinKeywords(keywords: Set<String>): String = keywords.joinToString("\n")

    /** 写回普通设置，复位请求单独生成和同步，不能随设置保存而轮换。 */
    fun writeTo(prefs: SharedPreferences, snapshot: ExpressSettingsSnapshot): Boolean =
        editorFor(prefs, snapshot).commit()

    internal fun editorFor(prefs: SharedPreferences, snapshot: ExpressSettingsSnapshot): SharedPreferences.Editor =
        prefs.edit()
            .putBoolean(KEY_SOURCE_CAINIAO, snapshot.sourceCainiao)
            .putBoolean(KEY_SOURCE_PINDUODUO, snapshot.sourcePinduoduo)
            .putBoolean(KEY_SOURCE_TAOBAO, snapshot.sourceTaobao)
            .putBoolean(KEY_SOURCE_SMS, snapshot.sourceSms)
            .putString(KEY_MODE, snapshot.mode)
            .putString(KEY_EXTRA_KEYWORDS, joinKeywords(snapshot.extraKeywords))
            .putString(KEY_EXCLUDE_KEYWORDS, joinKeywords(snapshot.excludeKeywords))
            .putInt(KEY_CONFIDENCE_THRESHOLD, snapshot.confidenceThreshold)
            .putString(KEY_TRACE_FETCH_MODE, snapshot.traceFetchMode)
            .putString(KEY_ARCHIVE_MODE, snapshot.archiveMode)
            .putBoolean(KEY_AUTO_WATCH, snapshot.autoWatch)
            .putString(KEY_WATCH_SCOPE, snapshot.watchScope)
            .putBoolean(KEY_WATCH_QUIET, snapshot.watchQuiet)
            .putInt(KEY_WATCH_QUIET_START, snapshot.watchQuietStart)
            .putInt(KEY_WATCH_QUIET_END, snapshot.watchQuietEnd)
            .putInt(KEY_WATCH_GAP_MIN, snapshot.watchGapMinutes)
            .putInt(KEY_WATCH_CYCLE_MIN, snapshot.watchCycleMinutes)
            .putBoolean(KEY_WATCH_NOTIFICATION, snapshot.watchNotification)
            .putBoolean(KEY_NOROOT_LISTENER, snapshot.noRootListener)
            // 分类先归一化（排序 + 只留可拦截的）再拼，否则同一配置每次存出顺序不同，比对不出改没改。
            .putString(
                KEY_INTERCEPTED_CATEGORIES,
                joinKeywords(NotificationCategory.names(snapshot.interceptedCategories).toSet()),
            )

    fun requestHookForceEnable(prefs: SharedPreferences, enable: Boolean): Boolean =
        prefs.edit()
            .putBoolean(KEY_HOOK_FORCE_ENABLED, enable)
            .putString(KEY_HOOK_RESET_REQUEST_ID, if (enable) UUID.randomUUID().toString() else null)
            .commit()

    fun hookResetRequest(prefs: SharedPreferences): String? =
        prefs.getString(KEY_HOOK_RESET_REQUEST_ID, null)?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: "legacy-force-enable".takeIf { prefs.getBoolean(KEY_HOOK_FORCE_ENABLED, false) }

    /** 模块 App 进程用。system_server 拿不到这个文件（跨 uid），走 RemotePreferences。 */
    fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
}
