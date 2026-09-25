package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
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
 */
object ExpressRecordStore {

    private const val PREFS = "reapress_records"
    private const val KEY_RECORDS = "records"

    /** 保留上限。首页只展示最近的，更早的没有查看入口，留着只占空间。 */
    private const val MAX_RECORDS = 200

    /**
     * 写入一条记录（合并同一个包裹的旧记录）。
     *
     * 「同一个包裹」走 [ExpressRecord.isSamePackageAs]，不是主键字符串相等 —— 见那个函数的
     * 注释：同一个包裹的多次推送里驿站名详略不同、运单号时有时无，按主键比会拆成两条，
     * 首页上就是两张卡片。
     *
     * @return true 表示这条记录改变了存储内容（新增或状态推进）
     */
    fun upsert(context: Context, record: ExpressRecord): Boolean {
        return runCatching {
            val current = load(context)
            val existing = current.firstOrNull { it.isSamePackageAs(record) }

            val merged = when {
                existing == null -> current + record
                record.status.order > existing.status.order -> current.map {
                    if (it.isSamePackageAs(record)) record else it
                }
                record.status.order < existing.status.order -> return@runCatching false
                // 同级：时间较新的胜出，但不改变「已存在」这个事实。
                record.timestamp >= existing.timestamp -> current.map {
                    if (it.isSamePackageAs(record)) record else it
                }
                else -> return@runCatching false
            }

            save(context, merged)
            true
        }.getOrDefault(false)
    }

    /** 全部记录，按时间倒序（最新在前）。 */
    fun load(context: Context): List<ExpressRecord> =
        runCatching {
            parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
                .sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS).apply()
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
                    put("kw", JSONArray(record.matchedKeywords))
                    put("conf", record.confidence)
                    put("at", record.timestamp)
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
                    matchedKeywords = obj.optJSONArray("kw")?.let { keywords ->
                        (0 until keywords.length()).map { keywords.optString(it) }
                    }.orEmpty(),
                    confidence = obj.optInt("conf"),
                    timestamp = obj.optLong("at"),
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
    val station: String,
    val records: List<ExpressRecord>,
)

/** 按首页需要的方式把记录分组。纯函数，可单测。 */
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

    fun group(records: List<ExpressRecord>): List<ExpressHomeSection> {
        val sections = mutableListOf<ExpressHomeSection>()

        val pickup = records.filter { it.status in PICKUP_STATUSES }
        if (pickup.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "到站包裹",
                stationGroups = groupByStation(pickup),
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

        val done = records.filter { it.status in DONE_STATUSES }
        if (done.isNotEmpty()) {
            sections += ExpressHomeSection(title = "已签收 / 异常", records = done)
        }

        return sections
    }

    /**
     * 按驿站聚合。
     *
     * 没有驿站信息的归到「未知取件地点」而不是丢弃 —— 通知里没写驿站名不代表这个包裹不存在，
     * 用户至少能看到取件码。
     */
    private fun groupByStation(records: List<ExpressRecord>): List<ExpressStationGroup> =
        records
            .groupBy { it.station?.takeIf { name -> name.isNotBlank() } ?: UNKNOWN_STATION }
            .map { (station, items) ->
                ExpressStationGroup(
                    station = station,
                    // 组内按取件码排序：同一货架/柜子的包裹排在一起，找件时更顺。
                    records = items.sortedBy { it.pickupCode.orEmpty() },
                )
            }
            // 有名字的驿站排前面，未知的垫底。
            .sortedWith(compareBy({ it.station == UNKNOWN_STATION }, { it.station }))

    const val UNKNOWN_STATION = "未知取件地点"
}
