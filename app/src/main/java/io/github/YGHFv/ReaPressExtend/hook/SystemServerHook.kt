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

package io.github.YGHFv.ReaPressExtend.hook

import android.app.Notification
import android.app.PendingIntent
import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.ExpressClassifier
import io.github.YGHFv.ReaPressExtend.core.ExpressParser
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressTextExtractor
import io.github.YGHFv.ReaPressExtend.core.ExpressVerdict
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.WatchdogReporter
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import java.lang.reflect.Method

/**
 * system_server 全局通知拦截。挂点选返回 boolean 的 `enqueueNotificationInternal` 重载（Android 14 有
 * 9/10/12 参三个重载，只有 12 参返回 boolean 且所有路径都汇到它，void 没有返回值可改写）。
 * hook 抛异常会把 system_server 打崩进开机循环：hook 体最外层 `runCatching` + `ExceptionMode.PROTECTIVE`
 * 双保险（异常一律退化为放行）、三重递归防护、[Watchdog] 连续两次启动异常就彻底不装。
 */
internal object SystemServerHook {

    private const val METHOD_NAME = "enqueueNotificationInternal"
    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    /** MIUI 不为未启动过的应用冷启进程，只报一次常落空；接收端只是覆写 prefs，重复投递无副作用。 */
    private val BOOT_REPORT_RETRY_DELAYS = longArrayOf(
        20_000L, 60_000L, 150_000L, 300_000L, 600_000L,
    )

    /** 递归防护第三重。用 ThreadLocal：NMS 调用来自多个 Binder 线程，全局标志会互相干扰。 */
    private val processing = ThreadLocal.withInitial { false }

    private val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()

    @Volatile private var bootReportInstalled = false

    /** 观察模式：只记日志不拦截。编译期固定（[BuildConfig.OBSERVE_ONLY]），切成拦截必须重新构建。 */
    val observeOnly: Boolean = BuildConfig.OBSERVE_ONLY

    @Volatile private var seenCount: Int = 0
    @Volatile private var hitCount: Int = 0

    private val missLogTimes = HashMap<String, Long>()

    private const val MISS_LOG_INTERVAL_MILLIS = 10 * 60 * 1000L

    private const val MISS_LOG_MAX_ENTRIES = 200

    /** hook 被调用的首次确认：白名单外的包在 [inspect] 静默返回，「没装上」和「装上了没匹配」靠这行区分。 */
    @Volatile private var firstCallConfirmed = false

    fun install(classLoader: ClassLoader): Boolean {
        val forceEnabled = runCatching {
            XposedBridge.framework()?.let { framework ->
                ExpressSettingsKeys.consumeHookForceEnable(
                    framework.getRemotePreferences(ExpressSettingsKeys.GROUP),
                )
            } ?: false
        }.getOrElse {
            XposedBridge.log("cannot read force-enable flag: ${it.javaClass.simpleName}")
            false
        }
        if (forceEnabled) {
            XposedBridge.logAlways("watchdog: force-enable requested by user, bypassing trip state")
        }

        val decision = Watchdog.beforeInstall(forceEnabled)
        when (decision) {
            is Watchdog.Decision.Refuse -> {
                XposedBridge.logError("system_server hook REFUSED by watchdog: ${decision.reason}")
                reportBootState(installed = false)
                return false
            }
            is Watchdog.Decision.Install -> {
                XposedBridge.log("watchdog: attempt #${decision.attempt} — proceeding to install")
            }
        }

        val nms = try {
            XposedHelpers.findClass(NMS_CLASS, classLoader)
        } catch (t: Throwable) {
            XposedBridge.logError("NMS class not found, hook aborted: ${t.message}")
            return false
        }

        val overloads = nms.declaredMethods.filter { it.name == METHOD_NAME }
        if (overloads.isEmpty()) {
            XposedBridge.logError("no $METHOD_NAME overload found on NMS — ROM may have renamed it")
            return false
        }

        XposedBridge.logAlways(
            "NMS overloads: " + overloads.joinToString { "${it.parameterTypes.size}args->${it.returnType.simpleName}" },
        )

        // 没有 boolean 重载说明 ROM 结构变了，宁可不工作也不瞎挂。
        val suppressible = overloads.filter { it.returnType == java.lang.Boolean.TYPE }
        if (suppressible.isEmpty()) {
            XposedBridge.logError(
                "no boolean-returning $METHOD_NAME overload — interception unavailable on this ROM " +
                    "(found: " + overloads.joinToString { "${it.parameterTypes.size}args->${it.returnType.simpleName}" } + ")",
            )
            return false
        }
        if (suppressible.size > 1) {
            XposedBridge.log("multiple suppressible overloads, hooking all: ${suppressible.size}")
        }

        var installed = 0
        for (method in suppressible) {
            val ok = runCatching { installOne(method) }.getOrElse {
                XposedBridge.logError("hook ${method.parameterTypes.size}-arg overload failed", it)
                false
            }
            if (ok) installed++
        }

        XposedBridge.logAlways(
            "system_server hook installed: $installed/${suppressible.size} suppressible overloads, " +
                "observeOnly=$observeOnly",
        )

        if (installed > 0) scheduleSurvivalMark()
        reportBootState(installed = installed > 0)
        return installed > 0
    }

