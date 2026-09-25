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
import io.github.YGHFv.ReaPressExtend.core.ExpressRule
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.relay.WatchdogReporter
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
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
    LaunchedEffect(resumeTick) {
        homeRecords = ExpressRecordStore.load(context)
    }

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
                    TAB_HOME -> HomePage(records = homeRecords)
                    TAB_RECORDS -> RecordPage()
                    TAB_ABOUT -> AboutPage(settings)
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
                        blurSupported = blurSupported,
                    )
                }
                Spacer(Modifier.height(padding.calculateBottomPadding()))
                Spacer(Modifier.height(4.dp))
            }
        }
    }
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
    blurSupported: Boolean,
) {
    val context = LocalContext.current

    GroupTitle("界面")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
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

    GroupTitle("工作模式")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        // 一个三态控件，不再有「总开关 + 模式」两个能互相矛盾的入口。
        // 以前那个开关在 system_server 侧压根没被读过，是个纯装饰。
        InfoRow("当前", modeLabel(settings.mode))
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
                    "模块判定快递通知并额外发一条，原通知照常显示。\n" +
                        "建议先用这个模式跑几天，确认模块的通知都发得出来。"
                else ->
                    "命中快递通知时吞掉原通知，只留模块发的那条。\n" +
                        "若模块的通知发不出去（权限被收回、进程被冻结），原通知又已经被吞，" +
                        "这一条就彻底看不到了 —— 所以切换前请先确认放行模式下通知收得到。"
            },
        )
    }

    GroupTitle("来源")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
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
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        InfoRow("当前阈值", "${settings.confidenceThreshold} / 100")
        SegmentedRow(
            options = listOf("30" to "宽松", "50" to "默认", "70" to "严格"),
            selected = settings.confidenceThreshold.toString(),
            onSelect = { value ->
                value.toIntOrNull()?.let { threshold ->
                    update { it.copy(confidenceThreshold = threshold) }
                }
            },
        )
        HintText(
            "阈值越高越不容易误拦非快递通知，但可能漏掉信息量少的快递提醒。" +
                "判定依据是关键词命中 + 运单号/取件码等结构化字段。",
        )
    }

    GroupTitle("关键词")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        InfoRow("内置关键词", "${ExpressRule.DEFAULT_KEYWORDS.size} 个")
        InfoRow("自定义追加", "${settings.extraKeywords.size} 个")
        InfoRow("排除词", "${settings.excludeKeywords.size} 个")
        HintText("自定义关键词需要键盘输入，当前版本先用内置值；后续版本提供编辑入口。")
    }

    GroupTitle("框架")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        InfoRow(
            "Xposed 服务",
            if (ExpressSettings.isServiceAvailable()) "已连接" else "未连接（设置无法同步给 hook）",
            if (ExpressSettings.isServiceAvailable()) null else MiuixTheme.colorScheme.error,
        )
        HintText(
            "未连接说明 LSPosed 没启用本模块、或模块不在作用域内。" +
                "此时界面上的设置仍会保存，但被注入的进程读不到。",
        )
    }
}

// ---------------------------------------------------------------- 记录页

/**
 * 投递记录页。
 *
 * 按天分组：同一天只出一行日标题（今天 / 昨天 / MM-dd），卡片里只留 HH:mm。
 * 比每条都写满「09-26 00:55:44」清爽，也更容易按天扫读 —— 排查「昨晚那条发了没有」
 * 时，眼睛先落到日标题上。
 */
@Composable
private fun RecordPage() {
    val context = LocalContext.current
    // 每次进入这一页重读一次；模块常年无界面，进来看到的就是最新状态。
    val entries = remember { ExpressNotificationLog.snapshot(context) }

    if (entries.isEmpty()) {
        GroupTitle("通知投递记录")
        RecordCard(
            title = "暂无记录",
            description = "拦截到快递通知后会出现在这里。这一页记录的是**投递结果**" +
                "（模块的通知有没有真的发出去），不是包裹列表。",
        )
        return
    }

    GroupTitle("通知投递记录")
    RecordCard(
        title = "已发出 ${entries.count { it.delivered }} / ${entries.size}",
        description = if (entries.any { !it.delivered }) {
            "标红的是没发出去的 —— 展开看失败原因。常见原因是通知权限未授予。"
        } else {
            "全部投递成功。"
        },
    )

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

/** 模式的中文名。文案表在 [ExpressSettingsKeys.MODE_OPTIONS]，界面与诊断摘要共用一份。 */
private fun modeLabel(mode: String): String =
    ExpressSettingsKeys.MODE_OPTIONS.firstOrNull { it.first == mode }?.second ?: mode

/** 卡片右上角的时刻（同一天内只需要时分）。 */
private fun clockLabel(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(at))

// ---------------------------------------------------------------- 关于页（含诊断）

/**
 * 关于页。诊断信息也放在这里 —— 它本来就是「出问题时才看」的内容，
 * 单独占一个页签不值得，但也不能藏起来（模块常年无界面，没有这些信息就无从排查）。
 */
@Composable
private fun AboutPage(settings: ExpressSettingsSnapshot) {
    val context = LocalContext.current
    val permissionOk = ExpressNotificationPoster.hasPermission(context)
    var hookForceRequested by remember { mutableStateOf(false) }

    GroupTitle("模块状态")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        InfoRow("构建模式", if (BuildConfig.DEBUG) "debug" else "release")
        InfoRow(
            "观察模式",
            if (BuildConfig.OBSERVE_ONLY) "开（只判定不投递）" else "关（正常投递）",
        )
        InfoRow("设置摘要", settings.summary())
    }

    GroupTitle("权限")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
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
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
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
            Text(
                reason,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.error,
            )
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

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        DiagnosticNote("1. 在 LSPosed 里勾选本模块，作用域必须包含 system（全局拦截的前提）。")
        DiagnosticNote("2. 改动作用域或模块代码后需重启设备，system_server 里的 hook 才会更新。")
        DiagnosticNote("3. 模块界面至少打开过一次才算脱离 stopped，广播才收得到。")
        DiagnosticNote("4. 若「记录」页显示未发出，先看上面的通知权限。")
    }

    GroupTitle("模块日志")
    val logs = remember { ModuleLogBuffer.snapshot().take(40) }
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        if (logs.isEmpty()) {
            InfoRow("暂无日志", "")
        } else {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                for (line in logs) {
                    Text(
                        "${ModuleLogBuffer.formatTime(line.at)} ${line.level} ${line.message}",
                        fontSize = 11.sp,
                        color = if (line.level == "ERROR") {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }
}
