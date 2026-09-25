package io.github.YGHFv.ReaPressExtend.core

/**
 * 一条被识别出来的快递信息。
 *
 * 所有字段都可空 —— 不同来源能拿到的信息量差别很大：
 * 短信通知只有纯文本；菜鸟通知有标题+正文；宿主进程富化 hook 能补上运单号和驿站。
 * 解析器**不编造**：拿不到就是 null，由展示层决定怎么降级。
 */
data class ExpressRecord(
    /** 来源包名，如 com.cainiao.wireless。 */
    val sourcePackage: String,
    /** 触发本次记录的原文（标题 + 正文拼起来），用于诊断与去重。 */
    val rawText: String,
    /** 运单号。带前缀的会归一化成大写。 */
    val trackingNumber: String? = null,
    /** 快递公司。由运单号前缀或文案里的公司名推得。 */
    val courier: Courier = Courier.UNKNOWN,
    /** 取件码，如 "8-2-3021"。 */
    val pickupCode: String? = null,
    /** 驿站 / 快递柜名称，如 "菜鸟驿站(杭州文一西路店)"。 */
    val station: String? = null,
    /** 当前物流状态。 */
    val status: ExpressStatus = ExpressStatus.UNKNOWN,
    /** 通知标题（原样保留，便于诊断）。 */
    val title: String? = null,
    /** 命中判定的关键词，用于在界面上解释"为什么这条被拦了"。 */
    val matchedKeywords: List<String> = emptyList(),
    /** 判定置信度 0..100。低于阈值的会被放行而不是拦截。 */
    val confidence: Int = 0,
    /** 事件时间（毫秒）。由调用方注入 —— core 层不碰系统时钟，方便单测。 */
    val timestamp: Long = 0L,
) {
    /**
     * 主键。用于日志与诊断，以及 [ExpressDedupe] 需要「一个」字符串时。
     *
     * 强到弱：运单号 → 取件码 → 原文哈希。**注意它不含驿站名** —— 判断两条记录是不是
     * 同一个包裹请用 [isSamePackageAs]，那个函数才能表达「运单号冲突就不合并」这类规则。
     */
    val dedupeKey: String
        get() = when {
            !trackingNumber.isNullOrBlank() -> "tn:$trackingNumber"
            !pickupCode.isNullOrBlank() -> "pc:$pickupCode"
            else -> "raw:${rawText.hashCode()}"
        }

    /**
     * 判断两条记录是否指向同一个包裹。
     *
     * 规则（越靠前越权威）：
     * 1. **双方都有运单号** → 完全按运单号判。运单号全局唯一，不同就是两件 —— 哪怕取件码
     *    碰巧一样（同一货架格先后放过两件的情况真实存在）。
     * 2. **双方都有取件码** → 按取件码判。**这里刻意不比驿站名**：同一个包裹的多次推送里
     *    驿站名的详略经常不一致（「菜鸟驿站(合肥南湖春城华韵古筝店)」vs「合肥南湖春城店」），
     *    拿驿站名参与比对会把同一个包裹拆成两条，首页上就是两张卡片。
     * 3. 只有一边有运单号/取件码 → 用另一边有的那个比对（情形 1/2 的退化版）。
     * 4. 都没有 → 只能比原文。
     */
    fun isSamePackageAs(other: ExpressRecord): Boolean {
        val thisTracking = trackingNumber?.takeIf { it.isNotBlank() }
        val otherTracking = other.trackingNumber?.takeIf { it.isNotBlank() }
        if (thisTracking != null && otherTracking != null) {
            return thisTracking == otherTracking
        }

        val thisPickup = pickupCode?.takeIf { it.isNotBlank() }
        val otherPickup = other.pickupCode?.takeIf { it.isNotBlank() }
        if (thisPickup != null && otherPickup != null) {
            return thisPickup == otherPickup
        }

        // 一边有运单号、另一边只有取件码之类的错位情形：拿两边的强标识并起来比，
        // 都没有才退回原文。这里不做启发式猜测 —— 认错包裹比多出一条记录更糟。
        if (thisTracking != null || otherTracking != null ||
            thisPickup != null || otherPickup != null
        ) {
            return thisTracking == otherTracking && thisPickup == otherPickup
        }

        return rawText == other.rawText
    }
}

/**
 * 物流状态。
 *
 * 顺序即「推进程度」，[isAdvanceFrom] 用它判断新状态是否值得覆盖旧状态 ——
 * 推送乱序时（先收到「已签收」再收到「运输中」）不能把状态往回退。
 */
enum class ExpressStatus(val displayName: String, val order: Int) {
    UNKNOWN("未知", 0),
    CREATED("已下单", 1),
    PICKED_UP("已揽收", 2),
    IN_TRANSIT("运输中", 3),
    ARRIVED_STATION("已到站", 4),
    DELIVERING("派送中", 5),
    READY_FOR_PICKUP("待取件", 6),
    SIGNED("已签收", 7),
    FAILED("投递失败", 8),
    ;

    fun isAdvanceFrom(previous: ExpressStatus): Boolean = order >= previous.order
}
