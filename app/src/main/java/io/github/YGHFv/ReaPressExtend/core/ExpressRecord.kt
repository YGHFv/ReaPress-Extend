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
    /**
     * 电商平台，如「淘宝」「天猫」。
     *
     * **不是快递公司** —— 快递公司是 [courier]。菜鸟首页卡片第一行左边那个词就是这个，
     * 用来回答「这件是哪买的」。宿主侧的字段名是 `pkgSourceDesc`（宿主已经映射好的中文名）。
     */
    val platform: String? = null,
    /** 商品名称，如「云南一级白糖砂糖食用纯甘蔗糖」。菜鸟卡片第一行右边那个词。 */
    val goodsName: String? = null,
    /**
     * 一次「到站 / 入站」发生的时间（毫秒）。
     *
     * 取的是宿主物流记录里**最后一次状态变更时间**（到站件的最后一次变更就是入站），
     * 所以只对 [ExpressStatus.ARRIVED_STATION] / [ExpressStatus.READY_FOR_PICKUP] 有「在站几天」
     * 的语义 —— 展示层要先看状态再用它，别拿运输中件的这个时间去算停留天数。
     */
    val arrivalAt: Long? = null,
    /**
     * 收件**手机号**的尾号（4 位），如 `1234`。
     *
     * 与「运单号尾号」是两回事：那个由展示层拿 [trackingNumber] 现算，不进模型。
     * 这里装的是通知里明写的「手机尾号1234」「手机后四位1234」—— 它是**独立信息**
     * （凭手机号取件时要说出口），所以值得单独留一个字段。
     *
     * 只来自通知文案：宿主侧的 `encryReceiverTel` 是加密串（32 位 hex），读不出尾号，
     * 所以不存在「富化版本」。抽不到就是 null。
     */
    val phoneTail: String? = null,
    /**
     * 运单动态 —— 宿主物流记录里最新的一条详情，如「已发往【上海转运中心】」。
     *
     * 宿主 key 是 `lastLogisticDetail`（真机字段 dump 证据见 `CainiaoPackageHook`）。
     * 只有宿主富化给得出，通知文案里没有。
     */
    val logisticsDetail: String? = null,
    /**
     * 驿站营业时间，**原样保留宿主给的那串**，如「周一至周日09点00分到21点00分」。
     *
     * 宿主 key 是 `packageStation.officeTime`。不在这里做格式化：core 里这个字段是
     * 「宿主原话」，规范化属于展示层（[ExpressFormatter.stationHoursLabel]），
     * 免得以后想换写法时历史记录里的数据已经不可逆地改过了。
     *
     * 宿主常常不下发（很多驿站就没填），为空表示「不知道」而不是「不营业」——
     * 展示层整段省略即可。
     */
    val stationHours: String? = null,
    /**
     * 用户在本模块里**手动确认已取件**的时间（毫秒）。null = 还没取。
     *
     * ## 为什么必须有这个字段
     *
     * 通知链路上根本没有「已取件」这个事件 —— 用户去驿站把包裹拿走时，菜鸟、快递公司
     * 都不会推一条通知过来（要等快递员回来扫签收，可能几小时甚至隔天）。也就是说
     * **「我取走了」这件事只有用户自己知道**，模型里不留一格，界面就永远只能显示「待取件」。
     *
     * ## 与 [status] 的关系：互不覆盖
     *
     * [status] 是外部的客观物流状态（通知/宿主给的），这里是用户的主观确认。两者是不同的东西：
     * - 用户确认不会改写 [status] —— 否则就分不清「用户说取了」和「快递公司说签收了」；
     * - 宿主随后推来的「已签收」也不会抹掉这里 —— 那是「这条已经被完整闭环」的证据，
     *   界面靠它把文案从「已取件」换成「已签收」。
     *
     * 字段只在用户双击卡片时由 UI 写入（见 `ExpressRecordStore.setPickedUp`），
     * 富化 hook 永远给不出它，所以它不该出现在跨进程 relay 契约里。
     */
    val pickedUpAt: Long? = null,
    /** 当前物流状态。 */
    val status: ExpressStatus = ExpressStatus.UNKNOWN,
    /** 通知标题（原样保留，便于诊断）。 */
    val title: String? = null,
    /** 这条记录从哪来。决定它能不能作为「包裹」出现在首页，以及诊断时该去哪一侧找日志。 */
    val origin: ExpressOrigin = ExpressOrigin.NOTIFICATION,
    /** 命中判定的关键词，用于在界面上解释"为什么这条被拦了"。 */
    val matchedKeywords: List<String> = emptyList(),
    /** 判定置信度 0..100。低于阈值的会被放行而不是拦截。 */
    val confidence: Int = 0,
    /** 事件时间（毫秒）。由调用方注入 —— core 层不碰系统时钟，方便单测。 */
    val timestamp: Long = 0L,
) {
    /** 用户是不是已经确认取走了这件（见 [pickedUpAt]）。 */
    val isPickedUp: Boolean get() = pickedUpAt != null

    /**
     * 主键。用于日志与诊断，以及 [ExpressDedupe] 需要「一个」字符串时。
     *
     * 强到弱：运单号 → 取件码 → 原文哈希。**注意它不含驿站名** —— 判断两条记录是不是
     * 同一个包裹请用 [isSamePackageAs]，那个函数才能表达「运单号冲突就不合并」这类规则。
     *
     * `ExpressRecordStore.setPickedUp` 也拿它当 UI 与存储之间的定位键：界面点的是列表里的
     * 某一条，存储层只知道一串字符，这个属性正是「一条记录的一串字符」。
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
     *    驿站名的详略经常不一致（「菜鸟驿站(合肥南湖新城华韵古筝店)」vs「合肥南湖新城店」），
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

    /**
     * 把宿主进程富化出的字段并进本记录。
     *
     * ## 只填空，不覆盖
     *
     * 通知记录里的字段是「用户当时看到过的东西」，富化值再准也不该把它改写掉：
     * 两条来源不一致时（比如通知写了 A 公司、宿主返回 B 公司），以通知为准 —— 用户是照着
     * 通知去找件的，界面和通知对不上比信息不全更让人困惑。
     *
     * 例外只有两处，都只在**富化值明确更完整**时才替换：
     * - 运单号：本记录的若是富化值的后缀（通知里被截断成尾号），换成全号
     * - 状态：只允许**推进**（复用 [ExpressStatus.isAdvanceFrom]），乱序送达不会把状态往回退
     *
     * @return 合并后的记录。没有任何字段被改变时返回自身（调用方可用引用相等判断"无变化"）
     */
    fun mergeEnrichment(other: ExpressRecord): ExpressRecord {
        val otherTracking = other.trackingNumber?.takeIf { it.isNotBlank() }
        val mergedTracking = when {
            otherTracking == null -> trackingNumber
            trackingNumber.isNullOrBlank() -> otherTracking
            trackingNumber == otherTracking -> trackingNumber
            // 通知里是截断的尾号，富化给了全号 —— 这是「更完整」，可以替换。
            otherTracking.endsWith(trackingNumber) -> otherTracking
            // 两边都有值且互不包含：不动。宁可留着通知里那串（用户核对过），
            // 也不要把可能是别的包裹的号写进来。
            else -> trackingNumber
        }
        val mergedStatus =
            if (other.status.isAdvanceFrom(status)) other.status else status

        val merged = copy(
            trackingNumber = mergedTracking,
            courier = if (courier == Courier.UNKNOWN) other.courier else courier,
            station = station ?: other.station,
            pickupCode = pickupCode ?: other.pickupCode,
            // 这几个只有宿主富化给得出（通知文案里没有），所以走同一套「只填空」规则：
            // 本记录已经有值就不动，没有才吸收。
            platform = platform ?: other.platform,
            goodsName = goodsName ?: other.goodsName,
            arrivalAt = arrivalAt ?: other.arrivalAt,
            logisticsDetail = logisticsDetail ?: other.logisticsDetail,
            stationHours = stationHours ?: other.stationHours,
            // 手机尾号的来源只有通知一处，富化值恒为 null，这条写在这里只是让「只填空」的
            // 规则在这张字段表上不留缺口。
            phoneTail = phoneTail ?: other.phoneTail,
            // 用户的「已取件」确认不能被合并冲掉 —— 合并的两边都是通知/富化数据，
            // 它们从来不带这个字段，所以「只填空」在此处等价于「原样保留」。
            pickedUpAt = pickedUpAt ?: other.pickedUpAt,
            status = mergedStatus,
            title = title ?: other.title,
        )
        return if (merged == this) this else merged
    }
}

/**
 * 记录的来源。
 *
 * 两类都会出现在首页，但必须区分运行时：首页多出一张卡片时，得能一眼判断是拦截判错了、
 * 还是富化插进来的、还是富化没配上已有通知而多建了一条。
 *
 * - [NOTIFICATION]：system_server 拦到的通知 —— 触发源。
 * - [ENRICHMENT]：宿主进程直接读到的包裹。**通知缺失时它是唯一来源**（用户没给通知权限、
 *   通知被划掉、那一刻模块进程没跑），有通知时它只是补充。
 */
enum class ExpressOrigin(val displayName: String) {
    /** system_server 拦到的通知。 */
    NOTIFICATION("通知"),

    /** 宿主进程富化 hook 读到的数据（可补充已有记录，也可在无通知时独立成条）。 */
    ENRICHMENT("宿主富化"),
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
