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
import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.ExpressClassifier
import io.github.YGHFv.ReaPressExtend.core.ExpressParser
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressTextExtractor
import io.github.YGHFv.ReaPressExtend.core.ExpressVerdict
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.WatchdogReporter
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import java.lang.reflect.Method

/**
 * system_server 侧的全局通知拦截。
 *
 * ## 挂在哪（关键：看**返回类型**，不是参数个数）
 *
 * Android 14 的 `NotificationManagerService` 有三个同名重载，从设备自己的 `services.jar`
 * 反编译确认，签名与返回类型如下：
 *
 * ```
 * enqueueNotificationInternal(String,String,II,String,I,Notification,I,Z)V          // 9 参，返回 void
 * enqueueNotificationInternal(String,String,II,String,I,Notification,I,Z,Z)V        // 10 参，返回 void
 * enqueueNotificationInternal(String,String,II,String,I,Notification,I,Z,
 *     PostNotificationTracker,Z)Z                                                   // 12 参，返回 boolean
 * ```
 *
 * **只有 12 参那个返回 boolean**，而它正是真正干活的那个。9/10 参只是转发：
 * 10 参拿到 12 参的 boolean 后仅用于决定要不要 `tracker.cancel()`，然后自己返回 void；
 * 9 参再把 10 参包一层，也返回 void。公开的 `NotificationManager.notify()` 走
 * `NotificationManagerService$11.enqueueNotificationWithTag` → 9 参 → 10 参 → 12 参。
 *
 * 由此得出两条结论，都直接影响实现：
 *
 * 1. **拦截只能在 12 参那层做**。void 方法没有返回值可以改写 —— 在 9/10 参上 hook 无法
 *    阻止通知入队，因为调用方根本不看它们的返回值。
 * 2. **只挂 12 参就够了**，因为所有路径最终都会汇到它（已用 dex xref 确认：12 参的调用者
 *    只有 10 参和 `lambda$onStart$0`）。挂 void 的那两个只会白跑一遍判定逻辑。
 *
 * 所以选择规则是「**返回 boolean 的那个**」，而不是「参数最多的那个」——
 * 后者在 ROM 调整参数表时会选错。
 *
 * 返回值语义上也是安全的：NMS 内部 `checkDisqualifyingFeatures` 判false 时就是
 * `return false` 提前退出，我们走的是同一条既有路径，不是凭空造一个状态。
 *
 * ## 安全约束
 *
 * 这是全项目风险最高的代码：hook 抛异常会把 system_server 打崩，进而开机循环。所以：
 * - 每个 hook 体最外层 `runCatching`，任何异常都退化为「放行」
 * - 用 `ExceptionMode.PROTECTIVE` 双保险（见 [XposedBridge.hookMethod]）
 * - 三重递归防护，防止「拦截 → 重发 → 又被拦截」的无限循环
 * - [Watchdog] 熔断：连续两次启动异常就彻底不装
 */
internal object SystemServerHook {

    private const val METHOD_NAME = "enqueueNotificationInternal"
    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    /**
     * 开机状态上报的重试时刻（毫秒）。
     *
     * 不能只报一次：模块 App 常常要等用户点图标才启动，而 MIUI 不会为一个从未启动过的
     * 应用冷启进程（实测：开机 10 秒推的那次落空）。这组间隔覆盖「开机后 20 秒到 10 分钟」
     * 这段窗口，用户只要在十分钟内打开过模块界面，状态就对得上。
     *
     * 接收端只是覆写几个 prefs 字段，重复投递无副作用 —— 所以宁可多推几次。
     */
    private val BOOT_REPORT_RETRY_DELAYS = longArrayOf(
        20_000L, 60_000L, 150_000L, 300_000L, 600_000L,
    )

    /**
     * 递归防护第三重（前两重是 extra 标记与包名判定）。
     *
     * 用 ThreadLocal 而不是全局标志：NMS 的调用来自多个 Binder 线程，全局标志会互相干扰。
     */
    private val processing = ThreadLocal.withInitial { false }

    /** 已挂上的 handle，供诊断与卸载。 */
    private val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()

    /**
     * 本次开机的状态上报结果，由 install 决定。
     */
    @Volatile private var bootReportInstalled = false

