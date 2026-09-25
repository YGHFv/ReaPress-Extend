package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressEnrichmentMatcher
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStationName
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 结构化快递记录的持久化。
 *
 * ## 与 [ExpressNotificationLog] 的分工
 *
 * - [ExpressNotificationLog] 记的是**投递审计**：这一条通知发出去没有、失败原因是什么。
 *   它是排查链路问题的证据，字段是扁平的标题/正文。
 * - 这里存的是**结构化包裹**：运单号、取件码、驿站、状态、时间。首页按驿站聚合展示靠的是它。
 *
 * 两者刻意分开：审计记录要保留每次尝试（同一条可能失败多次），而包裹记录要**按包裹去重合并**
 * —— 首页上同一个包裹只该出现一次，且显示最新状态。
 *
 * ## 合并规则
 *
 * 同一个 [ExpressRecord.dedupeKey] 只保留一条：
 * - 新状态**推进**时覆盖（运输中 → 待取件）
 * - 状态**倒退**时丢弃（乱序推送不该把「已签收」退回「运输中」）
 * - 同级时保留时间较新的（文案可能更新，比如取件码提醒升级）
 *
 * 这套规则与 [io.github.YGHFv.ReaPressExtend.core.ExpressDedupe] 一致 —— 那个管的是
 * 「这条要不要处理」，这个管的是「存下来的那条该是哪个版本」。
 *
 * ## 富化（[enrich]）
 *
 * 通知是第一手数据却不是最完整的数据：运单号常被截断、驿站和取件码时有时无。宿主进程的
 * 采集 hook 直接从菜鸟自己的库里读这三样，能补上。富化数据也**可以新建记录** ——
 * 宿主已经知道的包裹，不该因为「通知没拦到」就从首页消失。规则和取舍都写在 [enrich] 上。
 *
 * ## 取件确认（[setPickedUp]）
 *
 * 用户在界面上双击卡片（取件码那一行）确认「我取走了」，写进 [ExpressRecord.pickedUpAt]；
 * 再双击一次就是撤销。这是**唯一**
 * 由模块自己产生、而不是从宿主/通知读来的状态 —— 「取走了」这件事没有任何外部事件会告诉我们。
 * 写入是单条字段级的（不碰 status），分组层再据此判断「整站取完」，见
 * [ExpressHomeGrouper]。
 */
object ExpressRecordStore {

    private const val PREFS = "reapress_records"
    private const val KEY_RECORDS = "records"

    private const val LOG_TAG = "ReaPress"

    /** 保留上限。首页只展示最近的，更早的没有查看入口，留着只占空间。 */
    private const val MAX_RECORDS = 200

    /**
     * 判「一段运单号是另一段的后缀」时，短的那段至少要有多长。
     *
     * 与 [ExpressEnrichmentMatcher] 内部判截断用的 `MIN_TRACKING_LENGTH` 是同一个尺度，
     * 但那边是 private、语义也偏「匹配信号」；存储层的合并判定自己持有一份，改一边不会
     * 悄悄改掉另一边。
     */
    private const val MIN_TRUNCATION_LENGTH = 6

    /** [EnrichResult.matchedIndex] 用它表示「没配上任何已有记录，这一条是新加的」。 */
    internal const val NO_MATCH = -1

    /**
     * 写入一条记录（合并同一个包裹的旧记录）。
     *
     * 判断逻辑在 [applyUpsert]（纯函数，可单测），这里只负责读写存储。
     *
     * @return true 表示这条记录改变了存储内容（新增或状态推进）
     */
    fun upsert(context: Context, record: ExpressRecord): Boolean = runCatching {
        val next = applyUpsert(load(context), record) ?: return@runCatching false
        save(context, next)
        true
    }.getOrDefault(false)

    /**
     * [upsert] 的纯逻辑。
     *
     * 「同一个包裹」走 [isSamePackageLoose]，比 [ExpressRecord.isSamePackageAs] 宽一档 ——
     * 见那个函数的注释：富化和通知对同一个包裹给的单号详略不同，按严格相等比会拆成两张卡片。
     *
     * 合并**不是「新的整条覆盖旧的」**，而是两边字段的并集（[mergeVersions]）：通知有
     * 用户见过的文案，宿主侧有全号、驿站和取件码，谁更新不代表谁的字段更全。
     *
     * @return 新列表；null 表示这条记录不该改动存储（状态倒退，或同级但更旧）
     */
    internal fun applyUpsert(
        current: List<ExpressRecord>,
        record: ExpressRecord,
    ): List<ExpressRecord>? {
        val existing = current.firstOrNull { isSamePackageLoose(it, record) }
            ?: return current + record

        return when {
            record.status.order > existing.status.order -> current.map {
                if (isSamePackageLoose(it, record)) mergeVersions(it, record) else it
            }
            record.status.order < existing.status.order -> null
            // 同级：时间较新的胜出，但不改变「已存在」这个事实。
            record.timestamp >= existing.timestamp -> current.map {
                if (isSamePackageLoose(it, record)) mergeVersions(it, record) else it
            }
            else -> null
        }
    }

