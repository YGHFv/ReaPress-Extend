package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 看门狗状态回传。
 *
 * ## 为什么必须走广播
 *
 * 看门狗状态存在 `/data/system/`（system_server 是 `system` uid，只能写自己的地盘）。
 * 那个目录权限是 `0771 system:system`，模块 App 连**目录都进不去** —— 文件设成 0644 也没用，
 * 因为路径解析在目录那一层就被拒绝了（实测 `Permission denied`）。
 *
 * 所以状态只能由 system_server 主动推过来。推送时机是**每次开机安装 hook 之后**：
 * 那时 [SystemContextHolder.acquireFromActivityThread] 已经能拿到 context，
 * 且「被拒绝安装」这个最关键的信息正好在这时产生。
 *
 * ## 为什么每次开机都推，而不是只在熔断时推
 *
 * 只在熔断时推的话，用户看到的是「上次的旧状态」或「从未收到」—— 分不清
 * 「一切正常」和「广播没送到」。每次开机推一次，UI 就能显示带时间戳的确定状态。
 */
internal object WatchdogReporter {

    const val ACTION_WATCHDOG_STATUS = "io.github.YGHFv.ReaPressExtend.WATCHDOG_STATUS"

    const val EXTRA_INSTALLED = "installed"
    const val EXTRA_DISABLED = "disabled"
    const val EXTRA_REASON = "reason"
    const val EXTRA_DESCRIBE = "describe"

    /**
     * 把本次开机的看门狗决定推给模块 App 进程。
     *
     * @param installed hook 是否装上了
     * @param describe [Watchdog.describe] 的输出（attempt/ok/failures）
     */
    fun reportBoot(context: Context?, installed: Boolean, describe: String) {
        if (context == null) {
            // 拿不到 context 只影响 UI 显示，不影响 hook 本身。记一行即可。
            XposedBridge.log("watchdog report skipped: no system context available")
            return
        }
        val disabled = !installed
        val reason = if (disabled) {
            "hook 未安装（看门狗熔断或框架不支持）"
        } else {
            ""
        }
        runCatching {
            val intent = Intent(ACTION_WATCHDOG_STATUS).apply {
                // 模块 App 可能还没启动过（stopped），没有这个 flag 收不到。
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                putExtra(EXTRA_INSTALLED, installed)
                putExtra(EXTRA_DISABLED, disabled)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_DESCRIBE, describe)
            }
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            // logAlways 而不是 log：这行是「状态有没有推出去」的唯一证据，
            // 被「简洁日志」吞掉的话，用户报「诊断页一直显示未知」时就无从查起。
            XposedBridge.logAlways("watchdog state pushed to module app: installed=$installed $describe")
        }.onFailure {
            XposedBridge.logError("watchdog report failed", it)
        }
    }

    /**
     * 模块 App 侧：把收到的状态落进本地 prefs。
     *
     * 落盘而不是只留在内存：模块进程随时可能被回收，界面每次打开都要能显示上次开机的结果。
     *
     * **不写 `KEY_HOOK_DISABLED_BY_WATCHDOG`**：广播只知道「装没装上」，分不清是看门狗熔断
     * 还是 ROM 不兼容（方法被改名、框架缺 PROP_CAP_SYSTEM）。把后者也标成「看门狗熔断」会让
     * 用户去复位一个根本没熔断的东西。熔断是真事时会由 [lastBootDescribe] 里的
     * `disabled=true` 体现，界面据此显示。
     */
    fun persistLocally(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG_STATUS) return
        val installed = intent.getBooleanExtra(EXTRA_INSTALLED, false)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val describe = intent.getStringExtra(EXTRA_DESCRIBE).orEmpty()
        runCatching {
            ExpressSettingsKeys.localPrefs(context).edit()
                .putBoolean(KEY_LAST_BOOT_HOOK_INSTALLED, installed)
                .putString(KEY_LAST_BOOT_REASON, reason)
                .putString(KEY_LAST_BOOT_DESCRIBE, describe)
                .putLong(KEY_LAST_BOOT_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /** 最近一次开机时 hook 是否装上。未收到过任何广播时为 null（未知）。 */
    fun lastBootInstalled(context: Context): Boolean? {
        val prefs = ExpressSettingsKeys.localPrefs(context)
        if (!prefs.contains(KEY_LAST_BOOT_AT)) return null
        return prefs.getBoolean(KEY_LAST_BOOT_HOOK_INSTALLED, false)
    }

    /** 看门狗是否真的熔断了（从 system_server 推来的 describe 里解析）。 */
    fun watchdogTripped(context: Context): Boolean =
        lastBootDescribe(context).contains("disabled=true")

    fun lastBootAt(context: Context): Long =
        ExpressSettingsKeys.localPrefs(context).getLong(KEY_LAST_BOOT_AT, 0L)

    fun lastBootReason(context: Context): String =
        ExpressSettingsKeys.localPrefs(context).getString(KEY_LAST_BOOT_REASON, "").orEmpty()

    fun lastBootDescribe(context: Context): String =
        ExpressSettingsKeys.localPrefs(context).getString(KEY_LAST_BOOT_DESCRIBE, "").orEmpty()

    private const val KEY_LAST_BOOT_HOOK_INSTALLED = "last_boot_hook_installed"
    private const val KEY_LAST_BOOT_REASON = "last_boot_reason"
    private const val KEY_LAST_BOOT_DESCRIBE = "last_boot_describe"
    private const val KEY_LAST_BOOT_AT = "last_boot_at"
}