    /** 不能立刻发：`onSystemServerStarting` 时 `ActivityManagerService` 尚未注册，广播会 NPE（实测），交给延迟任务。 */
    private fun reportBootState(installed: Boolean) {
        bootReportInstalled = installed
        scheduleBootReport()
    }

    private fun scheduleBootReport() {
        val handler = runCatching {
            android.os.Handler(android.os.Looper.getMainLooper())
        }.getOrNull() ?: return

        for (delay in BOOT_REPORT_RETRY_DELAYS) {
            runCatching { handler.postDelayed({ runCatching { flushBootReport() } }, delay) }
        }
    }

    private fun flushBootReport() {
        val context = SystemContextHolder.acquireFromActivityThread()
        if (context == null) {
            XposedBridge.logAlways("boot report deferred: no system context yet")
            return
        }
        SystemWakeRelay.ensureRegistered(context)
        IntentTokenRelay.ensureRegistered(context)
        WatchdogReporter.reportBoot(context, bootReportInstalled, Watchdog.describe())
    }

    private fun installOne(method: Method): Boolean {
        val handle = XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                runCatching {
                    if (processing.get() == true) return
                    processing.set(true)
                    try {
                        inspect(param)
                    } finally {
                        processing.set(false)
                    }
                }.onFailure {
                    XposedBridge.logError("system_server hook body failed (fail-open)", it)
                }
            }
        }) ?: return false

        synchronized(handles) { handles.add(handle) }
        return true
    }

    /** 参数位置来自设备 services.jar 反编译（Android 14）；前面各参数在所有重载一致，按位取安全。 */
    private fun inspect(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val pkg = args.getOrNull(0) as? String ?: return
        val notification = args.getOrNull(6) as? Notification ?: return

        // 模块自己发的通知直接放行，否则「拦截 → 重发 → 又被拦截」无限循环。
        if (pkg == ExpressRelay.MODULE_PACKAGE) return
        if (NotificationExtrasReader.isModuleOrigin(notification)) return

        seenCount++

        if (!firstCallConfirmed) {
            firstCallConfirmed = true
            XposedBridge.logAlways("hook alive: first notification observed (pkg=$pkg)")
        }

        // 先做白名单判定再读设置：读设置是一次跨进程 Binder 调用，这条路径在 NMS 关键流程上。
        if (!isSourcePackage(pkg)) return

        val settings = FrameworkSettingsReader.read()
        if (!settings.isEnabled) return

        val extras = NotificationExtrasReader.read(notification)
        val title = ExpressTextExtractor.extractTitle(extras)
        val text = ExpressTextExtractor.extractFullText(extras)
        if (text.isBlank()) return

        val rule = settings.toRule()
        if (!ExpressClassifier.isSourceAllowed(pkg, rule)) return
        val verdict = ExpressClassifier.classify(pkg, text, rule)

        if (verdict.isExpress) {
            hitCount++
            XposedBridge.logAlways(
                "EXPRESS HIT pkg=$pkg conf=${verdict.confidence} " +
                    "kw=${verdict.matchedKeywords.joinToString(",")} " +
                    "title=${title.take(40)}",
            )
            val dropped = verdict.interceptedCategory
            if (dropped != null) {
                if (observeOnly) return
                if (!reportIntercepted(param, pkg, title, text, verdict, dropped, notification)) {
                    XposedBridge.logError("EXPRESS INTERCEPT SKIPPED category=${dropped.name} (relay unavailable)")
                    return
                }
                param.setResult(false)
                XposedBridge.logAlways(
                    "EXPRESS DROPPED category=${dropped.name} pkg=$pkg " +
                        "text=${text.take(50)}",
                )
            } else if (!observeOnly) {
                deliver(param, pkg, title, text, verdict, settings, contentIntentOf(notification))
            }
        } else {
            logMiss(pkg, verdict, text)
        }
    }

    private fun logMiss(pkg: String, verdict: ExpressVerdict, text: String) {
        val key = "$pkg|${text.take(50)}"
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(missLogTimes) {
            val last = missLogTimes[key]
            if (last != null && now - last < MISS_LOG_INTERVAL_MILLIS) return
            missLogTimes[key] = now
            if (missLogTimes.size > MISS_LOG_MAX_ENTRIES) missLogTimes.clear()
        }
        // `ignored=` 标出「关键词命中但状态规则放行」，否则与「没命中」在日志上同形。
        val ignored = verdict.ignoredStatus?.let { " ignored=${it.displayName}" }.orEmpty()
        XposedBridge.logAlways(
            "EXPRESS MISS pkg=$pkg conf=${verdict.confidence}$ignored text=${text.take(50)}",
        )
    }

    private fun reportIntercepted(
        param: XC_MethodHook.MethodHookParam,
        pkg: String,
        title: String,
        text: String,
        verdict: ExpressVerdict,
        category: NotificationCategory,
        notification: Notification,
    ): Boolean =
        runCatching {
            val contentIntent = contentIntentOf(notification)
            val record = ExpressParser.parse(pkg, title, text, verdict, System.currentTimeMillis())
            ExpressRelaySender.sendIntercepted(
                record = record,
                thisObject = param.thisObject,
                category = category.name,
                contentIntent = contentIntent,
                intentUri = contentIntent?.let { NotificationIntentReader.snapshot(it) },
                intentToken = IntentTokenStore.stash(contentIntent),
            )
        }.onFailure {
            XposedBridge.logError("intercepted report failed", it)
        }.getOrDefault(false)

    private fun contentIntentOf(notification: Notification): PendingIntent? =
        runCatching { notification.contentIntent }.getOrNull()

    private fun isSourcePackage(pkg: String): Boolean = pkg in knownSourcePackages

    /** 候选来源包：必须覆盖 [ExpressClassifier.isSourceAllowed] 可能放行的所有包名，否则早退会挡掉它们。 */
    private val knownSourcePackages: Set<String> = buildSet {
        add("com.cainiao.wireless")
        add("com.xunmeng.pinduoduo")
        add("com.taobao.taobao")
        add("com.android.mms")
        addAll(ExpressSettingsSnapshot.debugAllowedSources)
    }

    /** 投递并按模式决定是否吞掉；拦截必须用 [XC_MethodHook.MethodHookParam.setResult]（它同时置 returnEarly，只改 result 字段原方法照常执行）；先投递再拦截，广播没交出去时不吞。 */
    private fun deliver(
        param: XC_MethodHook.MethodHookParam,
        pkg: String,
        title: String,
        text: String,
        verdict: ExpressVerdict,
        settings: ExpressSettingsSnapshot,
        contentIntent: PendingIntent?,
    ) {
        val record = ExpressParser.parse(pkg, title, text, verdict, System.currentTimeMillis())
        // thisObject 是 NMS 实例，反射取 mContext 是 system_server 拿可用 Context 的唯一可靠途径；跳转给令牌本体 + 可落盘快照两份。
        val handedOff = ExpressRelaySender.send(
            record = record,
            thisObject = param.thisObject,
            contentIntent = contentIntent,
            intentUri = contentIntent?.let { NotificationIntentReader.snapshot(it) },
            intentToken = IntentTokenStore.stash(contentIntent),
        )
        SystemWakeRelay.ensureRegistered(SystemContextHolder.acquire())
        IntentTokenRelay.ensureRegistered(SystemContextHolder.acquire())

        if (!settings.isInterceptMode) {
            XposedBridge.logAlways("EXPRESS PASSTHROUGH key=${record.dedupeKey} (original kept)")
            return
        }
        if (!handedOff) {
            XposedBridge.logError(
                "EXPRESS INTERCEPT SKIPPED key=${record.dedupeKey} " +
                    "(relay handoff failed — original kept, user sees the original notification)",
            )
            return
        }
        param.setResult(false)
        XposedBridge.logAlways("EXPRESS INTERCEPTED key=${record.dedupeKey} (original suppressed)")
    }

    /** 延迟到 [Watchdog.survivalWindowMillis] 之后才标记：崩溃常发生在启动早期，立刻标记就检测不到。 */
    private fun scheduleSurvivalMark() {
        runCatching {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({ runCatching { Watchdog.markBootSurvived() } }, Watchdog.survivalWindowMillis)
        }.onFailure {
            XposedBridge.logError("cannot schedule watchdog survival mark", it)
        }
    }

    fun diagnostics(): String =
        "hooks=${handles.size} seen=$seenCount hits=$hitCount observeOnly=$observeOnly watchdog=${Watchdog.describe()}"
}
