/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressEnrichmentMatcher
import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressPlatform
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressRecordRepair
import io.github.YGHFv.ReaPressExtend.core.ExpressStationName
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.ExpressTraceCodec
import io.github.YGHFv.ReaPressExtend.core.GeoDistance
import io.github.YGHFv.ReaPressExtend.core.GeoPoint
import io.github.YGHFv.ReaPressExtend.core.IdentitySource
import io.github.YGHFv.ReaPressExtend.core.geoPointOf
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快递记录持久化。不变量：同一 [ExpressRecord.dedupeKey] 状态只推进不回退（同级取时间较新者），
 * 富化只填空不覆盖；[setPickedUp] 只动 pickedUpAt 不动 status，整站取完由 [ExpressHomeGrouper] 判断。
 */
object ExpressRecordStore {

    /** 存储文件名；备份 / 恢复会读它，改名必须与备份清单同步。 */
    internal const val PREFS = "reapress_records"
    private const val KEY_RECORDS = "records"

    private const val LOG_TAG = "ReaPress"

    private const val MAX_RECORDS = 200

    private const val MIN_TRUNCATION_LENGTH = 6

    internal const val NO_MATCH = -1

    private const val MIN_STATION_SUFFIX = 4

    private const val TAIL_MISS_LOG_INTERVAL_MS = 10 * 60_000L

    private const val MISS_LOG_MAX_ENTRIES = 128

    // 用 elapsedRealtime：改系统时间不该跳过节流窗口。
    private val tailMissLogTimes = HashMap<String, Long>()

    /** 既没运单号也没取件码的不算包裹（2026-09-26 真机「📦 揽件通知」）；挡在存储层，relay 契约是加键可以、改键名即破坏兼容。 */
    fun upsert(context: Context, record: ExpressRecord): Boolean = runCatching {
        if (!record.hasIdentity) {
            ModuleAndroidLog.legacy(
                LOG_TAG,
                "upsert dropped (no identity): raw=${record.rawText.take(40)}",
            )
            return@runCatching false
        }
        val next = applyUpsert(load(context), record) ?: return@runCatching false
        save(context, next)
        true
    }.getOrDefault(false)

    /** [upsert] 的纯逻辑；返回 null = 不该改存储（状态倒退，或同级但更旧）。 */
    internal fun applyUpsert(
        current: List<ExpressRecord>,
        record: ExpressRecord,
    ): List<ExpressRecord>? {
        val merged = applyUpsertOnce(current, record) ?: return null
        // 尾号匹配每次写入后重跑：包裹可能晚于带来取件码的那条通知到达。
        return applyTailMatches(merged).records
    }

    private fun applyUpsertOnce(
        current: List<ExpressRecord>,
        record: ExpressRecord,
    ): List<ExpressRecord>? {
        val existing = current.firstOrNull { isSamePackageLoose(it, record) }
            ?: return current + record

        return when {
            record.status.order > existing.status.order -> current.map {
                if (isSamePackageLoose(it, record)) mergeVersions(it, record) else it
            }
            // CREATED 纠正 IN_TRANSIT：宿主对未揽收件笼统写「运输中」，更具体的「等待揽收」不算乱序回退。
            record.status == ExpressStatus.CREATED &&
                existing.status == ExpressStatus.IN_TRANSIT &&
                record.timestamp >= existing.timestamp -> current.map {
                if (isSamePackageLoose(it, record)) mergeVersions(it, record) else it
            }
            record.status.order < existing.status.order -> null
            record.timestamp >= existing.timestamp -> current.map {
                if (isSamePackageLoose(it, record)) mergeVersions(it, record) else it
            }
            else -> null
        }
    }

    /** 宽一档的「同一包裹」：一边运单号是另一边的后缀且短段 ≥ [MIN_TRUNCATION_LENGTH] 位（通知只有尾号、富化给全号）。只用于 [upsert]。 */
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

    private fun mergeVersions(existing: ExpressRecord, incoming: ExpressRecord): ExpressRecord =
        incoming.mergeEnrichment(existing.mergeEnrichment(incoming))

