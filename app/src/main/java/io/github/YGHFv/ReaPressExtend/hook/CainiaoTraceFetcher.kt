package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.core.CainiaoTraceInfo
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 补齐宿主首页给不出的东西：全轨迹、驿站完整地址、商品图。
 *
 * ## 按需拉，不批量自动拉
 *
 * 2026-09-26 真机实证：首页刷新时批量自动拉，1.5s 间隔五连发后第 6 个就吃到淘宝
 * `RGV587` 风控 —— 批量节奏天然撞风控。改为**用户点开详情页时拉单个**（模块 App 经
 * `ExpressRelay.ACTION_TRACE_REQUEST` 把单号发过来）：用户的一次点击就是一次请求，
 * 时机、频率全由人手决定，节奏和人手一致，风控压力最小。也因此**不再按状态过滤** ——
 * 用户点开哪个就看哪个，运输中 / 待揽收的轨迹同样有价值。
 *
 * ## 三道闸门
 *
 * 1. **单号去重**：成功过**的运单号永不再拉（[succeeded]）—— 用户反复进出详情页、反复下拉
 *    都不会重复请求。**失败的只进冷却表（[failedAt]），不进成功表**：一次风控之后占坑的
 *    单号永远静默（「详情怎么都刷新不出来」的原始 bug），失败必须给重试的机会，只是要隔够
 *    [RETRY_COOLDOWN_MS]；
 * 2. **串行 + 最小间隔**（[executor] + [MIN_INTERVAL_MS]）：用户快速连点几个详情也不会
 *    打出连发（那是最容易被风控盯上的形状）。撞到风控时再加全线退避
 *    （[CainiaoTraceApi.riskBlocked]），那 10 分钟里连坑都不占；
 * 3. **不重试不追赶**：请求被闸门挡下就是挡下了，不排队不补拉 —— 下次点开详情再试。
 *
 * ## 结果怎么回去
 *
 * 复用现有的 `ACTION_ENRICH` 通道，投一条**带新字段的记录**出去（[stubRecord] 拼的最小原型，
 * 只带运单号）。模块 App 侧 `ExpressRecordStore.enrich` 会按运单号匹配回原来那条，然后走
 * `mergeEnrichment` 的「只填空」把轨迹 / 地址 / 商品图补上 —— 不需要为这条数据另开一套协议。
 */
internal object CainiaoTraceFetcher {

    /**
     * 相邻两次请求的最小间隔。真机实证（2026-09-26）：1.5s 五连发成功、第 6 个的预热就吃到
     * `RGV587` 风控 —— 临界就在 5~6 连发之间。按需拉取后连点详情仍可能打成小连发，
     * 保留这条间隔把请求拉开。
     */
    private const val MIN_INTERVAL_MS = 2_500L

    /** 单号失败后的重试冷却。风控的处罚窗口是分钟级的，撞完立刻重试只会延长它。 */
    private const val RETRY_COOLDOWN_MS = 10 * 60_000L

