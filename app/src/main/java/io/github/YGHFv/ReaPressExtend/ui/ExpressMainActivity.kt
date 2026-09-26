package io.github.YGHFv.ReaPressExtend.ui

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.ReaPressExtend.BuildConfig
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.core.ExpressRule
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.GeoPoint
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationRuleStore
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.HostCredentialRequester
import io.github.YGHFv.ReaPressExtend.relay.HostRefreshRequester
import io.github.YGHFv.ReaPressExtend.relay.HostWakePin
import io.github.YGHFv.ReaPressExtend.relay.ModuleTraceFetcher
import io.github.YGHFv.ReaPressExtend.relay.WatchdogReporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/**
 * 模块主界面。
 *
 * 四页：快递 / 记录 / 设置 / 关于，用 `HorizontalPager` 承载（可横滑切换，也可点底栏）。
 *
 * ## 界面承担的两个不可替代职责
 *
 * 1. **让模块脱离 stopped 状态** —— stopped 应用收不到广播，而拦截事件的投递正是靠广播。
 *    用户装完模块至少打开一次界面，投递链路才成立。
 * 2. **补上运行时的可观测性** —— 模块常年跑在 system_server 和别人的进程里，
 *    没有界面就完全看不出「hook 装上了没、通知发出去了没、被谁拦下了」。
 *
 * ## 外观三个开关（对齐参考实现 ReaMicro-Extend）
 *
 * - **模糊**：顶栏与底栏改走毛玻璃，内容从栏底下穿过并实时模糊
 * - **悬浮底栏**：底栏在标准整条与悬浮胶囊之间切换
 * - **液态玻璃**：悬浮胶囊的玻璃透视（依赖 RuntimeShader，Android 13+）
 *
 * 三者是**递进**关系：液态玻璃只在悬浮底栏开启时有意义，而悬浮底栏与模糊都依赖
 * `isRuntimeShaderSupported()`。所以这里的取值链是 `blurred = 模糊 && 支持`、
 * `liquid = 悬浮 && 液态 && 支持` —— 不支持的设备上开关仍可保存，只是不生效，
 * 界面上也会把开关置灰而不是静默失效。
 *
 * 「界面」那张卡片里还有第四行 **「隐藏后台卡片」**（见 [applyExcludeFromRecents]）：
 * 它改的是**窗口行为**而不是外观（让本模块不出现在系统最近任务里），与上面三个互不影响，
 * 所以不参与那套递进链。
 */
class ExpressMainActivity : ComponentActivity() {

    private lateinit var uiPrefs: ExpressUiPrefs

    /**
     * 主题模式。放在 Activity 上而不是 [setContent] 内部：深浅色要在 **MiuixTheme 之外**
     * 决定（它本身是 theme 的输入），所以必须提升到能包住 `MiuixTheme` 的那一层。
     */
    private val themeModeState = mutableIntStateOf(ExpressUiPrefs.THEME_FOLLOW_SYSTEM)

    /**
     * 「回到前台」计数器。
     *
     * 首页的包裹列表要在 Activity 重新可见时重读 —— 拦截事件是 system_server 广播过来的，
     * 用户多半是「收到通知 → 点开模块」，也就是 Activity 还活着但已离开前台时数据就变了。
     *
     * 用自增计数而不是 `LocalLifecycleOwner` 观察者：后者已废弃（搬到 lifecycle-runtime-compose），
     * 而为一个「重新读一次」引入一个依赖不划算。计数变化触发 LaunchedEffect 重跑，效果一样。
     */
    private val resumeTick = mutableIntStateOf(0)

    /**
     * 上次发唤醒销的时刻（见 [refreshHostOnResume]）。
     *
     * 节流的理由：`onResume` 在**任何**回到前台的时刻都会触发（从通知栏回来、从别的应用切回来、
     * 分屏里点一下），而每一次都会把菜鸟拉起来一次。用户主动打开模块的间隔通常远大于这个值，
     * 所以节流对他没有感知，却挡住了「切来切去把宿主反复拉起」那类无意义的后台唤醒。
     */
    private var lastHostWakeAt = 0L

    /**
     * 上次请宿主重查本地包裹表的时刻（见 [refreshHostOnResume]）。
     *
     * 比唤醒销那条短得多，因为两者代价差着量级：这一条不动进程，只让已经在跑的宿主读一次
     * 它自己的本地库；而它才是「打开模块就看到最新数据」真正经常生效的那一条 ——
     * 宿主常年以推送进程活着时，唤醒销是一记空操作。
     *
     * 一分钟也足够挡住「从身份码弹窗回来」「从驿站页返回」这类同一个使用回合内的重入。
     */
    private var lastHostRefreshAt = 0L

