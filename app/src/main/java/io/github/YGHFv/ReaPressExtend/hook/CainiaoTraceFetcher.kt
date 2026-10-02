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

package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.core.CainiaoTraceInfo
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.FetchRequestLedger
import io.github.YGHFv.ReaPressExtend.core.FetchRequestQueue
import io.github.YGHFv.ReaPressExtend.core.latestTraceDetail
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.util.concurrent.Executors

/**
 * 按需拉单个运单号的全轨迹、驿站地址、商品图、运单动态（模块 App 点开详情时经
 * ACTION_TRACE_REQUEST 把单号发过来）。9-26 实证：批量自动拉 1.5s 间隔五连发，第 6 个就吃到
 * 淘宝 RGV587 风控——改用户点开才拉，不再按状态过滤。
 * 闸门：成功过的单号永不再拉，失败的只进冷却表（隔够 [RETRY_COOLDOWN_MS] 才能重试，否则一次
 * 风控后单号永远静默）；串行 + 最小间隔；被挡下就挡下，不重试不追赶。
 * 结果经 [stubRecord] 走 ACTION_ENRICH 通道，模块侧按运单号匹配回原记录走「只填空」合并。
 */
internal object CainiaoTraceFetcher {

    /** 相邻两次请求的最小间隔。真机实证临界在 5~6 连发之间，按需拉取后连点详情仍可能打成小连发。 */
    private const val MIN_INTERVAL_MS = 2_500L

    private const val RETRY_COOLDOWN_MS = 10 * 60_000L

    private const val MAX_SUCCEEDED = 256

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-cainiao-trace").apply { isDaemon = true }
    }

    private val requests = FetchRequestLedger(RETRY_COOLDOWN_MS, MAX_SUCCEEDED)

    private var lastRequestAt = 0L

    @Volatile private var fetched = 0

    private val queue = FetchRequestQueue<CainiaoTraceInfo>(
        executor = executor,
        ledger = requests,
        clock = System::currentTimeMillis,
        pace = ::pace,
        isBlocked = { CainiaoTraceApi.riskBlocked() },
        onError = { XposedBridge.logError("cainiao trace 失败（已忽略）", it) },
    )

    /**
     * 拉单个运单号的全轨迹（闸门 → 串行执行 → [deliver] 投递结果）。
     * recheck=true 允许重拉成功过的单号（自动轮查用）：轮查的意义就是隔段时间再问一次，
     * 被成功表挡住等于什么都没干；放宽成功表会让「点开详情」也每次重拉、风控压力翻倍，
     * 所以做成显式开关。onComplete 供订单发现接回状态：本次投递回调正常返回或已有成功记录
     * 时为 true；失败或未执行为 false。它不代表广播最终送达或数据已持久化。
     */
    fun requestFetch(
        cookieProvider: () -> String?,
        tracking: String,
        recheck: Boolean = false,
        onComplete: (Boolean) -> Unit = {},
        deliver: (ExpressRecord) -> Unit,
    ) {
        queue.request(
            key = tracking,
            recheck = recheck,
            fetch = { CainiaoTraceApi.fetch(cookieProvider(), tracking) },
            deliver = { info ->
                deliver(apply(stubRecord(tracking), info))
                fetched++
                runCatching {
                    XposedBridge.logAlways(
                        "cainiao trace ok: tn=${tracking.take(8)}… pts=${info.points.size} " +
                            "st=${info.status ?: "-"} addr=${info.stationAddress} " +
                            "img=${info.goodsImage != null}",
                    )
                }
            },
            onComplete = onComplete,
        )
    }

    private fun pace() {
        val since = System.currentTimeMillis() - lastRequestAt
        if (lastRequestAt > 0L && since < MIN_INTERVAL_MS) {
            Thread.sleep(MIN_INTERVAL_MS - since)
        }
        lastRequestAt = System.currentTimeMillis()
    }

    /**
     * 把查到的信息挂回原记录。这里不做「只填空」判断——那是 mergeEnrichment 的职责，
     * 两边都判会让规则散成两处；courier 例外：认不出时传 UNKNOWN 也不会污染已有结果
     * （mergeEnrichment 里旧值非 UNKNOWN 时保留旧值）。
     */
    private fun apply(record: ExpressRecord, info: CainiaoTraceInfo): ExpressRecord = record.copy(
        courier = Courier.fromCompanyName(info.courierName),
        goodsName = info.goodsName ?: record.goodsName,
        trace = info.points,
        stationAddress = info.stationAddress,
        goodsImage = info.goodsImage,
        // 运单动态与轨迹是两个字段：卡片副行和通知正文读的是这个字段，不是 trace。
        // 它原来只有宿主富化一个来源，导致「拉到新轨迹而首页动态纹丝不动」。
        logisticsDetail = latestTraceDetail(info.points) ?: record.logisticsDetail,
        // 状态同理：卡片右上角那个词读它。只认结论型状态，是否收下由 mergeEnrichment 判（不回退）。
        status = info.status ?: record.status,
    )

    /** 按需拉取的请求里只有运单号，拼一份最小原型。所有富化字段留空——「只填空」保证不覆盖原值，放少比放多安全。 */
    private fun stubRecord(tracking: String) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = "trace request",
        trackingNumber = tracking,
        origin = ExpressOrigin.ENRICHMENT,
        timestamp = System.currentTimeMillis(),
    )

    fun describe(): String {
        val state = requests.snapshot()
        return "fetched=$fetched ok=${state.succeeded} cooling=${state.cooling} inFlight=${state.inFlight} " +
            "riskBlocked=${CainiaoTraceApi.riskBlocked()}"
    }
}