    private const val MAX_SUCCEEDED = 256

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-cainiao-trace").apply { isDaemon = true }
    }

    /** 拉成功过的运单号，永不再拉。容量到顶就整体清空 —— 清空的代价（重拉几次）远小于无限增长。 */
    private val succeeded = Collections.synchronizedSet(HashSet<String>())

    /** 失败过的运单号 → 上次尝试时刻（epoch ms）。冷却期内不重试；成功后从中移除。 */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 已入队还没记账的运单号：防并发重复入队（add 原子，第二个线程会直接返回）。 */
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    /** 上次发出请求的时刻，用于拉开间隔。只有 [executor] 那一个线程访问，不需要同步。 */
    private var lastRequestAt = 0L

    @Volatile private var fetched = 0

    /**
     * 拉单个运单号的全轨迹（闸门 → 串行执行 → [deliver] 投递结果）。
     *
     * @param cookieProvider 每次执行时现取 cookie（hook 进程读菜鸟文件；模块进程读
     *   [io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache] 内存缓存）
     * @param deliver 拉到结果后怎么投：hook 进程走 relay 广播；模块进程直接落库
     *
     * 被任何一道闸门挡下都**静默返回**：调用方（UI）另有超时降级，这里不需要也不应该
     * 反向通知 —— 反向通道会把「UI 等 hook 回应」变成双向协议，复杂度翻倍收益为零。
     */
    fun requestFetch(
        cookieProvider: () -> String?,
        tracking: String,
        deliver: (ExpressRecord) -> Unit,
    ) {
        // 退避期内的所有判断都放在占坑**之前**：这时候连「成功表」「冷却表」都不该动，
        // 否则一次退避期里的连点会把整批单号记成失败，退避一结束又全被冷却挡住。
        if (CainiaoTraceApi.riskBlocked()) return
        val now = System.currentTimeMillis()
        // in-flight 占坑防的是并发重复入队，不代表结果成败 —— 成败由 executor 闭包最后一步记账。
        if (!inFlight.add(tracking)) return
        if (tracking in succeeded) {
            inFlight.remove(tracking)
            return
        }
        failedAt[tracking]?.let { last ->
            if (now - last < RETRY_COOLDOWN_MS) {
                inFlight.remove(tracking)
                return
            }
        }

        // 异步线程持有 Activity 会泄漏，统一换成 application。
        executor.execute {
            var ok = false
            runCatching {
                pace()
                val info = CainiaoTraceApi.fetch(cookieProvider(), tracking)
                if (info == null) return@execute
                ok = true
                fetched++
                deliver(apply(stubRecord(tracking), info))
                XposedBridge.logAlways(
                    "cainiao trace ok: tn=${tracking.take(8)}… pts=${info.points.size} " +
                        "addr=${info.stationAddress} img=${info.goodsImage != null}",
                )
            }.onFailure {
                // 这条链路是「锦上添花」，任何异常都不该影响宿主 —— 记日志就行。
                XposedBridge.logError("cainiao trace 失败（已忽略）", it)
            }
            // 记账是闭包的最后一步：不管走的是 return@execute、异常还是正常路径都会执行。
            inFlight.remove(tracking)
            if (ok) {
                if (succeeded.size >= MAX_SUCCEEDED) succeeded.clear()
                succeeded.add(tracking)
                failedAt.remove(tracking)
            } else {
                failedAt[tracking] = System.currentTimeMillis()
            }
        }
    }

    /** 与上一次请求拉开 [MIN_INTERVAL_MS]。第一次不等待。 */
    private fun pace() {
        val since = System.currentTimeMillis() - lastRequestAt
        if (lastRequestAt > 0L && since < MIN_INTERVAL_MS) {
            Thread.sleep(MIN_INTERVAL_MS - since)
        }
        lastRequestAt = System.currentTimeMillis()
    }

    /**
     * 把查到的信息挂回原记录。
     *
     * 这里**不做「只填空」判断** —— 那是 `mergeEnrichment` 的职责，两边都判会让规则散成两处。
     * 只有 [courier] 例外：它要做的是「换一家」，而 `mergeEnrichment` 里旧值非 UNKNOWN 时
     * 会保留旧值，所以从公司名认不出时传 UNKNOWN 进去也不会污染已有结果。
     */
    private fun apply(record: ExpressRecord, info: CainiaoTraceInfo): ExpressRecord = record.copy(
        courier = Courier.fromCompanyName(info.courierName),
        goodsName = info.goodsName ?: record.goodsName,
        trace = info.points,
        stationAddress = info.stationAddress,
        goodsImage = info.goodsImage,
    )

    /**
     * 按需拉取的请求里只有运单号，拼一份最小原型给 [apply]。
     *
     * 所有富化字段留空、状态 UNKNOWN：`ExpressRecordStore.enrich` 按运单号把这份记录
     * 匹配回真正那条，`mergeEnrichment` 的「只填空」保证除轨迹 / 地址 / 图 / 公司外的
     * 字段一个都不会覆盖原值 —— 原型里放什么都无所谓，放少比放多安全。
     */
    private fun stubRecord(tracking: String) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "trace request",
        trackingNumber = tracking,
        origin = ExpressOrigin.ENRICHMENT,
        timestamp = System.currentTimeMillis(),
    )

    fun describe(): String =
        "fetched=$fetched ok=${succeeded.size} cooling=${failedAt.size} " +
            "riskBlocked=${CainiaoTraceApi.riskBlocked()}"
}
