package io.github.YGHFv.ReaPressExtend.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 把 [ExpressRecord] 渲染成替换通知的标题与正文。
 *
 * 纯函数：文案逻辑要能单测，且要在模块 App 进程（发通知）和可能的诊断界面（预览）里共用。
 *
 * 设计原则：**信息密度优先于花哨**。原通知往往把运单号、广告、表情、多个包裹混在一段里，
 * 用户真正要的是「哪个包裹、到哪了、取件码多少」。所以这里按固定字段重排，而不是照抄原文。
 */
object ExpressFormatter {

    /**
     * 通知标题。固定带前缀，让用户一眼分辨这是模块重发的。
     *
     * 公司名用 [Courier.shortName]（`中通` 而不是 `中通快递`）：通知栏一行放不下多少字，
     * 而公司名只是用来区分包裹的，省下的空间留给状态和取件码。
     * `UNKNOWN.shortName` 就是「快递」，不用另判。
     */
    fun title(record: ExpressRecord): String {
        val courier = record.courier.shortName
        return when (record.status) {
            ExpressStatus.READY_FOR_PICKUP -> "$courier · 待取件"
            ExpressStatus.ARRIVED_STATION -> "$courier · 已到站"
            ExpressStatus.DELIVERING -> "$courier · 派送中"
            ExpressStatus.SIGNED -> "$courier · 已签收"
            ExpressStatus.FAILED -> "$courier · 投递失败"
            ExpressStatus.IN_TRANSIT -> "$courier · 运输中"
            ExpressStatus.PICKED_UP -> "$courier · 已揽收"
            ExpressStatus.CREATED -> "$courier · 已下单"
            ExpressStatus.UNKNOWN -> courier
        }
    }

    /**
     * 通知**收起态**那句 —— 一件包裹「最重要的一句话」。
     *
     * 分岔点只有一个：**这件东西要不要用户动手**。
     * - 有取件码 / 驿站 → 说「去哪取、取什么」（`取件码 8-2-3021 · 文一西路店`）
     * - 都没有（还在路上）→ 说「现在在哪」（运单动态）加「哪一件」（公司 + 运单号）
     *
     * ## 为什么把标签砍了
     *
     * 旧版写的是 `取件码：8-2-3021` / `地点：文一西路店` / `运单号：SF…` / `状态：待取件` ——
     * 每个字段配一个标签，五行全是标签，真正的内容被挤成一小截，通知栏一眼扫过去什么都抓不住。
     * 现在：**只有「取件码」保留标签**（光一串 `8-2-3021` 用户不知道那是什么），
     * 「地点 / 运单号 / 状态」三个标签全去掉 —— 驿站名自带辨识度，运单号长得就像运单号，
     * 状态已经在标题里说过了（[title]）。
     *
     * @return 一个字段都没有时返回 null，由 [body] 退回原文首行
     */
    fun summaryLine(record: ExpressRecord): String? {
        val pickup = record.pickupCode?.takeIf { it.isNotBlank() }
        val station = record.station?.takeIf { it.isNotBlank() }
        if (pickup != null || station != null) {
            return listOfNotNull(pickup?.let { "取件码 $it" }, station).joinToString(" · ")
        }
        val detail = record.logisticsDetail?.takeIf { it.isNotBlank() }
        return listOfNotNull(detail, trackingLine(record))
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
    }

    /**
     * 「公司简称 运单号」。公司认不出时只留运单号。
     *
     * 这两样必须**连在一起**出现（`顺丰 SF1234567890123`）才算完整：单独一串数字用户认不出
     * 是哪家，单独一个「顺丰」又对不上是哪一件。
     */
    fun trackingLine(record: ExpressRecord): String? =
        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { tracking ->
            if (record.courier == Courier.UNKNOWN) tracking else "${record.courier.shortName} $tracking"
        }