    /**
     * upsert 用的「同一包裹」判定，比 [ExpressRecord.isSamePackageAs] 宽一档。
     *
     * 多出来的这一档只针对富化引入的新情形：**一边的运单号是另一边的一段后缀**。
     * 通知里经常只有尾号（`6789`），富化给的是全号（`SF123456789`），严格相等判不出来，
     * 同一件包裹会变成两张卡片。
     *
     * 卡一个长度下限（[MIN_TRUNCATION_LENGTH]）：四位尾号在任意两个单号之间都可能巧合命中，
     * 六位以上才够格当证据 —— 和 [ExpressEnrichmentMatcher] 判「截断」用的是同一个尺度。
     *
     * 只在 [upsert] 里用，**不动** [ExpressRecord.isSamePackageAs]：后者是 core 层的语义，
     * 「运单号不同就是两件」这条规则要留给所有调用方，放宽只能是存储层针对富化的局部决定。
     */
    private fun isSamePackageLoose(a: ExpressRecord, b: ExpressRecord): Boolean {
        if (a.isSamePackageAs(b)) return true
        val ta = a.trackingNumber?.takeIf { it.isNotBlank() } ?: return false
        val tb = b.trackingNumber?.takeIf { it.isNotBlank() } ?: return false
        return when {
            ta.length >= MIN_TRUNCATION_LENGTH && tb.length > ta.length -> tb.endsWith(ta)
            tb.length >= MIN_TRUNCATION_LENGTH && ta.length > tb.length -> ta.endsWith(tb)
            else -> false
        }
    }

    /**
     * 把同一包裹的新旧两个版本合成一条。
     *
     * 不能用「新的整条覆盖旧的」：两个来源各有所长 —— 通知有用户核对过的文案，
     * 宿主侧有全号、驿站和取件码 —— 谁更新**不代表**谁的字段更全。走两次
     * [ExpressRecord.mergeEnrichment]（它本身只填空不覆盖）：先让旧记录吸收新记录的独有字段，
     * 再让新记录吸收结果的独有字段，合起来就是两边字段的并集。而 mergeEnrichment 里
     * 「运单号被截断时取更完整的那个」这处例外，正好保证结果里留下的是**全号**而不是尾号。
     */
    private fun mergeVersions(existing: ExpressRecord, incoming: ExpressRecord): ExpressRecord =
        incoming.mergeEnrichment(existing.mergeEnrichment(incoming))

    /** 全部记录，按时间倒序（最新在前）。 */
    fun load(context: Context): List<ExpressRecord> =
        runCatching {
            parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
                .sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())

