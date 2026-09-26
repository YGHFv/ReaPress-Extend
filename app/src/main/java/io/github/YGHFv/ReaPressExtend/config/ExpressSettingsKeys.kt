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

/**
 * 设置键与默认值。
 *
 * 一份定义三处用：模块 UI（写）、system_server 侧 hook（读）、宿主进程 hook（读）。
 * 键名是跨进程契约，改动等于破坏兼容 —— 加键可以，改键名要同时改所有读取方。
 */
object ExpressSettingsKeys {

    /** RemotePreferences 的组名。 */
    const val GROUP = "express_settings"

    /** 本地 SharedPreferences 文件名（模块 App 进程自己的降级存储）。 */
    const val LOCAL_PREFS = "express_local_settings"

    // ---- 工作模式（唯一的开关）----

    /**
     * 工作模式。
     *
     * **这是模块唯一的启停控制**，取代了以前的「拦截总开关 + 模式」两个字段 ——
     * 那两个可以独立设置，会出现「开关说没启用、模式说拦截并替换」这种自相矛盾的状态，
     * 而且旧开关在 system_server 侧根本没被读过，是个纯装饰。
     *
     * 三态：
     * - [MODE_OFF]：完全不处理，通知原样放行（模块的「关」）
     * - [MODE_PASSTHROUGH]：判定并额外发一条，原通知不动
     * - [MODE_INTERCEPT]：判定并吞掉原通知，只留模块发的那条
     */
    const val KEY_MODE = "intercept_mode"

    /**
     * 旧版的独立总开关。**只读不写**，用于把老安装的设置迁移过来，避免升级后行为突变。
     */
    private const val KEY_LEGACY_INTERCEPT_ENABLED = "intercept_enabled"

    /**
     * 请求复位看门狗熔断。
     *
     * 看门狗的 `disabled` 标记存在 `/data/system/`，模块 App（uid 10xxx）**写不了那个目录**，
     * 所以界面上没法直接清零。改成这条路径：用户点「复位」→ 本开关写进 RemotePreferences →
     * system_server 下次开机安装 hook 时读到它 → 传给 `Watchdog.beforeInstall(forceEnabled=true)`
     * 跳过 disabled 判定 → 安装成功后由 system_server 把它清回 false。
     *
     * 一次性语义：system_server 消费后必须清掉，否则熔断保护就永久失效了。
     */
    const val KEY_HOOK_FORCE_ENABLED = "hook_force_enabled"

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

    // ---- 值 ----

    const val MODE_OFF = "off"
    const val MODE_INTERCEPT = "intercept"
    const val MODE_PASSTHROUGH = "passthrough"

    /**
     * 默认关闭。
     *
     * 装完不做任何事，用户必须在界面上主动选一个模式。理由：
     * - 选 [MODE_INTERCEPT] 会让「模块发不出通知」直接等于「用户什么都看不到」，
     *   这种风险不该由默认值替用户承担；
     * - 选 [MODE_PASSTHROUGH] 会在用户还没配置作用域、还没给通知权限时就发通知，
     *   大概率发不出去，只留下一堆失败审计，看起来像模块坏了。
     *
     * 所以默认值取「什么都不做」，把选择权交给用户 —— 设置页里每个模式的后果都写清楚了。
     * 与 [readFrom] 对全新安装的推断保持一致（那里也是 OFF）。
     */
    const val DEFAULT_MODE = MODE_OFF

    /** 界面上模式选项的展示顺序与文案，UI 与快照共用一份，避免两处各写一遍对不上。 */
    val MODE_OPTIONS: List<Pair<String, String>> = listOf(
        MODE_OFF to "关闭",
        MODE_PASSTHROUGH to "放行并附加",
        MODE_INTERCEPT to "拦截并替换",
    )

    const val DEFAULT_CONFIDENCE_THRESHOLD = 50

    /** 四个来源默认全开 —— 用户装这个模块就是为了处理它们。 */
    const val DEFAULT_SOURCE_ENABLED = true