    /**
     * 通知**展开态**正文（也是投递审计里存的那份）。
     *
     * 行序即重要性：摘要（去哪取）→ 哪一件（公司 + 全号）→ 现在在哪（运单动态）→ 买的是什么。
     * 逐行去重：摘要行已经吃下运单动态时（路上那些件），后面不再重复一遍。
     *
     * 一个字段都没抽到时退回原文 —— 宁可给用户看原始文案，也不要一条空通知。
     */
    fun body(record: ExpressRecord): String {
        val pickup = record.pickupCode?.takeIf { it.isNotBlank() }
        val station = record.station?.takeIf { it.isNotBlank() }
        val lines = mutableListOf<String>()
        summaryLine(record)?.let { lines += it }
        // 摘要行只可能是两种组合之一：有取件码时是「取件码 + 驿站」，没有时是「运单动态 + 运单号」。
        // 只有前者需要把运单号、动态补在后面；后者摘要里已经有了，再补就是把同一句话拆开重说一遍。
        if (pickup != null || station != null) {
            trackingLine(record)?.let { lines += it }
            record.logisticsDetail?.takeIf { it.isNotBlank() }?.let { lines += it }
        }
        // 商品行（`淘宝 · 云南白糖`）和首页卡片同一行同一种写法，两个界面之间来回看不会对不上。
        // 这里不带「商品：」前缀 —— 卡片上也没有，而它读了就知道是什么。
        goodsSummary(record)?.takeIf { it !in lines }?.let { lines += it }
        if (lines.isEmpty()) {
            return record.rawText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return lines.joinToString("\n")
    }

    /**
     * 在站时长：「N 天」。**按自然日差算，不是 24 小时整除。**
     *
     * 这条口径是照菜鸟定的（2026-09-26 实测）：一件 09-24 18:21 到站的包裹，
     * 09-26 03:30 打开时菜鸟显示「已入站2天」，而 24 小时制只会算出 1 天。
     * 用户对「几天」的直觉是**数日历格子**（24 号、25 号、26 号 → 2 天），
     * 不是「够不够 24 小时」—— 照 24 小时算，同一件包裹会在到站 24 小时后
     * 突然从「今天」跳成「1天」，那个跳变点跟用户感知的「过了一天」对不上。
     *
     * **不带「已入站」三个字**：它挂在到站包裹分组的卡片上，抬头已经说了这批件的状态，
     * 每行再重复一遍只是把右列第一行撑长（那行还要放公司名和运单号）。剩下的「2天」
     * 才是用户判断「要不要现在跑一趟」的依据。
     *
     * `now` 由调用方注入 —— core 层不碰系统时钟，这条规则才能单测。
     * [zone] 给默认值是因为它属于「运行环境」而不是「时间」：同一条记录在两个时区
     * 可能差一天，而用户看的是自己手机上的日历。
     *
     * **只对到站件用**：`arrivalAt` 取的是宿主物流记录里「最后一次状态变更时间」，
     * 对运输中的件算这个数是错的。调用方要先看 [ExpressRecord.status]。
     *
     * 不足一天只说「今天」：`0天` 读起来像出错，而「今天」在中文里本来就是
     * 「今天到的」这个意思。
     */
    fun inStationLabel(
        arrivalAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(arrivalAt).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        )
        // days <= 0 只可能来自时钟回拨或宿主给错时间，按「刚发生」处理，不显示「-1天」。
        return if (days <= 0L) "今天" else "${days}天"
    }

    /**
     * 驿站营业时间：把宿主的中文长写法压成「9:00-21:00」。
     *
     * 实测菜鸟 8.11.923 的 `packageStation.officeTime` 形如
     * 「周一至周日09点00分到21点00分」。它挂在驿站名后面（11sp 小字），原样贴上去
     * 会把抬头那一行挤到折行；而卡片真正要回答的是「现在去还是明天去」，也就是
     * 几个钟点而已。
     *
     * 规则：
     * - 「周一至周日」「每天」这类**全周**前缀丢掉 —— 它是绝大多数驿站的默认值，
     *   对用户零决策价值；
     * - 前缀**不是**全周时保留（`周一至周五` 丢掉就变成误导：用户周末白跑一趟），
     *   与时间之间用 `·` 隔开；
     * - 钟点统一成 `H:mm`（小时去前导零、分钟补零）—— 这是用户指定的口径；
     * - 抓到 4 个钟点按「上午段、下午段」两段输出（`9:00-12:00，14:00-21:00`），
     *   有午休的驿站不会被截掉后半段。
     *
     * 解析不出成对的钟点就**原样返回**：宁可难看，也不能把信息弄丢或者编出一个
     * 不存在的营业时间。
     */
    fun stationHoursLabel(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val times = STATION_TIME.findAll(text).take(4).toList()
        // 奇数个钟点说不清怎么配对（要么宿主给了半句话，要么我们的正则太宽），退回原文。
        if (times.size < 2 || times.size % 2 != 0) return text
        val ranges = times.chunked(2).joinToString("，") { "${clockOf(it[0])}-${clockOf(it[1])}" }
        val prefix = text.substring(0, times.first().range.first).trimEnd(*PREFIX_TRIM)
        return if (prefix.isEmpty() || FULL_WEEK.containsMatchIn(prefix)) ranges else "$prefix · $ranges"
    }