    /**
     * 用 [enrichment] 补充已有记录里缺失的字段；**没有能配上的记录就新建一条**。
     *
     * ## 为什么配不上也要建
     *
     * 富化数据（菜鸟详情页的包裹）和通知记录**没有共用的主键**，能不能算同一个包裹只能靠
     * [ExpressEnrichmentMatcher] 多信号打分推断。最初的实现是「配不上就丢弃」，但那会让
     * **宿主明明知道的包裹整批消失**：通知权限没给、通知被用户划掉、系统把它折叠了、
     * 或者拦截那一刻模块进程刚被杀 —— 这些情况下菜鸟 App 里躺着包裹卡片，模块却一无所知。
     * 而用户装这个模块图的就是「不打开宿主也能看到我的包裹」，少一个包裹比多一条记录严重得多。
     *
     * 所以规则是：
     * - **配得上** → 合并（[ExpressRecord.mergeEnrichment]，只填空不覆盖）
     * - **配不上** → 新建一条，来源记 [ExpressOrigin.ENRICHMENT]
     *
     * ## 新建会不会造成重复
     *
     * 会，但面很窄。走到「配不上」这一步，说明通知记录里**连这个运单号的尾号都没出现过**
     * （出现过的话 [ExpressEnrichmentMatcher] 直接给 60 分以上、早就配上了），取件码也没对上。
     * 唯一残留的重复场景是：那条通知压根没写运单号（只有驿站名 + 取件码），两边只能靠驿站名
     * 拿到 [ExpressEnrichmentMatcher] 的 15 分，不足以确认是不是同一件。这种就真的会多出一张
     * 卡片 —— 这是**有意的取舍**：宁可重复一张，不可漏掉一件。
     *
     * 反复到达不会重复建。新建之后这条记录自己就带了全号，下一次富化再来时打分是 100，
     * 会走到上面的合并分支（配上但无新信息 → 不打日志直接返回），所以详情页开几次都只留一条。
     *
     * ## 日志
     *
     * 新建前把候选的最高分打出来（[logEnrichMiss]），这是真机验证时区分几种情况的唯一依据：
     * `bestScore=0` = 确实没有对应通知（正常新建），`bestScore=15` = 有驿站名相同的候选但证据
     * 不足（**唯一可能重复的分支**，这行反复出现同一个 tn 就说明该重新权衡驿站名信号了）。
     *
     * @return true 表示存储内容确实变了（有字段被补上，或新增了一条）
     */
    fun enrich(context: Context, enrichment: ExpressRecord): Boolean = runCatching {
        val current = load(context)
        val result = applyEnrichment(current, enrichment) ?: return@runCatching false

        if (result.matchedIndex >= 0) {
            val target = current[result.matchedIndex]
            val merged = result.records[result.matchedIndex]
            ModuleAndroidLog.legacy(
                LOG_TAG,
                "enriched: tn=${target.trackingNumber} -> ${merged.trackingNumber} " +
                    "courier=${merged.courier.displayName} " +
                    "station=${merged.station ?: "-"} pickup=${merged.pickupCode ?: "-"}",
            )
        } else {
            logEnrichMiss(current, enrichment)
        }

        save(context, result.records)
        true
    }.getOrDefault(false)

    /**
     * [enrich] 的纯逻辑：配得上就合并到命中那条上，配不上就**追加一条**。
     *
     * 单独抽出来是因为「该合并还是该新建」是这块唯一容易出错的地方，理由与取舍见 [enrich]；
     * [enrich] 本身只剩读写存储和打日志。
     *
     * 新建那条走 [applyUpsert] 而不是直接 `current + enrichment`：富化记录自带全号，万一它以
     * 别的路径（同一条通知既走了 deliver 又触发了富化）已经落过库，upsert 能合并掉，不会留两条。
     *
     * @return [EnrichResult]；null 表示存储内容不需要改动
     */
    internal fun applyEnrichment(
        current: List<ExpressRecord>,
        enrichment: ExpressRecord,
    ): EnrichResult? {
        val index = ExpressEnrichmentMatcher.indexOfTarget(current, enrichment)
        if (index >= 0) {
            val target = current[index]
            val merged = target.mergeEnrichment(enrichment)
            // 配上了但没有新信息（比如详情页被反复打开）：不改存储，让调用方也跳过日志，
            // 否则每次点开详情页都留一行，真正有价值的那次反而被淹掉。
            if (merged === target) return null
            return EnrichResult(current.toMutableList().also { it[index] = merged }, index)
        }

        val next = applyUpsert(current, enrichment) ?: return null
        return EnrichResult(next, matchedIndex = NO_MATCH)
    }

    /** [applyEnrichment] 的结果。 */
    internal data class EnrichResult(
        val records: List<ExpressRecord>,
        /** 命中的已有记录下标；[NO_MATCH] 表示这条是新建的。 */
        val matchedIndex: Int,
    )

