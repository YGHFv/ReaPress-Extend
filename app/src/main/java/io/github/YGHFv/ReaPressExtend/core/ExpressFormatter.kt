package io.github.YGHFv.ReaPressExtend.core

import java.time.Instant
import java.time.ZoneId
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
     * 通知正文。
     *
     * 字段顺序按「用户最关心的」排：取件码 > 驿站 > 运单号 > 状态描述。
     * 缺失的字段整行省略，不留「运单号：null」这种噪音。
     */
    fun body(record: ExpressRecord): String {
        val lines = mutableListOf<String>()
        record.pickupCode?.takeIf { it.isNotBlank() }?.let { lines += "取件码：$it" }
        record.station?.takeIf { it.isNotBlank() }?.let { lines += "地点：$it" }
        // 商品行把「哪个平台买的」和「买的什么」拼一起 —— 和菜鸟首页卡片同一行同一种写法，
        // 用户在两个界面之间来回看时不会对不上。
        goodsLine(record)?.let { lines += it }
        record.trackingNumber?.takeIf { it.isNotBlank() }?.let { lines += "运单号：$it" }
        if (record.status != ExpressStatus.UNKNOWN) {
            lines += "状态：${record.status.displayName}"
        }
        // 一个字段都没抽到时退回原文 —— 宁可给用户看原始文案，也不要一条空通知。
        if (lines.isEmpty()) {
            return record.rawText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return lines.joinToString("\n")
    }

    /**
     * 「已入站 N 天」。**按自然日差算，不是 24 小时整除。**
     *
     * 这条口径是照菜鸟定的（2026-09-26 实测）：一件 09-24 18:21 到站的包裹，
     * 09-26 03:30 打开时菜鸟显示「已入站2天」，而 24 小时制只会算出 1 天。
     * 用户对「几天」的直觉是**数日历格子**（24 号、25 号、26 号 → 2 天），
     * 不是「够不够 24 小时」—— 照 24 小时算，同一件包裹会在到站 24 小时后
     * 突然从「今天」跳成「1天」，那个跳变点跟用户感知的「过了一天」对不上。
     *
     * `now` 由调用方注入 —— core 层不碰系统时钟，这条规则才能单测。
     * [zone] 给默认值是因为它属于「运行环境」而不是「时间」：同一条记录在两个时区
     * 可能差一天，而用户看的是自己手机上的日历。
     *
     * **只对到站件用**：`arrivalAt` 取的是宿主物流记录里「最后一次状态变更时间」，
     * 对运输中的件说「已入站」是错的。调用方要先看 [ExpressRecord.status]。
     *
     * 不足一天说「今天入站」而不是「已入站0天」：后者读起来像出错。
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
        // days <= 0 只可能来自时钟回拨或宿主给错时间，按「刚发生」处理，不显示「已入站-1天」。
        return if (days <= 0L) "今天入站" else "已入站${days}天"
    }

    /**
     * 「平台 · 商品名」—— 首页卡片和通知正文共用同一份拼接规则，两处不会串味。
     *
     * 只有一样时只显示那一样；两样都没有返回 null（调用方整行省略，不留「商品：null」）。
     * 用 `·` 而不是菜鸟的 `|`：`|` 在正文里偏高，一行出现两次会显得碎。
     */
    fun goodsSummary(record: ExpressRecord): String? {
        val platform = record.platform?.takeIf { it.isNotBlank() }
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