    // ---- 包裹详情（轨迹）的获取模式 ----

    const val KEY_TRACE_FETCH_MODE = "trace_fetch_mode"

    /**
     * 「自动更新」：模块收到宿主富化时，对到站 / 派送中的件**主动**批量拉全轨迹
     * （内部仍按 2.5s 间隔串行 + 风控退避）。不点详情也常新，代价是请求多、风控压力更大。
     */
    const val MODE_TRACE_AUTO = "trace_auto"

    /**
     * 「点击时获取」（默认）：只在用户点开详情 / 下拉刷新时拉**当前**这一个单号。
     * 一次点击就是一次请求，节奏和人手一致，风控压力最小。
     */
    const val MODE_TRACE_ON_DEMAND = "trace_on_demand"

    const val DEFAULT_TRACE_FETCH_MODE = MODE_TRACE_ON_DEMAND

    /**
     * 从 SharedPreferences 读出全部设置。
     *
     * 两处调用：system_server 侧每次事件前读一次（RemotePreferences 是快照，不缓存），
     * 模块 UI 直接读本地 prefs。用同一个函数保证两边解释一致。
     */
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
            hookForceEnabled = prefs.getBoolean(KEY_HOOK_FORCE_ENABLED, false),
            traceFetchMode = prefs.getString(KEY_TRACE_FETCH_MODE, DEFAULT_TRACE_FETCH_MODE)
                ?.takeIf { it == MODE_TRACE_AUTO || it == MODE_TRACE_ON_DEMAND }
                ?: DEFAULT_TRACE_FETCH_MODE,
        )
    }

    /**
     * 读工作模式，并把旧版的 `intercept_enabled` 迁移过来。
     *
     * 迁移规则只往「更保守」的方向走：旧开关关着 → [MODE_OFF]（用户本来就没启用）；
     * 开着但模式是放行 → 保持放行。**绝不**因为旧开关是 true 就升级成拦截 ——
     * 那个开关在旧版里压根没参与判定，用它推断「用户想要拦截」是凭空加戏。
     */
    private fun readMode(prefs: SharedPreferences): String {
        val stored = prefs.getString(KEY_MODE, null)
        if (stored == MODE_OFF || stored == MODE_PASSTHROUGH || stored == MODE_INTERCEPT) {
            return stored
        }
        val legacyEnabled = prefs.getBoolean(KEY_LEGACY_INTERCEPT_ENABLED, false)
        return if (legacyEnabled) MODE_PASSTHROUGH else MODE_OFF
    }

    /** 关键词按行分隔，忽略空行与首尾空白。 */
    fun splitKeywords(raw: String?): Set<String> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun joinKeywords(keywords: Set<String>): String = keywords.joinToString("\n")

    /**
     * 把快照写回 prefs。
     *
     * **不写 [KEY_HOOK_FORCE_ENABLED]**：它是一次性请求，由 [requestHookForceEnable] 单独置位、
     * 由 system_server 消费后清除。放进这里会让界面每次保存设置都把它重新激活，
     * 等于永久关掉看门狗保护。
     */
    fun writeTo(prefs: SharedPreferences, snapshot: ExpressSettingsSnapshot) {
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
            .apply()
    }

    /** 界面请求复位看门狗熔断。一次性：system_server 消费后会清掉。 */
    fun requestHookForceEnable(prefs: SharedPreferences, enable: Boolean) {
        prefs.edit().putBoolean(KEY_HOOK_FORCE_ENABLED, enable).apply()
    }

    /** system_server 侧：消费复位请求（读一次并清零）。 */
    fun consumeHookForceEnable(prefs: SharedPreferences): Boolean {
        val requested = prefs.getBoolean(KEY_HOOK_FORCE_ENABLED, false)
        if (requested) {
            prefs.edit().putBoolean(KEY_HOOK_FORCE_ENABLED, false).apply()
        }
        return requested
    }

    /** 模块 App 进程用：本地 prefs。system_server 拿不到这个文件（跨 uid），它走 RemotePreferences。 */
    fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
}