    /**
     * 「没配上、准备新建」的诊断行。
     *
     * 分数是排查的关键：它把「确实没有对应通知」（0 分）和「有个像但不是的候选」（15 分）
     * 分开 —— 后者才是可能重复的那一支。见 [enrich] 的注释。
     */
    private fun logEnrichMiss(current: List<ExpressRecord>, enrichment: ExpressRecord) {
        val bestScore = current.maxOfOrNull {
            ExpressEnrichmentMatcher.score(it, enrichment)
        } ?: 0
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "enrich MISS (no confident target): tn=${enrichment.trackingNumber} " +
                "station=${enrichment.station} candidates=${current.size} bestScore=$bestScore " +
                "threshold=${ExpressEnrichmentMatcher.MATCH_THRESHOLD} -> creating new record",
        )
    }

    /**
     * 标记 / 取消标记「已取件」。
     *
     * 只动 [ExpressRecord.pickedUpAt] 一个字段，**不动 status**：用户确认取件 ≠ 快递公司确认签收，
     * 后者迟早由宿主推过来，那时 status 自己会推进（见 [ExpressRecord.mergeEnrichment]）。
     * 反过来，把 status 直接改成「已签收」会让界面再也分不清这个「已签收」是宿主说的还是用户点的。
     *
     * @param key 目标记录的 [ExpressRecord.dedupeKey]（界面直接给手里那一条）
     * @param at 非 null = 标记为「此刻已取件」；null = 撤销标记
     * @return true 表示存储内容确实变了
     */
    fun setPickedUp(context: Context, key: String, at: Long?): Boolean = runCatching {
        val next = applyPickedUp(load(context), key, at) ?: return@runCatching false
        save(context, next)
        true
    }.getOrDefault(false)

    /**
     * [setPickedUp] 的纯逻辑。
     *
     * 按 [ExpressRecord.dedupeKey] 定位而不是按下标：界面手里的列表和存储里的列表之间隔着一次
     * `load()`（按时间倒序重排过），下标对不上；`dedupeKey` 才是两边都认的身份。
     *
     * 找不到目标（用户点的那条刚好被 [MAX_RECORDS] 裁掉了）返回 null —— 这不是错误，
     * 界面刷新一次就自愈，没必要为它专门报错。
     */
    internal fun applyPickedUp(
        current: List<ExpressRecord>,
        key: String,
        at: Long?,
    ): List<ExpressRecord>? {
        val index = current.indexOfFirst { it.dedupeKey == key }
        if (index < 0) return null
        val target = current[index]
        if (target.pickedUpAt == at) return null
        return current.toMutableList().also { it[index] = target.copy(pickedUpAt = at) }
    }

    /**
     * 清空全部包裹记录。**没有任何自动化路径会走到这里** —— 记录只能由用户主动删。
     *
     * ⚠️ 2026-09-26：界面上的入口（设置页「数据」那一档）已按用户要求撤掉，放置办法待定。
     * 这个函数保留完整语义，等入口回来时直接用 —— 别在找入口的过程中把它当成死代码删掉。
     *
     * 用 `commit()` 而不是 `apply()`：用户点完清空很可能马上切走甚至杀进程，异步落盘会把这次
     * 删除丢掉，下次打开旧记录又回来了。「删了没删掉」比这个同步写贵得多。记录最多两百条，
     * 代价可以忽略。
     *
     * 只删包裹记录，不碰 [ExpressNotificationLog] 的投递审计（那是排查链路问题的证据，在诊断页）。
     */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS).commit()
        }
    }

    private fun save(context: Context, records: List<ExpressRecord>) {
        val trimmed = if (records.size <= MAX_RECORDS) {
            records
        } else {
            records.sortedByDescending { it.timestamp }.take(MAX_RECORDS)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, serialize(trimmed))
            .apply()
    }

    // ---- 序列化。抽成 internal 纯函数以便单测 ----

    internal fun serialize(records: List<ExpressRecord>): String {
        val array = JSONArray()
        for (record in records) {
            array.put(
                JSONObject().apply {
                    put("pkg", record.sourcePackage)
                    put("raw", record.rawText)
                    put("tn", record.trackingNumber ?: JSONObject.NULL)
                    put("courier", record.courier.name)
                    put("pickup", record.pickupCode ?: JSONObject.NULL)
                    put("station", record.station ?: JSONObject.NULL)
                    put("status", record.status.name)
                    put("title", record.title ?: JSONObject.NULL)
                    put("origin", record.origin.name)
                    put("kw", JSONArray(record.matchedKeywords))
                    put("conf", record.confidence)
                    put("at", record.timestamp)
                    // 只有宿主富化给得出的几个字段（通知文案里只有手机尾号）。
                    // 键名取得短；**旧版本存下来的 JSON 里没有这几个键**，parse 侧对缺失键
                    // 一律退回 null —— 升级不会丢历史记录，这条是硬约束。
                    put("platform", record.platform ?: JSONObject.NULL)
                    put("goods", record.goodsName ?: JSONObject.NULL)
                    put("arrival", record.arrivalAt ?: JSONObject.NULL)
                    put("ptail", record.phoneTail ?: JSONObject.NULL)
                    put("dyn", record.logisticsDetail ?: JSONObject.NULL)
                    put("hours", record.stationHours ?: JSONObject.NULL)
                    // 用户在界面上确认的「已取件」。同样是后加的键，缺失即「没取过」。
                    put("picked", record.pickedUpAt ?: JSONObject.NULL)
                },
            )
        }
        return array.toString()
    }

    internal fun parse(raw: String?): List<ExpressRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                ExpressRecord(
                    sourcePackage = obj.optString("pkg"),
                    rawText = obj.optString("raw"),
                    trackingNumber = obj.optStringOrNull("tn"),
                    courier = enumOr(obj.optString("courier"), Courier.UNKNOWN),
                    pickupCode = obj.optStringOrNull("pickup"),
                    station = obj.optStringOrNull("station"),
                    status = enumOr(obj.optString("status"), ExpressStatus.UNKNOWN),
                    title = obj.optStringOrNull("title"),
                    // 旧版本存下来的 JSON 读不出 "origin" —— 一律退回「通知」，
                    // 语义上正确：富化字段是后加的，旧记录只可能来自通知。
                    // （更早的版本还存过 "man"/"manPhone"，那两个键现在直接忽略。）
                    origin = enumOr(obj.optString("origin"), ExpressOrigin.NOTIFICATION),
                    matchedKeywords = obj.optJSONArray("kw")?.let { keywords ->
                        (0 until keywords.length()).map { keywords.optString(it) }
                    }.orEmpty(),
                    confidence = obj.optInt("conf"),
                    timestamp = obj.optLong("at"),
                    platform = obj.optStringOrNull("platform"),
                    goodsName = obj.optStringOrNull("goods"),
                    // 缺键时 optLong 给 0，0 不是合法时间戳（发送端也用它表示「没有」）。
                    arrivalAt = obj.optLong("arrival").takeIf { it > 0L },
                    phoneTail = obj.optStringOrNull("ptail"),
                    logisticsDetail = obj.optStringOrNull("dyn"),
                    stationHours = obj.optStringOrNull("hours"),
                    // 同上：0 不是合法时间戳，缺键（旧 JSON）就读成「没取过」。
                    pickedUpAt = obj.optLong("picked").takeIf { it > 0L },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** `JSONObject.optString` 把 JSON null 也读成 "null" 字符串，这里统一成 Kotlin null。 */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}

