package io.github.YGHFv.ReaPressExtend.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import io.github.YGHFv.ReaPressExtend.core.ExpressRule
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationRuleStore
import io.github.YGHFv.ReaPressExtend.relay.WatchdogReporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
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

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ModuleLogBuffer.attach(this)
        super.onCreate(savedInstanceState)
        uiPrefs = ExpressUiPrefs.of(this)
        themeModeState.intValue = uiPrefs.themeMode
        applyWindowBackground()
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")
        requestNotificationPermissionIfNeeded()

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

    private companion object {
        const val LOG_TAG = "ReaPress"
        const val REQUEST_CODE = 4201
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 外观状态提升到这里：设置页改它，顶栏/底栏读它，改完要立刻重绘。
    var blurBars by remember { mutableStateOf(uiPrefs.blurBars) }
    var floatingNavBar by remember { mutableStateOf(uiPrefs.floatingNavBar) }
    var liquidGlass by remember { mutableStateOf(uiPrefs.liquidGlass) }
    // 双击确认取件。和上面三个一样只影响本机界面，所以也走 uiPrefs 而不是功能设置。
    var doubleTapPickup by remember { mutableStateOf(uiPrefs.doubleTapPickup) }

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

    // 运行时不支持 RuntimeShader（Android 13 以下）时，模糊与液态玻璃都没有效果。
    // 不隐藏开关而是置灰：用户能看到这些功能存在、知道为什么现在用不了。
    val blurSupported = remember { isRuntimeShaderSupported() }
    val blurred = blurBars && blurSupported
    val floating = floatingNavBar
    val liquid = floating && liquidGlass && blurSupported

    val pagerState = rememberPagerState(initialPage = TAB_HOME, pageCount = { TAB_TITLES.size })
    val scrollBehaviors = List(TAB_TITLES.size) { MiuixScrollBehavior() }
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
            onRename = { key, display ->
                ExpressStationRuleStore.setRename(context, key, display)
                stationRules = ExpressStationRuleStore.load(context)
            },
            onBack = { stationAdminOpen = false },
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
                        .verticalScroll(rememberScrollState(), overscrollEffect = null)
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

/** 刷新指示器的四段文案（下拉 / 松手 / 刷新中 / 完成）。miuix 默认是英文，必须覆盖。 */
private val REFRESH_TEXTS = listOf("下拉刷新", "松手刷新", "正在刷新…", "刷新成功")

/**
 * 下拉刷新的指示器**最短**显示时长。
 *
 * 重读本地 prefs 是几毫秒的事，不兜这一下的话指示器在松手那一帧就收完了，
 * 用户看不到任何「刷新发生了」的反馈。取值刚好够看清，不追求「像在忙」。
 */
private const val MIN_REFRESH_VISIBLE_MS = 400L

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
    blurSupported: Boolean,
    stationRules: ExpressStationRules,
    onOpenStations: () -> Unit,
) {
    GroupTitle("界面")
    SettingsCard {
        OverlayDropdownPreference(
            title = "主题",
            summary = "跟随系统，或固定为日间/夜间",
            items = ExpressUiPrefs.THEME_LABELS,
            selectedIndex = themeMode.coerceIn(
                ExpressUiPrefs.THEME_FOLLOW_SYSTEM,
                ExpressUiPrefs.THEME_DARK,
            ),
            onSelectedIndexChange = onThemeModeChange,
        )
        SwitchPreference(
            title = "模糊",
            summary = if (blurSupported) {
                "启用顶栏和底栏的模糊效果"
            } else {
                "当前系统版本不支持（需 Android 13 及以上）"
            },
            checked = blurBars,
            enabled = blurSupported,
            onCheckedChange = onBlurBarsChange,
        )
        SwitchPreference(
            title = "悬浮底栏",
            summary = "使用 Apple 风格的悬浮底栏",
            checked = floatingNavBar,
            onCheckedChange = onFloatingNavBarChange,
        )
        if (floatingNavBar) {
            SwitchPreference(
                title = "液态玻璃",
                summary = if (blurSupported) {
                    "启用悬浮底栏的液态玻璃效果"
                } else {
                    "当前系统版本不支持（需 Android 13 及以上）"
                },
                checked = liquidGlass,
                enabled = blurSupported,
                onCheckedChange = onLiquidGlassChange,
            )
        }
    }

    GroupTitle("取件")
    SettingsCard {
        // 这一组的开关和上面「界面」那组一样只影响本机：它们不改拦截、不改判定，
        // 也不投影给 system_server（见 ExpressUiPrefs 的类注释）。
        SwitchPreference(
            title = "双击确认取件",
            summary = "在到站卡片上双击取件码那一行，标记这件已取走",
            checked = doubleTapPickup,
            onCheckedChange = onDoubleTapPickupChange,
        )
        HintText(
            "双击范围是整行，不用对准那串数字。\n" +
                "同一驿站有多件时，已确认的会变灰、沉到卡片下面；全部确认后才一起移出「到站包裹」。\n" +
                "再双击一次可以撤销。标记只存在本机，不会同步给菜鸟或快递公司。",
        )
        CardDivider()
        // 驿站管理放在「取件」组里而不是单开一组：它服务的就是取件（去哪个驿站、认哪几张卡片），
        // 单开一组会给它一个与其分量不符的位置。
        ArrowPreference(
            title = "驿站管理",
            summary = stationAdminSummary(stationRules),
            onClick = onOpenStations,
        )
    }

    GroupTitle("工作模式")
    SettingsCard {
        // 一个三态控件，不再有「总开关 + 模式」两个能互相矛盾的入口。
        // 以前那个开关在 system_server 侧压根没被读过，是个纯装饰。
        // 不再另起一行写「当前：xx」—— 分段的选中态已经把它说清楚了，重复一行只是噪音。
        SegmentedRow(
            options = ExpressSettingsKeys.MODE_OPTIONS,
            selected = settings.mode,
            onSelect = { mode -> update { it.copy(mode = mode) } },
        )
        HintText(
            when (settings.mode) {
                ExpressSettingsKeys.MODE_OFF ->
                    "模块不处理任何通知，原通知原样显示。"
                ExpressSettingsKeys.MODE_PASSTHROUGH ->
                    "命中快递通知时额外发一条，原通知照常显示。建议先用这个模式跑几天。"
                else ->
                    "命中快递通知时吞掉原通知，只留模块发的那条。\n" +
                        "模块的通知发不出去时原通知已经没了，所以先确认放行模式下收得到再切。"
            },
        )
    }

    GroupTitle("来源")
    SettingsCard {
        SwitchPreference(
            title = "菜鸟",
            summary = "com.cainiao.wireless",
            checked = settings.sourceCainiao,
            onCheckedChange = { on -> update { it.copy(sourceCainiao = on) } },
        )
        SwitchPreference(
            title = "拼多多",
            summary = "com.xunmeng.pinduoduo（仅通知文案，未做富化）",
            checked = settings.sourcePinduoduo,
            onCheckedChange = { on -> update { it.copy(sourcePinduoduo = on) } },
        )
        SwitchPreference(
            title = "淘宝",
            summary = "com.taobao.taobao（仅通知文案，未做富化）",
            checked = settings.sourceTaobao,
            onCheckedChange = { on -> update { it.copy(sourceTaobao = on) } },
        )
        SwitchPreference(
            title = "快递短信",
            summary = "跟随短信通知，无需额外读短信权限",
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
        HintText("2. 改动作用域或模块代码后需重启设备，system_server 里的 hook 才会更新。")
        HintText("3. 模块界面至少打开过一次才算脱离 stopped，广播才收得到。")
        HintText("4. 若「记录」页显示未发出，先看上面的通知权限。")
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
