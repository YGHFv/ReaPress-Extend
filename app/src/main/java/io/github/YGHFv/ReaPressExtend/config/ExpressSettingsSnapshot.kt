package io.github.YGHFv.ReaPressExtend.config

import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.core.ExpressRule

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
) {
    /** 模块是否在工作。关掉时 hook 直接放行，不做判定也不投递。 */
    val isEnabled: Boolean get() = mode != ExpressSettingsKeys.MODE_OFF

    /** 是否处于「拦截并替换」模式（false = 放行原通知，只额外发一条）。 */
    val isInterceptMode: Boolean get() = mode == ExpressSettingsKeys.MODE_INTERCEPT

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