/**
 * 首页的展示分组。
 *
 * 对齐菜鸟首页的结构：**到站包裹按取件地点聚合**，运输中的平铺在下面。
 * 这个分组不是纯按状态切的 —— 到站/待取件才是用户要去取的东西，所以它们按地点聚合成卡片组，
 * 而运输中的用户只是「知道一下」，平铺即可。
 */
data class ExpressHomeSection(
    val title: String,
    /** 该分组下的驿站子分组（到站包裹用）。运输中分组为空，直接看 [records]。 */
    val stationGroups: List<ExpressStationGroup> = emptyList(),
    /** 不按驿站聚合的记录（运输中、已签收）。 */
    val records: List<ExpressRecord> = emptyList(),
) {
    val count: Int get() = stationGroups.sumOf { it.records.size } + records.size
}

/** 一个取件地点下的包裹。 */
data class ExpressStationGroup(
    /**
     * 驿站名。**已经是可以直接显示的那个名字**：过了 [ExpressStationName] 归一化（品牌前缀
     * `菜鸟驿站`、外层括号、空白都剥掉，同类写法取最长的一个），也过了用户在「驿站管理」里
     * 做的改名 / 合并（见 [ExpressHomeGrouper.stationLabels]）。
     *
     * 所以 UI 拿来直接显示即可，**不要再回头读 `record.station`** —— 那是用户改不动的原始串。
     * 空名归到 [ExpressHomeGrouper.UNKNOWN_STATION]。
     */
    val station: String,
    /**
     * 该地点的取件候选，**顺序有意义**：还没取的在前、已确认取件的在后，
     * 同档内按取件码排序。理由见 [ExpressHomeGrouper.groupByStation]。
     */
    val records: List<ExpressRecord>,
)

/**
 * 「驿站管理」页列出来的一行 —— 一个驿站当前的样子。
 *
 * 和 [ExpressStationGroup] 的区别：那个是**首页卡片**（只装待取件、已经分好组），
 * 这个是**管理用的条目**（装件数、原始写法、有没有被改过）。两者共用同一套归一化规则，
 * 但用途不同，所以不强行合并成一个类型。
 */
data class ExpressStationSummary(
    /**
     * 规则表的键 —— [ExpressStationRules.renames] 用的就是这个字符串。
     * 也是这站所有记录「代表了它们」的那个归一化名。
     */
    val key: String,
    /** 当前该显示的名字（用户的规则生效之后）。没改过时等于 [key]。 */
    val displayName: String,
    /** 这站出现过的**原始**写法（归一化之前，去重）。界面拿它告诉用户「这些写法被认为是一处」。 */
    val rawNames: List<String>,
    /** 这个驿站名下的记录（不分状态）。详情页拿它列出「这站是哪几件」。 */
    val records: List<ExpressRecord>,
    /** 用户手工改过名 / 合并过。界面据此决定要不要显示「恢复默认」。 */
    val renamed: Boolean,
) {
    /** 这个驿站名下有多少条记录。 */
    val count: Int get() = records.size
}

