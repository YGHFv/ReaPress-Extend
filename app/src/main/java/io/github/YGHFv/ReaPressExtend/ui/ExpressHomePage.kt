package io.github.YGHFv.ReaPressExtend.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeGrouper
import io.github.YGHFv.ReaPressExtend.notification.ExpressHomeSection
import io.github.YGHFv.ReaPressExtend.notification.ExpressStationGroup
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页。
 *
 * 布局对齐菜鸟首页：**到站包裹按取件地点聚合成卡片组**（那是用户真正要去取的东西），
 * 派送中、运输中平铺在下面（用户只是「知道一下」，派送中单独一档、排在运输中上面）。
 *
 * 聚合这件事的意义在于：同一个驿站常有多个包裹，分开列会让用户在一个驿站和另一个驿站之间
 * 来回找；聚在一起则「去一趟驿站，这几件一起拿」。
 *
 * @param rules 用户在「驿站管理」里做的合并 / 改名。分组时要它，否则同一个驿站的两种写法
 *   会显示成两张卡 —— 也就等于那一页白做了。
 * @param onTogglePickup 双击卡片切换「已取件」（确认 / 撤销）。**传 null 表示这个功能关着** ——
 *   不用另开一个布尔参数：手势开不开和回调有没有是同一件事，两个参数能互相矛盾。
 *   关掉它只是**挂不上手势**：已经标记过的记录照旧变灰、照旧参与整站确认、照旧排在后面
 *   （那些标记是存下来的数据，不该因为关掉开关就变个样子）。
 * @param onOpenDetail 单击卡片进包裹详情页（看全轨迹 / 驿站完整地址 / 商品图）。
 *   传 null 表示不提供入口 —— 与 [onTogglePickup] 同一种约定，且两者互相独立：
 *   取件开关关掉之后，单击进详情仍然应该可用。
 */
