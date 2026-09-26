package io.github.YGHFv.ReaPressExtend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.IdentitySource
import io.github.YGHFv.ReaPressExtend.core.StationFingerprint
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/**
 * 驿站管理 —— 设置页那一行点进来的二级页面。
 *
 * ## 它解决的问题
 *
 * 首页按驿站分组，而同一个取件地点在通知和菜鸟里可能是两个名字。模块会先自动认一批
 * （地址详略、品牌前缀、括号，见 `ExpressStationName`），但**机器认不出来的只能人来**：
 * 「这个驿站通知里叫 A、菜鸟里叫 B，其实是同一处」这件事没有任何字符串规律，只有住那儿的人知道。
 *
 * ## 三类规则，同一页填
 *
 * 它们都是「宿主给不出、只有用户知道」的信息，所以挤在同一张详情页里
 * （规则表见 [ExpressStationRules]）：
 * - **外显名称** —— 显示成「家门口」这种只有自己看得懂的名字；填成另一个驿站的名字就是**合并**；
 * - **默认身份码** —— 这站取件时出示**哪个平台**的码（菜鸟里叫「出库码」）。
 *   各平台的码互不通用：菜鸟驿站要菜鸟的，拼多多驿站要拼多多的，出示错了机器认不出来，
 *   用户就白跑一趟。所以这一项是**驿站属性**，不是全局一个开关（[IdentitySource]）。
 * - **精确地址** —— 宿主的 `stationDeliveryAddress` 常常空着，而「去哪个楼哪个门」正是要去的地方。
 *   这一项**不让用户手打**（2026-09-26 用户拍板）：地址长、门牌容易写错，而且真正有用的是
 *   「这一刻我站在哪」。改成**人在驿站门口点一次「获取当前位置」**，定位 + 附近 WiFi 一起记下来
 *   （[StationFingerprint]），地址文本由系统反查、拿不到就用坐标兜底。
 *   这一条既是详情页显示的那行地址，也是将来「到驿站附近提醒有件可取」的地基。
 *
 * ⚠️ 「默认取件码」那张表（[ExpressStationRules.pickupCodes]）仍在、仍被 `pickupCodeOf`
 * 使用，只是**这一版把入口让给了身份码**（2026-09-26 用户拍板：「那个是默认身份码」）。
 * 数据和回退逻辑都不删 —— 将来要恢复入口，加一个输入框即可。
 *
 * ## 为什么是「列表 → 详情」两层
 *
 * 一个驿站要改名、选合并对象、填码填地址、还要让用户看见「这站是哪几件」—— 全塞进列表里，
 * 每行都会变成一小片表单，一扫就分不清哪行对应哪个驿站。列表只回答「有哪些驿站」，
 * 操作全部收进详情页。
 *
 * ## 一行 = 一组规则键
 *
 * 所有回调传的都是 [ExpressStationSummary.ruleKeys] 整组，不是单个 key。用户把两处合并到
 * 一行之后，改名 / 填码 / 恢复默认必须同时作用在链上的每一个键上 —— 只动一个会让那一行
 * 当场裂回两行（2026-09-26 用户报的「合并了还是两条」就是这么来的）。
 *
 * 版面沿用模块那套词汇（`ExpressUiKit` 文件头）：`GroupTitle` + `SettingsCard`，
 * 没有为这一页新造字号或颜色。
 */
@Composable
internal fun StationAdminPage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules,
    onRename: (keys: List<String>, display: String?) -> Unit,
    onIdentitySource: (keys: List<String>, source: IdentitySource?) -> Unit,
    onCaptureLocation: (keys: List<String>) -> Unit,
    onRestore: (keys: List<String>) -> Unit,
    /** 「获取当前位置」这一次动作的状态；null = 没有任何采集在跑或刚跑完。 */
    capture: StationCaptureState?,
    onBack: () -> Unit,
) {
    // 驿站列表按当前规则算：改完名字立刻能看到结果，不用退出去再进来。
    val stations = remember(records, rules) {
        ExpressHomeGrouper.stations(records, rules)
    }
    var openedKey by remember { mutableStateOf<String?>(null) }
    // 记住的是 key 而不是 summary 对象：summary 每次重组都是新实例，而 key 在改名前后不变
    // （改名改的是规则的值，不是键），所以保存之后详情页不会自己弹回列表。
    val opened = stations.firstOrNull { it.key == openedKey }

    if (opened == null) {
        StationListPage(stations = stations, onOpen = { openedKey = it }, onBack = onBack)
    } else {
        StationDetailPage(
            station = opened,
            allStations = stations,
            rules = rules,
            onRename = onRename,
            onIdentitySource = onIdentitySource,
            onCaptureLocation = onCaptureLocation,
            onRestore = onRestore,
            // 采集状态按 key 归属：用户点完「获取」又退到列表、进另一个驿站时，
            // 上一条的提示不该跟过来（那会显得像「这一站也采过了」）。
            capture = capture?.takeIf { it.key == opened.key },
            onBack = { openedKey = null },
        )
    }
}