/**
 * 按首页需要的方式把记录分组。纯函数，可单测。
 *
 * 「用户在界面上确认已取件」这件事在这里落地成两个可见结果：
 * - 同一地点**部分**确认 → 卡片不出这一档，但组内顺序变成「待取在前、已取在后」（[groupByStation]）
 * - 同一地点**全部**确认 → 整批移出「到站包裹」，落进「已签收 / 异常」（[confirmedStations]）
 *
 * 之所以放在分组层而不是在写入时改记录状态：确认取件是**地点级**的进度概念（整站取完才算完），
 * 而存储里只有单条记录的标记。把「整站」这层判断留在纯函数里，写入侧就只管一件事。
 */
object ExpressHomeGrouper {

    /** 到站 / 待取件 —— 用户真正要去取的一类。 */
    private val PICKUP_STATUSES = setOf(
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
    )

    /** 在途。 */
    private val TRANSIT_STATUSES = setOf(
        ExpressStatus.CREATED,
        ExpressStatus.PICKED_UP,
        ExpressStatus.IN_TRANSIT,
        ExpressStatus.DELIVERING,
    )

    /** 已结束。 */
    private val DONE_STATUSES = setOf(
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    /**
     * @param rules 用户在「驿站管理」里做的合并 / 改名。默认空规则 = 只靠自动归一化分组，
     *   这也是单测里最常传的那一种。
     */
    fun group(
        records: List<ExpressRecord>,
        rules: ExpressStationRules = ExpressStationRules.EMPTY,
    ): List<ExpressHomeSection> {
        val sections = mutableListOf<ExpressHomeSection>()

        val pickup = records.filter { it.status in PICKUP_STATUSES }
        // 身份表按**全部记录**算一次，下面几处（整站确认 / 分组 / 落档）共用同一份。
        //
        // 范围是全部而不是只有 pickup —— 2026-09-26 实测踩到的坑：一个驿站的简称出现在待取件
        // 记录里，全名出现在一条**已签收**记录里，而用户的改名规则记在全名上。只拿 pickup
        // 聚类的话，简称在自己那一簇里当场成了代表名，规则（键是全名）命不中，
        // 于是「改了名，首页纹丝不动」。**簇代表名必须跟记录当前状态无关** ——
        // 同一个驿站在哪儿都该叫同一个名字。
        val ids = stationIdentities(records)
        // 归一化名 → 该显示的名字。规则作用在簇代表名上，整簇跟随（见 [stationLabels]）。
        val labels = labelsOf(ids, rules)
        // 「整站取完」的地点整批移出待取件。注意这里只是**把它从这一档里摘掉**，
        // 记录本身留在存储里（并在下面进「已签收 / 异常」）—— 删除数据不该由一次双击决定。
        //
        // 判定用**身份**而不是显示名：用户把两个驿站合并到同一个名字后，它们显示在同一张卡上，
        // 但件还是各自的 —— 拿显示名算，会让「A 的件取完了」把 B 的件也一起移走。
        val confirmed = confirmedStations(pickup, ids)
        val pending = pickup.filterNot { identityOf(it, ids) in confirmed }
        if (pending.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "到站包裹",
                // 注意传的是 pending 的全量（含「已标记但本地点还没取完」的那几件）：
                // 它们仍要显示在这张卡片上，只是沉到下面、变灰。
                stationGroups = groupByStation(pending, labels),
            )
        }

        val transit = records.filter { it.status in TRANSIT_STATUSES }
        if (transit.isNotEmpty()) {
            sections += ExpressHomeSection(title = "运输中", records = transit)
        }

        // 未知状态的单独一档：可能是解析没抽到状态词，但确实是快递通知。
        // 不给它一个位置的话这些记录就凭空消失了，用户会觉得「明明收到了却没显示」。
        val unknown = records.filter { it.status == ExpressStatus.UNKNOWN }
        if (unknown.isNotEmpty()) {
            sections += ExpressHomeSection(title = "其他", records = unknown)
        }

        // 整站确认取件的记录也归到这一档：用户已经取走了，它不该再留在「待取件」里，
        // 但它仍然是一条真实存在的包裹，直接删掉会让用户以为模块把数据弄丢了。
        // 等宿主推来「已签收」，它会自然留在这里；拿它当一条「已取」的凭据也不错。
        val done = records.filter {
            it.status in DONE_STATUSES ||
                (it.status in PICKUP_STATUSES && identityOf(it, ids) in confirmed)
        }
        if (done.isNotEmpty()) {
            sections += ExpressHomeSection(title = "已签收 / 异常", records = done)
        }

        return sections
    }

