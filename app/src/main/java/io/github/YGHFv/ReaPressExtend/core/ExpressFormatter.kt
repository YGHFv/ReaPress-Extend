package io.github.YGHFv.ReaPressExtend.core

/**
 * 把 [ExpressRecord] 渲染成替换通知的标题与正文。
 *
 * 纯函数：文案逻辑要能单测，且要在模块 App 进程（发通知）和可能的诊断界面（预览）里共用。
 *
 * 设计原则：**信息密度优先于花哨**。原通知往往把运单号、广告、表情、多个包裹混在一段里，
 * 用户真正要的是「哪个包裹、到哪了、取件码多少」。所以这里按固定字段重排，而不是照抄原文。
 */
object ExpressFormatter {

    /** 通知标题。固定带前缀，让用户一眼分辨这是模块重发的。 */
    fun title(record: ExpressRecord): String {
        val courier = if (record.courier == Courier.UNKNOWN) "快递" else record.courier.displayName
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