/**
 * 「获取当前位置」这一次动作的状态。
 *
 * @param key 这次采集属于哪一行（[ExpressStationSummary.key]）。**必须有它** —— 采集是异步的，
 *   而用户可以在等待期间退出去点开另一个驿站，那时上一条结果落到界面上就是错的。
 * @param busy 还在采（按钮显示「正在获取…」并置灰）。
 * @param note 上一次的结果说明（成功 / 部分成功 / 失败原因）。**如实说**：
 *   「只拿到定位没拿到 WiFi」和「两样都没有」要能分开，用户才知道要不要换个地方再点一次。
 */
internal data class StationCaptureState(
    val key: String,
    val busy: Boolean,
    val note: String? = null,
)

// ---------------------------------------------------------------- 列表

@Composable
private fun StationListPage(
    stations: List<ExpressStationSummary>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)

    StationScaffold("驿站管理", scrollBehavior, onBack) {
        // 这里原本有一组「怎么用」的说明（三段 HintText）。2026-09-26 用户要求移除：
        // 这一页是**操作页**而不是说明书，每次进来都先滚过三段字才能看到驿站列表。
        // 真正需要解释的规则都留在它发生的地方 —— 「合并」在详情页那个下拉的副标题里，
        // 「身份码」在那张卡片自己的说明里（那两处的读者是「正要做这个动作的人」，
        // 而列表页的读者是「来找某个驿站的人」，他不需要先读一遍概念）。
        if (stations.isEmpty()) {
            GroupTitle("驿站")
            SettingsCard {
                HintText("还没有任何包裹记录。收到快递通知或菜鸟同步过之后，这里会列出驿站。")
            }
        } else {
            GroupTitle("共 ${stations.size} 个驿站")
            SettingsCard {
                stations.forEachIndexed { index, station ->
                    if (index > 0) RowDivider()
                    ArrowPreference(
                        title = station.displayName,
                        summary = listSummary(station),
                        onClick = { onOpen(station.key) },
                    )
                }
            }
        }
    }
}

/**
 * 列表行的摘要。**必须短** —— `ArrowPreference` 的副标题塞不下两行，折行会把卡片撑得比正文还高。
 *
 * 原始写法具体是哪几种留给详情页说：那是「凭什么说这两个名字是一处」的解释，点进去才需要。
 */
private fun listSummary(station: ExpressStationSummary): String = buildString {
    append("${station.count} 件")
    if (station.rawNames.size > 1) append(" · ${station.rawNames.size} 种写法")
    // 「已自定义」而不是「已手动命名」：改名、身份码来源、精确地址三样都算动过，
    // 只改过一样同样该有个标记（否则用户会以为自己的选择没保存）。
    if (station.renamed) append(" · 已自定义")
}

// ---------------------------------------------------------------- 详情