    /**
     * 列出记录里出现过的驿站，供「驿站管理」页展示。
     *
     * 跟 [group] 用的是同一套归一化 + 聚类：界面上列出来的驿站，和首页卡片上的驿站，
     * 保证是同一批 —— 两边各算一次的话，用户会在管理页看到首页上不存在的条目。
     *
     * 返回的 [ExpressStationSummary.key] 就是 [ExpressStationRules.renames] 的键，
     * 界面显示什么名字、规则表用什么键，是同一个字符串，中间不做转换。
     *
     * **不筛状态**：管理页要能管到所有驿站（包括只有已签收包裹的那些）—— 用户想合并
     * 两个名字，不该因为其中一个暂时没有待取件就找不到入口。
     */
    fun stations(
        records: List<ExpressRecord>,
        rules: ExpressStationRules = ExpressStationRules.EMPTY,
    ): List<ExpressStationSummary> {
        val ids = stationIdentities(records)
        val labels = labelsOf(ids, rules)
        val byKey = LinkedHashMap<String, MutableList<ExpressRecord>>()
        for (record in records) {
            byKey.getOrPut(identityOf(record, ids)) { mutableListOf() } += record
        }
        return byKey
            .map { (key, items) ->
                ExpressStationSummary(
                    // key 是**簇代表名**，也正是规则表用的键 —— 用户在详情页改名时写进
                    // [ExpressStationRules.renames] 的就是它。
                    key = key,
                    // 走和首页同一张显示名表：管理页里看到的名字，必须和首页卡片上的名字一样。
                    displayName = labels[key] ?: key,
                    // 归一化**之前**的原始写法：用户要在这行里认出「哦，这两个名字原来是同一处」。
                    rawNames = items.mapNotNull { it.station?.takeIf(String::isNotBlank) }.distinct(),
                    records = items,
                    renamed = rules.hasRule(key),
                )
            }
            // 有名字的驿站排前面，未知的垫底 —— 和首页卡片同一个顺序。
            .sortedWith(compareBy({ it.key == UNKNOWN_STATION }, { it.displayName }))
    }

    /**
     * 整站确认：同一取件地点下的**全部**取件候选都被用户标记成已取件，这个地点才算「取完了」。
     *
     * ## 为什么必须整站一起确认
     *
     * 驿站是**去一趟一次性拿完**的场景，一个地点常常同时躺着好几件（同一个货架、同一个柜子）。
     * 点一件就让它当场消失的话，用户会丢掉「这站还剩几件没拿」的进度感 —— 而剩下的那几件
     * 恰恰是这趟要顺手带走的。所以单件标记只改卡片内部的顺序和颜色（见 [groupByStation]），
     * **只有全站都确认了才整批移出待取件**。
     *
     * 只有一个包裹的地点自然一步到位（标记 = 全站确认 = 移出），这也符合直觉：
     * 只有一件时「拿完这件」就等于「这站取完了」。
     *
     * 「全站」只拿**取件候选**算，不看已签收的历史记录 —— 否则一件半年前签收的旧记录会让
     * 这个地点永远凑不满，用户就再也移不掉它了。
     *
     * 判定用的是驿站**身份**（[ExpressStationName]）而不是名字字符串：同一个驿站的两种写法
     * 得算一站，否则同一处的包裹会被拆成两批，两边都凑不满，用户永远移不掉它们。
     */
    private fun confirmedStations(
        pickup: List<ExpressRecord>,
        ids: Map<String, String>,
    ): Set<String> =
        pickup.groupBy { identityOf(it, ids) }
            .filterValues { items -> items.all { it.isPickedUp } }
            .keys

