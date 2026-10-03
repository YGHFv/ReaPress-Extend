package io.github.YGHFv.ReaPressExtend.backup

import android.content.Context
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.relay.AutoWatch
import io.github.YGHFv.ReaPressExtend.relay.ModuleTraceFetcher
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieStore
import io.github.YGHFv.ReaPressExtend.relay.WatchState

internal object BackupRestoreRuntime {
    fun refreshCaches(context: Context, changed: Set<String>): List<String> = buildList {
        if (TraceCookieStore.PREFS in changed) {
            if (runCatching { TraceCookieCache.reloadAfterRestore(context) }.isFailure) {
                add("登录态缓存重载失败，请重新打开模块")
            }
        }
        if (ModuleTraceFetcher.PREFS in changed) {
            if (runCatching { ModuleTraceFetcher.reloadRiskAfterRestore(context) }.isFailure) {
                add("风控退避状态同步失败，当前进程仍保留已有退避")
            }
        }
        if (ExpressSettingsKeys.LOCAL_PREFS in changed && ExpressSettings.isServiceAvailable()) {
            if (!ExpressSettings.syncToFrameworkNow(context)) {
                add("框架配置同步失败，连接恢复后会再次同步")
            }
        }
    }

    fun resume(context: Context, changed: Set<String>): List<String> = buildList {
        val watchInputs = setOf(ExpressSettingsKeys.LOCAL_PREFS, ExpressRecordStore.PREFS, TraceCookieStore.PREFS, WatchState.PREFS)
        if (changed.any { it in watchInputs }) {
            if (!runCatching { AutoWatch.sync(context, "恢复备份", restart = true) }.getOrDefault(false)) {
                add("自动轮查启停未完成，请回到模块前台重试")
            }
        }
        if (runCatching { BackupScheduler.ensureScheduled(context) }.isFailure) {
            add("备份调度恢复失败，请检查备份设置")
        }
        if (runCatching { ExpressChangeNotifier.notify(context) }.isFailure) {
            add("界面刷新通知失败，请重新打开页面")
        }
    }
}