    /**
     * 上一次实际下发的「隐藏后台卡片」取值。**只用来决定日志打不打** ——
     * 那个 flag 每次 `onResume` 都必须重放（理由见 [applyExcludeFromRecents]），
     * 但没有变化时再写一行日志就是纯噪音。
     */
    private var lastExcludeFromRecents: Boolean? = null

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        refreshHostOnResume()
        // flag 记在**任务的根 Intent** 上，用户从后台划掉之后下次是全新任务 —— 每次都要重放。
        applyExcludeFromRecents(uiPrefs.hideFromRecents)
    }

    /**
     * **打开模块时静默刷新快递信息** —— 两条一起发，缺一条就有场景不工作。
     *
     * 模块拿包裹数据的两条路都不完全在自己手里：
     * - 拦截通知是**被动**的（宿主发了推送才有）；
     * - 菜鸟 hook 读的是宿主**本地那张包裹表**，而那张表要菜鸟自己活着才会被刷新。
     *
     * 于是「打开模块 → 数据是新的」中间缺的这一环在这里补上：
     *
     * 1. **唤醒销**（[HostWakePin.wake]）：把菜鸟进程拉起来，好让我们的 hook 装上。
     *    只在进程**不在**时有意义 —— 进程已存活时它是一记空操作。
     * 2. **刷新请求**（[HostRefreshRequester.request]）：请已在运行的宿主**现在**重查一次本地
     *    包裹表。只在进程**已存活**时有意义 —— 那条接收器是动态注册的，不随进程创建而存在，
     *    它叫不醒一个死进程（所以第 1 条不能删）。小米上菜鸟常年以推送进程（`:channel`）
     *    的形式活着，这恰恰是**常态**，所以第 2 条才是经常起作用的那条。
     *
     * 先发销再发请求：万一宿主是死的，销把进程拉起来之后，紧接着这条请求有可能正好落到它
     * 刚注册好的接收器上。反过来就没这个机会了。
     *
     * 宿主的 `Application` 一创建还会自己跑一遍冷启动自查（见 `CainiaoPackageHook`）——
     * 那是第三条路，但它每进程只跑一次，覆盖不了「主进程一直活着」。
     *
     * ⚠️ **这不是「保证刷新」**：关联启动可能被 ROM 拦下、菜鸟可能起来后又立刻被回收，
     * 而广播发出去之后这一层什么都看不到。有没有成功，看模块日志里随后有没有
     * `host self query: rows=N`（宿主读到本地表了）与 `enrichment received`（数据落进模块了）。
     *
     * ⚠️ 也**不是「保证数据是新的」**：宿主自查读的是它**本地库**，那个库要靠宿主自己
     * （首页查询 / 推送 / 小组件）才会收到新包裹。这里解决的是「库里有、没人读」，
     * 不是「服务端有、本地没有」。
     */
    private fun refreshHostOnResume() {
        val now = System.currentTimeMillis()
        if (now - lastHostWakeAt >= HOST_WAKE_MIN_INTERVAL_MS) {
            lastHostWakeAt = now
            HostWakePin.wake(this, "打开模块")
        }
        if (now - lastHostRefreshAt >= HOST_REFRESH_MIN_INTERVAL_MS) {
            lastHostRefreshAt = now
            HostRefreshRequester.request(this)
        }
    }

    /**
     * 「隐藏后台卡片」：把本模块从系统**最近任务（后台）**列表里摘掉 / 放回去。
     *
     * ## 为什么只能运行时做
     *
     * `android:excludeFromRecents`（清单）与 `Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`
     * 都只在**任务根 Activity 启动那一刻**定下来，之后改不了 —— 做成设置里的开关，就只剩
     * `ActivityManager.AppTask#setExcludeFromRecents` 一条路：它改的正是根 Intent 上那个 flag，
     * 任务存活期间随时可改。
     *
     * `getAppTasks()` 返回的是**本应用自己的**任务，官方就是拿它来管自己任务的
     * （老那套 `GET_TASKS` 自 Lollipop 起提权到 signature，第三方拿不到，但它从来只管别人的任务）——
     * 所以这里**不需要任何权限**。
     *
     * ## 为什么每次 onResume 都要重放
     *
     * flag 存在**任务的根 Intent**上。用户在最近任务里划掉本模块 = 任务被销毁，下次从桌面图标
     * 进来是**一个全新任务**，那次启动自然没有这个 flag —— 只在开关变化时设一次的话，
     * 「打开开关 → 划掉 → 再进来」这条最常见的路径就失效了。代价是每次回到前台两次
     * Binder 调用（`appTasks` + `setExcludeFromRecents`），可以忽略。
     *
     * ⚠️ 失败一律吞掉：`getAppTasks()` 在个别老 ROM 上可能给空列表，而这只是个**体验开关**，
     * 不该因为它让界面起不来。真出问题会在「模块日志」里留一行。
     */
    private fun applyExcludeFromRecents(exclude: Boolean) {
        val changed = lastExcludeFromRecents != exclude
        lastExcludeFromRecents = exclude
        runCatching {
            val manager = getSystemService(ActivityManager::class.java) ?: return
            val tasks = manager.appTasks
            // 按 taskId 认自己那一个；`getTaskInfo()` 在 SDK 里标了 `@Nullable`（任务刚消失时
            // 会给 null），所以用安全调用、认不到就退回「列表里只有一个任务」的情形 ——
            // 本应用只有 ExpressMainActivity 一个 Activity，正常情况下面这个列表就只有一条。
            val task = tasks.firstOrNull { it.taskInfo?.taskId == taskId } ?: tasks.firstOrNull()
            if (task == null) {
                if (changed) {
                    ModuleAndroidLog.legacy(LOG_TAG, "exclude from recents: 拿不到本应用的任务，跳过")
                }
            } else {
                task.setExcludeFromRecents(exclude)
                if (changed) {
                    ModuleAndroidLog.legacy(LOG_TAG, "exclude from recents: $exclude")
                }
            }
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "exclude from recents failed", it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ModuleLogBuffer.attach(this)
        super.onCreate(savedInstanceState)
        uiPrefs = ExpressUiPrefs.of(this)
        themeModeState.intValue = uiPrefs.themeMode
        applyWindowBackground()
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")
        requestNotificationPermissionIfNeeded()
        requestHostLoginState()

        setContent {
            // 手动模式优先，否则跟随系统。用 isSystemInDarkTheme() 而不是读 configuration，
            // 这样系统切换深浅色时 Compose 会重组，不必重启 Activity。
            val dark = when (themeModeState.intValue) {
                ExpressUiPrefs.THEME_LIGHT -> false
                ExpressUiPrefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            ImmersiveSystemBars(dark)
            SystemBarAppearance(dark)
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                ExpressApp(
                    uiPrefs = uiPrefs,
                    themeMode = themeModeState.intValue,
                    resumeTick = resumeTick.intValue,
                    onThemeModeChange = { mode ->
                        themeModeState.intValue = mode
                        uiPrefs.themeMode = mode
                        // 窗口底色要一起改，否则切到夜间时状态栏底下还是浅色。
                        applyWindowBackground()
                    },
                    // 提升到 Activity 这一层：开关的后果（改任务根 Intent 上的 flag）只有
                    // Activity 做得到，Compose 里拿不到 `taskId`。
                    onHideFromRecentsChange = { exclude ->
                        uiPrefs.hideFromRecents = exclude
                        applyExcludeFromRecents(exclude)
                    },
                )
            }
        }
    }

    /**
     * 系统栏沉浸（含小白条）。
     *
     * 三条缺一不可：
     * ① 必须放在 `setContent` 里、随深浅色重跑，才能赶在窗口真正建立之后生效 ——
     *    在 `onCreate` 顶部调一次会被随后的窗口初始化吃掉，结果是「半沉浸」：
     *    状态栏贴到了顶，**导航栏那几十 px 却仍然占着布局**；
     * ② `navigationBarStyle` 必须显式传，否则导航栏不参与 edge-to-edge；
     * ③ `isNavigationBarContrastEnforced = false` —— 系统默认会给导航栏叠一层对比度 scrim，
     *    这层 scrim 才是「底栏下面一条更亮的白带」的真身，也是「设了透明却没变化」的原因
     *    （透明只是让 scrim 露出来）。
     */
    @Composable
    private fun ImmersiveSystemBars(dark: Boolean) {
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark },
                navigationBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            onDispose { }
        }
    }

    /** 系统栏图标与页面底色相反（沉浸后状态栏、小白条都直接压在页面底色上）。 */
    @Composable
    private fun SystemBarAppearance(dark: Boolean) {
        LaunchedEffect(dark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    // 状态栏与导航栏一起处理：只改状态栏会让小白条上的图标变白→看不见。
                    val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightMask, lightMask)
                }
            }
        }
    }

    private fun applyWindowBackground() {
        // 窗口底色由 Activity 直接持有，不依赖 Compose 重组 —— 首帧之前系统栏区域显示的就是它。
        val dark = when (themeModeState.intValue) {
            ExpressUiPrefs.THEME_LIGHT -> false
            ExpressUiPrefs.THEME_DARK -> true
            else -> isNightMode()
        }
        window?.setBackgroundDrawable(
            ColorDrawable(if (dark) DARK_WINDOW_BG else LIGHT_WINDOW_BG),
        )
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 申请通知权限。
     *
     * **必须由 Activity 发起**：`requestPermissions` 只有 Activity 能调，接收器不行。
     * 而模块的通知正是从接收器发出去的 —— 那里的失败会静默（用户什么都收不到），
     * 所以在这里补上这个环节。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "request notification permission failed", it)
        }
    }

    /**
     * 模块一打开就向宿主索要一次淘宝登录态（发完即忘，不等待、不阻塞界面）。
     *
     * 「最好不打开菜鸟也能获取快递信息」卡在一个时序上：登录态只存在菜鸟的私有目录里，
     * 而宿主主动投递它的时机是「首页刷新」—— 用户「收到通知 → 点开模块」时，菜鸟很可能
     * 早被系统清掉了，那一屏的轨迹就还是空的。补一次主动索要，宿主只要还活着就会回。
     *
     * **尽力而为**：宿主进程不在时系统会静默丢弃广播（小米上的实测行为），送不到也没关系 ——
     * 下一次富化到达时宿主自己还会补一次，详情页那次也有兜底。模块到底有没有拿到登录态，
     * 看日志里是 `cookie synced` 还是 `cookie sync 收到宿主回执`。
     */
    private fun requestHostLoginState() {
        // 菜鸟和淘宝都要问一次：凭据在哪一边事先不知道（菜鸟没绑淘宝账号时，只有淘宝 App 里有）。
        // 两个包、重复投递、超时降级这些取舍都在 [HostCredentialRequester.requestFromHosts] 里。
        //
        // ⚠️ 这条只服务于**轨迹**：身份码从 2026-09-26 起不再要登录态，改成请菜鸟用自己的会话
        // 现取（见 [IdentityCodeFetcher]）—— 那个接口在 H5 通道上根本打不动。
        HostCredentialRequester.requestFromHosts(this)
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
        const val REQUEST_CODE = 4201

        /**
         * 两次「打开模块叫醒菜鸟」之间的最小间隔。
         *
         * 5 分钟：短到「用户从驿站回来重新打开」必然触发，长到「反复切前后台」不会把宿主
         * 一次次拉起来。这个值只影响「多久拉一次进程」，不影响正确性 —— 唤醒是幂等的，
         * 没叫醒也只是一次没有后续的空操作（真正干活的是 [HOST_REFRESH_MIN_INTERVAL_MS] 那条）。
         */
        const val HOST_WAKE_MIN_INTERVAL_MS = 5 * 60_000L

        /**
         * 两次「请宿主重查本地包裹表」之间的最小间隔。
         *
         * 1 分钟，比唤醒销那条短得多：这条不动进程，只让已经在跑的宿主读一次它自己的本地库，
         * 代价差着量级；而宿主常年以推送进程活着时，它才是真正让「打开模块就有新数据」成立的
         * 那一条。一分钟足够挡住同一回合内的重入（从身份码弹窗回来、从驿站页返回）。
         */
        const val HOST_REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}

/**
 * 四个页签。索引与 [TAB_TITLES] / [TAB_ICONS] 一一对应。
 *
 * 快递放第一页而不是设置：这是模块的主功能，用户打开界面十有八九是来看包裹的。
 */
