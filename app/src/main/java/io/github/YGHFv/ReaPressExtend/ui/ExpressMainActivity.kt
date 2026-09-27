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
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import io.github.YGHFv.ReaPressExtend.backup.BackupScheduler
import io.github.YGHFv.ReaPressExtend.backup.BackupSettings
import io.github.YGHFv.ReaPressExtend.core.NoRootPlan
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.core.ExpressRule
import io.github.YGHFv.ReaPressExtend.core.NotificationCategory
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.GeoPoint
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationRuleStore
import io.github.YGHFv.ReaPressExtend.noroot.NoRootAccess
import io.github.YGHFv.ReaPressExtend.relay.AutoWatch
import io.github.YGHFv.ReaPressExtend.relay.CainiaoDirectFetcher
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.HostCredentialRequester
import io.github.YGHFv.ReaPressExtend.relay.HostRefreshRequester
import io.github.YGHFv.ReaPressExtend.relay.HostWakePin
import io.github.YGHFv.ReaPressExtend.relay.ModuleTraceFetcher
import io.github.YGHFv.ReaPressExtend.relay.WatchdogReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 模块主界面：快递 / 记录 / 设置 / 关于四页。界面承担两个不可替代职责：让模块脱离 stopped 状态
 * （否则广播收不到，投递链路不成立），以及补上 hook 侧的运行时可观测性。
 * 外观三开关递进：液态玻璃依赖悬浮底栏，二者依赖 isRuntimeShaderSupported（不支持的设备置灰不隐藏）。
 */
class ExpressMainActivity : ComponentActivity() {

    private lateinit var uiPrefs: ExpressUiPrefs

    private val themeModeState = mutableIntStateOf(ExpressUiPrefs.THEME_FOLLOW_SYSTEM)

    /** onResume 自增触发列表重读（LocalLifecycleOwner 观察者已废弃，为一个重读不值得引依赖）。 */
    private val resumeTick = mutableIntStateOf(0)

    private var lastHostWakeAt = 0L

    private var lastHostRefreshAt = 0L

    /** 只用来决定日志打不打；flag 本身每次 onResume 都要重放。 */
    private var lastExcludeFromRecents: Boolean? = null

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        refreshHostOnResume()
        syncAutoWatchOnResume()
        // flag 记在任务的根 Intent 上，划掉后台后是全新任务 —— 每次 onResume 都必须重放。
        applyExcludeFromRecents(uiPrefs.hideFromRecents)
        // 国产 ROM 会吞第三方闹钟且用户看不出（开关还开着）；打开模块是唯一稳定会经过的地方，
        // 在这里补判到期，闹钟被吞也只晚到下次打开而不是永不备份。
        runCatching { BackupScheduler.maybeRunDue(this, "打开模块") }
            .onFailure { ModuleAndroidLog.error("ReaPress", "backup due check failed", it) }
    }

    private var lastAutoWatchSyncAt = 0L

    /** 前台服务合规的起停时机只有界面与开机广播两处，这是界面那一处；关着开关 sync 也会停掉服务。 */
    private fun syncAutoWatchOnResume() {
        val now = System.currentTimeMillis()
        if (now - lastAutoWatchSyncAt < AUTO_WATCH_SYNC_MIN_INTERVAL_MS) return
        lastAutoWatchSyncAt = now
        AutoWatch.sync(this, "打开模块")
    }

    /**
     * 打开模块时静默刷新：两条互补缺一不可 —— 唤醒销只在进程不在时有效（拉起装 hook），
     * 刷新请求只在进程存活时有效（动态接收器叫不醒死进程；小米上宿主常年以推送进程活着是常态）；
     * 先发销再发请求，宿主刚被拉起时请求可能正好落到刚注册好的接收器上。
     * 第三条是直连兜底（[scheduleDirectFetch]），整条路不经菜鸟进程，只覆盖淘宝/天猫件。
     * 不是「保证刷新」：判据看模块日志的 `host self query: rows=N` 与 `enrichment received`；
     * 也不解决「服务端有、本地没有」 —— 这里只解决「库里有、没人读」。
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
            // 与刷新请求共用节流：两者是「宿主活/死」两个分支，用户的一次打开只需要一个答案。
            scheduleDirectFetch(now)
        }
    }

    /** 直连兜底的宽限期：宿主回话了就什么都不做（直连是真金白银的请求数）。postDelayed 不随 Activity 销毁取消。 */
    private fun scheduleDirectFetch(requestedAt: Long) {
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            if (CainiaoDirectFetcher.hostAnsweredSince(requestedAt)) return@postDelayed
            CainiaoDirectFetcher.start(
                app,
                "打开模块 ${CainiaoDirectFetcher.GRACE_MS / 1000} 秒内没有等到宿主回执",
            )
        }, CainiaoDirectFetcher.GRACE_MS)
    }

    /**
     * 「隐藏后台卡片」：从系统最近任务摘掉 / 放回。清单 flag 只在任务根启动那一刻定下来，
     * 运行时唯一途径是 AppTask.setExcludeFromRecents（改根 Intent 上的 flag，无需权限）。
     * flag 在任务根 Intent 上，划掉后是全新任务，所以每次 onResume 都要重放。失败一律吞掉（体验开关）。
     */
    private fun applyExcludeFromRecents(exclude: Boolean) {
        val changed = lastExcludeFromRecents != exclude
        lastExcludeFromRecents = exclude
        runCatching {
            val manager = getSystemService(ActivityManager::class.java) ?: return
            val tasks = manager.appTasks
            // getTaskInfo() 标了 @Nullable（任务刚消失时给 null），认不到就退回单任务情形。
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
            // isSystemInDarkTheme() 而不是读 configuration：系统切深浅色时 Compose 重组，不必重启 Activity。
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
                        // 窗口底色要一起改，否则切夜间时状态栏底下还是浅色。
                        applyWindowBackground()
                    },
                    // 只有 Activity 拿得到 taskId 去改任务根 Intent 上的 flag。
                    onHideFromRecentsChange = { exclude ->
                        uiPrefs.hideFromRecents = exclude
                        applyExcludeFromRecents(exclude)
                    },
                )
            }
        }
    }

    /**
     * 系统栏沉浸。必须放在 setContent 里随深浅色重跑（onCreate 顶部调一次会被窗口初始化吃掉，
     * 结果导航栏仍占布局）；isNavigationBarContrastEnforced = false —— 系统默认的对比度 scrim
     * 才是「底栏下面一条更亮的白带」的真身。
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

    @Composable
    private fun SystemBarAppearance(dark: Boolean) {
        LaunchedEffect(dark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    // 状态栏与导航栏一起处理：只改状态栏会让小白条上的图标变白看不见。
                    val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightMask, lightMask)
                }
            }
        }
    }

    private fun applyWindowBackground() {
        // 窗口底色由 Activity 持有，不依赖 Compose 重组 —— 首帧之前系统栏区域显示的就是它。
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

    /** 通知权限必须由 Activity 发起：模块通知是从接收器发的，那里的失败会静默。 */
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

    /** 一打开就向宿主索要一次淘宝登录态（发完即忘，尽力而为：宿主不在时广播被静默丢弃）。只服务轨迹；身份码另走会话现取。 */
    private fun requestHostLoginState() {
        // 菜鸟和淘宝都要问：凭据在哪一边事先不知道。
        HostCredentialRequester.requestFromHosts(this)
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
        const val REQUEST_CODE = 4201

        /** 唤醒销是幂等的，这个间隔只挡同一使用回合内的重入。2026-09-27 从 5 分钟改小：HyperOS 拦直投后改成双路代发，代价只是两条广播，5 分钟会让最常见的重试动作什么都不做。 */
        const val HOST_WAKE_MIN_INTERVAL_MS = 60_000L

        /** 比唤醒销那条短得多也常用得多：不动进程，只让已存活的宿主重读一次本地库。 */
        const val HOST_REFRESH_MIN_INTERVAL_MS = 60_000L

        /** onResume 任何回前台都会跑，而 startForegroundService 会让服务重发常驻通知。 */
        const val AUTO_WATCH_SYNC_MIN_INTERVAL_MS = 60_000L
    }
}