    /** 宿主营业时间里的一个钟点：`09点00分` / `9:00` / `21点` 都认。 */
    private val STATION_TIME = Regex("""(\d{1,2})\s*[:：点时]\s*(\d{1,2})?\s*分?""")

    /** 前缀与时间之间的残留标点，如「周一至周五，09:00-18:00」里的逗号。 */
    private val PREFIX_TRIM = charArrayOf(' ', '，', ',', '、', '：', ':', '至', '-', '~', '到')

    /** 「全周」表述：这些前缀丢掉不损失信息，因为它们等于「没限制」。 */
    private val FULL_WEEK = Regex("""(每[天日]|全周|一周[七7]天|每?周[一1]\s*[至\-~到]\s*周[日天七])""")

    /** 把正则命中的钟点写成 `H:mm`；分钟缺省按 0 算（`09点` = 9:00）。 */
    private fun clockOf(match: MatchResult): String {
        val hour = match.groupValues[1].toIntOrNull() ?: return match.value
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        return "$hour:${minute.toString().padStart(2, '0')}"
    }

    /**
     * 「x小时前」—— 运输中 / 派送中卡片状态前面的相对时间。
     *
     * 起算点用 [statusSince]（当前状态是从哪次事件开始的），不是直接拿 [ExpressRecord.timestamp]。
     * 口径：不足 1 分钟不显示（「0分钟前」是错误观感）；1 小时内说分钟，一天内说小时，
     * 再往上说天。`now` 由调用方注入 —— core 层不碰系统时钟（与 [inStationLabel] 同一条规矩）。
     *
     * @return null = 时间异常（时钟回拨 / 两处来源都没有），整段不显示。
     */
    fun relativeAge(timestamp: Long, now: Long): String? {
        val elapsed = now - timestamp
        if (elapsed <= 0L) return null
        val minutes = elapsed / 60_000L
        return when {
            minutes < 1L -> null
            minutes < 60L -> "${minutes}分钟前"
            minutes < 24 * 60L -> "${minutes / 60L}小时前"
            else -> "${ChronoUnit.DAYS.between(Instant.ofEpochMilli(timestamp), Instant.ofEpochMilli(now))}天前"
        }
    }