private const val TAB_HOME = 0
private const val TAB_RECORDS = 1
private const val TAB_SETTINGS = 2
private const val TAB_ABOUT = 3

private val TAB_TITLES = listOf("快递", "记录", "设置", "关于")
private val TAB_ICONS: List<ImageVector> = listOf(
    MiuixIcons.Home,
    MiuixIcons.Recent,
    MiuixIcons.Settings,
    MiuixIcons.Info,
)

@Composable
private fun ExpressApp(
    uiPrefs: ExpressUiPrefs,
    themeMode: Int,
    resumeTick: Int,
    onThemeModeChange: (Int) -> Unit,
    /**
     * 「隐藏后台卡片」被切换。**必须由 Activity 实现**（它才有 `taskId` 去改任务的根 Intent），
     * 所以和 [onThemeModeChange] 一样是提升上来的回调，而不是就地写 prefs。
     */
    onHideFromRecentsChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 外观状态提升到这里：设置页改它，顶栏/底栏读它，改完要立刻重绘。
    var blurBars by remember { mutableStateOf(uiPrefs.blurBars) }
    var floatingNavBar by remember { mutableStateOf(uiPrefs.floatingNavBar) }
    var liquidGlass by remember { mutableStateOf(uiPrefs.liquidGlass) }
    // 双击确认取件。和上面三个一样只影响本机界面，所以也走 uiPrefs 而不是功能设置。
    var doubleTapPickup by remember { mutableStateOf(uiPrefs.doubleTapPickup) }
    // 「隐藏后台卡片」。初值取自 prefs —— Activity 的 onResume 已经按它设过一次 flag
    // （那条路覆盖「划掉后台再进来」的情景），这里只是让开关的显示跟生效的那份取值对上。
    var hideFromRecents by remember { mutableStateOf(uiPrefs.hideFromRecents) }

    // 功能设置（拦截开关等）。与外观分开：那份要投影给 system_server，这份不用。
    var settings by remember { mutableStateOf(ExpressSettings.read(context)) }
    fun updateSettings(block: (ExpressSettingsSnapshot) -> ExpressSettingsSnapshot) {
        val next = block(settings)
        ExpressSettings.write(context, next)
        settings = next
    }

    // 首页的包裹列表。拦截事件由 system_server 广播到本进程，而用户多半是「收到通知 →
    // 点开模块」—— 也就是 Activity 还活着但已离开前台时数据就变了。
    // 所以 [resumeTick] 每次 onResume 自增，这里随之重读；切回首页标签时也重读一次。
    var homeRecords by remember { mutableStateOf(ExpressRecordStore.load(context)) }
    // 记录页那份投递审计。提到这一层是因为「下拉刷新」要能就地重读它（见下面的 refresh）。
    var recordEntries by remember { mutableStateOf(ExpressNotificationLog.snapshot(context)) }
    LaunchedEffect(resumeTick) {
        homeRecords = ExpressRecordStore.load(context)
        recordEntries = ExpressNotificationLog.snapshot(context)
    }

    /**
     * 双击卡片那**一行**：写/清「已取件」标记，然后重读列表。
     *
     * 重读而不是就地改内存里的那一条：这个标记的可见后果是**分组级**的（同一地点的包裹全部
     * 确认后要整批移出「到站包裹」），那是 [io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper]
     * 拿全量列表算出来的 —— 界面上手改一条只会让卡片显示和分组对不上。
     * 列表上限 200 条、单人包裹量级，重读的代价可以忽略。
     */
    val togglePickup: (ExpressRecord) -> Unit = { record ->
        val marking = !record.isPickedUp
        val changed = ExpressRecordStore.setPickedUp(
            context,
            record.dedupeKey,
            // 非 null = 标记为「此刻取走」；null = 撤销。时间戳只用来记录，不参与判定。
            if (marking) System.currentTimeMillis() else null,
        )
        if (changed) homeRecords = ExpressRecordStore.load(context)
    }

    // 下拉刷新的指示器状态。两页各自一份：一边正在刷时不该把另一边的圈也叫出来。
    var homeRefreshing by remember { mutableStateOf(false) }
    var recordRefreshing by remember { mutableStateOf(false) }

    /**
     * 下拉刷新的公共流程：置位指示器 → 重读 → 保证指示器至少显示 [MIN_REFRESH_VISIBLE_MS] → 收位。
     *
     * 「至少显示」这一段是必要的：重读是本地 SharedPreferences 的同步读取，几毫秒就完了，
     * 指示器会在松手的同一帧收回去，看起来像「下拉没有反应」。兜一下是为了让这次刷新**可见**，
     * 不是为了假装在忙 —— 所以取的是一个刚好能看清的值，不是一秒的假进度。
     */
    fun refresh(reload: () -> Unit, setRefreshing: (Boolean) -> Unit) {
        scope.launch {
            setRefreshing(true)
            val started = System.currentTimeMillis()
            reload()
            val spent = System.currentTimeMillis() - started
            if (spent < MIN_REFRESH_VISIBLE_MS) delay(MIN_REFRESH_VISIBLE_MS - spent)
            setRefreshing(false)
        }
    }

    val onHomeRefresh: () -> Unit = {
        refresh({ homeRecords = ExpressRecordStore.load(context) }) { homeRefreshing = it }
    }
    val onRecordRefresh: () -> Unit = {
        refresh({ recordEntries = ExpressNotificationLog.snapshot(context) }) { recordRefreshing = it }
    }

    // 二级页（模块日志）。打开时整页替换掉主界面：日志页自带顶栏与返回，
    // 底栏那四个页签在这一层没有意义，留着只会让人以为还能往左右滑。
    var logPageOpen by remember { mutableStateOf(false) }

    // 驿站管理（合并 / 改外显名）的规则，以及那个二级页面开没开。
    // 规则改完立刻重读一遍：首页分组是拿它现算的，不重读的话名字改了但卡片没动。
    var stationRules by remember { mutableStateOf(ExpressStationRuleStore.load(context)) }
    var stationAdminOpen by remember { mutableStateOf(false) }

    // 「归档快递」二级页（首页最下面那一行进来的）。只看不写，所以这里没有配套的写入侧状态。
    var archiveOpen by remember { mutableStateOf(false) }

    // 「获取当前位置」这一次动作的状态，以及**正在等授权的那一行**。
    // 授权是异步的（用户可能过几秒才点「允许」），回调里没有别的办法知道「谁在等」——
    // 所以必须在发起申请的时候就把 keys 存下来。
    var stationCapture by remember { mutableStateOf<StationCaptureState?>(null) }
    var pendingCaptureKeys by remember { mutableStateOf<List<String>?>(null) }

    /**
     * 采一次现场（定位 + 附近 WiFi），落到这一行的规则上。
     *
     * 三样东西分开处置，因为它们**各自都可能缺**，而缺了要如实说：
     * - 位置 → 写进指纹；这是这项功能的主产物；
     * - WiFi → 写进指纹（同一个对象），空列表照样写（「这站没记到 AP」本身是信息）；
     * - 地址文本 → 反查得到就用系统的，拿不到退回坐标串。写的是一个**普通字符串**，
     *   所以包裹详情页读地址那一路（`stationAddressOf`）一行都不用改。
     *
     * 采集期间再点按钮由界面挡掉（见 `StationCaptureState.busy`）：并发两次采集的话，
     * 后一次会把前一次的指纹覆盖掉，而用户看到的是「点了没反应却记了个别的地方的坐标」。
     */
    fun captureStation(keys: List<String>) {
        val key = keys.firstOrNull() ?: return
        scope.launch {
            stationCapture = StationCaptureState(key, busy = true)
            val capture = StationLocator.capture(context)
            if (capture.permissionMissing) {
                // 交给 launcher 去弹框，授权回来后再走一遍这条路（[pendingCaptureKeys]）。
                pendingCaptureKeys = keys
                stationCapture = StationCaptureState(key, busy = false, note = PERMISSION_HINT)
                return@launch
            }
            val fingerprint = capture.fingerprint
            if (fingerprint.isEmpty) {
                // 一样都没采到就**不写**（写入侧也会拒绝空指纹）：写进去只会在界面上显示成
                // 「已记录」，而实际上什么都判不了 —— 那比留着「未记录」更误导。
                stationCapture = StationCaptureState(key, busy = false, note = EMPTY_CAPTURE_HINT)
                return@launch
            }
            ExpressStationRuleStore.setFingerprint(context, keys, fingerprint)
            ExpressStationRuleStore.setAddress(
                context,
                keys,
                capture.addressText ?: coordinatesOf(fingerprint.position),
            )
            stationRules = ExpressStationRuleStore.load(context)
            stationCapture = StationCaptureState(key, busy = false, note = captureNote(capture))
        }
    }

    /**
     * 定位 / WiFi 权限的申请。
     *
     * 申请**两份**：精确定位是采坐标必需（粗定位分不开相邻两个驿站），
     * Android 13 起读 WiFi 扫描结果还要 NEARBY_WIFI_DEVICES。
     *
     * 只要**任意一项**被允许就继续采：WiFi 没批下来时定位照样能记，反过来也一样 ——
     * 半份数据比一份都没有强，缺的那一半由 [captureNote] 照实说出来。
     */
    val stationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val keys = pendingCaptureKeys ?: return@rememberLauncherForActivityResult
        pendingCaptureKeys = null
        if (granted.values.any { it }) {
            captureStation(keys)
        } else {
            stationCapture = StationCaptureState(
                key = keys.firstOrNull().orEmpty(),
                busy = false,
                note = PERMISSION_DENIED_HINT,
            )
        }
    }

    // 包裹详情页（全轨迹 / 驿站完整地址 / 商品图）。存的是**记录快照**而不是它的 dedupeKey：
    // 富化把通知里截断的尾号补成全号时 dedupeKey 会跟着变（它是 `tn:`/`pc:`/`raw:` 拼出来的），
    // 只存 key 的话那一刻详情页会凭空关掉。渲染时再拿快照去最新列表里配一份更全的。
    var detailRecord by remember { mutableStateOf<ExpressRecord?>(null) }
    // 详情页自己的刷新指示器状态（与 homeRefreshing / recordRefreshing 同一套约定）。
    var detailRefreshing by remember { mutableStateOf(false) }

    // 身份码弹窗（首页右上角那个按钮）。
    var identityOpen by remember { mutableStateOf(false) }
    // 定位权限申请的结果计数器：授权回来后自增，弹窗随之重算一次「最近驿站」。
    // 没有它的话，用户在系统弹框里点「允许」之后，当前这一屏还会停在「最近有来件的那个」。
    var locationTick by remember { mutableIntStateOf(0) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { locationTick++ }

    /**
     * 按需拉取当前包裹的全轨迹（「点击时获取」模式）。
     *
     * 优先走模块进程静默拉（[ModuleTraceFetcher.onDemand]，cookie 在内存缓存里就行，
     * **不依赖菜鸟活着**）；没有缓存 cookie 才广播给菜鸟进程兜底 —— 那边拉到后照旧
     * 经 ACTION_ENRICH 落库，且会顺带把 cookie 同步过来，下次就走本进程了。
     *
     * 结果何时到不归这里管：`ACTION_TRACE_ARRIVED` 广播（本地重读收位）或 [DETAIL_REFRESH_TIMEOUT_MS]
     * 超时，两条路都会结束刷新指示器。请求被闸门（成功表 / 风控退避）挡下时静默 ——
     * 详情页显示的「暂无物流轨迹」就是它的降级表现。
     */
    fun requestTraceForDetail(record: ExpressRecord) {
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() } ?: return
        val served = ModuleTraceFetcher.onDemand(context, tracking)
        if (!served) {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_TRACE_REQUEST)
                    .setPackage(ExpressRelay.HOST_PACKAGE)
                    .putExtra(ExpressRelay.EXTRA_TRACE_TRACKING, tracking),
            )
        }
        // 超时收位兜底：正常路径是 ARRIVED 广播提前收位；广播没来（菜鸟不在、闸门挡下）
        // 时指示器不能永远转着。
        scope.launch { delay(DETAIL_REFRESH_TIMEOUT_MS); detailRefreshing = false }
    }

    val onDetailRefresh: () -> Unit = {
        // 先重读一遍本地：轨迹落库是异步的，刷新时把已经到的先显示出来。
        homeRecords = ExpressRecordStore.load(context)
        if (ExpressSettings.read(context).traceFetchMode == ExpressSettingsKeys.MODE_TRACE_ON_DEMAND) {
            // 「点击时获取」模式才主动发请求；「自动更新」模式由富化链路自己拉。
            detailRecord?.let(::requestTraceForDetail)
        }
        detailRefreshing = true
    }

    // 运行时不支持 RuntimeShader（Android 13 以下）时，模糊与液态玻璃都没有效果。
    // 不隐藏开关而是置灰：用户能看到这些功能存在、知道为什么现在用不了。
    val blurSupported = remember { isRuntimeShaderSupported() }
    val blurred = blurBars && blurSupported
    val floating = floatingNavBar
    val liquid = floating && liquidGlass && blurSupported

    val pagerState = rememberPagerState(initialPage = TAB_HOME, pageCount = { TAB_TITLES.size })
    val scrollBehaviors = List(TAB_TITLES.size) { MiuixScrollBehavior() }
    // 每个页签各自的滚动位置。**必须建在这一层** —— 也就是所有二级页的提前 return **之前**：
    // 详情 / 归档 / 驿站管理 / 日志都是整页替换主界面的（见下面几处 `return`），主界面从组合树里
    // 摘掉之后，原先写在 `scrollContent` 里的 `rememberScrollState()` 就跟着没了 ——
    // 用户从详情返回时列表会跳回顶部，包裹一多就等于每次都要重新滚一遍（2026-09-26 用户报的）。
    // 提升到这一层之后，二级页期间这些 ScrollState 一直活着，返回时按原偏移重新挂上。
    //
    // 放在 `List(...)` 的 init 里调 remember 是安全的：次数恒等于页签数，重组之间一一对应。
    val pageScrollStates = List(TAB_TITLES.size) { rememberScrollState() }
    val currentPage = pagerState.currentPage.coerceIn(0, TAB_TITLES.lastIndex)
    val scrollBehavior = scrollBehaviors[currentPage]

    val surface = MiuixTheme.colorScheme.surface
    // backdrop 捕获页面内容，供顶栏/底栏的毛玻璃采样。drawRect(surface) 是兜底底色，
    // 没有它的话内容还没绘制时玻璃层会采到透明。
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val barBlurColors = BlurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = BAR_TINT_ALPHA))),
    )

    LaunchedEffect(pagerState.settledPage) {
        // 切回首页时重读包裹列表：拦截事件到达时模块进程可能不在前台，
        // 界面上的列表要能反映最新状态。
        if (pagerState.settledPage == TAB_HOME) {
            homeRecords = ExpressRecordStore.load(context)
        }
        scrollBehaviors.forEach { it.state.heightOffset = 0f }
    }

    val animateToTab: (Int) -> Unit = { index ->
        scope.launch { pagerState.springAnimateToPage(index) }
    }

    // 二级页整页替换主界面，不叠在上面：日志页自带顶栏（含返回），底栏那四个页签在
    // 这一层没有意义。用提前 return 而不是 if/else 包住整个 Scaffold ——
    // 前面那些 remember 都是无条件的，早退不会让 Compose 的槽位错位。
    if (logPageOpen) {
        LogPage(onBack = { logPageOpen = false })
        return
    }

    // 驿站管理同样是二级页。传的是**全量**记录而不是首页算出来的分组：管理页要能管到
    // 所有驿站（包括只有已签收包裹的那些），否则用户想合并两个名字时，可能因为其中一个
    // 暂时没有待取件而在列表里找不到它。
    if (stationAdminOpen) {
        StationAdminPage(
            records = homeRecords,
            rules = stationRules,
            // 几个写入口都收**一组**键（整行的 ruleKeys）：合并过来的那一行只有一个主键，
            // 但链上还挂着别的键，只动主键会让同一行当场裂回两行。每次改完重读一遍，
            // 首页分组是拿规则现算的，不重读的话名字改了但卡片没动。
            onRename = { keys, display ->
                ExpressStationRuleStore.setRename(context, keys, display)
                stationRules = ExpressStationRuleStore.load(context)
            },
            onIdentitySource = { keys, source ->
                ExpressStationRuleStore.setIdentitySource(context, keys, source)
                stationRules = ExpressStationRuleStore.load(context)
            },
            // 「精确地址」不再手填，改成**站在驿站门口点一次「获取当前位置」**：
            // 地址和位置指纹都由这一次采集产生（见 captureStation）。
            onCaptureLocation = ::captureStation,
            onRestore = { keys ->
                ExpressStationRuleStore.clearRules(context, keys)
                stationRules = ExpressStationRuleStore.load(context)
            },
            capture = stationCapture,
            onBack = { stationAdminOpen = false },
        )
        return
    }

    // 包裹详情同样是二级页。整页替换而不是弹窗：一屏轨迹能到二三十行，弹窗里滚不动。
    val detail = detailRecord
    if (detail != null) {
        // 「点击时获取」模式：每次进详情都发一次拉取请求。重复请求由引擎的闸门去重
        // （成功过的单号直接跳过），这里不用记「上次拉过谁」。
        LaunchedEffect(detail) {
            if (ExpressSettings.read(context).traceFetchMode ==
                ExpressSettingsKeys.MODE_TRACE_ON_DEMAND
            ) {
                requestTraceForDetail(detail)
            }
        }

        // 拉取结果落库后（本进程直拉或菜鸟兜底回来的）会发内部广播 —— 收到就重读，
        // 详情页上的轨迹 / 地址 / 商品图原地更新。二选一收位：这里提前收，
        // 或者 requestTraceForDetail 里的超时兜底。
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    homeRecords = ExpressRecordStore.load(context)
                    detailRefreshing = false
                }
            }
            val filter = IntentFilter(ExpressRelay.ACTION_TRACE_ARRIVED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            onDispose { context.unregisterReceiver(receiver) }
        }

        // 宿主自查那一批灌完之后的重读（见 ACTION_RECORDS_CHANGED 的注释）。
        //
        // 与上面那条**必须分开注册**：那个的接收体里还有 `detailRefreshing = false`，
        // 而这条消息到达时详情页可能正等着一单的轨迹 —— 被提前收掉指示器，
        // 用户看到的是「暂无物流轨迹」，但其实还在拉。
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    homeRecords = ExpressRecordStore.load(context)
                }
            }
            val filter = IntentFilter(ExpressRelay.ACTION_RECORDS_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            onDispose { context.unregisterReceiver(receiver) }
        }

        // 在有同样内容的那条记录里挑**最新**的一份：详情页开着的时候，轨迹富化可能刚落到
        // 存储里、主列表刚被重读过。用 isSamePackageAs 而不是 dedupeKey 相等，正是为了兜住
        // 「运单号从尾号补成全号」这种主键发生变化的合并 —— 否则那一刻会退回旧快照，
        // 详情页上刚拉到的轨迹看不见。
        val latest = homeRecords.firstOrNull { it.isSamePackageAs(detail) } ?: detail
        val labels = remember(homeRecords, stationRules) {
            ExpressHomeGrouper.stationLabels(homeRecords, stationRules)
        }
        // 轨迹空着时，把**真实原因**透给用户 —— 干等到超时再显示「请确认菜鸟在后台」
        // 是误导：用户报的「怎么都获取不了」，八成根本不是菜鸟没在跑。
        // 计算很便宜（几个 volatile 读），不做 remember。
        val traceHint = if (CainiaoTraceApi.riskBlocked()) {
            val minutes =
                ((CainiaoTraceApi.riskBlockedUntil - System.currentTimeMillis()) / 60_000L)
                    .coerceAtLeast(1)
            "拉取被淘宝风控暂时拦住（按请求频率保护），约 $minutes 分钟后下拉重试。"
        } else {
            // 没取到就用最近一次失败的原因（`CainiaoTraceApi.lastError`：没有登录态 /
            // 解析不出结果 / 接口形状变了…）。
            //
            // ⚠️ 它是**进程级**的「最近一次」，不按运单号分。放在这里仍然是对的：本页只在
            // `record.trace` **完全为空**时才用这句，而拉取是串行单线程、刚被本页触发过 ——
            // 「最近一次失败」就是关于这条件的、或者是最接近真相的那句话。
            // 要按件区分就得再引一层按单号的错误表，收益远小于复杂度。
            CainiaoTraceApi.lastError
        }
        ExpressDetailPage(
            record = latest,
            stationLabel = ExpressHomeGrouper.stationLabelOf(latest, labels),
            rules = stationRules,
            isRefreshing = detailRefreshing,
            onRefresh = onDetailRefresh,
            onBack = { detailRecord = null },
            traceHint = traceHint,
        )
        return
    }

    // 「归档快递」二级页（首页最下面那一行进来的）。
    //
    // 它**排在详情判定之后**，这是有意的：从归档页点进某个包裹时 `detailRecord` 被设上、
    // `archiveOpen` 仍是 true，两个条件同时成立 —— 详情先判定就赢了，用户看到的是详情页；
    // 详情返回（`detailRecord = null`）之后又自然落回**归档页**，而不是被一脚踢回首页。
    // 反过来放（归档在详情之前）就必须在打开详情时手动把 archiveOpen 清掉，那等于丢掉返回位置。
    if (archiveOpen) {
        ExpressArchivePage(
            // 传**全量**记录：归档页自己筛（聚类要拿完整集合做，理由见那边的 @param）。
            records = homeRecords,
            rules = stationRules,
            onBack = { archiveOpen = false },
            onOpenDetail = { record -> detailRecord = record },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = TAB_TITLES[currentPage],
                largeTitle = TAB_TITLES[currentPage],
                scrollBehavior = scrollBehavior,
                color = if (blurred) Color.Transparent else surface,
                modifier = if (blurred) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = BAR_BLUR_RADIUS,
                        colors = barBlurColors,
                    )
                } else {
                    Modifier
                },
                // 身份码入口只挂在首页：它回答的是「站在驿站门口要念什么」，
                // 与设置 / 记录 / 关于三页无关，摆在别的页上只会让人以为它跟那一页有关系。
                actions = {
                    if (currentPage == TAB_HOME) {
                        IconButton(
                            onClick = { identityOpen = true },
                            // 与返回箭头同一条规矩：顶栏上的图标按钮不铺胶囊底色。
                            backgroundColor = Color.Transparent,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Scan,
                                contentDescription = "身份码",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (floating) {
                // 悬浮胶囊：底栏脱离屏幕边缘浮起来，页面内容从它底下穿过。
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .padding(bottom = if (navInset > 0.dp) 8.dp + navInset else 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingBottomBar(
                        selectedIndex = currentPage,
                        onSelected = animateToTab,
                        backdrop = backdrop,
                        tabsCount = TAB_ICONS.size,
                        isBlurEnabled = liquid,
                    ) { activateTab ->
                        TAB_ICONS.forEachIndexed { index, icon ->
                            FloatingBottomBarItem(
                                selected = currentPage == index,
                                onClick = { activateTab(index) },
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = TAB_TITLES[index],
                                    tint = top.yukonga.miuix.kmp.theme.LocalContentColor.current,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = TAB_TITLES[index],
                                    color = top.yukonga.miuix.kmp.theme.LocalContentColor.current,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            } else {
                NavigationBar(
                    color = if (blurred) Color.Transparent else surface,
                    modifier = if (blurred) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = BAR_BLUR_RADIUS,
                            colors = barBlurColors,
                        )
                    } else {
                        Modifier
                    },
                ) {
                    TAB_ICONS.forEachIndexed { index, icon ->
                        NavigationBarItem(
                            selected = currentPage == index,
                            onClick = { animateToTab(index) },
                            icon = icon,
                            label = TAB_TITLES[index],
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            modifier = Modifier
                // 横滑拦截。**必须同时把 pager 自己的 userScrollEnabled 关掉** ——
                // 两套手势同时生效时，override 会在 Initial 阶段把点击也吃掉，
                // 结果是页面里所有按钮（开关、下拉、按钮）全部点不动。
                // 参考实现里这一条是 `userScrollEnabled && !interceptPager`，正是此意。
                .pagerGestureOverride(
                    pagerState = pagerState,
                    mode = PagerInterceptionMode.CrossAxisInterceptor,
                    enabled = true,
                )
                // 内容挂在 backdrop 上，顶栏/底栏的玻璃才能采到它。
                .then(if (blurred || floating) Modifier.layerBackdrop(backdrop) else Modifier),
            state = pagerState,
            beyondViewportPageCount = 1,
            // 滚动交给上面的 override，pager 自己不再处理手势。
            userScrollEnabled = false,
            overscrollEffect = null,
            pageNestedScrollConnection = PagerGestureNestedScrollConnection,
            flingBehavior = flingBehavior(
                state = pagerState,
                snapAnimationSpec = PagerNavigationSpringSpec,
            ),
        ) { page ->
            val pageScroll = scrollBehaviors[page]
            // 一页的滚动主体：整页纵向滚动 + 顶栏收缩 + 越界回弹。
            //
            // 下拉刷新（快递 / 记录两页）要套在**这一层外面**，而它是靠嵌套滚动拿增量的 ——
            // 所以 `overScrollVertical()` 必须留在这一层、不能因为「已经有刷新了」就摘掉：
            // miuix 的越界节点会检测外层的 PullToRefresh 状态，刷新激活时主动把增量让出去，
            // 两者本来就是一伙的。
            val scrollContent: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .nestedScroll(pageScroll.nestedScrollConnection)
                        // 滚动位置按页取（状态建在上面那一层，二级页返回时才能保住偏移）。
                        .verticalScroll(pageScrollStates[page], overscrollEffect = null)
                        .padding(top = padding.calculateTopPadding())
                        .padding(vertical = 4.dp),
                ) {
                    when (page) {
                        // 关掉开关时传 null 而不是「传了但内部再判一次」：双击手势挂不挂、长什么样，
                        // 由这一个参数决定，界面里没有第二处判断可以跟它跑偏。
                        TAB_HOME -> HomePage(
                            records = homeRecords,
                            rules = stationRules,
                            onTogglePickup = if (doubleTapPickup) togglePickup else null,
                            onOpenDetail = { record ->
                                detailRecord = record
                                // 顺手重读一遍列表：到站件的轨迹是异步拉回来的（宿主进程发请求
                                // → 广播回模块进程 → 落库），用户看到卡片时它可能刚好落库。
                                // 不重读这一下，详情页首次打开经常是空的，得退出去再进来。
                                scope.launch { homeRecords = ExpressRecordStore.load(context) }
                            },
                            // 底部「归档快递」那一行。归档页是**只读视图**，不需要提前准备数据 ——
                            // 它拿的还是同一份 homeRecords，存储重读后它自己会重组。
                            onOpenArchive = { archiveOpen = true },
                        )
                        TAB_RECORDS -> RecordPage(recordEntries)
                        TAB_ABOUT -> AboutPage(
                            settings = settings,
                            onOpenLog = { logPageOpen = true },
                        )
                        else -> SettingsPage(
                            settings = settings,
                            update = ::updateSettings,
                            uiPrefs = uiPrefs,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            blurBars = blurBars,
                            onBlurBarsChange = {
                                blurBars = it
                                uiPrefs.blurBars = it
                            },
                            floatingNavBar = floatingNavBar,
                            onFloatingNavBarChange = {
                                floatingNavBar = it
                                uiPrefs.floatingNavBar = it
                            },
                            liquidGlass = liquidGlass,
                            onLiquidGlassChange = {
                                liquidGlass = it
                                uiPrefs.liquidGlass = it
                            },
                            doubleTapPickup = doubleTapPickup,
                            onDoubleTapPickupChange = {
                                doubleTapPickup = it
                                uiPrefs.doubleTapPickup = it
                            },
                            hideFromRecents = hideFromRecents,
                            onHideFromRecentsChange = {
                                hideFromRecents = it
                                // prefs 与任务 flag 都由 Activity 那一层写：前者是它的初始化职责，
                                // 后者只有它做得到（见 ExpressApp 的参数说明）。
                                onHideFromRecentsChange(it)
                            },
                            blurSupported = blurSupported,
                            stationRules = stationRules,
                            onOpenStations = { stationAdminOpen = true },
                        )
                    }
                    Spacer(Modifier.height(padding.calculateBottomPadding()))
                    Spacer(Modifier.height(4.dp))
                }
            }
            // 只有快递 / 记录两页套下拉刷新：设置页满屏开关、关于页是静态信息，
            // 下拉没有可刷的东西 —— 挂上去只会让用户拉出一个永远刷不出新内容的圈。
            val contentPadding = PaddingValues(top = padding.calculateTopPadding())
            when (page) {
                TAB_HOME -> RefreshablePage(
                    isRefreshing = homeRefreshing,
                    onRefresh = onHomeRefresh,
                    scrollBehavior = pageScroll,
                    contentPadding = contentPadding,
                ) {
                    scrollContent()
                }
                TAB_RECORDS -> RefreshablePage(
                    isRefreshing = recordRefreshing,
                    onRefresh = onRecordRefresh,
                    scrollBehavior = pageScroll,
                    contentPadding = contentPadding,
                ) {
                    scrollContent()
                }
                else -> scrollContent()
            }
        }

        // 身份码弹窗。必须挂在 Scaffold 里面 —— miuix 的 `OverlayDialog` 靠 Scaffold 提供的
        // `MiuixPopupHost` 渲染，放在外面会静默不显示（库文档明写了这条前提）。
        //
        // 传的是**全量** homeRecords：弹窗要自己算「最近的那个驿站」，
        // 那是驿站级的聚合，首页那几个分组（只装待取件）不够用。
        IdentityCodeDialog(
            show = identityOpen,
            records = homeRecords,
            rules = stationRules,
            resumeTick = resumeTick,
            onNeedLocation = {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            onDismiss = { identityOpen = false },
        )
    }
}

/**
 * 给一页套上下拉刷新。
 *
 * 三个取舍：
 *
 * 1. **文案必须自己给中文**：miuix 默认是 `Pull down to refresh` 那一套英文。
 * 2. `contentPadding` 只挪刷新指示器的位置（库内部是按 `offset` 用的，不占布局），
 *    用来把它推到顶栏下面 —— 不给的话指示器会画在顶栏底下，被顶栏盖住。
 * 3. 两页各自持有 `isRefreshing`，互不牵连。
 *
 * 状态提升用 `isRefreshing` 这个单向入参是库的约定：`onRefresh` 里自己置 `true`、
 * 完事置 `false`，库只负责把指示器与手势对上（见 `ExpressApp` 里那个 `refresh`）。
 */
@Composable
private fun RefreshablePage(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        refreshTexts = REFRESH_TEXTS,
        contentPadding = contentPadding,
        topAppBarScrollBehavior = scrollBehavior,
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

/**
 * 下拉刷新的指示器**最短**显示时长。
 *
 * 重读本地 prefs 是几毫秒的事，不兜这一下的话指示器在松手那一帧就收完了，
 * 用户看不到任何「刷新发生了」的反馈。取值刚好够看清，不追求「像在忙」。
 */
private const val MIN_REFRESH_VISIBLE_MS = 400L

/**
 * 详情页按需拉取的**收位超时**。正常路径是轨迹落库后的内部广播提前收位；广播没来
 * （菜鸟不在后台兜底失败、请求被风控退避挡下）时，指示器不能永远转着 —— 到点就收，
 * 页面上「暂无物流轨迹」的提示就是降级结果。
 */
private const val DETAIL_REFRESH_TIMEOUT_MS = 6_000L

/**
 * 设置页「驿站管理」那一行的摘要。
 *
 * 只说「有没有手工规则」这一个事实，**不说驿站总数**：那要遍历全部记录才知道，
 * 而设置页每次重组都算一遍是白费。点进去第一行就写着「共 N 个驿站」。
 */
private fun stationAdminSummary(rules: ExpressStationRules): String =
    if (rules.isEmpty) {
        "合并同一驿站的不同写法、改显示名称"
    } else {
        "已设置 ${rules.renames.size} 条规则"
    }

// ---------------------------------------------------------------- 现场采集（驿站地址 / 指纹）

/** 坐标文本（地址反查失败时的兜底）。5 位小数 ≈ 1 米，正好是驿站门口的尺度。 */
private fun coordinatesOf(position: GeoPoint?): String =
    position?.let { "%.5f, %.5f".format(java.util.Locale.US, it.lat, it.lng) } ?: ""

/**
 * 一次采集的结果说明。
 *
 * **如实说采到了什么、缺了什么**，不写成「获取成功」：缺 WiFi 和缺定位的处置完全不同
 * （前者多半是系统限流、再点一次就好；后者是系统定位没开，得去设置里开），
 * 而这两种在界面上如果都是一句「成功」，用户就只能靠反复试点 —— 那是猜，不是排查。
 */
private fun captureNote(capture: Capture): String {
    val fingerprint = capture.fingerprint
    val parts = buildList {
        add(
            if (fingerprint.position != null) {
                "已记下当前位置"
            } else {
                "没取到定位（确认系统的定位服务已打开）"
            },
        )
        add(
            if (fingerprint.wifi.isEmpty()) {
                "没扫到附近 WiFi（可能没开 WiFi，或被系统限流）"
            } else {
                "同时记下 ${fingerprint.wifi.size} 个 WiFi"
            },
        )
        // 只在「有坐标、但地址是坐标本身」时说这一句 —— 没有坐标时上面已经说了缺定位，
        // 再说一次地址兜底只会让两句话互相解释。
        if (capture.addressText == null && fingerprint.position != null) {
            add("地址一栏用坐标代替（本机没有可用的地址反查服务）")
        }
    }
    return parts.joinToString("；") + "。"
}

/** 缺权限时先提示去授权；授权回来会自动继续这次采集。 */
private const val PERMISSION_HINT = "需要先允许定位权限，允许后会自动接着获取。"

private const val PERMISSION_DENIED_HINT =
    "没有定位权限，取不到位置。可以到系统设置里给本模块开启定位，再点一次。"

private const val EMPTY_CAPTURE_HINT =
    "没取到位置和附近 WiFi。请确认系统定位与 WiFi 已打开，站在驿站门口再点一次。"

// ---------------------------------------------------------------- 设置页

@Composable
private fun SettingsPage(
    settings: ExpressSettingsSnapshot,
    update: ((ExpressSettingsSnapshot) -> ExpressSettingsSnapshot) -> Unit,
    uiPrefs: ExpressUiPrefs,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    blurBars: Boolean,
    onBlurBarsChange: (Boolean) -> Unit,
    floatingNavBar: Boolean,
    onFloatingNavBarChange: (Boolean) -> Unit,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    doubleTapPickup: Boolean,
    onDoubleTapPickupChange: (Boolean) -> Unit,
    hideFromRecents: Boolean,
    onHideFromRecentsChange: (Boolean) -> Unit,
    blurSupported: Boolean,
    stationRules: ExpressStationRules,
    onOpenStations: () -> Unit,
) {
    GroupTitle("界面")
    SettingsCard {
        OverlayDropdownPreference(
            title = "主题",
            items = ExpressUiPrefs.THEME_LABELS,
            selectedIndex = themeMode.coerceIn(
                ExpressUiPrefs.THEME_FOLLOW_SYSTEM,
                ExpressUiPrefs.THEME_DARK,
            ),
            onSelectedIndexChange = onThemeModeChange,
        )
        SwitchPreference(
            // summary 只在不支持时出现 —— 那是必要信息（开关为什么点不动）；支持时
            // 开关本身已经自说明，副标题只是噪音。
            title = "模糊",
            summary = if (blurSupported) null else "需 Android 13 及以上",
            checked = blurBars,
            enabled = blurSupported,
            onCheckedChange = onBlurBarsChange,
        )
        SwitchPreference(
            title = "悬浮底栏",
            checked = floatingNavBar,
            onCheckedChange = onFloatingNavBarChange,
        )
        if (floatingNavBar) {
            SwitchPreference(
                title = "液态玻璃",
                summary = if (blurSupported) null else "需 Android 13 及以上",
                checked = liquidGlass,
                enabled = blurSupported,
                onCheckedChange = onLiquidGlassChange,
            )
        }
        // 第四行「隐藏后台卡片」：前三行是**外观**，这一行改的是**窗口行为**（本模块要不要
        // 出现在系统最近任务里）。同放一张卡片是因为它同样只影响本机界面（见 ExpressUiPrefs 类注释）。
        //
        // 副标题必须留：单看「隐藏后台卡片」根本看不出「后台」指系统最近任务 —— 用户很可能
        // 理解成「首页那些卡片」（2026-09-26 定这个开关时就是这么来回确认的）。
        // 它不受 floatingNavBar 影响，所以留在那个 if 外面。
        SwitchPreference(
            title = "隐藏后台卡片",
            summary = "从系统「最近任务」里隐藏本模块",
            checked = hideFromRecents,
            onCheckedChange = onHideFromRecentsChange,
        )
    }

    GroupTitle("取件")
    SettingsCard {
        // 这一组的开关和上面「界面」那组一样只影响本机：它们不改拦截、不改判定，
        // 也不投影给 system_server（见 ExpressUiPrefs 的类注释）。
        SwitchPreference(
            title = "双击确认取件",
            summary = "双击到站卡片标记已取件",
            checked = doubleTapPickup,
            onCheckedChange = onDoubleTapPickupChange,
        )
        // 这里**故意不画分割线**（2026-09-26 用户要求移除）：两个开关各占一整行、行高一致，
        // 中间那道 0.5dp 的线除了把一张小卡片切碎没有别的信息量；miuix 自己的偏好列表也是
        // 靠行间距分行的。开关行 ↔ 带箭头入口行之间才需要线（见驿站详情页的「保存 / 恢复默认」）。
        //
        // 驿站管理放在「取件」组里而不是单开一组：它服务的就是取件（去哪个驿站、认哪几张卡片），
        // 单开一组会给它一个与其分量不符的位置。
        ArrowPreference(
            title = "驿站管理",
            summary = stationAdminSummary(stationRules),
            onClick = onOpenStations,
        )
    }

    GroupTitle("包裹详情")
    SettingsCard {
        // 轨迹什么时候拉：一次点击一次请求（默认，另有低速保底）还是富化到达时批量自动拉。
        // 选项名已把语义说清；风控细节属于「出问题时才看」，在关于页诊断里。
        SegmentedRow(
            options = listOf(
                ExpressSettingsKeys.MODE_TRACE_ON_DEMAND to "点击时获取",
                ExpressSettingsKeys.MODE_TRACE_AUTO to "自动更新",
            ),
            selected = settings.traceFetchMode,
            onSelect = { mode -> update { it.copy(traceFetchMode = mode) } },
        )
    }

    GroupTitle("工作模式")
    SettingsCard {
        // 一个三态控件，不再有「总开关 + 模式」两个能互相矛盾的入口。
        // 不再逐态写说明 —— 选项名已自明；唯一值得占一行的是拦截模式的**不可逆警告**：
        // 原通知被吞后如果模块自己发不出去，那条通知就永远丢了。
        SegmentedRow(
            options = ExpressSettingsKeys.MODE_OPTIONS,
            selected = settings.mode,
            onSelect = { mode -> update { it.copy(mode = mode) } },
        )
        if (settings.mode == ExpressSettingsKeys.MODE_INTERCEPT) {
            HintText("原通知会被吞掉，请先在放行模式确认通知能收到。")
        }
    }

    GroupTitle("来源")
    SettingsCard {
        SwitchPreference(
            title = "菜鸟",
            checked = settings.sourceCainiao,
            onCheckedChange = { on -> update { it.copy(sourceCainiao = on) } },
        )
        SwitchPreference(
            title = "拼多多",
            summary = "仅通知文案",
            checked = settings.sourcePinduoduo,
            onCheckedChange = { on -> update { it.copy(sourcePinduoduo = on) } },
        )
        SwitchPreference(
            title = "淘宝",
            summary = "仅通知文案",
            checked = settings.sourceTaobao,
            onCheckedChange = { on -> update { it.copy(sourceTaobao = on) } },
        )
        SwitchPreference(
            title = "快递短信",
            checked = settings.sourceSms,
            onCheckedChange = { on -> update { it.copy(sourceSms = on) } },
        )
    }

    GroupTitle("判定阈值")
    SettingsCard {
        SegmentedRow(
            options = listOf("30" to "宽松", "50" to "默认", "70" to "严格"),
            selected = settings.confidenceThreshold.toString(),
            onSelect = { value ->
                value.toIntOrNull()?.let { threshold ->
                    update { it.copy(confidenceThreshold = threshold) }
                }
            },
        )
        HintText("阈值越高越不容易误拦，但可能漏掉信息量少的快递提醒。")
    }

    GroupTitle("关键词")
    SettingsCard {
        InfoRow("内置关键词", "${ExpressRule.DEFAULT_KEYWORDS.size} 个")
        InfoRow("自定义追加", "${settings.extraKeywords.size} 个")
        InfoRow("排除词", "${settings.excludeKeywords.size} 个")
        HintText("自定义关键词暂只读，后续版本提供编辑入口。")
    }

    GroupTitle("框架")
    SettingsCard {
        InfoRow(
            "Xposed 服务",
            if (ExpressSettings.isServiceAvailable()) "已连接" else "未连接（设置无法同步给 hook）",
            if (ExpressSettings.isServiceAvailable()) null else MiuixTheme.colorScheme.error,
        )
        HintText("未连接说明 LSPosed 没启用本模块，或作用域没包含它。设置仍会保存，但被注入的进程读不到。")
    }
}

// ---------------------------------------------------------------- 记录页

/**
 * 投递记录页。
 *
 * 列表由 [ExpressApp] 持有（它是「下拉刷新」的重读对象），这里只负责画。
 *
 * 按天分组：同一天只出一行日标题（今天 / 昨天 / MM-dd），卡片里只留 HH:mm。
 * 比每条都写满「09-26 00:55:44」清爽，也更容易按天扫读 —— 排查「昨晚那条发了没有」
 * 时，眼睛先落到日标题上。
 *
 * 统计从卡片挪进了分组抬头：原来那张摘要卡上还挂着「清空投递记录」，清空入口撤掉之后
 * （放置办法待定）它就只剩一行统计 —— 为一行「已发出 12 / 12」单独占一张卡不值当，
 * 抬头那行恰好是它的位置。
 */
@Composable
private fun RecordPage(entries: List<ExpressNotificationLog.Entry>) {
    if (entries.isEmpty()) {
        GroupTitle("通知投递记录")
        RecordCard(
            title = "暂无记录",
            description = "拦截到快递通知后会出现在这里。这一页记的是投递结果" +
                "（模块的通知有没有真的发出去），不是包裹列表。",
        )
        return
    }

    GroupTitle("通知投递记录 · 已发出 ${entries.count { it.delivered }} / ${entries.size}")

    entries.take(30).forEachIndexed { index, entry ->
        val day = dayLabel(entry.at)
        // 只在跨天时插日标题，同一天连续几条共用一行。
        val previousDay = entries.getOrNull(index - 1)?.let { dayLabel(it.at) }
        if (index == 0 || previousDay != day) {
            GroupTitle(day)
        }
        RecordCard(
            title = "${ExpressSettingsSnapshot.displayName(entry.sourcePackage)} · ${entry.title}",
            titleColor = if (entry.delivered) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.error
            },
            description = buildString {
                append(entry.detail)
                if (!entry.delivered) {
                    append("\n未发出：")
                    append(entry.failureDetail.ifBlank { "未知原因" })
                }
            },
            trailing = { RecordTime(clockLabel(entry.at)) },
        )
    }
}

/** 「今天 / 昨天 / MM-dd」——同一天共用一行日标题，比每条都写满日期清爽。 */
private fun dayLabel(at: Long): String {
    val calendar = java.util.Calendar.getInstance()
    val today = calendar.clone() as java.util.Calendar
    today.set(java.util.Calendar.HOUR_OF_DAY, 0)
    today.set(java.util.Calendar.MINUTE, 0)
    today.set(java.util.Calendar.SECOND, 0)
    today.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = today.timeInMillis
    return when {
        at >= todayStart -> "今天"
        at >= todayStart - 24 * 60 * 60 * 1000L -> "昨天"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(at))
    }
}

/** 卡片右上角的时刻（同一天内只需要时分）。 */
private fun clockLabel(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(at))

// ---------------------------------------------------------------- 关于页（含诊断）

/** 构建时间显示串：gradle 在构建时写进 [BuildConfig.BUILD_TIME]（epoch 毫秒）。 */
private val BUILD_TIME_DISPLAY: String = java.text.SimpleDateFormat(
    "yyyy-MM-dd HH:mm",
    java.util.Locale.CHINA,
).format(java.util.Date(BuildConfig.BUILD_TIME))

/**
 * 关于页。诊断信息也放在这里 —— 它本来就是「出问题时才看」的内容，
 * 单独占一个页签不值得，但也不能藏起来（模块常年无界面，没有这些信息就无从排查）。
 *
 * @param onOpenLog 打开二级页「模块日志」（[LogPage]）
 */
@Composable
private fun AboutPage(settings: ExpressSettingsSnapshot, onOpenLog: () -> Unit) {
    val context = LocalContext.current
    val permissionOk = ExpressNotificationPoster.hasPermission(context)
    var hookForceRequested by remember { mutableStateOf(false) }

    GroupTitle("模块状态")
    SettingsCard {
        InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // 构建时间紧跟版本：多台设备 / 多个渠道装的是哪一包，「版本号相同但构建不同」
        // 的排查场景里是唯一可靠的对账依据。格式在 gradle 里生成时就定好了。
        InfoRow("构建时间", BUILD_TIME_DISPLAY)
        InfoRow("构建模式", if (BuildConfig.DEBUG) "debug" else "release")
        InfoRow(
            "观察模式",
            if (BuildConfig.OBSERVE_ONLY) "开（只判定不投递）" else "关（正常投递）",
        )
        InfoRow("设置摘要", settings.summary())
    }

    GroupTitle("权限")
    SettingsCard {
        InfoRow(
            "通知权限",
            if (permissionOk) "已授予" else "未授予 —— 替换通知发不出去",
            if (permissionOk) null else MiuixTheme.colorScheme.error,
        )
        InfoRow(
            "系统通知开关",
            if (ExpressNotificationPoster.areNotificationsEnabled(context)) "开启" else "关闭",
        )
    }

    GroupTitle("system_server hook")
    SettingsCard {
        val installed = WatchdogReporter.lastBootInstalled(context)
        val at = WatchdogReporter.lastBootAt(context)
        InfoRow(
            "最近一次开机",
            when (installed) {
                true -> "hook 已安装"
                false -> "hook 未安装"
                null -> "未知（未收到 system_server 上报）"
            },
            if (installed == true) null else MiuixTheme.colorScheme.error,
        )
        if (at > 0L) {
            InfoRow("上报时间", ExpressNotificationLog.formatTime(at))
        }
        val describe = WatchdogReporter.lastBootDescribe(context)
        if (describe.isNotBlank()) {
            InfoRow("看门狗", describe)
        }
        val tripped = WatchdogReporter.watchdogTripped(context)
        val reason = WatchdogReporter.lastBootReason(context)
        if (reason.isNotBlank()) {
            HintText(reason, color = MiuixTheme.colorScheme.error)
        }
        if (tripped) {
            HintText(
                "连续多次启动在安装 hook 后未能正常完成，模块已自动停用 system_server hook，" +
                    "以免设备陷入开机循环。此时模块不再拦截，但设备可正常使用。",
            )
            CardActionRow("复位并重新启用") {
                // 复位请求要先写进本地 prefs，再投影到 RemotePreferences —— 直接写 RemotePreferences
                // 在框架未连接时会静默失败，而用户点这个按钮时框架多半就是有问题的状态。
                ExpressSettingsKeys.requestHookForceEnable(
                    ExpressSettingsKeys.localPrefs(context),
                    true,
                )
                ExpressSettings.syncToFrameworkNow(context)
                hookForceRequested = true
            }
            if (hookForceRequested) {
                HintText("已请求复位。重启设备后 system_server 会重新尝试安装 hook。")
            }
        } else {
            HintText(
                "看门狗在每次开机安装 hook 后上报一次。未安装的常见原因：" +
                    "LSPosed 未启用模块、作用域缺少 system、或框架不支持 system_server hook。",
            )
        }
    }

    GroupTitle("接入须知")
    // 下面这些是「装完不生效」时唯一能自助排查的线索，放在关于页而不是藏进日志。

    SettingsCard {
        HintText("1. 在 LSPosed 里勾选本模块，作用域必须包含 system（全局拦截的前提）。")
        HintText(
            "2. 作用域还要包含菜鸟与淘宝：菜鸟给包裹数据，淘宝给淘宝登录态。" +
                "菜鸟没绑淘宝账号时，它自己的 cookie 里就没有淘宝那份登录态，" +
                "轨迹只能靠淘宝那头 —— 没勾淘宝，详情页的轨迹会一直取不到。",
        )
        HintText("3. 改动作用域或模块代码后需重启设备，system_server 里的 hook 才会更新。")
        HintText("4. 模块界面至少打开过一次才算脱离 stopped，广播才收得到。")
        HintText("5. 若「记录」页显示未发出，先看上面的通知权限。")
    }

    GroupTitle("诊断")
    SettingsCard {
        // 一行入口，不在这里摊开日志：日志一次几十上百行，摆在这一页会把「模块状态 /
        // 权限 / 看门狗」这些真要看的信息全推到屏幕外。点进去是一整页（[LogPage]），
        // 那一页有顶栏和返回，也放得下更长的正文。
        //
        // 摘要只给条数（和错误数）：`ArrowPreference` 的副标题位置塞不下
        // 「最近 09-26 04:12:33.123」这种长度，折行会把卡片撑得比正文还高。
        val logs = ModuleLogBuffer.snapshot()
        ArrowPreference(
            title = "模块日志",
            summary = logSummary(logs),
            onClick = onOpenLog,
        )
    }
}