@Composable
internal fun HomePage(
    records: List<ExpressRecord>,
    rules: ExpressStationRules = ExpressStationRules.EMPTY,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)? = null,
) {
    if (records.isEmpty()) {
        EmptyHome()
        return
    }

    val sections = ExpressHomeGrouper.group(records, rules)
    // 驿站显示名表：整页算一次。到站卡片抬头是分组时就带出来的（同一张表），下面那些
    // 平铺卡片（运输中 / 已签收）自己取一次 —— 它们不在分组里，拿不到 group.station。
    //
    // 逐条算不行：每条都要重跑一遍聚类，而且两份结果一旦有出入，同一条记录在两个位置
    // 就会印出两个名字。
    val stationLabels = ExpressHomeGrouper.stationLabels(records, rules)
    // 「已入站2天」这种相对时间需要一个「现在」。在 UI 层取一次再往下传 —— core 层刻意不碰
    // 系统时钟（见 ExpressFormatter.inStationLabel），所以这里是整条链路上唯一的时间来源。
    val now = System.currentTimeMillis()

    for (section in sections) {
        GroupTitle("${section.title} · ${section.count}件")
        if (section.stationGroups.isNotEmpty()) {
            for (group in section.stationGroups) {
                StationGroupCard(group, now, onTogglePickup, onOpenDetail)
            }
        }
        for (record in section.records) {
            ParcelCard(
                record = record,
                stationLabel = ExpressHomeGrouper.stationLabelOf(record, stationLabels),
                now = now,
                onTogglePickup = onTogglePickup,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

/**
 * 空状态。
 *
 * 一句居中主文案 + 一组「收不到包裹？」的自查条目，全部走模块统一排版（见 `ExpressUiKit` 文件头）。
 *
 * 以前这里是一张「InfoRow(暂无包裹 / 拦截到…会出现在这里)」再加三段卡片外悬空的说明文字，
 * 两个问题：`InfoRow` 是给「标签 — 值」用的，用在「暂无包裹」上语义不对（左右分栏把一句
 * 完整的话硬拆成两半）；卡片外那三段没有卡片托着，与上面那张卡片的左右边界也差着量。
 * 现在自查条目自己成组、进卡片，扫一眼就知道是「怎么让包裹出现」。
 */
@Composable
private fun EmptyHome() {
    GroupTitle("我的包裹")
    SettingsCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 32.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("暂无包裹", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "拦截到快递通知后会自动出现在这里",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    GroupTitle("收不到包裹？")
    SettingsCard {
        HintText("1. LSPosed 作用域包含 system（关于页可确认）")
        HintText("2. 通知权限已授予")
        HintText("3. 对应来源的开关是打开的（设置页）")
    }
}

/** 一个取件地点下的所有包裹，合成一张卡片。 */
@Composable
private fun StationGroupCard(
    group: ExpressStationGroup,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    SettingsCard {
        // 地点抬头：菜鸟首页把驿站名放在最显眼的位置，因为它决定了「去哪取」。
        //
        // 营业时间取组里第一条有值的。同一个驿站的所有包裹拿到的是同一份站点数据，
        // 但**逐条找而不是只看第一条**：宿主是按站点下发 officeTime 的，
        // 组内第一件恰好没带、第二件带了的情况不该把整组的营业时间吞掉。
        //
        // 拿到的是「周一至周日09点00分到21点00分」这种中文长写法，必须过一遍规范化 ——
        // 它只有 11sp 却是抬头行里最长的一段，原样贴上去直接折行。规则见 stationHoursLabel。
        val hours = group.records
            .firstNotNullOfOrNull { ExpressFormatter.stationHoursLabel(it.stationHours) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 直接用分组给的名字：品牌前缀（`菜鸟驿站`）、外层括号、空白已经在
                    // ExpressStationName 里剥掉了，而且同一个驿站的不同写法也被归到了一起。
                    // UI 这边再处理一遍只会让两处规则慢慢跑偏。
                    text = group.station,
                    fontSize = 16.sp,
                    fontWeight = FontWeight(550),
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false：驿站名按内容宽度排，放不下时才收缩 —— 不让它无条件占掉一半宽度。
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 营业时间紧跟驿站名、小两号。它决定「现在去还是明天去」，所以跟抬头放在一起；
                // 但抬头的主语是「去哪取」，它不能抢驿站名的视觉重量。
                if (hours != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = hours,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                "${group.records.size} 件",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        for ((index, record) in group.records.withIndex()) {
            if (index > 0) RowDivider()
            PickupCardBody(record, now, onTogglePickup, onOpenDetail)
        }
    }
}

/**
 * 不按地点聚合时的单条卡片（运输中、已签收、用户已确认取件移出待取件的）。
 *
 * 双击还原只对**已标记过已取件**的记录开放，理由见 [cardTapTarget] 的调用点。
 * 单击进详情则是**所有**卡片都开的 —— 想知道「件现在到底到哪了」的场合，运输中的件
 * 比到站件更需要这条入口。
 *
 * @param stationLabel 该记录该显示的驿站名（用户改过名就是改后的名字），未知传 null。
 *   由调用方用 [ExpressHomeGrouper.stationLabels] 算好传进来，**不要在卡片里读
 *   `record.station`** —— 那是原始串，用户在驿站管理里改的名字对它不生效。
 */
@Composable
private fun ParcelCard(
    record: ExpressRecord,
    stationLabel: String?,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    // 运输中 / 已签收的普通卡片本来就没有「确认取件」这个动作，给它们挂双击是凭空多出一个
    // 可操作区，误触的代价大于收益 —— 所以这里按记录本身过滤，而不是照搬到站卡片那套。
    //
    // 已标记的必须能撤销：整站确认后这些件会移出「到站包裹」落到这一档，而那是它们**唯一**
    // 剩下的撤销入口（单件驿站误触一下就直接移档，没有灰态窗口可以退回去）。
    val target = if (record.isPickedUp) onTogglePickup else null
    SettingsCard {
        ParcelCardBody(record, stationLabel, now, target, onOpenDetail)
    }
}

/**
 * 左侧取件码列宽。
 *
 * 定宽而不是 `wrapContentWidth`：右列是左对齐的长文本，左列随内容一宽一窄会让右列
 * 整体左右跳，多件包裹上下排时看着像没对齐。
 *
 * 宽度上限是**真机截图量出来的**，不能靠字体度量估算：28sp 时 `1-5-8644` 在 104dp 里
 * 会折成两行（`1-5-864` + `4`）。现在取件码降到 26sp，常见 8 字符串约 110dp；
 * 再加宽到 124dp 是为了让那串数字和右列之间留出明显的呼吸感 —— 112dp 时取件码几乎
 * 顶到右列文字上（截图对比过）。右列现在只有两短行，匀出这 12dp 不心疼。
 */
private val PICKUP_COLUMN_WIDTH = 124.dp

/**
 * **到站包裹**的主体，左右结构。
 *
 * 只有到站件走这套 —— 运输中 / 已签收走 [ParcelCardBody]（上下结构，见那里的注释）。
 *
 * 左列只放「到了驿站要念出来的那串东西」—— 取件码，大号；没有取件码时才退回快递公司名。
 * 右列两行：①快递 + 运单号 + 在站多久（拿不到到站时间时这一行可能整行不排）②来源 + 物品。
 *
 * 为什么到站件要分栏：用户在驿站门口看这一屏，视线先落在左列那串码上（念给店员），
 * 确认之后再扫右边「是哪件、放了几天」。原来的上下结构里这两类信息挤在同一列，
 * 取件码只靠字号突出，不如左右分栏一眼分层。
 *
 * 左列同时是**单击 / 双击的落点**，而且落点是**整行**（左列 + 右列两行文字）：
 * 取件码只有 6~8 个字符、字号虽大但也只是百来 dp 宽的一条，让用户去精确戳它太苛刻；
 * 整行是这个卡片里唯一有意义的操作目标，列内和右列都没有别的可点区域，不会和谁抢手势。
 * 把落点限定在左列还有个实际毛病：用户的手势习惯落在卡片中部偏右（右列文字上），
 * 那里点不动会让人以为功能坏了。详见 [cardTapTarget]。
 *
 * @param onOpenDetail 单击进详情。与 [onTogglePickup] 互相独立：取件开关关掉后
 *   单击进详情仍然有效（两件事在 [cardTapTarget] 里各装各的）。
 */
@Composable
private fun PickupCardBody(
    record: ExpressRecord,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 手势挂在 padding **之前**：这样点击区域含那圈 16dp/12dp 的留白，
            // 也就是真正的「整行」。挂在 padding 之后的话，卡片边缘那一圈点不动 ——
            // 而用户的手指常常正落在那里。
            .cardTapTarget(record, onOpenDetail, onTogglePickup)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 没有取件码时左列显示公司名 —— 手势照样有效（同一件包裹、同一个动作），
        // 用户不会因为「这件没码」就找不到确认入口。
        PickupColumn(record)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            // 第一行：快递 + 运单号，末尾接在站时长（「2天」/「今天」）。
            //
            // 拆成两个 Text 而不是拼成一整串：空间不够时**该被牺牲的是运单号**，
            // 不是那几个字 —— 后者才是用户判断「要不要现在跑一趟」的依据。
            // 左边那段用 weight(1f, fill = false)，够宽时按内容宽度、不够时才收缩。
            //
            // 两段都可能为空（左列已经占了公司名和运单号时 left 为空；宿主没给到站时间时
            // tail 为空），**全空就整行不排** —— 否则第二行上面会多出一段说不清来历的空隙。
            val left = courierLine(record)
            val tail = stationTail(record, now)
            if (left.isNotEmpty() || tail != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (left.isNotEmpty()) {
                        Text(
                            text = left,
                            fontSize = 12.sp,
                            fontWeight = FontWeight(500),
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (tail != null) {
                        // 前面那段为空时不能带分隔符，否则行首会多出一个孤零零的「 · 」。
                        Text(
                            text = if (left.isNotEmpty()) " · $tail" else tail,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
            // 第二行：来源 · 物品。拼装规则与通知正文共用 ExpressFormatter.goodsSummary，
            // 两处不会串味。商品名可能很长（电商标题动辄 30 字），截断而不是换行 ——
            // 换行会让卡片高度随标题长短乱跳，扫一屏时对不齐。
            ExpressFormatter.goodsSummary(record)?.let { goods ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = goods,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 运输中 / 已签收的主体，上下结构 —— 和到站件的 [PickupCardBody] 刻意不一样。
 *
 * 这些件没有「到驿站念一串码」的场景，取件码不该独占左侧一整列；把横向空间全留给
 * 运单号和商品名，信息能完整显示。**不为了视觉统一而统一**：两套布局服务的动作不同。
 *
 * 三行结构（状态挂在右上角）：
 * ①主标识 + 运单号全号 + 驿站名 ②手机尾号 · 运单动态 ③来源 · 物品。
 * 运单号从「单独一行」升到标题行之后，卡片少了一行 —— 少掉的那行本来就只装一个字段，
 * 而它和公司名是同一件事（哪家的哪一单）。驿站名 2026-09-26 同理并进标题行
 * （见 [ParcelTitleRow]），卡片再少一行。
 *
 * ## 副行 / 商品行为什么在状态行**下面**占整行宽
 *
 * 最早它们和标题一起被关在状态左侧的那一栏里（Row 两栏结构），右上角状态的正下方
 * 永远是一块空白。2026-09-26 用户明确要求「改回原本的布局，文本显示延申到空白」——
 * 所以只有标题行需要给状态让位（避免重叠），副行和商品行排在状态这一行之下、
 * **占整行宽度**：文本自然延伸进那块空白，卡片不因为留白白高一截。
 *
 * 用户整站确认取件后移出「到站包裹」的记录也走这里（进了「已签收 / 异常」那一档）。
 * 对它们不用 [PickupCardBody]：那个左列是为「到驿站念一串码」服务的，而这些件已经取回来了 ——
 * 再摆一个大号取件码是在提示一个已经做完的动作。状态文案由
 * [ExpressFormatter.statusLabel] 换成「已取件」。
 *
 * @param stationLabel 该显示的驿站名，未知传 null（整段不排）。**不是 `record.station`** ——
 *   原始串过不了用户的改名 / 合并规则。2026-09-26 起直接排进标题行、紧跟运单号，
 *   字号与颜色都跟运单号同档（不再是原来那行蓝色）。
 * @param onTogglePickup 双击整张卡片撤销「已取件」。**只有已标记过的记录会传非 null**（见
 *   [ParcelCard]）—— 否则每一张运输中的卡片都会变成一个隐藏的双击区。
 * @param onOpenDetail 单击进详情。所有卡片都开。
 */
@Composable
private fun ParcelCardBody(
    record: ExpressRecord,
    stationLabel: String?,
    now: Long,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
) {
    Column(
        modifier = Modifier
            // 和到站卡片同一条规矩：手势在 padding 之前，点击区域含整张卡片的留白。
            .cardTapTarget(record, onOpenDetail, onTogglePickup)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 标题行 + 状态，同一行：这是唯一需要给右上角状态让位的一行（状态贴着它排）。
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                // 驿站名一并交给标题行：它和运单号是同一类信息（这一单是哪个、归哪儿），
                // 单独占一行时卡片白高一截（见 ParcelTitleRow 的注释）。
                ParcelTitleRow(record, stationLabel)
            }
            Text(
                text = transitStatusLabel(record, now),
                fontSize = 13.sp,
                color = statusColor(record),
            )
        }
        // 副行：手机尾号 · 运单动态。与商品行**同一个字号（12sp）、各自一行** —— 两行是
        // 同一档的次要信息，一大一小就分出了本不存在的层级（2026-09-26 用户指出）。
        // 占整行宽后，文本延伸进右上角状态下面那块空白（上面的结构注释）；
        // 超出仍截尾 —— 完整动态在详情页看。
        parcelSubtitle(record)?.let { subtitle ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 商品行：和菜鸟卡片同一行的写法（「淘宝 · 云南一级白糖…」）。
        // **强制一行**：商品名动辄三十字，换行会让卡片高度随标题长短乱跳，
        // 上下几张卡片就对不齐了。
        ExpressFormatter.goodsSummary(record)?.let { goods ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = goods,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 运输中 / 派送中卡片右上角的状态文案：相对时间 + 状态（「2小时前 派送中」）。
 *
 * 起算点用 [ExpressFormatter.statusSince]（轨迹最新节点与宿主 gmt_modified 取较新者），
 * 不直接拿 [ExpressRecord.timestamp]：宿主几小时不刷新时 gmt_modified 停在旧值 ——
 * 真机实证：13:54 已派送的件显示「11小时前」。轨迹里那次事件才是状态真正的开始时刻。
 *
 * 只对**还在路上**的件显示：已签收的状态本身就是结论，再算「多久前签收」没有决策价值；
 * 用户标记过「已取件」的同理（statusLabel 会显示「已取件」，不叠加时间）。
 *
 * 拿不到时间（时钟回拨 / 两处来源都没有）就退回纯状态，整段不会开天窗。
 */
private fun transitStatusLabel(record: ExpressRecord, now: Long): String {
    val status = ExpressFormatter.statusLabel(record)
    val onTheWay = record.status == ExpressStatus.IN_TRANSIT ||
        record.status == ExpressStatus.PICKED_UP ||
        record.status == ExpressStatus.DELIVERING
    if (!onTheWay || record.isPickedUp) return status
    val since = ExpressFormatter.statusSince(record) ?: return status
    val age = ExpressFormatter.relativeAge(since, now) ?: return status
    return "$age $status"
}

/**
 * 运输中 / 已签收卡片的标题行：主标识（取件码 / 公司简称）+ **运单号全号** + **驿站名**。
 *
 * 运单号跟在公司名后面、**沿用 12sp 常规字重**，不跟着标题一起放大：它是核对用的附属信息，
 * 放大就会跟公司名抢视觉重心，整行也变重。读序正好对上用户的动作顺序 ——
 * 「哪家快递 → 哪一单」。驿站名接在运单号后面，是同一读序的第三段（这一单归哪儿）。
 *
 * 驿站名 2026-09-26 从卡片最底行挪进来（用户要求「站点放到运单号后面」），两个原因：
 * ①它和运单号是同一类信息，分两行时白占一行高度（商品名那一行才是真正需要横向空间的）；
 * ②原来那行用 `primary` 蓝色，是整张卡片上唯一的彩色文字 —— 扫一屏时比包裹本身还抢眼，
 * 而分组抬头「到站包裹」已经用蓝色标了地点，这里再标一次属于重复强调。
 * 现在它和运单号同一档字号、同一个次要色，只靠 `·` 分隔：**位置本身说明了它是驿站**，
 * 不需要颜色再标一次（用户 2026-09-26 明确要求去掉蓝色）。
 *
 * 主标识位已经在显示运单号时（认不出公司，见 [titleShowsTracking]）不再补一遍，
 * 否则同一串数字会在标题里出现两次。
 *
 * @param stationLabel 该显示的驿站名（用户改过名就是改后的名字），未知传 null（整段不排）。
 *   **不是 `record.station`** —— 原始串过不了用户的改名 / 合并规则。
 */
@Composable
private fun ParcelTitleRow(record: ExpressRecord, stationLabel: String?) {
    val pickup = record.pickupCode?.takeIf { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = pickup ?: courierLabel(record),
            fontSize = if (pickup != null) 24.sp else 17.sp,
            fontWeight = if (pickup != null) FontWeight(600) else FontWeight(550),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val tracking = record.trackingNumber?.takeIf { it.isNotBlank() }
        if (tracking != null && !titleShowsTracking(record)) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = tracking,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 三个字段（主标识 / 运单号 / 驿站名）全都 fill = false：够宽时各按内容排，
                // 不够时一起收缩 —— 不会出现某一个把其它两个挤出卡片。
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (stationLabel != null) {
            // 分隔符单独一个 Text，不拼进驿站名里：它和驿站名是两种东西，
            // 拼在一起的话「·」会跟着驿站名一起被截断（极端窄屏下只剩个孤零零的点）。
            Spacer(Modifier.width(6.dp))
            Text(
                text = "·",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stationLabel,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 这一段是行尾的「弹性尾巴」：三个字段全都 fill = false，空间不够时一起收缩，
                // 不会出现某一个把其它两个挤出卡片。驿站名本身长短差得最多（有的带楼栋号），
                // 所以是最先被截的那一段 —— 截掉的也确实是它最不关键的后半截。
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * 运输中卡片的副行：公司简称（**仅当标题被取件码占了**）+ 手机尾号 + 运单动态。
 *
 * 动态拼装规则（为什么手机尾号在前、动态在后）在 ExpressFormatter.detailLine 里，
 * 那边是纯函数、可单测。公司名只在标题没显示它时才重复 —— 和到站卡片第一行是同一条规矩。
 *
 * 三样都凑不出来时退回原文首行：那是「其他」分组里解析失败的记录，卡片上总得有点什么，
 * 空着比给一句原文更糟。只有原文也没有（理论上不该发生）才返回 null，整行省略。
 */
private fun parcelSubtitle(record: ExpressRecord): String? {
    val courier = if (!record.pickupCode.isNullOrBlank() && record.courier != Courier.UNKNOWN) {
        record.courier.shortName
    } else {
        null
    }
    val parts = listOfNotNull(courier, ExpressFormatter.detailLine(record))
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return record.rawText.lineSequence().firstOrNull { it.isNotBlank() }?.take(40)
}

/**
 * 卡片上的单击 / 双击落点：**单击进详情、双击切换「已取件」**。
 *
 * 挂在**整行**上而不是取件码那串字上：取件码 6~8 个字符，让用户精确戳它太苛刻；
 * 而整行是这个卡片里唯一有意义的操作目标 —— 左列和右列都没有别的可点区域，
 * 不存在和谁抢手势的问题。
 *
 * ## 为什么用 `detectTapGestures` 而不是 `combinedClickable`
 *
 * 一条理由是按下不留痕：`combinedClickable` 自带一层 indication，整张卡片按下时会闪一下。
 * 这里两个动作（看详情 / 标记取件）都不需要「按到了」的反馈，闪一下反而像是「选中了」。
 *
 * 另一条更要紧：**单击必须等双击窗口过期再触发**。两个回调并存时 `detectTapGestures` 会先等
 * 约 300ms 看有没有第二下，没有才回调 `onTap` —— 这正是要的行为，否则双击的第一下就直接跳进
 * 详情页，取件手势永远触发不了。反过来说，当 `onDoubleTap` 为 null（取件开关关着、
 * 或这张卡片本来就不支持取件）时那个等待一并省掉，单击立刻响应。
 *
 * 也不会拖累列表纵向滚动：手势识别器在指针移动超出 slop 时就把这一串 event 放给父级。
 *
 * ## key 的取法
 *
 * 用 [ExpressRecord.dedupeKey] **加两个「开没开」的布尔**，而不是把回调本身放进 key：
 * lambda 每次重组都是新对象，拿它当 key 会让手势块不停重启、把进行中的手势掐断；
 * 而只按 dedupeKey 做 key 又会在开关切换后留着旧回调。列表刷新后这一行可能被复用给
 * 另一条记录，dedupeKey 变了就重置。
 *
 * @param onOpenDetail 单击进详情。null = 不提供这个入口
 * @param onTogglePickup 双击切换「已取件」。null = 这张卡片没有这个动作（功能关着，
 *   或它本来就不支持 —— 见 [ParcelCard]）
 */
private fun Modifier.cardTapTarget(
    record: ExpressRecord,
    onOpenDetail: ((ExpressRecord) -> Unit)?,
    onTogglePickup: ((ExpressRecord) -> Unit)?,
): Modifier = if (onOpenDetail == null && onTogglePickup == null) {
    this
} else {
    pointerInput(record.dedupeKey, onOpenDetail != null, onTogglePickup != null) {
        detectTapGestures(
            // 显式标出 `Offset` 参数：没有它这两个 lambda 会被推成 `() -> Unit`，
            // 与 `onTap` / `onDoubleTap` 要求的 `(Offset) -> Unit` 对不上。
            onTap = onOpenDetail?.let { detail -> { _: Offset -> detail(record) } },
            onDoubleTap = onTogglePickup?.let { toggle -> { _: Offset -> toggle(record) } },
        )
    }
}

/**
 * 到站卡片的左列 —— 主标识位，大号显示。
 *
 * 取件码给 26sp（这一屏最大的字，比卡片抬头的 16sp 还大）：它是用户在驿站唯一要
 * 念出口的东西，隔着柜台扫一眼必须能看清，值得占掉这个视觉重量。
 * 字号上限是被列宽卡住的 —— 28sp 时 `1-5-8644` 会折行（真机截图验证过），
 * 而再放宽列宽就要从右列嘴里抢空间了。
 *
 * 退回公司名 / 运单号时降到 17sp：14 位运单号给 26sp 会折成三行撑高卡片。
 *
 * 已确认取件的整列**变灰**（[MiuixTheme.colorScheme.disabledOnSurface]）。用「禁用色」而不是
 * 随手挑一个灰：它本来就表达「这一项已经不用管了」，正是这里的语义；换主题/换深浅色时
 * 也跟着走，不会在夜间主题里糊成一团。
 */
@Composable
private fun PickupColumn(record: ExpressRecord) {
    val pickup = record.pickupCode?.takeIf { it.isNotBlank() }
    val color = if (record.isPickedUp) {
        MiuixTheme.colorScheme.disabledOnSurface
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.width(PICKUP_COLUMN_WIDTH)) {
        if (pickup != null) {
            Text(
                text = pickup,
                fontSize = 26.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight(600),
                color = color,
                // 异常长的取件码（带楼层/货架号的）允许折两行，不截断 ——
                // 这串数字截断等于没用，用户念的是完整的那串。
                maxLines = 2,
            )
        } else {
            Text(
                text = courierLabel(record),
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight(550),
                color = color,
                maxLines = 2,
            )
        }
    }
}

/**
 * 到站卡片第一行的尾段：在站时长（`2天` / `今天`）。
 * **拿不到到站时间就整段不显示**（返回 null）。
 *
 * 原来这里在没有到站时间时退回状态文案 —— 而到站件的状态不是「已到站」就是「待取件」：
 * 前者和分组抬头「到站包裹」重复，后者就是用户明确要求去掉的那个蓝色「待取件」。
 * 这一格宁可空着，把注意力让给第二行的来源和商品名。
 *
 * 它跟着运单号走而不是单独占一行：和「快递 + 运单号」属于同一类信息（这件现在什么情况），
 * 合在一行省高度，读法也和菜鸟卡片一致（`中通快递 | 已入站2天`）。
 * 它**不参与收缩**（调用点不给 weight）：宁可让运单号被截，也不能把这几个字挤掉。
 * 用次要色而不是状态色：它是背景信息，不是要用户立刻行动的事。
 */
private fun stationTail(record: ExpressRecord, now: Long): String? {
    // 这个函数只服务到站卡片，但状态判断留在里面而不是靠调用方保证 —— 算这个数的前提
    // 是这件真的在站里，拿运输中件的「最后一次状态变更时间」去算会得出一个假的停留天数。
    if (record.status !in STATION_STATUSES) return null
    return record.arrivalAt?.let { ExpressFormatter.inStationLabel(it, now) }
}

/**
 * 驿站名。
 *
 * 原始串形如 `菜鸟驿站(临河阳光花园店)`。抬头已经说明了这是取件地点，
 * 去掉「菜鸟驿站」前缀只留门店名，既省横向空间又更易读。
 *
 * 只剥**最外层**那一对括号：门店名本身可能带括号（`菜鸟驿站(A店(东门))`），
 * 用 removeSuffix 会把内层的右括号也吃掉。
 */
private fun stationTitle(station: String): String {
    val trimmed = station.trim()
    val withoutBrand = trimmed.removePrefix("菜鸟驿站")
    if (withoutBrand.length >= 2 &&
        ((withoutBrand.startsWith("(") && withoutBrand.endsWith(")")) ||
            (withoutBrand.startsWith("（") && withoutBrand.endsWith("）")))
    ) {
        return withoutBrand.substring(1, withoutBrand.length - 1).ifBlank { trimmed }
    }
    return withoutBrand.ifBlank { trimmed }
}

/**
 * 没有取件码时，主标识位（左列 / 标题）显示什么。
 *
 * 认得出公司就给**简称**，认不出才退回运单号：
 *
 * - 公司名比单号好认，能让用户一眼分出「这几件是我哪几个包裹」，所以优先。
 * - 真正要避开的是「快递包裹」那四个字 —— 用户明确说过它没信息量
 *   （认不出公司时卡片上就挂这行字）。这种情况换成运单号，至少能拿去核对、报给客服。
 * - 连单号都没有时只能留着「快递包裹」占位，总比空一行强。
 *
 * 注意这一格**只有到站卡片左列**在宽度上受限（[PICKUP_COLUMN_WIDTH]）；运单号 14 位会折两行，
 * 所以左列那一路的字号比取件码小一档。
 */
private fun courierLabel(record: ExpressRecord): String = when {
    record.courier != Courier.UNKNOWN -> record.courier.shortName
    !record.trackingNumber.isNullOrBlank() -> record.trackingNumber
    else -> "快递包裹"
}

/**
 * 主标识位是不是**已经在显示运单号**了（= 没取件码、也认不出公司、但有单号）。
 *
 * 用来避免同一个单号在一张卡片上出现两次：主标识位占了它，右列第一行（[courierLine]）
 * 和运输中卡片的标题行（[ParcelTitleRow]）就要让开。
 */
private fun titleShowsTracking(record: ExpressRecord): Boolean =
    record.pickupCode.isNullOrBlank() &&
        record.courier == Courier.UNKNOWN &&
        !record.trackingNumber.isNullOrBlank()

/**
 * 在站时长只在这两个状态下显示。
 *
 * [ExpressRecord.arrivalAt] 取的是宿主物流记录里最后一次状态变更的时间 —— 对到站件，
 * 那一次变更就是「入站」；对运输中的件，那是「离开上一站」，拿它算停留天数会把用户引到驿站白跑。
 */
private val STATION_STATUSES = setOf(
    ExpressStatus.ARRIVED_STATION,
    ExpressStatus.READY_FOR_PICKUP,
)

/**
 * 到站卡片右列第一行的左段：快递公司简称 + 运单号**全号**。
 *
 * 和左列（[courierLabel]）分工，**绝不重复左列已经显示过的东西**：
 * - 左列是取件码 → 这里补「公司 · 运单号」
 * - 左列是公司简称 → 这里只补运单号
 * - 左列是运单号 → 这里什么都不补，这行只剩行尾的到站时长
 *
 * 所以**返回值可能是空串**，调用方得按空处理（不能直接给 [Text]，那会渲染出多余间距）。
 *
 * 用全号而不是尾号：改成左右分栏后右侧空间还够；用户核对包裹、跟客服报单号时，
 * 尾号不够用（菜鸟首页只给尾号是因为它的卡片横向更挤）。
 * 公司名用简称（`中通` 而不是 `中通快递`）就是为了给全号腾出这两个字的位置。
 */
private fun courierLine(record: ExpressRecord): String = buildString {
    val hasPickup = !record.pickupCode.isNullOrBlank()
    // 左列显示公司名 ⟺ 没有取件码且认得出公司，这时它已经承担了公司信息。
    if (hasPickup && record.courier != Courier.UNKNOWN) {
        append(record.courier.shortName)
    }
    if (!titleShowsTracking(record)) {
        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { tracking ->
            if (isNotEmpty()) append(" · ")
            append(tracking)
        }
    }
}

/**
 * 状态文案的颜色。
 *
 * 用户自己确认取走的件用次要色（不上状态色）：待取件的蓝/绿在这里已经不是待办了，
 * 它就是一条「取完了」的灰色记录 —— 和它在卡片上被划走的那种感觉一致。
 */
@Composable
private fun statusColor(record: ExpressRecord) = when {
    record.isPickedUp -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    record.status == ExpressStatus.FAILED -> MiuixTheme.colorScheme.error
    record.status == ExpressStatus.READY_FOR_PICKUP ||
        record.status == ExpressStatus.ARRIVED_STATION -> MiuixTheme.colorScheme.primary
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}