    /**
     * 尾号匹配（2026-09-27 用户要求）：通知取件码按「运单号尾段」认领到宿主包裹上。
     * 护栏：认领方必须无运单号（防自激）、必须唯一命中（给错码比不给更糟）、驿站要说得通。
     * 覆盖 pickupCode 时原码存 [ExpressRecord.previousPickupCode]；被认领的通知记录移出记录表
     * （投递审计在 [ExpressNotificationLog] 另存一份）。
     */
    internal fun applyTailMatches(
        current: List<ExpressRecord>,
        anchor: ExpressRecord? = null,
    ): TailMatchResult {
        var anchorIndex = anchor?.let { a -> current.indexOfFirst { it === a } } ?: NO_MATCH
        val claimants = current.filter { isTailClaimant(it) }
        if (claimants.isEmpty()) return TailMatchResult(current, anchorIndex)

        val work = ArrayList(current)
        val dropped = HashSet<Int>()
        val claimed = HashSet<Int>()
        for (claimant in claimants) {
            val tail = claimant.parcelTail.orEmpty().trim()
            val candidates = work.indices.filter { i ->
                i !in dropped && i !in claimed &&
                    work[i].trackingNumber?.takeIf { it.isNotBlank() }?.endsWith(tail) == true
            }
            val compatible = candidates.filter { stationCompatible(work[it].station, claimant.station) }
            if (compatible.size != 1) {
                if (compatible.size > 1) {
                    ModuleAndroidLog.legacy(
                        LOG_TAG,
                        "tail match skipped: tail=$tail 命中 ${compatible.size} 件（要求唯一），一件都不动",
                    )
                }
                logTailMiss(claimant, tail, candidates.size)
                continue
            }
            val targetIndex = compatible.first()
            work[targetIndex] = claimTail(work[targetIndex], claimant)
            claimed += targetIndex
            dropped += work.indexOfFirst { it === claimant }
        }
        if (dropped.isEmpty()) {
            return TailMatchResult(current, anchorIndex)
        }
        val kept = work.filterIndexed { i, _ -> i !in dropped }
        anchorIndex = if (anchorIndex in dropped) {
            NO_MATCH
        } else {
            (anchorIndex - dropped.count { it < anchorIndex }).coerceAtLeast(NO_MATCH)
        }
        return TailMatchResult(kept, anchorIndex)
    }

    private fun isTailClaimant(record: ExpressRecord): Boolean =
        record.trackingNumber.isNullOrBlank() &&
            !record.pickupCode.isNullOrBlank() &&
            !record.parcelTail.isNullOrBlank()