    /**
     * 观察模式：只记日志不拦截。**编译期固定**（[BuildConfig.OBSERVE_ONLY]），不是运行时开关 ——
     * 这样它不可能被设置项误开，要切换到拦截必须重新构建一个版本。
     */
    val observeOnly: Boolean = BuildConfig.OBSERVE_ONLY

    /** 诊断计数。 */
    @Volatile private var seenCount: Int = 0
    @Volatile private var hitCount: Int = 0

    /** 未命中日志的节流表：文案 key → 上次记录时刻（elapsedRealtime）。 */
    private val missLogTimes = HashMap<String, Long>()

    /** 同一条未命中文案的最小记录间隔。 */
    private const val MISS_LOG_INTERVAL_MILLIS = 10 * 60 * 1000L

    /** 节流表条目上限，超出即整体清空（正常远达不到）。 */
    private const val MISS_LOG_MAX_ENTRIES = 200

    /**
     * 「hook 确实被调用了」的首次确认。
     *
     * 存在的理由：白名单之外的包在 [inspect] 里静默返回（不打日志，因为系统通知量太大），
     * 于是「hook 没装上」和「装上了但没匹配到」在日志上完全一样 —— 两种情况的排查方向相反。
     * 这一行在**第一条**经过 NMS 的通知上打出来，把两者区分开。
     *
     * 每个进程只打一次，代价可忽略。
     */
    @Volatile private var firstCallConfirmed = false

