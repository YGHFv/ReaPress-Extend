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
        // 准入守卫：连运单号和取件码都没有的东西**不是包裹**，是一条噪音通知 ——
        // 记进来就是首页上一块「快递包裹 / 未知」的空壳卡片（真机 2026-09-26 的
        // 「📦 揽件通知」）。判据与理由见 [ExpressRecord.hasIdentity]。
        // 在存储层挡而不是在发送端挡：relay 契约是「加键可以、改键名 = 破坏兼容」，
        // 这里的把关对所有来源生效，也不用动跨进程协议。
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
            // CREATED 纠正 IN_TRANSIT：宿主的 statusDesc 对未揽收件笼统写「运输中」，先落下
            // 的 IN_TRANSIT 是笼统值，后来更具体的「等待揽收」行不是乱序回退（取舍见
            // [ExpressRecord.mergeEnrichment] 里同名特例）。update 守卫若不放行，
            // mergeVersions 根本没机会执行。
            record.status == ExpressStatus.CREATED &&
                existing.status == ExpressStatus.IN_TRANSIT &&
                record.timestamp >= existing.timestamp -> current.map {
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
                // 读时顺手清掉历史「无身份」记录：写入侧（[upsert] / [enrich]）的守卫只挡得住
                // 新数据，挡不住守卫上线前已经躺在 XML 里的那几条。在读的这一层过滤而不另写
                // 一次性迁移 —— 下一次任何写入都会把过滤结果存回去，历史垃圾自然蒸发。
                .filter { it.hasIdentity }
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
     * 之后分两种：
     *
     * - **富化这条自己带了取件码** → 下一次富化到达时，[applyEnrichment] 会拿合并后的记录
     *   再比一遍，把那条「只有取件码」的通知记录收编掉，重复自动消失。所以这类重复是**一过性**的
     *   ——2026-09-26 真机现场正是如此：通知 13:20:54 建条，富化 13:21:07 补齐取件码 → 收编。
     * - **富化这条也没有取件码**（宿主没下发 `authCode`，真机 04:27 那批就是这样）→ 两边各自
     *   只有一个强标识、且互不相同，**没有任何可比的东西**，只能并存。这是**有意的取舍**：
     *   宁可重复一张，不可漏掉一件，更不可猜错了把两个包裹并成一个。
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
        // 与 [upsert] 同一条准入规矩：没有强标识的富化数据既无法可靠匹配（站名信号
        // 过不了匹配线），配不上时也只能建成又一张空壳卡片 —— 直接丢弃。
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
                    "station=${merged.station ?: "-"} pickup=${merged.pickupCode ?: "-"}" +
                    // 收编是「首页少一张重复卡片」的唯一证据，必须看得见：这行出现 = 之前
                    // 那几条强标识错位的记录已经被并进来了。
                    if (result.absorbedCount > 0) " absorbed=${result.absorbedCount}" else "",
            )
        } else {
            logEnrichMiss(current, enrichment)
        }

        save(context, result.records)
        true
    }.getOrDefault(false)

    /**
     * [enrich] 的纯逻辑：配得上就合并到命中那条上（**顺带收编**因为强标识错位而单独成条的重复，
     * 见下方），配不上就**追加一条**。
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
            var representative = target.mergeEnrichment(enrichment)

            // 这一段是 2026-09-26 真机现场补的，修的是「通知和富化各自成条、首页两张卡」。
            //
            // 起因：记录的身份靠**共同的强标识**（运单号 / 取件码）认，而两边常常只剩一个。
            // 富化第一次到达时宿主可能还没给 `authCode`（真机日志 04:27 那次 pickup/station
            // 都是 null），于是库里那条只有运单号；13:20 通知到达时只有取件码 —— 两边没有
            // 任何可比的东西，只能各自成条。13:21 富化补齐取件码，匹配到的**却是自己那条**
            // （运单号精确 100 分 > 取件码 50 分），通知那条就孤零零留下了。
            //
            // 合并让代表记录把两边强标识都拿到了。此时再回头比一次，之前「够不着」的那几条
            // 现在够得着了（`JT… + 1-1-6004` vs `1-1-6004`）—— 收编掉，重复当场消失。
            //
            // 判定用的仍是 [ExpressRecord.isSamePackageAs] 的严格语义（共同标识必须相等，
            // 冲突的运单号一律不合），所以不会把无关包裹卷进来。
            val absorbed = mutableListOf<Int>()
            for ((i, other) in current.withIndex()) {
                if (i == index) continue
                if (!representative.isSamePackageAs(other)) continue
                representative = representative.mergeEnrichment(other)
                absorbed += i
            }

            if (absorbed.isEmpty()) {
                // 配上了但没有新信息（比如详情页被反复打开）：不改存储，让调用方也跳过日志，
                // 否则每次点开详情页都留一行，真正有价值的那次反而被淹掉。
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
            return EnrichResult(next, representativeIndex, target, absorbed.size)
        }

        val next = applyUpsert(current, enrichment) ?: return null
        return EnrichResult(next, matchedIndex = NO_MATCH)
    }

    /** [applyEnrichment] 的结果。 */
    internal data class EnrichResult(
        val records: List<ExpressRecord>,
        /** 命中记录在 [records] 里的下标；[NO_MATCH] 表示这条是新建的。 */
        val matchedIndex: Int,
        /** 命中前的那条记录，只用于日志对比「补了什么」；新建时 null。 */
        val target: ExpressRecord? = null,
        /** 这次顺带收编掉的重复条数（合并补上强标识之后才认出来的那几条）。 */
        val absorbedCount: Int = 0,
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
                    // 轨迹接口补出来的三个字段。键名同样取短；**旧 JSON 里没有这些键**，
                    // parse 侧对缺失一律退回默认值 —— 升级不丢历史记录。
                    // 轨迹直接存成编码后的字符串（`[["时间","文案"],…]`），
                    // 编解码只有 ExpressTraceCodec 一处在做。
                    put("saddr", record.stationAddress ?: JSONObject.NULL)
                    // 驿站坐标。同样是后加的键、短名，旧 JSON 缺键 → null。
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
                    // 归一化放在**读路径**上：旧版本把宿主的 `pkgSourceDesc` 原样存下来了 ——
                    // 非淘包裹那份是「普通收件」这种收件类型词，不是平台名。清洗只写在
                    // hook 侧的话，历史记录（以及「只填空不覆盖」的合并结果）永远洗不掉。
                    platform = ExpressPlatform.normalize(obj.optStringOrNull("platform")),
                    goodsName = obj.optStringOrNull("goods"),
                    // 缺键时 optLong 给 0，0 不是合法时间戳（发送端也用它表示「没有」）。
                    arrivalAt = obj.optLong("arrival").takeIf { it > 0L },
                    phoneTail = obj.optStringOrNull("ptail"),
                    logisticsDetail = obj.optStringOrNull("dyn"),
                    stationHours = obj.optStringOrNull("hours"),
                    // 同上：0 不是合法时间戳，缺键（旧 JSON）就读成「没取过」。
                    pickedUpAt = obj.optLong("picked").takeIf { it > 0L },
                    // 轨迹接口补出来的字段。旧 JSON 里没有这几个键 ——
                    // 缺 "trace" 解出空表、缺另两个解出 null，都不会让历史记录读不出来。
                    stationAddress = obj.optStringOrNull("saddr"),
                    // 坐标用 optDouble：缺键得到 NaN（不是 0）——0 是几内亚湾上的一个点，
                    // 当成有效坐标会让「最近的驿站」永远算到非洲去。NaN 一律当「没有」。
                    stationLat = obj.optDouble("lat").takeIf { !it.isNaN() },
                    stationLng = obj.optDouble("lng").takeIf { !it.isNaN() },
                    trace = ExpressTraceCodec.decode(obj.optStringOrNull("trace")),
                    goodsImage = obj.optStringOrNull("gimg"),
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
 * 对齐菜鸟首页的结构：**到站包裹按取件地点聚合**，在途的平铺在下面。
 * 这个分组不是纯按状态切的 —— 到站/待取件才是用户要去取的东西，所以它们按地点聚合成卡片组，
 * 而在途的（派送中、运输中）用户只是「知道一下」，平铺即可。
 *
 * 档位由近到远排列：到站包裹 → 派送中 → 运输中 → 其他 → 已签收 / 异常。
 */
data class ExpressHomeSection(
    val title: String,
    /** 该分组下的驿站子分组（到站包裹用）。其余分组为空，直接看 [records]。 */
    val stationGroups: List<ExpressStationGroup> = emptyList(),
    /** 不按驿站聚合的记录（派送中、运输中、其他、已签收）。 */
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
     * 这一行的**主键**：规则表里代表整行的那一个键（[ExpressStationRules.renames] 用的字符串）。
     *
     * 「合并到其他驿站」把这个字符串写成目标 —— 它取的是**链头**（没有任何别的键指向它的
     * 那一个），所以规则写在这里，整条链跟着变（理由与实现见 [ExpressHomeGrouper.stations]）。
     * 单键的行（绝大多数）它就是那个唯一的键。
     */
    val key: String,
    /** 当前该显示的名字（用户的规则生效之后）。没改过时等于 [key]。 */
    val displayName: String,
    /** 这站出现过的**原始**写法（归一化之前，去重）。界面拿它告诉用户「这些写法被认为是一处」。 */
    val rawNames: List<String>,
    /** 这个驿站名下的记录（不分状态）。详情页拿它列出「这站是哪几件」。 */
    val records: List<ExpressRecord>,
    /** 用户手工改过名 / 合并过 / 填过默认码、精确地址、现场指纹。界面据此决定要不要显示「恢复默认」。 */
    val renamed: Boolean,
    /**
     * 这一行覆盖的**全部**规则键（归一化名，去重后的顺序与记录顺序一致）。
     *
     * 一行只有在用户把几处**合并**到一起之后才会多于一个键 —— 那时 [key] 只是链头，
     * 剩下的键是顺着链并进来的。写规则（改名 / 合并 / 默认码 / 精确地址）和清规则
     * （恢复默认）都必须**对整组一起做**：只动链头的话，链里另一条会当场裂回独立的一行，
     * 正是 2026-09-26 用户看到的「合并了还是两条」。
     *
     * 默认值给 [key] 是为了让单键的构造点不必都写一遍 —— 但 [ExpressHomeGrouper.stations]
     * 总是显式传整组。
     */
    val ruleKeys: List<String> = listOf(key),
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

    /**
     * 在途。
     *
     * [ExpressStatus.DELIVERING] **不在这里** —— 它自己一档（见 [group]）。
     * 理由：快递员正在送的件是「今天可能就到」的，用户看这一屏的期待和纯在途件不同
     * （一个要留意电话，一个只是知道一下）；混在两三十件运输中里等于没显示。
     */
    private val TRANSIT_STATUSES = setOf(
        ExpressStatus.CREATED,
        ExpressStatus.PICKED_UP,
        ExpressStatus.IN_TRANSIT,
    )

    /** 派送中：单独一档，摆在「运输中」上面。 */
    private val DELIVERING_STATUSES = setOf(ExpressStatus.DELIVERING)

    /** 已结束。 */
    private val DONE_STATUSES = setOf(
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    /**
     * @param rules 用户在「驿站管理」里做的合并 / 改名。默认空规则 = 只靠自动归一化分组，
     *   这也是单测里最常传的那一种。
     * @param now 首页当前时刻（毫秒）。**只用于归档切分**：已完成超过 [ARCHIVE_RETENTION_MS]
     *   的记录不再出现在「已签收 / 异常」这一档里 —— 它们进了「归档快递」二级页（[archive]）。
     *
     *   默认 `0L` = **不做归档切分**（now 为 0 时任何记录都不可能超期），所有已完成记录都留在
     *   这一档。只关心分组结构的调用方（多数单测）用这个默认值即可；真正的首页那一处调用传的是
     *   真实时钟 —— core 层刻意不碰系统时钟，所以时间必须从这里注入。
     */
    fun group(
        records: List<ExpressRecord>,
        rules: ExpressStationRules = ExpressStationRules.EMPTY,
        now: Long = 0L,
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

        // 派送中排在「运输中」**上面**：越靠上越接近「今天要动手」。档位顺序就是这屏的
        // 阅读顺序，不用再给 section 加权重字段 —— 排列即语义。
        val delivering = records.filter { it.status in DELIVERING_STATUSES }
        if (delivering.isNotEmpty()) {
            // 抬头直接取状态名：卡片右上角显示的也是这个词，两处各写一份字符串迟早会飘。
            sections += ExpressHomeSection(
                title = ExpressStatus.DELIVERING.displayName,
                records = delivering,
            )
        }

        val transit = records.filter { it.status in TRANSIT_STATUSES }
        if (transit.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "运输中",
                // **纯时间倒序**：最近有动静的排前面。2026-09-26 用户第二次定的规则 ——
                // 此前这里按「状态推进度」排（已在路上的在前、还没揽收的垫底），那是他上一轮
                // 点名的顺序；现在的原话是「运输中和已签收快递都按时间排序，最近更新和签收的
                // 在前面」。不要用「推进度 + 时间」两层混合：用户扫这一档时问的是「哪件刚更新」。
                //
                // 时间取 [updatedAtOf]（轨迹最新节点 / 到站时间 / 宿主 gmt_modified 取最新）——
                // 与卡片右上角那个「2小时前」依据的是同一个值（`ExpressFormatter.statusSince`
                // 是它的一部分），**排序依据必须和用户看到的相对时间一致**，否则会出现
                // 「它排在『3分钟前』上面，自己却写着『5小时前』」这种一眼就看出错的顺序。
                records = transit.sortedWith(compareByDescending { updatedAtOf(it) }),
            )
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
        //
        // 超过 [ARCHIVE_RETENTION_MS] 的那些**不在这里**：它们进了「归档快递」二级页。
        // 首页这一档于是恒等于「最近一周内结束的件」—— 用户回头核对的就是这些，
        // 再往前的只会让列表一直长下去（2026-09-26 用户要求）。
        val done = finishedRecords(records, ids).filterNot { isArchived(it, now) }
        if (done.isNotEmpty()) {
            sections += ExpressHomeSection(
                title = "已签收 / 异常",
                // 最近签收的在前 —— 与「运输中」同一条规矩（用户点名的「运输中和已签收快递
                // 都按时间排序」）。异常件（投递失败）与已签收件同档同序：它是不是要处理，
                // 由状态文案说明，不靠位置表达。
                records = done.sortedWith(compareByDescending { completedAtOf(it) }),
            )
        }

        return sections
    }

    /**
     * 列出记录里出现过的驿站，供「驿站管理」页展示。
     *
     * 跟 [group] 用的是同一套归一化 + 聚类：界面上列出来的驿站，和首页卡片上的驿站，
     * 保证是同一批 —— 两边各算一次的话，用户会在管理页看到首页上不存在的条目。
     *
     * 返回的 [ExpressStationSummary.ruleKeys] 就是 [ExpressStationRules] 几张表的键空间，
     * 界面显示什么名字、规则表用什么键，是同一个字符串，中间不做转换。
     *
     * ## 分组键是**显示名**，不是簇身份（2026-09-26 修）
     *
     * 用户报过「驿站管理已经合并的还是会显示两条」。现场规则是链式的：
     * `阜阳颍滨花园店 → 颖滨23号楼109… → 颖滨花园驿站`。两条链路算出的**显示名相同**、
     * **簇身份不同**（一个是链头、一个是被并进来的那条），按身份分组就给出两行同名条目。
     *
     * 列表回答的是「我这台机器上有几处取件点」，同一处只能有一行 —— 所以按显示名归并。
     * 而 [group] 里的分组归类、整站确认仍然按**簇身份**算，两者不能混：那两件事关心的是
     * 「哪几件是同一个物理地点」，和用户给它起了什么名字无关（见 [identityOf]）。
     *
     * ## 缺地名的记录只看取件候选
     *
     * 宿主对运输中 / 派送中的包裹本来就不下发 `stationName`，「本人签收」这类状态识别不出的
     * 更是连驿站都没有。它们进不了这一页 —— 否则列表底部永远挂着一行「未知取件地点」，
     * 用户既不知道那是哪、也没法对它做什么（2026-09-26 用户报的原话）。
     *
     * 待取件的缺名记录**要**列出来：那是唯一能给它填「默认取件码 / 精确地址」的地方，
     * 也是「手动添加取件码」那个功能将来的入口。
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

        val grouped = LinkedHashMap<String, MutableList<ExpressRecord>>()
        for (record in records) {
            val identity = identityOf(record, ids)
            val display = labels[identity] ?: identity
            // 见上方「缺地名的记录只看取件候选」。
            if (display == UNKNOWN_STATION && record.status !in PICKUP_STATUSES) continue
            grouped.getOrPut(display) { mutableListOf() } += record
        }

        return grouped
            .map { (display, items) ->
                // 整行的全部规则键。写规则 / 清规则都要对整组一起做，理由见 [ExpressStationSummary.ruleKeys]。
                val keys = items.map { normalizedKey(it) }.distinct()
                ExpressStationSummary(
                    // 「链头」= 组内**唯一没有指向组内别的键**的那个键，也就是这条改名链的终点。
                    // 规则写在这里，链上所有键都会经由它到达同一个显示名（`apply` 是链式解析）。
                    //
                    // 判据不能反过来写「没有任何键指向它」：真机现场是 `阜阳颍滨花园店 →
                    // 颖滨23号楼109…`、而 `颖滨23号楼109… → 颖滨花园驿站`，链的终点是后者 ——
                    // 写在前者上只会让前半段改掉、后半段留在原地，一行又裂成两行。
                    // 一个都挑不出来（用户的规则互相指成环）时退回第一个：环是输入错误，
                    // 界面不该因此打不开，退回哪个键至少能让用户进去改掉。
                    key = keys.firstOrNull { key ->
                        keys.none { other -> other == rules.renames[key] }
                    } ?: keys.first(),
                    // 走和首页同一张显示名表：管理页里看到的名字，必须和首页卡片上的名字一样。
                    displayName = display,
                    // 归一化**之前**的原始写法：用户要在这行里认出「哦，这两个名字原来是同一处」。
                    rawNames = items.mapNotNull { it.station?.takeIf(String::isNotBlank) }.distinct(),
                    records = items,
                    // 几张表任一有值都算「动过」—— 只记下一份位置指纹而没有改名，同样该给「恢复默认」。
                    renamed = keys.any { rules.hasRule(it) },
                    ruleKeys = keys,
                )
            }
            // 与首页卡片**同一套**排序规则（[stationOrder]），只有**件数方向**不同 ——
            // 管理页这边是「件多的靠前」，首页那边是「件少的靠前」（见那里的 ⚠️）。
            .sortedWith(
                stationOrder<ExpressStationSummary>(
                    isUnknown = { it.key == UNKNOWN_STATION },
                    records = { it.records },
                    name = { it.displayName },
                    countDescending = true,
                ),
            )
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
     * 已完成的记录在首页停留多久，超过就进「归档快递」（毫秒）。
     *
     * 7 天是用户定的（2026-09-26 原话：「已签收超过 7 天的快递自动进入归档快递的二级页面」）。
     * 依据是这一档的用途 —— 用户回头翻「已签收 / 异常」只可能是核对最近几天的事；
     * 一周以前的签收记录既不需要动作、也早已不在驿站了，留在首页只是把列表越拖越长。
     */
    const val ARCHIVE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * 「归档快递」二级页要显示的那些记录：**已完成、且超过 [ARCHIVE_RETENTION_MS] 没再动过**的。
     *
     * 按 [completedAtOf] 倒序 —— 最近归档的排最上面。归档件是「历史」，用户翻它的动机通常是
     * 「上周那件到底签收没有」，所以新的一般比旧的更常被找。
     *
     * ## 为什么传的是**全部**记录
     *
     * 和 [group] 同一条理由：驿站名要先做聚类（[stationIdentities]），而聚类的输入必须是完整集合
     * —— 只喂归档件的话，某个驿站的简称在全量里是被折叠到全名上的，在这里却会当场独立成一个
     * 代表名，归档页和首页就会对同一个驿站印出两个名字。
     *
     * @param now 当前时刻（毫秒）。与 [group] 的 `now` 是同一个值 —— 两处若不一致，
     *   会出现「一件既不在首页、也不在归档页」的空窗（恰好卡在两次调用之间的那件）。
     */
    fun archive(
        records: List<ExpressRecord>,
        now: Long,
        retentionMs: Long = ARCHIVE_RETENTION_MS,
    ): List<ExpressRecord> =
        finishedRecords(records, stationIdentities(records))
            .filter { isArchived(it, now, retentionMs) }
            .sortedWith(compareByDescending { completedAtOf(it) })

    /**
     * 「已完成」的记录：宿主推来的已签收 / 投递失败，加上**用户整站确认取走**的到站件。
     *
     * 这是首页「已签收 / 异常」那一档的完整候选集，[group] 与 [archive] 共用同一份 ——
     * 两处各写一遍迟早会飘，而飘的后果是同一件包裹同时出现在两个页面、或者同时在两个页面消失
     * （用户只会觉得「模块把数据弄丢了」）。
     */
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

    /**
     * 这件是不是该归档了。判据只有一条：**完成之后过了 [retentionMs]**。
     *
     * `>=` 而不是 `>`：「超过 7 天」按整天算，正好第 7 天那一刻归档是符合直觉的。
     *
     * ⚠️ 传 `now = 0` 时（见 [group] 的默认值）任何记录都不算归档 —— 差值恒为负。
     * 这正是「不做归档切分」想要的效果，不是巧合。
     */
    private fun isArchived(
        record: ExpressRecord,
        now: Long,
        retentionMs: Long = ARCHIVE_RETENTION_MS,
    ): Boolean = now - completedAtOf(record) >= retentionMs

    /**
     * 这件「最后有动静」是什么时候（毫秒）：轨迹最新节点、到站时间、宿主 `gmt_modified` 取最新。
     *
     * 三处都要：轨迹最新节点是「真正发生了什么」的时刻（宿主几小时不刷新时 `gmt_modified`
     * 会停在旧值 —— 真机实证：13:54 已派送的件显示「11小时前」）；[ExpressRecord.arrivalAt]
     * 是宿主物流记录的最后变更时间；[ExpressRecord.timestamp] 兜底（旧记录 / 没有轨迹的件）。
     *
     * 三条来源都没有（`timestamp` 为 0 的构造）时返回 0：排序里自然垫底，不会抛异常。
     */
    private fun updatedAtOf(record: ExpressRecord): Long = maxOf(
        ExpressFormatter.statusSince(record) ?: 0L,
        record.arrivalAt ?: 0L,
        record.timestamp,
    )

    /**
     * 这件**结束**是什么时候（毫秒）。
     *
     * ## 为什么不能走 [updatedAtOf]（真机实证：2026-09-26）
     *
     * updatedAtOf 含 [ExpressRecord.timestamp]，而 timestamp 的语义是「这条记录最后一次
     * 被数据源更新」—— 宿主自查 / 富化合并 / 推送都可能把它刷新到**今天**。真机上一条
     * 9 月 16 日就签收的件，`at` 停在 9 月 26 日（宿主自查灌库），于是归档判据
     * `now - 完成时刻 >= 7 天` 永远不成立 → 归档页永远「暂无」，超期件永远占着首页。
     *
     * 「完成时刻」要的是**能证明它完成了**的时刻，取三处较新者：
     * - 轨迹最后一个节点的时间（真实签收/失败时刻，`ExpressFormatter.tracePointTime` 解析）；
     * - [ExpressRecord.arrivalAt]（宿主物流记录的最后变更时间，签收后不再变）；
     * - [ExpressRecord.pickedUpAt]（用户确认取件的时刻 —— 整站确认的到站件可能没有
     *   签收轨迹，这一笔就是它唯一的完成凭据；只看前两处会把刚取走的件当场归档）。
     *
     * 三处都缺（旧记录没有轨迹也没有 arrivalAt）才兜底 [ExpressRecord.timestamp]：
     * 判不了就别乱归档，宁可让它在首页多待一会儿。
     */
    private fun completedAtOf(record: ExpressRecord): Long {
        val proof = maxOf(
            record.trace.lastOrNull()?.let { ExpressFormatter.tracePointTime(it.time) } ?: 0L,
            record.arrivalAt ?: 0L,
            record.pickedUpAt ?: 0L,
        )
        return if (proof > 0L) proof else record.timestamp
    }

    /**
     * 驿站之间的排序：**件多的靠前，同件数时最近还有新件到的靠前**。
     *
     * 2026-09-26 用户定的规则（原话：「驿站按最近的快递在那个驿站和驿站快递数量排序，
     * 数量多和最近收件的驿站靠前」）。服务的场景是「一趟能拿几件、什么时候去」——
     * 件多的那一站值得排最上面，而刚到的件比躺了半个月的件更值得现在跑一趟。
     *
     * ⚠️ **件数那一层的方向是可配的，两处调用点方向相反**（同一天用户第二次要求）：
     * - **首页卡片组**：件数**少**的靠前（「首页待取件快递按从少到多排序」）。
     * - **驿站管理列表**：件数**多**的靠前（上面那句原话，用户 2026-09-26 明确「首页改，
     *   管理页保持」）。
     *
     * 两处方向不同不是矛盾，问的问题本来就不同：首页是「我现在先看哪一站」（待取件里那些
     * 只剩一件的站，往往就是顺手拿一下、最省事的），管理页是「哪一站最值得管」。
     * 「最近到件」那一层两边都保持**新的靠前**。
     *
     * ## 为什么做成一个带取值函数的比较器
     *
     * 首页的卡片组（[ExpressStationGroup]）和驿站管理列表（[ExpressStationSummary]）是两个
     * 不同的类型，但除了件数方向外**其余规则必须是同一个顺序** —— 以前两边各写一遍
     * `compareBy({ 未知 }, { 名字 })`，注释里写着「和首页卡片同一个顺序」，实际靠人肉对齐，
     * 改一边忘一边就飘了。这里把规则收在一处，两个调用点只负责把字段取出来、把方向传进来。
     *
     * ## 四层键
     *
     * 1. **未知取件地点永远垫底**：它不是一处真实地点（见 [UNKNOWN_STATION]），
     *    份量再多也不该挤掉真驿站；
     * 2. 件数（方向见上，[countDescending]）；
     * 3. 同件数时，该站**最新一条**的时间新的在前（「最近收件的靠前」）；
     * 4. 兜底按名字 —— **这一层不能省**：件数和时间都相同时必须给出全序，否则列表顺序
     *    跟着底层容器的迭代序抖，用户看着像「它自己跳了一下」。
     *
     * @param countDescending 件数那一层是否降序（true = 多的靠前）。见上面那条 ⚠️。
     */
    private fun <T> stationOrder(
        isUnknown: (T) -> Boolean,
        records: (T) -> List<ExpressRecord>,
        name: (T) -> String,
        countDescending: Boolean,
    ): Comparator<T> {
        // 方向要拼进比较器链里，不能靠 `.reversed()`：那会把**整条链**都翻过来，
        // 连「未知地点垫底」和「名字兜底」一起反掉。
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

    /**
     * 一组记录里**最新一条**的时间，供 [stationOrder] 第 3 层用。
     *
     * [ExpressRecord.arrivalAt] 优先 —— 那是「这件什么时候躺进驿站」的直接答案；
     * 没有则退回 [ExpressRecord.timestamp]（宿主最后更新时刻）。运输中 / 派送中的件本来
     * 就没有到站时间，不兜底的话「刚揽收的件」在这一层等于永远垫底。
     *
     * 整组都没有时间（旧记录 / 时钟异常）时返回 0：不参与排序，交给下一层的名字。
     */
    private fun latestAtOf(records: List<ExpressRecord>): Long =
        records.maxOfOrNull { it.arrivalAt ?: it.timestamp } ?: 0L

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
            // 驿站之间的顺序：未知地点垫底、**件少的靠前**、最近到件的靠前（见 [stationOrder]）。
            // 首页这一层刻意与管理页相反（那边件多的靠前）—— 理由在那个函数的 ⚠️ 里。
            .sortedWith(
                stationOrder<ExpressStationGroup>(
                    isUnknown = { it.station == UNKNOWN_STATION },
                    records = { it.records },
                    name = { it.station },
                    countDescending = false,
                ),
            )
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

    /**
     * 一条记录**最终该念出来**的取件码。
     *
     * 记录自己有码就用它；没有就回退到用户在「驿站管理」里给该站设的默认码
     * （[ExpressStationRules.pickupCodeFor]，会顺着改名链找）。
     *
     * 为什么需要这一层：菜鸟对一部分包裹（`普通收件` / 末端非淘包裹）根本不下发 `authCode`，
     * 而那些包裹在驿站小程序里是有码的。让用户填一次，之后这一站的缺码件**都能显示出来**
     * —— 这是「默认取件码」的全部意义，也是将来「手动添加取件码」的地基。
     *
     * 刻意**不写回** [ExpressRecord.pickupCode]：那是宿主/通知说的事实，规则只是本机显示。
     * 用户删掉规则，卡片就该回到「没有码」的样子（和改驿站名同一条边界）。
     */
    fun pickupCodeOf(record: ExpressRecord, rules: ExpressStationRules): String? =
        record.pickupCode?.takeIf { it.isNotBlank() }
            ?: rules.pickupCodeFor(normalizedKey(record))

    /** 一条记录该显示的精确地址：记录自己的优先，缺了回退到该站用户填的精确地址。 */
    fun stationAddressOf(record: ExpressRecord, rules: ExpressStationRules): String? =
        record.stationAddress?.takeIf { it.isNotBlank() }
            ?: rules.addressFor(normalizedKey(record))

    /**
     * 该站出示哪个平台的**身份码**；没设过返回 null（调用方按默认平台处理）。
     *
     * 与 [pickupCodeOf] 差一层：这里**没有**「记录自己的优先」—— 身份码是账号级的，
     * 包裹身上没有它的位置（宿主不下发），只可能来自用户对该驿站的设置。
     */
    fun identitySourceOf(record: ExpressRecord, rules: ExpressStationRules): IdentitySource? =
        rules.identitySourceFor(normalizedKey(record))

    /**
     * 身份码弹窗用：把记录里出现过的取件点压成「点位」列表，供挑「离我最近的那个」。
     *
     * 一条点位聚合了它名下**所有**记录能给出的信息，各字段独立取第一个有值的：
     * 坐标只有宿主下发过的那几条才有，取件码只有待取件的那几条才有 —— 不该因为
     * 组内第一条恰好两样都缺就把整个点位的信息吞掉。
     *
     * 未知地点（没有驿站名的）不收：弹窗要显示「去哪取」，一个说不出名字的点位帮不上忙。
     *
     * 顺序按该点最近一条记录的时间**倒序**，也就是「最近有动静的排前面」。它同时是
     * 拿不到定位时的兜底顺序 —— 挑不出「最近」的时候，最近来件的那个驿站是最合理的猜测。
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
                // 待取件的排在前面：取件码要念的是「现在能取的这件」，而同一站里往往还躺着
                // 已签收的历史件（它们的 authCode 早就没用了）。用户手动标记过取件的也排开。
                val pending = items.filter { it.status in PICKUP_STATUSES && it.pickedUpAt == null }
                ExpressStationSpot(
                    displayName = display,
                    // 坐标两边都过一遍 [geoPointOf]：宿主写进来的已经是归一后的度，
                    // 而这之前落库的历史记录里还存着 1e5 倍的放大值，读时一并修掉。
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
     * 从 [spots] 里挑「用户此刻最可能要去的那一个」—— 身份码弹窗下半屏就靠它。
     *
     * ## 规则（两步，每一步都能被用户看懂、被日志解释）
     *
     * 1. **人在驿站附近（≤ [nearbyMeters]）→ 就是它**。这一档只在「有定位、且该站有坐标」
     *    时才成立 —— 站在柜台前打开弹窗是这个界面最常见的一刻，「最近的」就是对的答案。
     * 2. **否则按待取件件数**（[ExpressStationSpot.readyCount]）取最多的那个；并列时取
     *    [spots] 里靠前的（= 最近有来件的那个）。
     *
     * ## 为什么第 2 步不是「按距离接着排」
     *
     * 因为宿主**经常不下发驿站坐标**（2026-09-26 真机实测：手里 4 个取件点只有 2 个有坐标），
     * 而「没有坐标」不等于「很远」。只按距离排等于让有坐标的那两三个点垄断结果 ——
     * 真机上看到的正是这一幕：手里攥着 10 件待取的站在几公里外，弹窗却推了一个只有 1 件、
     * 12 天前到站的站，仅仅因为它是唯一有坐标的那个。用户报的「最近的取件点显示不对」就是它。
     *
     * 而「哪站件多」在拿不到位置时才是真正相关的信号：用户要的是「去哪个站跑一趟」。
     * 位置敏感的场景（站在驿站门口）由第 1 步兜住。
     *
     * 返回的是 [SpotPick] 而不是裸的点位：界面要按**依据**改标题（「最近的」和「件最多的」
     * 是两句话），而这个依据只有这里知道 —— 让界面去复述规则，规则就又多了一份。
     *
     * @param position 当前位置；拿不到定位（没权限 / 没有最后已知位置）时传 null。
     */
    fun pickSpot(
        spots: List<ExpressStationSpot>,
        position: GeoPoint?,
        nearbyMeters: Double = NEARBY_STATION_M,
    ): SpotPick? {
        if (spots.isEmpty()) return null
        // 没有取件码的点排在最后：弹窗下半屏要回答「念什么」，一个说不出码的点顶上来
        // 只会把真正能用的那个挤下去（宿主对在途件本来就不给 authCode）。
        val withCode = spots.filter { !it.pickupCode.isNullOrBlank() }
        val candidates = withCode.ifEmpty { spots }

        // 距离按需算。缺一半（没定位 / 该点没坐标）就是 null —— 「不知道多远」，不是 0。
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
            // 只有「就在附近」才采纳。最近的一个都在几公里外，说明用户不在任何一个取件点边上
            // （在家点开弹窗就是这样），那时的距离只是「谁更远」的排序，对「该去哪一趟」没有指导意义。
            if (nearest != null && nearest.first <= nearbyMeters) {
                return SpotPick(nearest.second, SpotPickReason.NEARBY, nearest.first)
            }
        }

        val pick = candidates.maxByOrNull { it.readyCount } ?: candidates.first()
        return SpotPick(pick, SpotPickReason.READY_COUNT, distanceTo(pick))
    }

    /**
     * 「算近」的半径。
     *
     * 取 1 公里：模块只申请了粗略定位（COARSE），基站定位的误差可达一两公里，
     * 更小的值会把「明明站在门口」误判成不近；更大则两个点会同时命中而按错顺序。
     */
    const val NEARBY_STATION_M = 1_000.0
}

/**
 * 身份码弹窗里的一处取件点。
 *
 * [position] 来自宿主 `packageStation.stationLat/stationLng`，**已经过 [geoPointOf] 归一化**
 * （宿主下发的是 1e5 倍的整数，见那个函数）。菜鸟没给过坐标就是 null —— 真机上很常见，
 * 那时的选择规则由 [ExpressHomeGrouper.pickSpot] 决定。
 */
data class ExpressStationSpot(
    /** 该显示的驿站名（已过用户的改名 / 合并规则）。 */
    val displayName: String,
    /** 驿站坐标（WGS84 度）；null = 宿主没给，按「不知道多远」处理。 */
    val position: GeoPoint?,
    /** 这一处当前该念的取件码（待取件的记录优先，缺了用该站默认码）；整组都没有时 null。 */
    val pickupCode: String?,
    /**
     * 用户在驿站管理里给这站选的**身份码来源**（菜鸟 / 拼多多）；没设过 null。
     *
     * 它决定弹窗去哪个平台取码，也决定「取不到」时该怎么解释 —— 选中一个还没实现的平台
     * （[IdentitySource.supported] 为 false）时必须如实说明，不能悄悄换一个平台。
     */
    val identitySource: IdentitySource? = null,
    /**
     * 该站当前**待取件**的件数（到站 / 待取件，且用户没手动标记过）。
     *
     * 它是「哪一站值得跑一趟」的直接答案，也是拿不到定位时 [ExpressHomeGrouper.pickSpot]
     * 的判据 —— 一个只有坐标、却一件待取都没有的站，不该抢在手里攥着十件待取的站前面。
     */
    val readyCount: Int = 0,
)

/**
 * [ExpressHomeGrouper.pickSpot] 的结果：挑中的取件点 + **凭什么挑中它** + 距离。
 *
 * 为什么把「依据」也返回出来：弹窗那一段的标题得跟着依据走（「最近的取件点」与
 * 「待取件最多的取件点」是两句话，写错等于对用户撒谎），而这个依据只有挑选函数知道。
 * 把规则再在界面里复述一遍，就是让同一件事有两个说法 —— 改了一处忘了另一处，
 * 界面就开始解释一个它并没有执行的规则。
 */
data class SpotPick(
    val spot: ExpressStationSpot,
    val reason: SpotPickReason,
    /** 当前位置到该点的直线距离（米）；没定位或该点没坐标时 null。 */
    val distanceMeters: Double?,
)

/** [ExpressHomeGrouper.pickSpot] 是怎么挑出这个点的。 */
enum class SpotPickReason {
    /** 只有这一个候选（或有码的只有一个），没什么可挑的。 */
    ONLY,
    /** 人就在它附近（≤ [ExpressHomeGrouper.NEARBY_STATION_M]）。 */
    NEARBY,
    /** 拿不到「近」的依据（没定位 / 最近的一个都在几公里外），改按待取件件数挑的。 */
    READY_COUNT,
}