    private fun claimTail(target: ExpressRecord, claimant: ExpressRecord): ExpressRecord {
        val claimedCode = claimant.pickupCode.orEmpty()
        val previous = target.pickupCode?.takeIf { it.isNotBlank() && it != claimedCode }
            ?: target.previousPickupCode
        val matched = target.copy(
            pickupCode = claimedCode,
            previousPickupCode = previous,
            // 驿站只填空：宿主那条通常更全（带楼栋号）。
            station = target.station?.takeIf { ExpressStationName.hasLocation(it) }
                ?: claimant.station?.takeIf { ExpressStationName.hasLocation(it) },
            // 状态只推进，且不认 UNKNOWN（拿它去比会把已有状态抹平）。
            status = if (claimant.status != ExpressStatus.UNKNOWN &&
                claimant.status.order > target.status.order
            ) {
                claimant.status
            } else {
                target.status
            },
        )
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "tail matched: tail=${claimant.parcelTail} code=$claimedCode -> tn=${target.trackingNumber}" +
                (previous?.let { " (原码 $it 已保留)" } ?: "") +
                " station=${matched.station ?: "-"}",
        )
        return matched
    }

    private fun logTailMiss(claimant: ExpressRecord, tail: String, candidateCount: Int) {
        val key = "$tail|${claimant.station}|${claimant.pickupCode}"
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(tailMissLogTimes) {
            val last = tailMissLogTimes[key]
            if (last != null && now - last < TAIL_MISS_LOG_INTERVAL_MS) return
            tailMissLogTimes[key] = now
            if (tailMissLogTimes.size > MISS_LOG_MAX_ENTRIES) tailMissLogTimes.clear()
        }
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "tail miss: tail=$tail code=${claimant.pickupCode} station=${claimant.station ?: "-"} " +
                "同尾号候选=$candidateCount 件 → 这条通知先留着",
        )
    }

    /** 归一化后相等，或短的是长的后缀且够长；一边说不出地点时不拦。 */
    private fun stationCompatible(a: String?, b: String?): Boolean {
        val na = ExpressStationName.normalize(a)
        val nb = ExpressStationName.normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return true
        if (na == nb) return true
        val shorter = if (na.length <= nb.length) na else nb
        val longer = if (na.length <= nb.length) nb else na
        return shorter.length >= MIN_STATION_SUFFIX && longer.endsWith(shorter)
    }

    internal data class TailMatchResult(
        val records: List<ExpressRecord>,
        /** anchor 在新列表里的下标；[NO_MATCH] = 已不在。 */
        val anchorIndex: Int = NO_MATCH,
    )

    fun load(context: Context): List<ExpressRecord> =
        runCatching {
            parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
                // 读时按当前解析逻辑重扫修复（纯函数幂等）：解析层修好不会改掉已落盘的旧值，
                // 而脏值会继续参与分组、挡住富化的真名。
                .map(ExpressRecordRepair::repair)
                .filter { it.hasIdentity }
                .sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())

    /** 修 → 认领 → 有变化才写回，把「读时自愈」变成一次性；界面首次装载列表前调一次（无变化不写字节）。 */
    fun reconcile(context: Context): Boolean = runCatching {
        val stored = load(context)
        val merged = applyTailMatches(stored).records
        if (merged == stored) return@runCatching false
        save(context, merged)
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "records reconciled: ${stored.size} -> ${merged.size} 条（旧解析结果已按当前逻辑重算）",
        )
        true
    }.getOrDefault(false)

    /** 用 [enrichment] 补已有记录缺失字段（只填空不覆盖）；配不上就新建一条 —— 宿主已知的包裹不该因通知没拦到而从首页消失。 */
    fun enrich(context: Context, enrichment: ExpressRecord): Boolean = runCatching {
        if (!enrichment.hasIdentity) {
            ModuleAndroidLog.legacy(
                LOG_TAG,
                "enrich dropped (no identity): station=${enrichment.station ?: "-"}",
            )
            return@runCatching false
        }
        val current = load(context)
        val result = applyEnrichment(current, enrichment) ?: return@runCatching false

        if (result.matchedIndex >= 0) {
            val target = result.target
            val merged = result.records[result.matchedIndex]
            ModuleAndroidLog.legacy(
                LOG_TAG,
                "enriched: tn=${target?.trackingNumber} -> ${merged.trackingNumber} " +
                    "courier=${merged.courier.displayName} " +
                    "station=${merged.station ?: "-"} pickup=${merged.pickupCode ?: "-"} " +
                    "dyn=${merged.logisticsDetail?.take(24) ?: "-"}" +
                    if (result.absorbedCount > 0) " absorbed=${result.absorbedCount}" else "",
            )
        } else {
            logEnrichMiss(current, enrichment)
        }

        save(context, result.records)
        true
    }.getOrDefault(false)

    /**
     * [enrich] 的纯逻辑：配上则合并并顺带收编强标识错位导致的重复条（2026-09-26 真机：富化先到时
     * 两边无可比字段各自成条，拿到两边强标识后再用严格语义比一次），配不上走 [applyUpsert] 追加。
     * 返回 null = 存储无需改动。
     */
    internal fun applyEnrichment(
        current: List<ExpressRecord>,
        enrichment: ExpressRecord,
    ): EnrichResult? {
        val index = ExpressEnrichmentMatcher.indexOfTarget(current, enrichment)
        if (index >= 0) {
            val target = current[index]
            var representative = target.mergeEnrichment(enrichment)

            val absorbed = mutableListOf<Int>()
            for ((i, other) in current.withIndex()) {
                if (i == index) continue
                if (!representative.isSamePackageAs(other)) continue
                representative = representative.mergeEnrichment(other)
                absorbed += i
            }

            if (absorbed.isEmpty()) {
                if (representative === target) return null
                return EnrichResult(
                    records = current.toMutableList().also { it[index] = representative },
                    matchedIndex = index,
                    target = target,
                )
            }

            val absorbedSet = absorbed.toHashSet()
            val next = ArrayList<ExpressRecord>(current.size)
            var representativeIndex = NO_MATCH
            for ((i, record) in current.withIndex()) {
                when {
                    i == index -> {
                        representativeIndex = next.size
                        next += representative
                    }
                    i in absorbedSet -> Unit
                    else -> next += record
                }
            }
            // 尾号匹配可能换掉取件码或把自己并进另一条 —— 命中下标要在它之后重新定位。
            val passed = applyTailMatches(next, anchor = representative)
            return EnrichResult(passed.records, passed.anchorIndex, target, absorbed.size)
        }

        val next = applyUpsert(current, enrichment) ?: return null
        return EnrichResult(next, matchedIndex = NO_MATCH)
    }

    internal data class EnrichResult(
        val records: List<ExpressRecord>,
        /** 命中下标；[NO_MATCH] = 新建。 */
        val matchedIndex: Int,
        val target: ExpressRecord? = null,
        val absorbedCount: Int = 0,
    )

    /** 「没配上」的诊断行：0 分 = 确实没有对应通知，15 分 = 有像但不是的候选（才是可能重复的那一支）。 */
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

    /** 标记 / 取消标记「已取件」：只动 pickedUpAt 不动 status（用户确认取件 ≠ 快递公司确认签收）。 */
    fun setPickedUp(context: Context, key: String, at: Long?): Boolean = runCatching {
        val next = applyPickedUp(load(context), key, at) ?: return@runCatching false
        save(context, next)
        true
    }.getOrDefault(false)

    /** [setPickedUp] 的纯逻辑：按 [ExpressRecord.dedupeKey] 定位（中间隔着一次 load，下标对不上）；找不到返回 null 不是错误。 */
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

    /** 清空全部记录（无自动化路径，勿当死代码删）。用 commit：点完清空可能马上杀进程，apply 会把删除丢掉。 */
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

    internal fun serialize(records: List<ExpressRecord>): String {
        val array = JSONArray()
        for (record in records) {
            array.put(
                JSONObject().apply {
                    put("pkg", record.sourcePackage)
                    put("raw", record.rawText)
                    put("tn", record.trackingNumber ?: JSONObject.NULL)
                    // 拼多多订单号；前 6 位是下单日期。
                    put("osn", record.orderSn ?: JSONObject.NULL)
                    put("courier", record.courier.name)
                    put("pickup", record.pickupCode ?: JSONObject.NULL)
                    put("station", record.station ?: JSONObject.NULL)
                    put("status", record.status.name)
                    put("title", record.title ?: JSONObject.NULL)
                    put("origin", record.origin.name)
                    put("kw", JSONArray(record.matchedKeywords))
                    put("conf", record.confidence)
                    put("at", record.timestamp)
                    // 富化 / 轨迹字段，键名一律取短。旧版本 JSON 没有这些键，parse 侧对缺失退回
                    // null —— 升级不丢历史记录，硬约束。
                    put("platform", record.platform ?: JSONObject.NULL)
                    put("goods", record.goodsName ?: JSONObject.NULL)
                    put("arrival", record.arrivalAt ?: JSONObject.NULL)
                    put("ptail", record.phoneTail ?: JSONObject.NULL)
                    put("tail", record.parcelTail ?: JSONObject.NULL)
                    put("oldpc", record.previousPickupCode ?: JSONObject.NULL)
                    put("dyn", record.logisticsDetail ?: JSONObject.NULL)
                    put("hours", record.stationHours ?: JSONObject.NULL)
                    put("picked", record.pickedUpAt ?: JSONObject.NULL)
                    put("saddr", record.stationAddress ?: JSONObject.NULL)
                    put("lat", record.stationLat ?: JSONObject.NULL)
                    put("lng", record.stationLng ?: JSONObject.NULL)
                    put("trace", record.trace.takeIf { it.isNotEmpty() }?.let { ExpressTraceCodec.encode(it) } ?: JSONObject.NULL)
                    put("gimg", record.goodsImage ?: JSONObject.NULL)
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
                    orderSn = obj.optStringOrNull("osn"),
                    courier = enumOr(obj.optString("courier"), Courier.UNKNOWN),
                    pickupCode = obj.optStringOrNull("pickup"),
                    station = obj.optStringOrNull("station"),
                    status = enumOr(obj.optString("status"), ExpressStatus.UNKNOWN),
                    title = obj.optStringOrNull("title"),
                    origin = enumOr(obj.optString("origin"), ExpressOrigin.NOTIFICATION),
                    matchedKeywords = obj.optJSONArray("kw")?.let { keywords ->
                        (0 until keywords.length()).map { keywords.optString(it) }
                    }.orEmpty(),
                    confidence = obj.optInt("conf"),
                    timestamp = obj.optLong("at"),
                    // 归一化放在读路径：旧记录里存着宿主原样的收件类型词（「普通收件」），只在 hook 侧清洗洗不掉历史值。
                    platform = ExpressPlatform.normalize(obj.optStringOrNull("platform")),
                    goodsName = obj.optStringOrNull("goods"),
                    arrivalAt = obj.optLong("arrival").takeIf { it > 0L },
                    phoneTail = obj.optStringOrNull("ptail"),
                    parcelTail = obj.optStringOrNull("tail"),
                    previousPickupCode = obj.optStringOrNull("oldpc"),
                    logisticsDetail = obj.optStringOrNull("dyn"),
                    stationHours = obj.optStringOrNull("hours"),
                    pickedUpAt = obj.optLong("picked").takeIf { it > 0L },
                    stationAddress = obj.optStringOrNull("saddr"),
                    // 坐标用 optDouble：缺键得 NaN；0 当有效坐标会让「最近的驿站」算到几内亚湾去。
                    stationLat = obj.optDouble("lat").takeIf { !it.isNaN() },
                    stationLng = obj.optDouble("lng").takeIf { !it.isNaN() },
                    trace = ExpressTraceCodec.decode(obj.optStringOrNull("trace")),
                    goodsImage = obj.optStringOrNull("gimg"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** `optString` 会把 JSON null 读成 "null" 字符串，这里统一成 Kotlin null。 */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}

/** 首页展示分组：到站 / 待取件按取件地点聚合，在途与已签收平铺。 */
data class ExpressHomeSection(
    val title: String,
    val stationGroups: List<ExpressStationGroup> = emptyList(),
    val records: List<ExpressRecord> = emptyList(),
) {
    val count: Int get() = stationGroups.sumOf { it.records.size } + records.size
}

data class ExpressStationGroup(
    /** 已套用户改名 / 合并规则的显示名，UI 直接显示，不要再读 record.station（那是改不动的原始串）。 */
    val station: String,
    /** 顺序有意义：未取的在前、已取的在后，同档按取件码排序。 */
    val records: List<ExpressRecord>,
)

/** 「驿站管理」页列出的一行：一个驿站当前的样子。 */
data class ExpressStationSummary(
    val key: String,
    val displayName: String,
    val rawNames: List<String>,
    val records: List<ExpressRecord>,
    val renamed: Boolean,
    /** 覆盖的全部规则键，多于一个即合并来的：写 / 清规则必须对整组一起做，只动链头会让链裂回两行（2026-09-26）。 */
    val ruleKeys: List<String> = listOf(key),
) {
    val count: Int get() = records.size
}

/** 按首页需要把记录分组（纯函数）：整站确认在这里落地成组内排序与整批移档。 */
object ExpressHomeGrouper {

    private val PICKUP_STATUSES = setOf(
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
    )

    private val TRANSIT_STATUSES = setOf(
        ExpressStatus.CREATED,
        ExpressStatus.PICKED_UP,
        ExpressStatus.IN_TRANSIT,
    )

    private val DELIVERING_STATUSES = setOf(ExpressStatus.DELIVERING)

    private val DONE_STATUSES = setOf(
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    fun group(
        records: List<ExpressRecord>,
        rules: ExpressStationRules = ExpressStationRules.EMPTY,
        now: Long = 0L,
        retentionMs: Long = ARCHIVE_RETENTION_MS,
    ): List<ExpressHomeSection> {
        val sections = mutableListOf<ExpressHomeSection>()

        val pickup = records.filter { it.status in PICKUP_STATUSES }
        // 身份表必须按全部记录算一次：简称在待取件、全名在已签收时，按档聚类会让改名规则命不中（2026-09-26 实测）。
        val ids = stationIdentities(records)
        val labels = labelsOf(ids, rules)
        // 整站取完的地点整批移出待取件；判定用驿站身份而不是显示名，否则两站合并后没取的也会被移走。
        val confirmed = confirmedStations(pickup, ids)
        val pending = pickup.filterNot { identityOf(it, ids) in confirmed }
        if (pending.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "到站包裹",
                // 传 pending 全量：已标记但本地点没取完的那几件仍要显示（沉到下面、变灰）。
                stationGroups = groupByStation(pending, labels),
            )
        }

        val delivering = records.filter { it.status in DELIVERING_STATUSES }
        if (delivering.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = ExpressStatus.DELIVERING.displayName,
                records = delivering,
            )
        }

        val transit = records.filter { it.status in TRANSIT_STATUSES }
        if (transit.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "运输中",
                // 排序依据 updatedAtOf，与卡片右上角的相对时间同源。
                records = transit.sortedWith(compareByDescending { updatedAtOf(it) }),
            )
        }

        val unknown = records.filter { it.status == ExpressStatus.UNKNOWN }
            .filterNot { isStaleUnknown(it, now) }
        if (unknown.isNotEmpty()) {
            sections += ExpressHomeSection(title = "其他", records = unknown)
        }

        // 整站确认取件的也落这档（不删，等宿主推签收）；手动标记取件的不归档，要等物流签收（2026-09-27）。
        val done = finishedRecords(records, ids)
            .filterNot { it.status in DONE_STATUSES && isArchived(it, now, retentionMs) }
        if (done.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "已签收 / 异常",
                records = done.sortedWith(compareByDescending { completedAtOf(it) }),
            )
        }

        return sections
    }

    /** 供「驿站管理」页展示（与 [group] 同一套归一化 + 聚类）。分组键是显示名而非身份：链式规则下两簇可同名，按身份会出两行同名条目。 */
    fun stations(
        records: List<ExpressRecord>,
        rules: ExpressStationRules = ExpressStationRules.EMPTY,
    ): List<ExpressStationSummary> {
        val ids = stationIdentities(records)
        val labels = labelsOf(ids, rules)

        val grouped = LinkedHashMap<String, MutableList<ExpressRecord>>()
        for (record in records) {
            val identity = identityOf(record, ids)
            val display = labels[identity] ?: identity
            if (display == UNKNOWN_STATION && record.status !in PICKUP_STATUSES) continue
            grouped.getOrPut(display) { mutableListOf() } += record
        }

        return grouped
            .map { (display, items) ->
                val keys = items.map { normalizedKey(it) }.distinct()
                ExpressStationSummary(
                    // 链头 = 组内没有指向组内别的键的那个键。判据不能反着写，否则一行裂成两行；成环时退回第一个。
                    key = keys.firstOrNull { key ->
                        keys.none { other -> other == rules.renames[key] }
                    } ?: keys.first(),
                    displayName = display,
                    rawNames = items.mapNotNull { ExpressStationName.placeName(it.station) }.distinct(),
                    records = items,
                    renamed = keys.any { rules.hasRule(it) },
                    ruleKeys = keys,
                )
            }
            .sortedWith(
                stationOrder<ExpressStationSummary>(
                    isUnknown = { it.key == UNKNOWN_STATION },
                    records = { it.records },
                    name = { it.displayName },
                    countDescending = true,
                ),
            )
    }

    /** 同一地点的全部取件候选都被标记已取件才算整站取完；只拿取件候选算，判定用身份。 */
    private fun confirmedStations(
        pickup: List<ExpressRecord>,
        ids: Map<String, String>,
    ): Set<String> =
        pickup.groupBy { identityOf(it, ids) }
            .filterValues { items -> items.all { it.isPickedUp } }
            .keys

    /** 7 天（用户定）；同时是未知件「长期无动静」的固定窗口。 */
    const val ARCHIVE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * 「归档快递」页的记录：物流签收 / 失败按 [retentionMs] 过期（手动标记取件的要等物流签收），
     * 加状态 UNKNOWN 且超过 [ARCHIVE_RETENTION_MS] 的未知件。必须传全部记录（驿站聚类要完整集合）；
     * `now` 必须与 [group] 同值，否则出现两页都不在的空窗。
     */
    fun archive(
        records: List<ExpressRecord>,
        now: Long,
        retentionMs: Long = ARCHIVE_RETENTION_MS,
    ): List<ExpressRecord> =
        (
            records.filter { it.status in DONE_STATUSES && isArchived(it, now, retentionMs) } +
                records.filter { it.status == ExpressStatus.UNKNOWN && isStaleUnknown(it, now) }
            )
            .sortedWith(compareByDescending { completedAtOf(it) })

    private fun finishedRecords(
        records: List<ExpressRecord>,
        ids: Map<String, String>,
    ): List<ExpressRecord> {
        val confirmed = confirmedStations(records.filter { it.status in PICKUP_STATUSES }, ids)
        return records.filter {
            it.status in DONE_STATUSES ||
                (it.status in PICKUP_STATUSES && identityOf(it, ids) in confirmed)
        }
    }

    private fun isArchived(
        record: ExpressRecord,
        now: Long,
        retentionMs: Long = ARCHIVE_RETENTION_MS,
    ): Boolean = now - completedAtOf(record) >= retentionMs

    /** 时间只认硬证据（轨迹末节点 / arrivalAt），不信 timestamp：模块自己的回写也会碰它，2026-09-27 真机实证归档判据因此永不成立。 */
    private fun isStaleUnknown(record: ExpressRecord, now: Long): Boolean {
        val evidence = maxOf(
            record.trace.lastOrNull()?.let { ExpressFormatter.tracePointTime(it.time) } ?: 0L,
            record.arrivalAt ?: 0L,
        )
        return evidence > 0L && now - evidence >= ARCHIVE_RETENTION_MS
    }

    private fun updatedAtOf(record: ExpressRecord): Long = maxOf(
        ExpressFormatter.statusSince(record) ?: 0L,
        record.arrivalAt ?: 0L,
        record.timestamp,
    )

    /** 结束时刻取轨迹末节点 / arrivalAt / pickedUpAt 较新者。不能走 [updatedAtOf]：它含 timestamp，宿主自查 / 富化会把它刷到今天，归档判据永不成立（2026-09-26 实证）。 */
    private fun completedAtOf(record: ExpressRecord): Long {
        val proof = maxOf(
            record.trace.lastOrNull()?.let { ExpressFormatter.tracePointTime(it.time) } ?: 0L,
            record.arrivalAt ?: 0L,
            record.pickedUpAt ?: 0L,
        )
        return if (proof > 0L) proof else record.timestamp
    }

    /** 驿站排序：未知垫底 → 件数（方向传入，首页件少靠前、管理页件多靠前）→ 最新来件 → 名字兜底。首页与管理页共用。 */
    private fun <T> stationOrder(
        isUnknown: (T) -> Boolean,
        records: (T) -> List<ExpressRecord>,
        name: (T) -> String,
        countDescending: Boolean,
    ): Comparator<T> {
        // 方向要拼进链里，不能 .reversed()（会把「未知垫底」和「名字兜底」一起反掉）。
        val byCount = if (countDescending) {
            compareByDescending<T> { records(it).size }
        } else {
            compareBy<T> { records(it).size }
        }
        return compareBy<T> { isUnknown(it) }
            .then(byCount)
            .thenByDescending { latestAtOf(records(it)) }
            .thenBy { name(it) }
    }

    private fun latestAtOf(records: List<ExpressRecord>): Long =
        records.maxOfOrNull { it.arrivalAt ?: it.timestamp } ?: 0L

    /** 按驿站身份聚合（简称与全名落同一张卡）；没有驿站信息的归 [UNKNOWN_STATION] 而不是丢弃。 */
    private fun groupByStation(
        records: List<ExpressRecord>,
        labels: Map<String, String>,
    ): List<ExpressStationGroup> {
        val grouped = LinkedHashMap<String, MutableList<ExpressRecord>>()
        for (record in records) {
            grouped.getOrPut(labels[normalizedKey(record)] ?: UNKNOWN_STATION) { mutableListOf() } += record
        }
        return grouped
            .map { (station, items) ->
                ExpressStationGroup(
                    station = station,
                    records = items.sortedWith(
                        compareBy({ it.isPickedUp }, { it.pickupCode.orEmpty() }),
                    ),
                )
            }
            .sortedWith(
                stationOrder<ExpressStationGroup>(
                    isUnknown = { it.station == UNKNOWN_STATION },
                    records = { it.records },
                    name = { it.station },
                    countDescending = false,
                ),
            )
    }

    private fun normalizedKey(record: ExpressRecord): String =
        ExpressStationName.normalize(record.station).ifEmpty { UNKNOWN_STATION }

    /** 记录所属的驿站身份（簇代表名），只到聚类不套规则；显示名走 [stationLabels]。两者分开，整站确认按身份算才不会把没取的也移走。 */
    private fun identityOf(record: ExpressRecord, ids: Map<String, String>): String {
        val key = normalizedKey(record)
        return ids[key] ?: key
    }

    /** 归一化名 → 代表名。必须传全部记录：同一驿站的两种写法会分散在不同状态档，按档聚类会让改名规则只命中一个。 */
    private fun stationIdentities(records: List<ExpressRecord>): Map<String, String> =
        ExpressStationName.representatives(records.map { normalizedKey(it) }.toSet())

    private fun labelsOf(
        ids: Map<String, String>,
        rules: ExpressStationRules,
    ): Map<String, String> = ids.mapValues { (_, rep) -> rules.apply(rep) }

    /** 界面上所有印驿站名的地方都走这张表；规则套在簇代表名上，整簇一起跟随。 */
    fun stationLabels(
        records: List<ExpressRecord>,
        rules: ExpressStationRules,
    ): Map<String, String> = labelsOf(stationIdentities(records), rules)

    fun stationLabelOf(record: ExpressRecord, labels: Map<String, String>): String? =
        labels[normalizedKey(record)]?.takeIf { it != UNKNOWN_STATION }

    /** 取件码：记录自己的优先，缺了回退用户给该站设的默认码（菜鸟对部分包裹根本不下发 authCode）。刻意不写回记录 —— 用户删规则后卡片要回到「没有码」。 */
    fun pickupCodeOf(record: ExpressRecord, rules: ExpressStationRules): String? =
        record.pickupCode?.takeIf { it.isNotBlank() }
            ?: rules.pickupCodeFor(normalizedKey(record))

    fun stationAddressOf(record: ExpressRecord, rules: ExpressStationRules): String? =
        record.stationAddress?.takeIf { it.isNotBlank() }
            ?: rules.addressFor(normalizedKey(record))

    /** 该站出示哪个平台的身份码；账号级，包裹身上没有它的位置，只来自用户设置。null = 按默认平台处理。 */
    fun identitySourceOf(record: ExpressRecord, rules: ExpressStationRules): IdentitySource? =
        rules.identitySourceFor(normalizedKey(record))

    /**
     * 身份码弹窗的取件点列表：按显示名聚合，未知地点不收；各字段独立取第一个有值
     * （坐标与取件码常分散在不同记录上），按最近来件倒序（定位不可用时的兜底顺序）。
     * 取件码以未取件优先（已签收历史件的 authCode 早就没用）。
     */
    fun spots(
        records: List<ExpressRecord>,
        rules: ExpressStationRules,
    ): List<ExpressStationSpot> {
        val ids = stationIdentities(records)
        val labels = labelsOf(ids, rules)
        val grouped = LinkedHashMap<String, MutableList<ExpressRecord>>()
        for (record in records) {
            val display = labels[identityOf(record, ids)] ?: identityOf(record, ids)
            if (display == UNKNOWN_STATION) continue
            grouped.getOrPut(display) { mutableListOf() } += record
        }
        return grouped
            .map { (display, items) ->
                val pending = items.filter { it.status in PICKUP_STATUSES && it.pickedUpAt == null }
                ExpressStationSpot(
                    displayName = display,
                    // 坐标过 geoPointOf：历史记录里存着宿主 1e5 倍的放大值，读时一并修掉。
                    position = items.firstNotNullOfOrNull { geoPointOf(it.stationLat, it.stationLng) },
                    pickupCode = (pending.ifEmpty { items }).firstNotNullOfOrNull { pickupCodeOf(it, rules) },
                    identitySource = items.firstNotNullOfOrNull { identitySourceOf(it, rules) },
                    readyCount = pending.size,
                ) to items.maxOf { it.timestamp }
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    const val UNKNOWN_STATION = "未知取件地点"

    /**
     * 挑「用户此刻最可能去的那一个」：人在驿站附近（≤ [nearbyMeters]）→ 就是它；否则按待取件
     * 件数最多。不接着按距离排：宿主经常不下发驿站坐标（2026-09-26 真机 4 个点只有 2 个有坐标），
     * 只按距离会让有坐标的点垄断结果。返回依据供弹窗标题区分「最近的 / 件最多的」。
     */
    fun pickSpot(
        spots: List<ExpressStationSpot>,
        position: GeoPoint?,
        nearbyMeters: Double = NEARBY_STATION_M,
    ): SpotPick? {
        if (spots.isEmpty()) return null
        // 没有取件码的点排在最后：一个说不出码的点顶上来只会把能用的挤下去。
        val withCode = spots.filter { !it.pickupCode.isNullOrBlank() }
        val candidates = withCode.ifEmpty { spots }

        // 缺一半（没定位 / 该点没坐标）就是 null ——「不知道多远」，不是 0。
        fun distanceTo(spot: ExpressStationSpot): Double? = position?.let { from ->
            spot.position?.let { to ->
                GeoDistance.meters(from.lat, from.lng, to.lat, to.lng)
            }
        }

        if (candidates.size == 1) {
            val only = candidates.first()
            return SpotPick(only, SpotPickReason.ONLY, distanceTo(only))
        }

        if (position != null) {
            val nearest = candidates
                .mapNotNull { spot -> distanceTo(spot)?.let { it to spot } }
                .minByOrNull { it.first }
            // 只有就在附近才采纳：最近的都超出半径说明用户不在任何取件点边上，距离没有指导意义。
            if (nearest != null && nearest.first <= nearbyMeters) {
                return SpotPick(nearest.second, SpotPickReason.NEARBY, nearest.first)
            }
        }

        val pick = candidates.maxByOrNull { it.readyCount } ?: candidates.first()
        return SpotPick(pick, SpotPickReason.READY_COUNT, distanceTo(pick))
    }

    /** 1 公里：模块只申请粗略定位（COARSE），基站误差可达一两公里。 */
    const val NEARBY_STATION_M = 1_000.0
}

/** 身份码弹窗里的一处取件点。 */
data class ExpressStationSpot(
    val displayName: String,
    /** 驿站坐标（WGS84 度）；null = 宿主没给（真机很常见）。 */
    val position: GeoPoint?,
    /** 这一处当前该念的取件码；整组都没有时 null。 */
    val pickupCode: String?,
    /** 用户给该站选的身份码平台（菜鸟 / 拼多多）；null = 没设过。 */
    val identitySource: IdentitySource? = null,
    /** 当前待取件件数；定位不可用时是挑选判据。 */
    val readyCount: Int = 0,
)

/** [ExpressHomeGrouper.pickSpot] 的结果；弹窗标题按 [reason] 区分「最近的 / 件最多的」两句话。 */
data class SpotPick(
    val spot: ExpressStationSpot,
    val reason: SpotPickReason,
    /** 直线距离（米）；没定位或该点没坐标时 null。 */
    val distanceMeters: Double?,
)

enum class SpotPickReason {
    ONLY,
    NEARBY,
    READY_COUNT,
}