    fun install(classLoader: ClassLoader): Boolean {
        // 复位请求由界面写入 RemotePreferences，是一次性的：这里读一次就清零。
        // **只在读得到 RemotePreferences 时才可能为 true** —— 读不到（框架无 remote 能力）
        // 时按 false 处理，也就是「不强行安装」，保持熔断保护有效。
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
                // 拒绝安装是**有意的降级**：设备能正常开机，模块不工作。
                // 立刻广播告知模块 App —— 否则用户只会看到「模块莫名其妙不工作了」。
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

        // 选择规则：返回 boolean 的那个。它才是真正干活、且能改写返回值阻止入队的那个。
        // 一个都没有时说明 ROM 结构变了 —— 不挂任何 hook，只记日志（宁可不工作也不要瞎挂）。
        val suppressible = overloads.filter { it.returnType == java.lang.Boolean.TYPE }
        if (suppressible.isEmpty()) {
            XposedBridge.logError(
                "no boolean-returning $METHOD_NAME overload — interception unavailable on this ROM " +
                    "(found: " + overloads.joinToString { "${it.parameterTypes.size}args->${it.returnType.simpleName}" } + ")",
            )
            return false
        }
        if (suppressible.size > 1) {
            // 多个可拦截重载：全挂。它们的入参前缀一致，按位取参仍然安全。
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

    /**
     * 把本次开机的决定推给模块 App。
     *
     * **不能立刻发**：本方法在 `onSystemServerStarting` 里被调用，那时处于
     * `startBootstrapServices`，`ActivityManagerService` 尚未注册到 `ServiceManager` ——
     * 广播会抛 `NullPointerException: IActivityManager on a null object reference`
     * （实测确认）。所以这里只记下结果，真正的投递交给 [scheduleBootReport] 排的延迟任务，
     * 或由 [inspect] 在第一条通知经过时顺手上报（通常更早）。
     *
     * **为什么要重试**：实测「开机 10 秒后推一次」经常落空 —— 模块 App 那时还没启动过
     * （进程是用户点图标才起来的），MIUI 不会为一个 stopped 应用冷启动进程，
     * 哪怕带了 `FLAG_INCLUDE_STOPPED_PACKAGES`。所以排一组递增间隔的重试，
     * 覆盖「用户过一会儿才打开模块」这个常见情形。
     *
     * 重试是安全的：接收端只是覆写几个 prefs 字段，重复投递没有副作用。
     */
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

    /** 实际投递。 */
    private fun flushBootReport() {
        val context = SystemContextHolder.acquireFromActivityThread()
        if (context == null) {
            // 拿不到 context 就等下一次重试（或第一条通知经过时的兜底）。
            XposedBridge.logAlways("boot report deferred: no system context yet")
            return
        }
        WatchdogReporter.reportBoot(context, bootReportInstalled, Watchdog.describe())
    }

    private fun installOne(method: Method): Boolean {
        val handle = XposedBridge.hookMethod(method, object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                // 一切都包住：任何异常都退化为「不干预」，原方法照常执行。
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

    /**
     * 观察一条通知。
     *
     * 参数位置是从设备自己的 `services.jar` 反编译确认的（Android 14）：
     * `[0]=pkg [1]=opPkg [2]=callingUid [3]=callingPid [4]=tag [5]=id [6]=notification [7]=incomingUserId`
     * 前面的参数在所有重载里都一致，所以按位取是安全的。
     */
    private fun inspect(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val pkg = args.getOrNull(0) as? String ?: return
        val notification = args.getOrNull(6) as? Notification ?: return

        // 防护一：模块自己发的通知直接放行，否则「拦截 → 重发 → 又被拦截」无限循环。
        if (pkg == ExpressRelay.MODULE_PACKAGE) return
        if (NotificationExtrasReader.isModuleOrigin(notification)) return

        seenCount++

        // 首次被调用时打一行。这是「hook 装上了」与「装上了但没匹配到」的唯一区分点 ——
        // 两者在日志上都是沉默，但排查方向完全相反。
        if (!firstCallConfirmed) {
            firstCallConfirmed = true
            XposedBridge.logAlways("hook alive: first notification observed (pkg=$pkg)")
        }

        // 顺序要紧：**先做纯字符串的白名单判定，再读设置**。
        // 读设置是一次跨进程 Binder 调用，而这条路径在 NMS 的关键流程上、系统通知量极大 ——
        // 为系统通知打 Binder 纯属浪费。白名单之外的包在这里就返回，连文本都不读。
        if (!isSourcePackage(pkg)) return

        val settings = FrameworkSettingsReader.read()
        // 模块被关掉时直接放行。放在读设置之后、读通知内容之前 ——
        // 「关」是用户的明确意图，连判定都不该做，更不该有投递。
        if (!settings.isEnabled) return

        val extras = NotificationExtrasReader.read(notification)
        val title = ExpressTextExtractor.extractTitle(extras)
        val text = ExpressTextExtractor.extractFullText(extras)
        if (text.isBlank()) return

        val rule = settings.toRule()
        if (!ExpressClassifier.isSourceAllowed(pkg, rule)) return
        val verdict = ExpressClassifier.classify(pkg, text, rule)

        // 白名单内的包才走到这里，量不大，所以两个分支都用 logAlways ——
        // 「命中了」和「没命中」都是排查时想知道的，用普通 log 会被「简洁日志」吞掉，
        // 于是日志上看起来像 hook 根本没跑。
        if (verdict.isExpress) {
            hitCount++
            XposedBridge.logAlways(
                "EXPRESS HIT pkg=$pkg conf=${verdict.confidence} " +
                    "kw=${verdict.matchedKeywords.joinToString(",")} " +
                    "title=${title.take(40)}",
            )
            if (!observeOnly) {
                deliver(param, pkg, title, text, verdict, settings)
            }
        } else {
            logMiss(pkg, verdict, text)
        }
    }

    /**
     * 未命中日志，带节流。
     *
     * 为什么需要节流：短信 App 的前台服务通知（「"短信"正在运行」）会持续重发，
     * 每次经过 hook 都记一行的话，日志会被它刷满 —— 真正要排查的快递事件反而被淹没。
     * 同一条文案在 [MISS_LOG_INTERVAL_MILLIS] 内只记一次。
     *
     * 用上次记录的时间戳而不是「只记一次」：同一段文案隔了很久又出现（比如用户重开了
     * 短信 App）时，仍然想看得到，这能区分「hook 没跑」和「跑过但没命中」。
     */
    private fun logMiss(pkg: String, verdict: ExpressVerdict, text: String) {
        val key = "$pkg|${text.take(50)}"
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(missLogTimes) {
            val last = missLogTimes[key]
            if (last != null && now - last < MISS_LOG_INTERVAL_MILLIS) return
            missLogTimes[key] = now
            // 防止长期运行后 map 无限增长：条目上限远超实际不同文案数，超出就整体清空。
            if (missLogTimes.size > MISS_LOG_MAX_ENTRIES) missLogTimes.clear()
        }
        // 带一行「为什么放行」：`ignored=` 表示关键词其实命中了、是状态规则放的行
        // （见 ExpressClassifier.SILENT_STATUSES）。没有它的话，日志上「揽件通知不拦」
        // 和「关键词没命中」长得一模一样，排查时会往错的方向找。
        val ignored = verdict.ignoredStatus?.let { " ignored=${it.displayName}" }.orEmpty()
        XposedBridge.logAlways(
            "EXPRESS MISS pkg=$pkg conf=${verdict.confidence}$ignored text=${text.take(50)}",
        )
    }

    /**
     * 是否是关心的来源包。
     *
     * 与 [ExpressClassifier.isSourceAllowed] 分开：那个函数带用户的**启用开关**语义
     * （关掉的来源返回 false），这个只判「包名在不在候选集里」。这里用后者做早退，
     * 因为它不依赖设置读取，能挡掉绝大多数系统通知。
     */
    private fun isSourcePackage(pkg: String): Boolean = pkg in knownSourcePackages

    /**
     * 候选来源包。
     *
     * 必须覆盖 [ExpressClassifier.isSourceAllowed] 可能放行的**所有**包名，否则早退会把
     * 本该处理的包挡掉。debug 下 `com.android.shell` 的来源定义与 config 层共用一份
     * （[ExpressSettingsSnapshot.debugAllowedSources]），避免两处各写一份而漂移。
     */
    private val knownSourcePackages: Set<String> = buildSet {
        add("com.cainiao.wireless")
        add("com.xunmeng.pinduoduo")
        add("com.taobao.taobao")
        add("com.android.mms")
        addAll(ExpressSettingsSnapshot.debugAllowedSources)
    }

    /**
     * 阶段 3/4：投递给模块 App 进程，并按模式决定是否吞掉原通知。
     *
     * ## 拦截的返回值语义
     *
     * 返回 `false` 表示「没入队」。NMS 内部本来就有这条路径：`checkDisqualifyingFeatures`
     * 判定不合格时就是 `return false` 提前退出，我们复用它，不是凭空造一个状态。
     *
     * ## 为什么用 setResult 而不是直接改 result 字段
     *
     * [XC_MethodHook.MethodHookParam.setResult] 会同时置 `returnEarly`，适配层看到它就不再
     * 执行原方法、直接把这个值当返回值递出去 —— 这正是「拦截」需要的语义。
     * 只改 result 字段的话原方法照常执行，拦截不会生效。
     *
     * ## 投递与拦截的先后
     *
     * 先投递再拦截：广播是异步的，投递失败（模块 App 收不到）时用户将什么都看不到 ——
     * 这是「静默丢通知」风险。所以实际部署建议先用「放行并附加」模式跑几天
     * （见 UI 上的提示），确认投递链路稳定后再切「拦截并替换」。
     * [com.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog] 会两边都留痕，
     * 便于事后核对。
     */
    private fun deliver(
        param: XC_MethodHook.MethodHookParam,
        pkg: String,
        title: String,
        text: String,
        verdict: ExpressVerdict,
        settings: ExpressSettingsSnapshot,
    ) {
        val record = ExpressParser.parse(pkg, title, text, verdict, System.currentTimeMillis())
        // thisObject 是 NotificationManagerService 实例 —— 从它身上反射取 mContext，
        // 这是 system_server 里拿到可用 Context 的唯一可靠途径（详见 SystemContextHolder）。
        ExpressRelaySender.send(record, param.thisObject)

        if (settings.isInterceptMode) {
            param.setResult(false)
            XposedBridge.logAlways("EXPRESS INTERCEPTED key=${record.dedupeKey} (original suppressed)")
        } else {
            XposedBridge.logAlways("EXPRESS PASSTHROUGH key=${record.dedupeKey} (original kept)")
        }
    }

    /**
     * 排定存活标记。
     *
     * 用 Handler 而不是 Timer：system_server 里已经有 Looper，不必再起线程。
     * 延迟到 [Watchdog.survivalWindowMillis] 之后才标记 —— 崩溃常发生在启动早期，
     * 立刻标记就检测不到了。
     */
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