private const val TAB_HOME = 0
private const val TAB_RECORDS = 1
private const val TAB_SETTINGS = 2
private const val TAB_ABOUT = 3

private val QUIET_START_HOURS = (20..23).toList()
private val QUIET_END_HOURS = (5..10).toList()
private val QUIET_START_LABELS = QUIET_START_HOURS.map { "%02d:00".format(it) }
private val QUIET_END_LABELS = QUIET_END_HOURS.map { "%02d:00".format(it) }

/** 把手改 prefs 出来的非标档位如实插进下拉：间隔是数值，显示成错的会让人以为设置丢了。 */
private fun watchEntries(options: List<Pair<Int, String>>, current: Int): List<Pair<Int, String>> =
    (options + (current to "$current 分钟")).distinctBy { it.first }.sortedBy { it.first }

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
    /** 必须 Activity 实现（只有它有 taskId 去改任务根 Intent）。 */
    onHideFromRecentsChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 外观状态提升到这里：设置页改它，顶栏/底栏读它。
    var blurBars by remember { mutableStateOf(uiPrefs.blurBars) }
    var floatingNavBar by remember { mutableStateOf(uiPrefs.floatingNavBar) }
    var liquidGlass by remember { mutableStateOf(uiPrefs.liquidGlass) }
    var doubleTapPickup by remember { mutableStateOf(uiPrefs.doubleTapPickup) }
    var hideFromRecents by remember { mutableStateOf(uiPrefs.hideFromRecents) }

    var settings by remember { mutableStateOf(ExpressSettings.read(context)) }
    fun updateSettings(block: (ExpressSettingsSnapshot) -> ExpressSettingsSnapshot) {
        val next = block(settings)
        ExpressSettings.write(context, next)
        settings = next
    }

    // 归档窗口三处共用（首页分组 / 归档入口 / 归档页），各自推导会短暂不一致、同一件包裹在两页间闪。
    val archiveRetentionMs =
        if (settings.isArchiveOnSign) 0L else ExpressHomeGrouper.ARCHIVE_RETENTION_MS

    // 拦截事件到达时 Activity 可能已离开前台，resumeTick 每次自增这里随之重读。
    // 首读前先把历史记录按当前解析逻辑归并落盘：解析层修好不会改掉已落盘的旧值，而脏值会继续
    // 分组、挡住富化真名；无变化时一个字节都不写。
    remember { ExpressRecordStore.reconcile(context) }
    var homeRecords by remember { mutableStateOf(ExpressRecordStore.load(context)) }
    var recordEntries by remember { mutableStateOf(ExpressNotificationLog.snapshot(context)) }
    LaunchedEffect(resumeTick) {
        // 每次回到前台都走这里：磁盘 IO 必须丢到 IO 线程，主线程上读 200 条 JSON 会卡掉恢复动画的首几帧。
        val loaded = withContext(Dispatchers.IO) { ExpressRecordStore.load(context) }
        homeRecords = loaded
        recordEntries = withContext(Dispatchers.IO) { ExpressNotificationLog.snapshot(context) }
        // 自动补一次轨迹：onResume 那串只解决「宿主库里有、没人读」，宿主那张表不会因叫醒而变新，
        // 必须真发一次请求。放在这里是为了用刚读到的列表；引擎自己会节流（foregroundRefresh）。
        withContext(Dispatchers.IO) {
            runCatching { ModuleTraceFetcher.foregroundRefresh(context, loaded, "打开模块") }
        }
    }

    /**
     * 存储一变就重读（ACTION_RECORDS_CHANGED）。必须挂在主界面这一层（所有二级页提前 return 之前）：
     * 原来只注册在详情页，用户停在首页时宿主灌进来的新数据完全不可见，首页停在旧快照。
     */
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // 广播在主线程回调；enrich/deliver 事件很频繁，读盘+JSON 解析放 IO 线程。
                scope.launch {
                    val records = withContext(Dispatchers.IO) { ExpressRecordStore.load(context) }
                    val entries = withContext(Dispatchers.IO) { ExpressNotificationLog.snapshot(context) }
                    homeRecords = records
                    recordEntries = entries
                }
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

    /** 双击标记已取件后重读而不是就地改：可见后果是分组级的（整站确认要整批移档），由 Grouper 拿全量算。 */
    val togglePickup: (ExpressRecord) -> Unit = { record ->
        val marking = !record.isPickedUp
        scope.launch {
            val changed = withContext(Dispatchers.IO) {
                ExpressRecordStore.setPickedUp(
                    context,
                    record.dedupeKey,
                    if (marking) System.currentTimeMillis() else null,
                )
            }
            if (changed) {
                homeRecords = withContext(Dispatchers.IO) { ExpressRecordStore.load(context) }
            }
        }
    }

    // 下拉刷新的指示器状态，两页各自一份。
    var homeRefreshing by remember { mutableStateOf(false) }
    var recordRefreshing by remember { mutableStateOf(false) }

    /** 置位 → 重读 → 保证指示器至少显示 [MIN_REFRESH_VISIBLE_MS] → 收位。重读本身在 IO 线程。 */
    fun refresh(reload: suspend () -> Unit, setRefreshing: (Boolean) -> Unit) {
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
        refresh({
            homeRecords = withContext(Dispatchers.IO) { ExpressRecordStore.load(context) }
        }) { homeRefreshing = it }
        // 用户主动下拉是明确指令，走 force（只跳启动节流，风控退避照旧）。三条腿各管一格：
        // 拉起宿主 + 请它重查本地表、宿主叫不动时直连淘宝、轨迹 force 刷新。
        val snapshot = homeRecords
        scope.launch(Dispatchers.IO) {
            runCatching { ModuleTraceFetcher.foregroundRefresh(context, snapshot, "首页下拉刷新", force = true) }
        }
        HostWakePin.wake(context, "首页下拉刷新")
        HostRefreshRequester.request(context)
        CainiaoDirectFetcher.start(context, "首页下拉刷新", force = true)
    }
    val onRecordRefresh: () -> Unit = {
        refresh({
            recordEntries = withContext(Dispatchers.IO) { ExpressNotificationLog.snapshot(context) }
        }) { recordRefreshing = it }
    }

    // 各二级页：整页替换主界面（自带顶栏与返回，底栏页签在这一层没有意义）。
    var logPageOpen by remember { mutableStateOf(false) }

    var recordDetail by remember { mutableStateOf<ExpressNotificationLog.Entry?>(null) }

    // 与「记录」是同一份存储的两个视图（Entry.kind 区分）：那边是发出的通知，这边是被吞掉的通知。
    var interceptPageOpen by remember { mutableStateOf(false) }

    var noRootPageOpen by remember { mutableStateOf(false) }

    var backupPageOpen by remember { mutableStateOf(false) }

    // 驿站规则改完立刻重读：首页分组是拿它现算的，不重读的话名字改了但卡片没动。
    var stationRules by remember { mutableStateOf(ExpressStationRuleStore.load(context)) }
    var stationAdminOpen by remember { mutableStateOf(false) }

    var archiveOpen by remember { mutableStateOf(false) }
    // 归档页滚动状态提升到这一层：归档页进详情时整页被移出组合，状态留在这里返回后才能恢复原位。
    val archiveListState = rememberLazyListState()

    // 授权是异步的，回调里没有别的办法知道「谁在等」，必须在发起申请时存下 keys。
    var stationCapture by remember { mutableStateOf<StationCaptureState?>(null) }
    var pendingCaptureKeys by remember { mutableStateOf<List<String>?>(null) }

    /** 采一次现场（定位 + 附近 WiFi）落到规则上；空指纹不写 —— 写进去显示「已记录」却什么都判不了，比留着「未记录」更误导。 */
    fun captureStation(keys: List<String>) {
        val key = keys.firstOrNull() ?: return
        scope.launch {
            stationCapture = StationCaptureState(key, busy = true)
            val capture = StationLocator.capture(context)
            if (capture.permissionMissing) {
                pendingCaptureKeys = keys
                stationCapture = StationCaptureState(key, busy = false, note = PERMISSION_HINT)
                return@launch
            }
            val fingerprint = capture.fingerprint
            if (fingerprint.isEmpty) {
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

    /** 申请精确定位 + NEARBY_WIFI_DEVICES；任意一项被允许就继续采，缺的由 [captureNote] 照实说。 */
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

    // 存记录快照而不是 dedupeKey：富化把截断尾号补成全号后 key 会变，只存 key 详情页会凭空关掉。
    var detailRecord by remember { mutableStateOf<ExpressRecord?>(null) }
    var detailRefreshing by remember { mutableStateOf(false) }

    var identityOpen by remember { mutableStateOf(false) }

    // 授权回来自增，弹窗随之重算「最近驿站」，否则停在授权前的结果。
    var locationTick by remember { mutableIntStateOf(0) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { locationTick++ }

    /**
     * 按需拉当前包裹全轨迹：优先模块进程静默拉（cookie 在内存缓存即可，不依赖菜鸟活着）；
     * 没有缓存 cookie 才广播给菜鸟兜底。超时收位兜底，指示器不能永远转着。
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
        scope.launch { delay(DETAIL_REFRESH_TIMEOUT_MS); detailRefreshing = false }
    }

    val onDetailRefresh: () -> Unit = {
        // 轨迹落库是异步的，刷新时把已经到的先显示出来。
        homeRecords = ExpressRecordStore.load(context)
        if (ExpressSettings.read(context).traceFetchMode == ExpressSettingsKeys.MODE_TRACE_ON_DEMAND) {
            detailRecord?.let(::requestTraceForDetail)
        }
        detailRefreshing = true
    }

    // 运行时不支持 RuntimeShader 时置灰而不隐藏：让用户知道功能存在、为什么用不了。
    val blurSupported = remember { isRuntimeShaderSupported() }
    val blurred = blurBars && blurSupported
    val floating = floatingNavBar
    val liquid = floating && liquidGlass && blurSupported

    val pagerState = rememberPagerState(initialPage = TAB_HOME, pageCount = { TAB_TITLES.size })
    val scrollBehaviors = List(TAB_TITLES.size) { MiuixScrollBehavior() }
    // 必须建在所有二级页提前 return 之前：二级页把主界面从组合树里摘掉，写在 scrollContent 里的
    // rememberScrollState 会跟着没了 —— 从详情返回时列表跳回顶部（2026-09-26 用户报的）。
    val pageScrollStates = List(TAB_TITLES.size) { rememberScrollState() }
    val currentPage = pagerState.currentPage.coerceIn(0, TAB_TITLES.lastIndex)
    val scrollBehavior = scrollBehaviors[currentPage]

    val surface = MiuixTheme.colorScheme.surface
    // drawRect(surface) 是兜底底色，没有它内容未绘制时玻璃层会采到透明。
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val barBlurColors = BlurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = BAR_TINT_ALPHA))),
    )

    LaunchedEffect(pagerState.settledPage) {
        // 切回首页时重读：拦截事件到达时模块可能不在前台。
        if (pagerState.settledPage == TAB_HOME) {
            homeRecords = withContext(Dispatchers.IO) { ExpressRecordStore.load(context) }
        }
        scrollBehaviors.forEach { it.state.heightOffset = 0f }
    }

    val animateToTab: (Int) -> Unit = { index ->
        scope.launch { pagerState.springAnimateToPage(index) }
    }

    /** 恢复备份后 prefs 被整份换掉，所有「从 prefs 读进 state」的都要重读一遍，少一样界面就停在恢复前。 */
    val reloadAfterRestore: () -> Unit = {
        scope.launch {
            val loadedSettings = withContext(Dispatchers.IO) { ExpressSettings.read(context) }
            val loadedRules = withContext(Dispatchers.IO) { ExpressStationRuleStore.load(context) }
            val loadedRecords = withContext(Dispatchers.IO) { ExpressRecordStore.load(context) }
            val loadedEntries = withContext(Dispatchers.IO) { ExpressNotificationLog.snapshot(context) }
            settings = loadedSettings
            blurBars = uiPrefs.blurBars
            floatingNavBar = uiPrefs.floatingNavBar
            liquidGlass = uiPrefs.liquidGlass
            doubleTapPickup = uiPrefs.doubleTapPickup
            hideFromRecents = uiPrefs.hideFromRecents
            onThemeModeChange(uiPrefs.themeMode)
            stationRules = loadedRules
            homeRecords = loadedRecords
            recordEntries = loadedEntries
        }
    }

    // 二级页用提前 return 整页替换主界面；前面的 remember 都是无条件的，早退不会让 Compose 槽位错位。
    if (logPageOpen) {
        LogPage(onBack = { logPageOpen = false })
        return
    }

    // 传全量记录：管理页要能管到所有驿站，包括只有已签收包裹的那些。
    if (stationAdminOpen) {
        StationAdminPage(
            records = homeRecords,
            rules = stationRules,
            // 写入口都收一组键（整行 ruleKeys）：只动主键会让合并过的行当场裂回两行。
            onRename = { keys, display ->
                ExpressStationRuleStore.setRename(context, keys, display)
                stationRules = ExpressStationRuleStore.load(context)
            },
            onIdentitySource = { keys, source ->
                ExpressStationRuleStore.setIdentitySource(context, keys, source)
                stationRules = ExpressStationRuleStore.load(context)
            },
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

    // 包裹详情二级页。整页替换而不是弹窗：一屏轨迹能到二三十行。
    val detail = detailRecord
    if (detail != null) {
        LaunchedEffect(detail) {
            if (ExpressSettings.read(context).traceFetchMode ==
                ExpressSettingsKeys.MODE_TRACE_ON_DEMAND
            ) {
                requestTraceForDetail(detail)
            }
        }

        // 只收 TRACE_ARRIVED：列表重读由上面那层 RECORDS_CHANGED 负责，两条分开正是为了
        // 不让一批数据落库把这里的指示器提前收掉。
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

        // 用 isSamePackageAs 而不是 dedupeKey 相等：兜住「运单号从尾号补成全号」的主键变化合并，
        // 否则那一刻退回旧快照，刚拉到的轨迹看不见。
        val latest = homeRecords.firstOrNull { it.isSamePackageAs(detail) } ?: detail
        val labels = remember(homeRecords, stationRules) {
            ExpressHomeGrouper.stationLabels(homeRecords, stationRules)
        }
        // 轨迹空着时把真实原因透给用户，干等超时再显示「请确认菜鸟在后台」是误导。
        val traceHint = if (CainiaoTraceApi.riskBlocked()) {
            val minutes =
                ((CainiaoTraceApi.riskBlockedUntil - System.currentTimeMillis()) / 60_000L)
                    .coerceAtLeast(1)
            "拉取被淘宝风控暂时拦截，约 $minutes 分钟后重试。"
        } else {
            // lastError 是进程级「最近一次」，本页只在 trace 完全为空且刚触发过拉取时使用。
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

    // 归档页排在详情判定之后是有意的：从归档页点进详情时两个条件同时成立，详情先赢；
    // 详情返回后自然落回归档页而不是被踢回首页。
    if (archiveOpen) {
        ExpressArchivePage(
            records = homeRecords,
            rules = stationRules,
            listState = archiveListState,
            onBack = { archiveOpen = false },
            onOpenDetail = { record -> detailRecord = record },
            archiveRetentionMs = archiveRetentionMs,
        )
        return
    }

    recordDetail?.let { entry ->
        NotificationDetailPage(entry = entry, onBack = { recordDetail = null })
        return
    }

    // 拦截页也排在详情判定之后（与归档页同一个理由）。
    if (interceptPageOpen) {
        ExpressInterceptPage(
            entries = recordEntries.filter {
                it.kind == ExpressNotificationLog.Kind.INTERCEPTED
            },
            onBack = { interceptPageOpen = false },
            onOpen = { recordDetail = it },
        )
        return
    }

    if (noRootPageOpen) {
        NoRootPage(
            settings = settings,
            update = ::updateSettings,
            onBack = { noRootPageOpen = false },
        )
        return
    }

    if (backupPageOpen) {
        BackupPage(
            onDataRestored = reloadAfterRestore,
            onBack = { backupPageOpen = false },
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
                actions = {
                    // 身份码入口只挂首页：它回答的是「站在驿站门口要念什么」。
                    if (currentPage == TAB_HOME) {
                        IconButton(
                            onClick = { identityOpen = true },
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
                // 悬浮胶囊：底栏脱离屏幕边缘，页面内容从它底下穿过。
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
                // 必须同时关掉 pager 自己的 userScrollEnabled：两套手势同时生效时 override
                // 会把点击也吃掉，页面里所有按钮都点不动。
                .pagerGestureOverride(
                    pagerState = pagerState,
                    mode = PagerInterceptionMode.CrossAxisInterceptor,
                    enabled = true,
                )
                .then(if (blurred || floating) Modifier.layerBackdrop(backdrop) else Modifier),
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            overscrollEffect = null,
            pageNestedScrollConnection = PagerGestureNestedScrollConnection,
            flingBehavior = flingBehavior(
                state = pagerState,
                snapAnimationSpec = PagerNavigationSpringSpec,
            ),
        ) { page ->
            val pageScroll = scrollBehaviors[page]
            // 下拉刷新套在这一层外面，靠嵌套滚动拿增量，所以 overScrollVertical 必须留在这一层：
            // miuix 的越界节点检测外层 PullToRefresh 状态，两者本来就是一伙的。
            val scrollContent: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .nestedScroll(pageScroll.nestedScrollConnection)
                        .verticalScroll(pageScrollStates[page], overscrollEffect = null)
                        .padding(top = padding.calculateTopPadding())
                        .padding(vertical = 4.dp),
                ) {
                    when (page) {
                        // 关掉开关传 null：双击手势挂不挂由这一个参数决定，没有第二处判断可以跑偏。
                        TAB_HOME -> HomePage(
                            records = homeRecords,
                            rules = stationRules,
                            onTogglePickup = if (doubleTapPickup) togglePickup else null,
                            onOpenDetail = { record ->
                                detailRecord = record
                                // 顺手重读：到站件轨迹是异步落库的，不重读详情页首次打开经常是空的。
                                scope.launch {
                                    homeRecords = withContext(Dispatchers.IO) {
                                        ExpressRecordStore.load(context)
                                    }
                                }
                            },
                            onOpenArchive = { archiveOpen = true },
                            archiveRetentionMs = archiveRetentionMs,
                        )
                        // 「记录」只看投递记录；拦截记录是另一个视图，混在一页会拉歪「已发出 N/M」统计。
                        TAB_RECORDS -> {
                            // 过滤结果按 entries 记忆：这条重组路径很频繁，别每次都重建列表。
                            val deliveredEntries = remember(recordEntries) {
                                recordEntries.filter {
                                    it.kind == ExpressNotificationLog.Kind.DELIVERED
                                }
                            }
                            RecordPage(
                                entries = deliveredEntries,
                                onOpen = { recordDetail = it },
                            )
                        }
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
                                onHideFromRecentsChange(it)
                            },
                            blurSupported = blurSupported,
                            stationRules = stationRules,
                            onOpenStations = { stationAdminOpen = true },
                            // 拦截随时可能发生，点开那一刻先重读再进二级页。
                            interceptCount = recordEntries.count {
                                it.kind == ExpressNotificationLog.Kind.INTERCEPTED
                            },
                            onOpenIntercepts = {
                                scope.launch {
                                    recordEntries = withContext(Dispatchers.IO) {
                                        ExpressNotificationLog.snapshot(context)
                                    }
                                }
                                interceptPageOpen = true
                            },
                            onOpenNoRoot = { noRootPageOpen = true },
                            onOpenBackup = { backupPageOpen = true },
                            onDataRestored = reloadAfterRestore,
                        )
                    }
                    Spacer(Modifier.height(padding.calculateBottomPadding()))
                    Spacer(Modifier.height(4.dp))
                }
            }
            // 只有快递 / 记录两页套下拉刷新：设置页满屏开关、关于页静态信息，挂上去只会拉出空圈。
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

        // 必须挂在 Scaffold 里面：miuix 的 OverlayDialog 靠 Scaffold 提供的 MiuixPopupHost 渲染，
        // 放在外面会静默不显示。传全量记录：弹窗要自己算「最近的驿站」，分组不够用。
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

/** 给一页套上下拉刷新：中文文案、contentPadding 把指示器推到顶栏下、两页各自持有 isRefreshing。 */
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

/** 本地重读几毫秒就完，不兜这一下指示器松手即收，刷新不可见。 */
private const val MIN_REFRESH_VISIBLE_MS = 400L

/** 详情页按需拉取的收位超时：广播没来（菜鸟不在、风控挡下）时指示器到点就收。 */
private const val DETAIL_REFRESH_TIMEOUT_MS = 6_000L

private fun stationAdminSummary(rules: ExpressStationRules): String =
    if (rules.isEmpty) {
        "合并同一驿站的不同写法、改显示名称"
    } else {
        "已设置 ${rules.renames.size} 条规则"
    }

// ---------------------------------------------------------------- 现场采集（驿站地址 / 指纹）

/** 5 位小数 ≈ 1 米，正好是驿站门口的尺度。 */
private fun coordinatesOf(position: GeoPoint?): String =
    position?.let { "%.5f, %.5f".format(java.util.Locale.US, it.lat, it.lng) } ?: ""

/** 如实说采到了什么缺了什么：缺 WiFi（系统限流）与缺定位（没开）处置完全不同，都是「成功」用户只能反复试点。 */
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
        if (capture.addressText == null && fingerprint.position != null) {
            add("地址一栏用坐标代替（本机没有可用的地址反查服务）")
        }
    }
    return parts.joinToString("；") + "。"
}

private const val PERMISSION_HINT = "需要先允许定位权限，允许后会自动接着获取。"

private const val PERMISSION_DENIED_HINT = "缺少定位权限：请在系统设置授权后重试。"

private const val EMPTY_CAPTURE_HINT = "未取到定位与 WiFi：确认两者已打开后在驿站附近重试。"

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
    interceptCount: Int,
    onOpenIntercepts: () -> Unit,
    onOpenNoRoot: () -> Unit,
    onOpenBackup: () -> Unit,
    onDataRestored: () -> Unit,
) {
    val context = LocalContext.current

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
        SwitchPreference(
            title = "隐藏后台卡片",
            summary = "从系统「最近任务」里隐藏本模块",
            checked = hideFromRecents,
            onCheckedChange = onHideFromRecentsChange,
        )
    }

    GroupTitle("取件")
    SettingsCard {
        SwitchPreference(
            title = "双击确认取件",
            summary = "双击到站卡片标记已取件",
            checked = doubleTapPickup,
            onCheckedChange = onDoubleTapPickupChange,
        )
        ArrowPreference(
            title = "驿站管理",
            summary = stationAdminSummary(stationRules),
            onClick = onOpenStations,
        )
    }

    GroupTitle("包裹详情")
    SettingsCard {
        SegmentedRow(
            options = listOf(
                ExpressSettingsKeys.MODE_TRACE_ON_DEMAND to "点击时获取",
                ExpressSettingsKeys.MODE_TRACE_AUTO to "自动更新",
            ),
            selected = settings.traceFetchMode,
            onSelect = { mode -> update { it.copy(traceFetchMode = mode) } },
        )
    }

    GroupTitle("归档")
    SettingsCard {
        // 判据是物流签收时刻，双击标记的「已取件」不算（下面那句提示必须写，否则用户会疑惑「取完了怎么还在」）。
        SegmentedRow(
            options = ExpressSettingsKeys.ARCHIVE_MODE_OPTIONS,
            selected = settings.archiveMode,
            onSelect = { mode -> update { it.copy(archiveMode = mode) } },
        )
        HintText("以物流签收为准；手动标记取件的包裹要等物流签收后才归档")
    }

    GroupTitle("自动轮查")
    SettingsCard {
        SwitchPreference(
            title = "自动轮查",
            summary = if (settings.autoWatch) "通知栏会有一条常驻通知「自动轮查」" else null,
            checked = settings.autoWatch,
            onCheckedChange = { on ->
                update { it.copy(autoWatch = on) }
                // 起停立刻生效：此刻在前台，拉起前台服务是合规的。
                AutoWatch.sync(context, if (on) "设置页开启" else "设置页关闭")
            },
        )
        if (settings.autoWatch) {
            SegmentedRow(
                options = ExpressSettingsKeys.WATCH_SCOPE_OPTIONS,
                selected = settings.watchScope,
                onSelect = { scope -> update { it.copy(watchScope = scope) } },
            )
            HintText("「在途」= 运输中 + 派送中；「未完成」另含到站待取，请求数翻倍。")
            val gapEntries = watchEntries(
                ExpressSettingsKeys.WATCH_GAP_OPTIONS,
                settings.watchGapMinutes,
            )
            OverlayDropdownPreference(
                title = "件与件间隔",
                items = gapEntries.map { it.second },
                selectedIndex = gapEntries.indexOfFirst { it.first == settings.watchGapMinutes }
                    .coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    val minutes = gapEntries.getOrNull(index)?.first
                    if (minutes != null) {
                        update { it.copy(watchGapMinutes = minutes) }
                        // 服务可能正睡在更长的等待里：改小间隔却等旧整段，界面表现成「改了没用」。
                        AutoWatch.reschedule(context, minutes.toLong() * 60_000L)
                    }
                },
            )
            val cycleEntries = watchEntries(
                ExpressSettingsKeys.WATCH_CYCLE_OPTIONS,
                settings.watchCycleMinutes,
            )
            OverlayDropdownPreference(
                title = "一轮间隔",
                items = cycleEntries.map { it.second },
                selectedIndex = cycleEntries.indexOfFirst { it.first == settings.watchCycleMinutes }
                    .coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    val minutes = cycleEntries.getOrNull(index)?.first
                    if (minutes != null) {
                        update { it.copy(watchCycleMinutes = minutes) }
                        AutoWatch.reschedule(context, minutes.toLong() * 60_000L)
                    }
                },
            )
            SwitchPreference(
                title = "轮查通知",
                summary = "通知栏那条常驻通知（关掉只压成静默，撤不掉）",
                checked = settings.watchNotification,
                onCheckedChange = { on -> update { it.copy(watchNotification = on) } },
            )
            if (!settings.watchNotification) {
                // 前台服务的通知在 Android 上撤不掉，写「不再显示」就是骗人。
                HintText("Android 要求前台服务必须有通知：已压到最低优先级，不占状态栏、不出声，通知栏仍有一条。")
            }
            SwitchPreference(
                title = "夜间暂停",
                summary = "停在 ${settings.quietWindowLabel()}",
                checked = settings.watchQuiet,
                onCheckedChange = { on -> update { it.copy(watchQuiet = on) } },
            )
            if (settings.watchQuiet) {
                OverlayDropdownPreference(
                    title = "夜间开始",
                    items = QUIET_START_LABELS,
                    selectedIndex = QUIET_START_HOURS.indexOf(settings.watchQuietStart).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        QUIET_START_HOURS.getOrNull(index)?.let { hour ->
                            update { it.copy(watchQuietStart = hour) }
                        }
                    },
                )
                OverlayDropdownPreference(
                    title = "早上恢复",
                    items = QUIET_END_LABELS,
                    selectedIndex = QUIET_END_HOURS.indexOf(settings.watchQuietEnd).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        QUIET_END_HOURS.getOrNull(index)?.let { hour ->
                            update { it.copy(watchQuietEnd = hour) }
                        }
                    },
                )
            }
            HintText(
                // 必须由 watchCadenceLabel() 生成，写死会「说一套做一套」。
                "当前节奏：${settings.watchCadenceLabel()}（间隔 ±随机抖动，避免固定周期）。" +
                    if (settings.noRootListener) {
                        // 免 root 下每轮起点自己找新包裹，上一句「新包裹只能靠推送」在这种配置下是假的。
                        "每轮顺带同步淘宝订单（免 root），新包裹会自己出现；拼多多与他人寄件仍只靠通知文案。"
                    } else {
                        "只刷新已有包裹的轨迹，新包裹由菜鸟/淘宝推送。"
                    },
            )
        }
    }

    GroupTitle("工作模式")
    SettingsCard {
        SegmentedRow(
            options = ExpressSettingsKeys.MODE_OPTIONS,
            selected = settings.mode,
            onSelect = { mode -> update { it.copy(mode = mode) } },
        )
        // 拦截模式不可逆：原通知被吞后模块自己发不出去，那条通知就永远丢了。
        if (settings.mode == ExpressSettingsKeys.MODE_INTERCEPT) {
            HintText("原通知会被吞掉，请先在放行模式确认通知能收到。")
        }
    }

    GroupTitle("通知拦截")
    SettingsCard {
        // 工作模式决定「正常处理的通知长什么样」，这里决定「哪几类根本不值得打扰」；
        // 可拦截分类的唯一出处是 NotificationCategory.toggleable，界面只负责画。
        NotificationCategory.toggleable.forEach { category ->
            SwitchPreference(
                title = "拦截${category.displayName}",
                summary = category.summary,
                checked = category in settings.interceptedCategories,
                onCheckedChange = { on ->
                    update { snapshot ->
                        snapshot.copy(
                            interceptedCategories = if (on) {
                                snapshot.interceptedCategories + category
                            } else {
                                snapshot.interceptedCategories - category
                            },
                        )
                    }
                },
            )
        }
        // 「勾了却没反应」的唯一成因：工作模式是「关闭」时 hook 在判定之前就放行。
        if (settings.mode == ExpressSettingsKeys.MODE_OFF &&
            settings.interceptedCategories.isNotEmpty()
        ) {
            HintText("工作模式为「关闭」，以上拦截不生效，请先选择模式。")
        }
        ArrowPreference(
            title = "拦截记录",
            summary = if (interceptCount == 0) "暂无记录" else "已拦截 $interceptCount 条",
            onClick = onOpenIntercepts,
        )
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
        HintText("未连接：LSPosed 未启用本模块或作用域缺失。设置仍会保存，但 hook 读不到。")
    }

    // 免 root 与「框架」紧挨着：回答的是同一个问题（数据怎么来的），是框架不可用时的另一条腿。
    GroupTitle("免 root 模式")
    SettingsCard {
        SwitchPreference(
            title = "免 root 采集",
            summary = "用系统「通知使用权」读快递通知，不需要 root",
            checked = settings.noRootListener,
            onCheckedChange = { on -> update { it.copy(noRootListener = on) } },
        )
        ArrowPreference(
            title = "免 root 模式",
            summary = noRootSummary(settings),
            onClick = onOpenNoRoot,
        )
    }

    GroupTitle("备份与恢复")
    SettingsCard {
        ArrowPreference(
            title = "备份与恢复",
            summary = backupEntrySummary(),
            onClick = onOpenBackup,
        )
    }
}

/** 只说当前状态（备份在哪、上次何时备的）；不加 remember，用户从二级页改完回来这行必须是新的。 */
@Composable
private fun backupEntrySummary(): String {
    val context = LocalContext.current
    val config = BackupSettings.load(context)
    val auto = when {
        config.onDataChange && config.intervalMs > BackupSettings.INTERVAL_OFF -> "有变更 + 定时"
        config.onDataChange -> "有变更时"
        config.intervalMs > BackupSettings.INTERVAL_OFF -> BackupSettings.intervalLabel(config.intervalMs)
        else -> "手动"
    }
    val last = if (config.lastBackupAt <= 0L) {
        "从未备份"
    } else {
        val age = (System.currentTimeMillis() - config.lastBackupAt).coerceAtLeast(0L)
        // describeAge 在 60 秒内返回「刚刚」，拼成「上次 刚刚」很怪。
        if (age < 60_000L) "刚刚备份" else "上次 ${NoRootPlan.describeAge(age)}"
    }
    val lock = if (config.encrypt) " · 已加密" else ""
    return "$auto · $last$lock"
}

/** 不加 remember：这是用户回来看状态的地方，缓存会让「刚授权完还显示未授予」。 */
@Composable
private fun noRootSummary(settings: ExpressSettingsSnapshot): String {
    val context = LocalContext.current
    if (!settings.noRootListener) return "已关闭"
    if (settings.mode == ExpressSettingsKeys.MODE_OFF) return "工作模式为「关闭」，不会生效"
    return if (NoRootAccess.isListenerAccessGranted(context)) {
        "采集已开启"
    } else {
        "等待「通知使用权」授权"
    }
}

// ---------------------------------------------------------------- 记录页

/** 投递记录页：按天分组（同一天共用一行日标题），统计进分组抬头，整卡可点开详情对照原文。 */
@Composable
private fun RecordPage(
    entries: List<ExpressNotificationLog.Entry>,
    onOpen: (ExpressNotificationLog.Entry) -> Unit,
) {
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

    // todayStart 只在重组时算一次：放 dayLabel 里每条记录都要重建 Calendar，30 条 × 每次重组就是 30 个。
    val todayStart = remember {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    entries.take(30).forEachIndexed { index, entry ->
        val day = dayLabel(entry.at, todayStart)
        val previousDay = entries.getOrNull(index - 1)?.let { dayLabel(it.at, todayStart) }
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
            onClick = { onOpen(entry) },
        )
    }
}

/** 归档判据用的「一天」毫秒数。 */
private const val DAY_MS = 24 * 60 * 60 * 1000L

/** SimpleDateFormat 构造不便宜且非线程安全；所有调用都在重组（主线程）里，缓存成单例即可。 */
private val recordDayFormat = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
private val recordClockFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

/** [todayStart] 由调用方在重组里 remember，避免每条记录重建 Calendar；跨天后下一次重组自然刷新。 */
private fun dayLabel(at: Long, todayStart: Long): String = when {
    at >= todayStart -> "今天"
    at >= todayStart - DAY_MS -> "昨天"
    else -> recordDayFormat.format(java.util.Date(at))
}

private fun clockLabel(at: Long): String = recordClockFormat.format(java.util.Date(at))

// ---------------------------------------------------------------- 关于页（含诊断）

private val BUILD_TIME_DISPLAY: String = java.text.SimpleDateFormat(
    "yyyy-MM-dd HH:mm",
    java.util.Locale.CHINA,
).format(java.util.Date(BuildConfig.BUILD_TIME))

/** 关于页：诊断信息也放这里 —— 模块常年无界面，没有这些信息就无从排查。 */
@Composable
private fun AboutPage(settings: ExpressSettingsSnapshot, onOpenLog: () -> Unit) {
    val context = LocalContext.current
    val permissionOk = ExpressNotificationPoster.hasPermission(context)
    var hookForceRequested by remember { mutableStateOf(false) }

    GroupTitle("模块状态")
    SettingsCard {
        InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // 多台设备多渠道的对账依据：版本号相同但构建不同。
        InfoRow("构建时间", BUILD_TIME_DISPLAY)
        InfoRow("构建模式", if (BuildConfig.DEBUG) "debug" else "release")
        InfoRow(
            "观察模式",
            if (BuildConfig.OBSERVE_ONLY) "开（只判定不投递）" else "关（正常投递）",
        )
        InfoRow("数据来源", NoRootAccess.sourceStatus(context).mode.displayName)
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
                "多次启动 hook 安装失败，已自动停用 system_server hook 以避免开机循环；" +
                    "模块不再拦截，设备可正常使用。",
            )
            CardActionRow("复位并重新启用") {
                // 先写本地 prefs 再投影 RemotePreferences：框架未连接时直接写会静默失败，
                // 而用户点这个按钮时框架多半就是有问题状态。
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
                "看门狗每次开机上报一次。未安装的常见原因：LSPosed 未启用模块、" +
                    "作用域缺 system、或框架不支持 system_server hook。",
            )
        }
    }

    GroupTitle("接入须知")
    SettingsCard {
        HintText("1. 在 LSPosed 启用本模块，作用域必须包含 system。")
        HintText(
            "2. 作用域还需包含菜鸟与淘宝：前者供包裹数据，后者供登录态" +
                "（菜鸟未绑淘宝时，轨迹只能走淘宝的 cookie）。",
        )
        HintText("3. 改作用域或模块代码后需重启设备。")
        HintText("4. 模块界面至少打开过一次，广播才能送达（stopped 状态）。")
        HintText("5. 若「记录」页显示未发出，先看上面的通知权限。")
    }

    GroupTitle("诊断")
    SettingsCard {
        // 一行入口不摊开日志：ArrowPreference 副标题塞不下时间戳，折行会把卡片撑得比正文还高。
        val logs = ModuleLogBuffer.snapshot()
        ArrowPreference(
            title = "模块日志",
            summary = logSummary(logs),
            onClick = onOpenLog,
        )
    }
}