    /**
     * 按驿站聚合。
     *
     * 没有驿站信息的归到「未知取件地点」而不是丢弃 —— 通知里没写驿站名不代表这个包裹不存在，
     * 用户至少能看到取件码。
     *
     * 分组键是驿站**身份**而不是名字字符串，理由见 [ExpressStationName]：同一个驿站的简称
     * 和带楼栋号的全名必须落在同一张卡上。
     */
    private fun groupByStation(
        records: List<ExpressRecord>,
        labels: Map<String, String>,
    ): List<ExpressStationGroup> {
        // 自己攒 LinkedHashMap 而不是 records.groupBy：分组键要先查显示名表，groupBy 塞不进这一步。
        val grouped = LinkedHashMap<String, MutableList<ExpressRecord>>()
        for (record in records) {
            grouped.getOrPut(labels[normalizedKey(record)] ?: UNKNOWN_STATION) { mutableListOf() } += record
        }
        return grouped
            .map { (station, items) ->
                ExpressStationGroup(
                    // 存的是**归一化后的代表名**（最长的那种写法），UI 直接显示，不用再剥一次。
                    station = station,
                    // 两条排序规则叠在一起：
                    // ① **还没取的排前面**。用户来这一屏是去取件的，视线该先落到要拿的那几件上；
                    //    已取的下沉到底部，顺带起一条「进度」的作用 —— 这站还剩几件一眼可见。
                    // ② 同档内按取件码排序，同一货架/柜子的包裹挨在一起，找件时更顺。
                    // compareBy 的 Boolean 是 false < true，正好把 isPickedUp 为真的排到后面。
                    records = items.sortedWith(
                        compareBy({ it.isPickedUp }, { it.pickupCode.orEmpty() }),
                    ),
                )
            }
            // 有名字的驿站排前面，未知的垫底。
            .sortedWith(compareBy({ it.station == UNKNOWN_STATION }, { it.station }))
    }

    /**
     * 归一化后的驿站名（**不含**用户规则）。空名（只有空白、或整个名字就是品牌）
     * 一律归到 [UNKNOWN_STATION]。
     */
    private fun normalizedKey(record: ExpressRecord): String =
        ExpressStationName.normalize(record.station).ifEmpty { UNKNOWN_STATION }

    /**
     * 一条记录所属的驿站**身份** —— 也就是它那一簇的代表名。
     *
     * 只到聚类为止，**不套规则**：显示名字走 [stationLabels]。两者刻意分开：
     * - 「身份」回答「这几件是不是一个地方」（整站确认、分组归类用），用户改名不该影响它；
     * - 「显示名」回答「这个位置该印什么字」，用户改名就是改它。
     *
     * 合并成一个的代价是：用户把 A 合并到 B 之后，A 的件和 B 的件显示名相同，整站确认若按
     * 显示名算就会把两批件当成一批 —— 「A 取完了」把还没取的 B 也一起移出待取件。
     */
    private fun identityOf(record: ExpressRecord, ids: Map<String, String>): String {
        val key = normalizedKey(record)
        return ids[key] ?: key
    }

    /**
     * 身份表：归一化名 → 代表名。同一个驿站的几种写法在这里折叠成同一个代表。
     *
     * 用户规则**不参与**这一步 —— 聚类只认字符串本身的相似性，规则是聚类之后套上去的
     * （理由见 [stationLabels]）。
     *
     * ⚠️ **传进来的必须是全部记录**，不能只传某一档（比如待取件）。同一个驿站的两种写法
     * 完全可能分散在不同状态里（简称在待取件、全名在已签收），按档聚类会给出两个代表名，
     * 于是规则只能命中其中一个 —— 用户改了名，首页那张卡照旧。
     */
    private fun stationIdentities(records: List<ExpressRecord>): Map<String, String> =
        ExpressStationName.representatives(records.map { normalizedKey(it) }.toSet())

    /** 身份表 → 显示名表。规则套在**簇代表名**上，所以整簇一起跟随。 */
    private fun labelsOf(
        ids: Map<String, String>,
        rules: ExpressStationRules,
    ): Map<String, String> = ids.mapValues { (_, rep) -> rules.apply(rep) }

    /**
     * 「归一化名 → 该显示的驿站名」总表。
     *
     * 界面上**所有**印驿站名的地方都走这张表（到站卡片抬头、运输中 / 已签收卡片底部那行）。
     * 谁自己回头读 `record.station`，用户改了名就会出现「有的地方改了、有的地方没改」——
     * 正是 2026-09-26 用户报的那个问题。
     *
     * 规则套在**簇代表名**而不是每条记录自己的归一化名上：一个驿站的简称和全名是两个
     * 不同的归一化名，用户只可能在其中之一上写规则（界面上一次只列一条），整簇都该跟着变。
     */
    fun stationLabels(
        records: List<ExpressRecord>,
        rules: ExpressStationRules,
    ): Map<String, String> = labelsOf(stationIdentities(records), rules)

    /**
     * 一条记录该显示的驿站名；未知 / 空返回 null，调用方整行不排。
     *
     * [labels] 由 [stationLabels] 算出一次、反复使用 —— 这个函数会被逐条调用，
     * 别在里面重算身份表。
     */
    fun stationLabelOf(record: ExpressRecord, labels: Map<String, String>): String? =
        labels[normalizedKey(record)]?.takeIf { it != UNKNOWN_STATION }

    const val UNKNOWN_STATION = "未知取件地点"
}