    /** 轨迹点时间的两种写法：秒级是接口原样，分钟级是个别快递公司给的缩水版。 */
    private val TRACE_TIME_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val TRACE_TIME_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 轨迹点时间字符串 → epoch 毫秒。
     *
     * 轨迹里存的是宿主原样给的「2026-09-26 13:54:00」（见 [ExpressTracePoint]）：排序可以
     * 按字符串比（同源格式固定），但要跟 [ExpressRecord.timestamp]（epoch）比新旧、
     * 要算「几小时前」，就必须真正解析一次。解析不出返回 null —— 宁可没有这个时间，
     * 不能编一个出来。
     */
    fun tracePointTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(raw.trim(), TRACE_TIME_FULL).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Throwable) {
        try {
            LocalDateTime.parse(raw.trim(), TRACE_TIME_MINUTE).atZone(zone).toInstant().toEpochMilli()
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 「x小时前」该从哪个时刻起算：**当前状态是从哪次事件开始的**。
     *
     * 只用 [ExpressRecord.timestamp]（宿主 `logistics_gmt_modified`）有一个真实缺陷：
     * 宿主自己几小时不刷新时它就停在旧值 —— 真机实证（2026-09-26）：13:54 已派送的件
     * 显示「11小时前」，因为 gmt_modified 停在凌晨；而真正的派送时刻轨迹里写着
     * （13:54 那条「正在派件」）。所以两个来源取**较新者**：
     * 轨迹最新节点是最新的事件清单，宿主 gmt_modified 兜底（轨迹没拉到、或拉得比宿主还旧时仍有值）。
     *
     * @return null = 两处都给不出有效时间（timestamp 为 0 且轨迹为空 / 时间解析不出）。
     */
    fun statusSince(record: ExpressRecord, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val fromTrace = record.trace.lastOrNull()?.let { tracePointTime(it.time, zone) }
        val fromRecord = record.timestamp.takeIf { it > 0L }
        return listOfNotNull(fromTrace, fromRecord).maxOrNull()
    }

    /**
     * 「平台 · 商品名」—— 首页卡片和通知正文共用同一份拼接规则，两处不会串味。
     *
     * 只有一样时只显示那一样；两样都没有返回 null（调用方整行省略，不留「商品：null」）。
     * 用 `·` 而不是菜鸟的 `|`：`|` 在正文里偏高，一行出现两次会显得碎。
     *
     * 平台侧再过一次 [ExpressPlatform.normalize]：写入 / 读取两个边界已经洗过了，这里是
     * **排版这一层的兜底** —— 凡是走到这一行的记录，无论它从哪条路来，都不该出现
     * 「来源：普通收件」这种把收件类型词当平台的情况（它还会把商品名的位置占住）。
     */
    fun goodsSummary(record: ExpressRecord): String? {
        val platform = ExpressPlatform.normalize(record.platform)
        val goods = record.goodsName?.takeIf { it.isNotBlank() }
        return when {
            platform != null && goods != null -> "$platform · $goods"
            goods != null -> goods
            platform != null -> platform
            else -> null
        }
    }

    /** 通知正文里的商品行：给 [goodsSummary] 加上字段名前缀（正文里需要，卡片上不需要）。 */
    fun goodsLine(record: ExpressRecord): String? =
        goodsSummary(record)?.let { "商品：$it" }

    /**
     * 运输中卡片的副行：「手机尾号XXXX · 运单动态」。两样都没有时返回 null（整行省略）。
     *
     * **运单号尾号刻意不在这里出现**：全号已经跟在标题的公司名后面了，再补一句
     * 「尾号 9976」就是把同一串数字说两遍。只有 [ExpressRecord.phoneTail] ——
     * 通知里明写的「手机尾号1234」—— 是独立信息（凭手机号取件时要念出口），才值得占这行。
     *
     * 运单动态排在后面且不截断优先级更高：它是「这件现在在哪」的答案，
     * 而手机尾号只在特定的取件场景才有用。调用方负责给这一行加 `maxLines=1`，
     * 空间不够时被截的是尾部 —— 也就是动态，这是刻意的：动态下一小时就会变，
     * 手机尾号不会。
     */
    fun detailLine(record: ExpressRecord): String? {
        val parts = mutableListOf<String>()
        record.phoneTail?.takeIf { it.isNotBlank() }?.let { parts += "手机尾号$it" }
        record.logisticsDetail?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * 卡片右上角的状态文案。
     *
     * 用户自己确认过取件的记录显示「已取件」—— 那是**用户做过的事**，不该被宿主还没更新过来的
     * 「待取件」盖住（宿主可能要等快递员回来扫签收，隔天都有可能）。所以这里的优先级是
     * 「用户标记 > 宿主状态」，但 [ExpressStatus.SIGNED] 除外：那是外部给的更权威的说法，
     * 而且结论一致（用户取走了、快递也签收了），没必要再显示成用户自己点的那个词。
     *
     * 只做文案，不碰 [ExpressRecord.status] —— 两者是不同的东西，见 `ExpressRecord.pickedUpAt`。
     */
    fun statusLabel(record: ExpressRecord): String = when {
        !record.isPickedUp -> record.status.displayName
        record.status == ExpressStatus.SIGNED -> record.status.displayName
        else -> PICKED_UP_LABEL
    }

    /** 用户确认取件后的状态文案。展示层与通知文案共用一份，避免两边写法不一致。 */
    const val PICKED_UP_LABEL = "已取件"

    /** 合并多条记录时的标题。 */
    fun summaryTitle(records: List<ExpressRecord>): String {
        val ready = records.count { it.status == ExpressStatus.READY_FOR_PICKUP }
        return when {
            records.isEmpty() -> "快递"
            ready > 0 -> "有 $ready 个包裹待取件"
            records.size == 1 -> title(records.first())
            else -> "${records.size} 个包裹动态"
        }
    }

    /** 合并多条记录时的正文，每条一行摘要。 */
    fun summaryBody(records: List<ExpressRecord>): String =
        records.joinToString("\n") { record ->
            buildString {
                append("· ")
                append(title(record))
                record.pickupCode?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            }
        }
}