@Composable
private fun StationDetailPage(
    station: ExpressStationSummary,
    allStations: List<ExpressStationSummary>,
    rules: ExpressStationRules,
    onRename: (List<String>, String?) -> Unit,
    onIdentitySource: (List<String>, IdentitySource?) -> Unit,
    onCaptureLocation: (List<String>) -> Unit,
    onRestore: (List<String>) -> Unit,
    capture: StationCaptureState?,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)

    // 这一行当前的三项值。取「整行任一键上的那一个」—— 合并过来之前填的码可能落在链里
    // 别的键上，只按 [ExpressStationSummary.key]（链头）查会显示成空。
    val currentName = station.displayName
    // 没设过时显示成菜鸟（那是默认平台），但**不会因此往表里写一条规则** ——
    // 新建的驿站进来就是「菜鸟」，dirty 为 false，用户不动它就不会产生数据。
    val currentSource = station.ruleKeys.firstNotNullOfOrNull { rules.identitySourceFor(it) }
        ?: IdentitySource.CAINIAO
    val currentAddress = station.ruleKeys.firstNotNullOfOrNull { rules.addressFor(it) }.orEmpty()
    val currentFingerprint = station.ruleKeys.firstNotNullOfOrNull { rules.fingerprintFor(it) }

    // 草稿按 key 记：换一个驿站进来时要重置成那个驿站的值，而不是留着上一个的。
    // 只有名称和身份码是「草稿 + 保存」—— 地址和指纹是**当场落库**的（点一次按钮就采一次，
    // 没有可编辑的中间态），所以它们不进草稿，也就不会让 dirty 变真。
    var draftName by remember(station.key) { mutableStateOf(currentName) }
    var draftSource by remember(station.key) { mutableStateOf(currentSource) }
    val dirty = draftName != currentName || draftSource != currentSource

    val others = allStations.filter { it.key != station.key }

    StationScaffold("驿站详情", scrollBehavior, onBack) {
        GroupTitle("外显名称")
        SettingsCard {
            HintText("在首页卡片上显示成什么。只改本机显示，不会同步给菜鸟或快递公司。")
            DraftField(draftName) { draftName = it }
        }

        GroupTitle("默认身份码")
        SettingsCard {
            HintText(
                "这一站取件时出示哪个平台的码（菜鸟里叫「出库码」）。" +
                    "各平台的码互不通用 —— 在菜鸟驿站出示拼多多的码是认不出来的，" +
                    "所以按驿站各选一次。",
            )
            OverlayDropdownPreference(
                title = "身份码来源",
                summary = if (draftSource.supported) {
                    "取件时出示${draftSource.displayName}的码"
                } else {
                    "可以选，但${draftSource.displayName}的码暂时取不到" +
                        "（它的签名在 native 层算，无法复刻）"
                },
                items = IDENTITY_SOURCE_OPTIONS.map { it.displayName },
                selectedIndex = IDENTITY_SOURCE_OPTIONS.indexOf(draftSource),
                onSelectedIndexChange = { draftSource = IDENTITY_SOURCE_OPTIONS[it] },
            )
        }

        GroupTitle("精确地址")
        SettingsCard {
            HintText(
                "站在驿站门口点下面的按钮，把这一刻的定位和附近的 WiFi 记在这一站名下 ——" +
                    "地址会显示在包裹详情页上，将来还能据此提醒「到驿站附近了，有件可取」。" +
                    "人在别处点没用，记下的就是别处的坐标。",
            )
            InfoRow("地址", currentAddress.ifBlank { "未记录" })
            InfoRow("定位", positionLabel(currentFingerprint))
            InfoRow("附近 WiFi", wifiLabel(currentFingerprint))
            capturedLabel(currentFingerprint)?.let { InfoRow("记录时间", it) }
            CardActionRow(
                label = if (capture?.busy == true) "正在获取…" else "获取当前位置",
                // 采集中再点没有意义（会并发两次采集、后一次覆盖前一次），直接忽略。
                onClick = { if (capture?.busy != true) onCaptureLocation(station.ruleKeys) },
            )
            capture?.note?.let { HintText(it) }
        }

        // 保存 / 恢复默认合成一张卡片：两个输入框共用一个「保存」——
        // 每个框各挂一个按钮的话，用户改了两项就要按两次，而且按完第一次界面重算，
        // 第二个按钮的位置会跳。
        if (dirty || station.renamed) {
            SettingsCard {
                if (dirty) {
                    CardActionRow("保存") {
                        onRename(station.ruleKeys, draftName)
                        onIdentitySource(station.ruleKeys, draftSource)
                    }
                }
                if (station.renamed) {
                    if (dirty) CardDivider()
                    // 几张表一起清 —— 只清改名会留下用户填的码和地址，是个说不通的半吊子状态。
                    CardActionRow("恢复默认", danger = true) { onRestore(station.ruleKeys) }
                }
            }
        }

        // 原始写法摊在这里而不是列表里：点进来的人就是想确认「我改的到底是哪几件」，
        // 而这几个原始串正是「凭什么说它们是一处」的证据。
        if (station.rawNames.size > 1 || station.rawNames.firstOrNull() != station.displayName) {
            GroupTitle("记录里的原始写法")
            SettingsCard {
                station.rawNames.forEachIndexed { index, raw ->
                    if (index > 0) RowDivider()
                    Text(
                        raw,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (others.isNotEmpty()) {
            GroupTitle("合并到其他驿站")
            SettingsCard {
                OverlayDropdownPreference(
                    title = "合并到",
                    summary = "和另一个驿站的包裹并成一张卡片",
                    items = listOf(NOT_MERGED) + others.map { it.displayName },
                    // 永远停在「不合并」：这一行是**动作**而不是状态 —— 合并成功后这个驿站就不存在了，
                    // 界面会回列表，没有「当前合并到谁」需要显示。
                    selectedIndex = 0,
                    onSelectedIndexChange = { index ->
                        // 只写改名表：默认取件码 / 精确地址是「这一处地点」的属性，
                        // 合并进对方之后也该跟着留在自己身上（链式查询找得到）。
                        if (index > 0) onRename(station.ruleKeys, others[index - 1].key)
                    },
                )
                HintText(
                    "选定之后，这站的 ${station.count} 件包裹会显示在对方的卡片上并沿用对方的名称。" +
                        "想改回来，在对方那一页里恢复默认即可。",
                )
            }
        }

        GroupTitle("这站的包裹 · ${station.count} 件")
        SettingsCard {
            station.records.forEachIndexed { index, record ->
                if (index > 0) RowDivider()
                ParcelLine(record)
            }
        }
    }
}

/** 详情页三个输入框共用的样式：整宽、左右 16dp 与卡片内容对齐。 */
@Composable
private fun DraftField(value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 一行包裹：左边取件码（没有再退回运单号），右边状态。 */
@Composable
private fun ParcelLine(record: ExpressRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            record.pickupCode?.takeIf { it.isNotBlank() }
                ?: record.trackingNumber?.takeIf { it.isNotBlank() }
                ?: "—",
            modifier = Modifier.weight(1f),
        )
        SecondaryText(ExpressFormatter.statusLabel(record))
    }
}

// ---------------------------------------------------------------- 骨架

/** 二级页共用的外壳：顶栏 + 返回 + 整页滚动。与 `LogPage` 同一套写法。 */
@Composable
private fun StationScaffold(
    title: String,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        // 同 `LogPage`：顶栏上的返回不铺胶囊底色，否则顶栏会多出一个圆角块。
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(vertical = 4.dp),
        ) {
            content()
            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 「合并到」下拉里的第一项（= 保持独立）。 */
private const val NOT_MERGED = "不合并"

// ---------------------------------------------------------------- 指纹的只读呈现

/**
 * 「定位」那一行的值。
 *
 * 坐标保留 **5 位小数**（约 1 米）：这是驿站门口的尺度，再粗就分不出相邻两栋楼，
 * 再细是本机定位给不出的假精度。
 *
 * 精度半径**必须一起给**：它是判断这个坐标能不能用的唯一依据，而「±800 米」和「±10 米」
 * 在界面上长得一模一样，可用性却差着数量级 —— 只印坐标会让用户以为记得很准。
 */
private fun positionLabel(fingerprint: StationFingerprint?): String {
    val position = fingerprint?.position ?: return "未记录"
    val coordinates = "%.5f, %.5f".format(Locale.US, position.lat, position.lng)
    val accuracy = fingerprint.accuracyMeters?.takeIf { it >= 0f }
    return if (accuracy == null) coordinates else "$coordinates（±${accuracy.roundToInt()} 米）"
}

/**
 * 「附近 WiFi」那一行的值：**只给个数**。
 *
 * 具体是哪些 BSSID 是判据而不是信息 —— 一屏十几个十六进制串既念不出来也认不出来，
 * 摊开只会把「有没有记到」这件事埋掉。真要看，规则文件里就在。
 */
private fun wifiLabel(fingerprint: StationFingerprint?): String {
    val count = fingerprint?.wifi?.size ?: 0
    return if (count == 0) "未记录" else "已记录 $count 个"
}

/** 「记录时间」那一行的值；没记过时返回 null（整行不排）。 */
private fun capturedLabel(fingerprint: StationFingerprint?): String? {
    val at = fingerprint?.capturedAt ?: return null
    if (at <= 0L) return null
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))
}

/**
 * 「身份码来源」下拉的选项。顺序即枚举声明顺序，界面上的 `index` 与之一一对应。
 *
 * 拼多多**现在也在里面**：它取不到码（[IdentitySource.supported] 为 false），但驿站要能
 * 先把这个归属记下来 —— 反过来「列表里没有这一项」会让用户以为选错了地方。
 */
private val IDENTITY_SOURCE_OPTIONS: List<IdentitySource> = IdentitySource.values().toList()
