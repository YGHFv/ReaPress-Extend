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
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.core.WatchSchedule

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

    /**
     * 用户选择**直接吞掉**的通知分类（设置 → 通知拦截），换行分隔的分类名。
     *
     * 存的是 [io.github.YGHFv.ReaPressExtend.core.NotificationCategory] 的 `name`，
     * 与关键词那两条一样用「换行拼的字符串」而不是 `putStringSet`：
     * 三个读取方（模块 UI / system_server / 宿主进程）走的是同一份 `writeTo`，
     * 少一种类型就少一处 RemotePreferences 的实现差异要对。
     *
     * 未勾选 = 键不存在 = 空集，与加这个键之前的行为一致。认不出的名字、
     * 以及不可拦截的分类会被 [io.github.YGHFv.ReaPressExtend.core.NotificationCategory.parse] 丢掉。
     */
    const val KEY_INTERCEPTED_CATEGORIES = "intercepted_categories"

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

    // ---- 归档 ----

    /**
     * 归档时机。**只影响模块 UI 的列表切分**（system_server / 宿主进程不读它，
     * 但走同一份 readFrom/writeTo 存取，加键不破坏契约）。
     *
     * 两态：
     * - [MODE_ARCHIVE_ON_SIGN]：物流一签收就进「归档快递」；
     * - [MODE_ARCHIVE_AFTER_SIGN_7D]（默认）：物流签收 7 天后再归档（历史行为）。
     *
     * 两条铁律与模式无关（见 `ExpressHomeGrouper` 的注释）：
     * 签收时刻以**物流**为准（轨迹末节点 / arrivalAt）；**手动标记取件不算签收**，
     * 必须等物流推来已签收才有归档资格。
     */
    const val KEY_ARCHIVE_MODE = "archive_mode"

    /** 物流签收后立即归档。 */
    const val MODE_ARCHIVE_ON_SIGN = "archive_on_sign"

    /** 物流签收 7 天后归档（默认，2026-09-26 用户定的原行为）。 */
    const val MODE_ARCHIVE_AFTER_SIGN_7D = "archive_sign_7d"

    const val DEFAULT_ARCHIVE_MODE = MODE_ARCHIVE_AFTER_SIGN_7D

    /** 归档模式的两个选项，UI 与解析共用一份（同 [MODE_OPTIONS] 的做法）。 */
    val ARCHIVE_MODE_OPTIONS: List<Pair<String, String>> = listOf(
        MODE_ARCHIVE_ON_SIGN to "签收后归档",
        MODE_ARCHIVE_AFTER_SIGN_7D to "签收7天后归档",
    )

    // ---- 自动轮查 ----
    //
    // 与上面「获取模式」的分工：那个决定**被触发时**怎么拉（点开详情 / 富化到达），
    // 这一组决定**模块要不要自己定时拉**。前者永远需要一个外部触发（用户点击、宿主刷新），
    // 而宿主不刷新时首页就一直停在旧数据上 —— 轮查补的就是这一格。

    /**
     * 自动轮查总开关。**默认关**。
     *
     * 关的理由不是「怕费电」，而是它必须**常驻一条通知**（见 `AutoWatchService`）：
     * 后台每几分钟醒一次只能靠前台服务，而前台服务在通知栏里是撤不掉的。
     * 这种「看得见的后台常驻」不该由默认值替用户决定，得他自己开。
     */
    const val KEY_AUTO_WATCH = "auto_watch"

    /** 轮查范围，取值见下面的 `MODE_WATCH_*`。 */
    const val KEY_WATCH_SCOPE = "watch_scope"

    /** 夜间暂停。默认开 —— 半夜没有快递在动，那些请求纯属白花。 */
    const val KEY_WATCH_QUIET = "watch_quiet"

    /** 夜间暂停的**起**（整点小时 0..23），默认 22。 */
    const val KEY_WATCH_QUIET_START = "watch_quiet_start"

    /** 夜间暂停的**止**（整点小时 0..23），默认 8。 */
    const val KEY_WATCH_QUIET_END = "watch_quiet_end"

    /** 只问还在路上（运输中 / 派送中）的件。 */
    const val MODE_WATCH_TRANSIT = "watch_transit"

    /** 连到站待取件一起问（没闭环的都算）。到站件的动态也会推进，但请求数翻倍。 */
    const val MODE_WATCH_UNFINISHED = "watch_unfinished"

    const val DEFAULT_WATCH_SCOPE = MODE_WATCH_TRANSIT

    /** 夜间暂停的**起**（整点小时 0..23），默认 22。 */
    const val DEFAULT_WATCH_QUIET_START = 22
    const val DEFAULT_WATCH_QUIET_END = 8

    /**
     * 件与件之间的基准间隔（**分钟**）。默认 3，与 `WatchSchedule.DEFAULT_BASE_GAP_MS` 同一个值。
     *
     * 2026-09-27 用户要求可调 —— 之前它写死在 `WatchSchedule` 里，用户只能接受 3 分钟。
     * 合法区间与夹取都在 `WatchSchedule.clampGapMinutes`（core 里，带单测）。
     */
    const val KEY_WATCH_GAP_MIN = "watch_gap_min"

    /** 一轮跑完之后等多久（**分钟**）。默认 30，含义见 `WatchSchedule.DEFAULT_CYCLE_WAIT_MS`。 */
    const val KEY_WATCH_CYCLE_MIN = "watch_cycle_min"

    /**
     * 轮查时要不要那条前台服务通知。默认**开**。
     *
     * ⚠️ 关掉**不等于**通知消失：Android 要求前台服务必须有通知，否则服务会被直接杀掉。
     * 这里能做的只是把它降成最低优先级 + 静默（不占状态栏图标、不出声），
     * 通知栏里仍会有一条 —— 想彻底不看到只能在系统设置里把「自动轮查」渠道关掉。
     * 界面上的说明必须把这条说清楚（见设置页那一行 HintText），不能让用户以为它不见了。
     */
    const val KEY_WATCH_NOTIFICATION = "watch_notification"

    const val DEFAULT_WATCH_GAP_MIN = 3
    const val DEFAULT_WATCH_CYCLE_MIN = 30

    /** 件间隔可选项。键是分钟数，值是给人看的标签；UI 与写入侧共用一份，避免两处对不上。 */
    val WATCH_GAP_OPTIONS: List<Pair<Int, String>> = listOf(
        1 to "1 分钟",
        2 to "2 分钟",
        3 to "3 分钟",
        5 to "5 分钟",
        10 to "10 分钟",
        20 to "20 分钟",
    )

    /** 轮间隔可选项。 */
    val WATCH_CYCLE_OPTIONS: List<Pair<Int, String>> = listOf(
        5 to "5 分钟",
        15 to "15 分钟",
        30 to "30 分钟",
        60 to "1 小时",
        120 to "2 小时",
        360 to "6 小时",
    )

    /** 轮查范围的两个选项，UI 与解析共用一份（同 [MODE_OPTIONS] 的做法）。 */
    val WATCH_SCOPE_OPTIONS: List<Pair<String, String>> = listOf(
        MODE_WATCH_TRANSIT to "在途",
        MODE_WATCH_UNFINISHED to "未完成",
    )

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
            // 解析（含「丢掉不可拦截的分类」那道守卫）在 core 里，两处读取方共用同一份解释。
            interceptedCategories = NotificationCategory.parse(
                splitKeywords(prefs.getString(KEY_INTERCEPTED_CATEGORIES, null)),
            ),
            hookForceEnabled = prefs.getBoolean(KEY_HOOK_FORCE_ENABLED, false),
            traceFetchMode = prefs.getString(KEY_TRACE_FETCH_MODE, DEFAULT_TRACE_FETCH_MODE)
                ?.takeIf { it == MODE_TRACE_AUTO || it == MODE_TRACE_ON_DEMAND }
                ?: DEFAULT_TRACE_FETCH_MODE,
            // 归档模式同样夹取：认不出的值回落默认（手改 XML / 旧版本残留都不至于炸）。
            archiveMode = prefs.getString(KEY_ARCHIVE_MODE, DEFAULT_ARCHIVE_MODE)
                ?.takeIf { it == MODE_ARCHIVE_ON_SIGN || it == MODE_ARCHIVE_AFTER_SIGN_7D }
                ?: DEFAULT_ARCHIVE_MODE,
            autoWatch = prefs.getBoolean(KEY_AUTO_WATCH, false),
            watchScope = prefs.getString(KEY_WATCH_SCOPE, DEFAULT_WATCH_SCOPE)
                ?.takeIf { it == MODE_WATCH_TRANSIT || it == MODE_WATCH_UNFINISHED }
                ?: DEFAULT_WATCH_SCOPE,
            watchQuiet = prefs.getBoolean(KEY_WATCH_QUIET, true),
            // 小时数一律夹到合法区间：手改 XML / 换地区之后算出 25 点这种值，
            // 排程那边会一直等一个不存在的时刻（表现是「轮查再也没跑过」）。
            watchQuietStart = prefs.getInt(KEY_WATCH_QUIET_START, DEFAULT_WATCH_QUIET_START)
                .coerceIn(0, 23),
            watchQuietEnd = prefs.getInt(KEY_WATCH_QUIET_END, DEFAULT_WATCH_QUIET_END)
                .coerceIn(0, 23),
            // 间隔同样夹取：手改 XML 写个 0 或 9999 进去，排程那边会变成「连发」或「再也不跑」，
            // 而界面上看不出为什么。夹取规则在 core（WatchSchedule），与单测同一份出处。
            watchGapMinutes = WatchSchedule.clampGapMinutes(
                prefs.getInt(KEY_WATCH_GAP_MIN, DEFAULT_WATCH_GAP_MIN),
            ),
            watchCycleMinutes = WatchSchedule.clampCycleMinutes(
                prefs.getInt(KEY_WATCH_CYCLE_MIN, DEFAULT_WATCH_CYCLE_MIN),
            ),
            // 默认开：关掉之后通知栏那一条会变成最低优先级（见 KEY_WATCH_NOTIFICATION）。
            watchNotification = prefs.getBoolean(KEY_WATCH_NOTIFICATION, true),
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
            .putString(KEY_ARCHIVE_MODE, snapshot.archiveMode)
            .putBoolean(KEY_AUTO_WATCH, snapshot.autoWatch)
            .putString(KEY_WATCH_SCOPE, snapshot.watchScope)
            .putBoolean(KEY_WATCH_QUIET, snapshot.watchQuiet)
            .putInt(KEY_WATCH_QUIET_START, snapshot.watchQuietStart)
            .putInt(KEY_WATCH_QUIET_END, snapshot.watchQuietEnd)
            .putInt(KEY_WATCH_GAP_MIN, snapshot.watchGapMinutes)
            .putInt(KEY_WATCH_CYCLE_MIN, snapshot.watchCycleMinutes)
            .putBoolean(KEY_WATCH_NOTIFICATION, snapshot.watchNotification)
            // 分类用 `names()` 归一化（排序 + 只留可拦截的）再拼：界面上的集合顺序随点击先后变，
            // 不排序的话同一个配置每次存出来都不同，比对 prefs 时看不出「到底改没改」。
            .putString(
                KEY_INTERCEPTED_CATEGORIES,
                joinKeywords(NotificationCategory.names(snapshot.interceptedCategories).toSet()),
            )
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
