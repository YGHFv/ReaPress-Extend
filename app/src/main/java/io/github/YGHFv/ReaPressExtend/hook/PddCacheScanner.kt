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

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.PddCacheDiscovery
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 拼多多缓存扫描器 —— 「发现」这条腿的执行端（解析在 [PddCacheDiscovery]，纯函数）。
 *
 * ## 它做什么
 *
 * 模块跑在拼多多进程里，用宿主 uid 读宿主自己的 HTTP 响应缓存
 * （`cache/pdd_cache` 目录；与菜鸟登录态「读宿主私有目录」是同一条合法性依据）。
 * 扫到的每个包裹构造成 [ExpressRecord] 走 [ExpressRelaySender.sendEnrichment]
 * 投给模块进程 —— 那条路的接收侧（`ExpressRecordStore.enrich`）已经带着全套
 * 落库规矩：`hasIdentity` 准入（运单号是强标识，本来源必然满足）、
 * 只填空不覆盖、配不上才新建。**发现侧不需要自己的存储，也不该有。**
 *
 * ## 触发节奏
 *
 * - 宿主进程起来时扫一次（缓存里躺着的就是上次会话的全部快递数据）；
 * - parse 出口见到快递实体时（[noteExpressActivity]）**节流重扫** —— 用户在
 *   拼多多里看快递时缓存会被刷新，那是重扫的正确时机；10 分钟一挡足够新，
 *   且对宿主零压力（MIN_PRIORITY 后台线程 + 文件数/大小封顶）。
 *
 * ## 幂等
 *
 * 每个包裹按「运单号 + 全部可变字段」记指纹，内容没变就不重投 ——
 * 模块侧对重复富化本来就不改存储，但少发一条广播就少吵醒一次接收端。
 */
internal object PddCacheScanner {

    /** 缓存目录相对宿主 data 目录的位置。 */
    private const val CACHE_DIR = "cache/pdd_cache"

    /** 单文件读取上限。快递卡片缓存都是几十 KB 级，超过的几乎肯定不是。 */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 单次扫描的文件数上限（缓存会被宿主自己清理，正常远到不了这个数）。 */
    private const val MAX_FILES = 2000

    /** [noteExpressActivity] 的重扫最小间隔。 */
    private const val RESCAN_INTERVAL_MS = 10 * 60_000L

    /** 指纹表上限 —— 超额整体清空，代价只是把当前缓存里的件重投一遍（模块侧幂等）。 */
    private const val MAX_FINGERPRINTS = 512

    /** 已投递过的内容指纹：运单号 → 「orderSn|pickupCode|phoneTail」。 */
    private val fingerprints = HashMap<String, String>()

    /** 上次节流重扫的时刻（elapsedRealtime）。 */
    private val lastRescanAt = AtomicLong(0L)

    @Volatile private var scannedOnce = false

    /** 宿主进程起来后的首次扫描（等 Context 就绪 —— install 时机早于 Application#onCreate）。 */
    fun scanOnInstallAsync() {
        if (scannedOnce) return
        scannedOnce = true
        scanAsync("install")
    }

    /**
     * parse 出口见到了快递实体 —— 用户正在看拼多多快递页，缓存可能刚被刷新。
     * 按节流窗口决定要不要重扫。
     */
    fun noteExpressActivity() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastRescanAt.get()
        if (last != 0L && now - last < RESCAN_INTERVAL_MS) return
        if (!lastRescanAt.compareAndSet(last, now)) return
        scanAsync("activity")
    }

    /** 起后台线程做一次完整扫描。扫描失败只留日志，绝不影响宿主。 */
    private fun scanAsync(reason: String) {
        Thread({
            runCatching { scan(reason) }
                .onFailure { XposedBridge.logError("pdd cache scan failed: $reason", it) }
        }, "pdd-cache-scan-$reason").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun scan(reason: String) {
        val context = runCatching {
            var ctx: Context? = null
            repeat(20) {
                ctx = runCatching { HostContextHolder.acquire() }.getOrNull()
                if (ctx != null) return@repeat
                Thread.sleep(500)
            }
            ctx
        }.getOrNull() ?: run {
            XposedBridge.logAlways("pdd cache scan: no host context after 10s, dropped ($reason)")
            return
        }

        // 相对路径以宿主 data 目录为基准 —— 解析器按「路径含 pdd_cache」过滤，
        // 基准选浅了（比如 cacheDir 自己）路径里就没有这个标记了。
        val dataDir = File(context.getApplicationInfo().dataDir)
        val cacheDir = File(dataDir, CACHE_DIR)
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.length() in 1..MAX_FILE_BYTES }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_FILES)
            .orEmpty()
        if (files.isEmpty()) {
            XposedBridge.logAlways("pdd cache scan ($reason): cache dir empty, nothing to discover")
            return
        }

        // ISO_8859_1 读入 —— mojibake 的转回在解析器里做（见 PddCacheDiscovery 的输入约定）。
        // 路径以宿主 data 目录为基准（`cache/pdd_cache/…`），解析器按这个标记过滤。
        val contents = HashMap<String, String>()
        for (f in files) {
            contents[f.relativeTo(dataDir).path] =
                runCatching { f.readBytes().toString(Charsets.ISO_8859_1) }.getOrDefault("")
        }
        val packages = PddCacheDiscovery.parse(contents)

        var sent = 0
        for (pkg in packages) {
            // 指纹必须覆盖所有会变的字段：状态/驿站/公司/提示升级了也要重投，
            // 否则模块侧已落库的那条永远停在旧值。（上一版只指纹三样，升级解析器后
            // 全量指纹必然失配 —— 正好借这次把库里那批「未知」旧记录刷一遍。）
            val fp = listOf(
                pkg.orderSn.orEmpty(),
                pkg.pickupCode.orEmpty(),
                pkg.phoneTail.orEmpty(),
                pkg.status.name,
                pkg.courier.name,
                pkg.station.orEmpty(),
                pkg.logisticsDetail.orEmpty(),
            ).joinToString("|")
            val unchanged = synchronized(fingerprints) {
                if (fingerprints[pkg.trackingNumber] == fp) {
                    true
                } else {
                    if (fingerprints.size > MAX_FINGERPRINTS) fingerprints.clear()
                    fingerprints[pkg.trackingNumber] = fp
                    false
                }
            }
            if (unchanged) continue
            sendEnrichment(context, pkg)
            sent++
        }
        XposedBridge.logAlways(
            "pdd cache scan ($reason): files=${files.size} discovered=${packages.size} sent=$sent",
        )
        // 一句可观测的总结送回模块侧（探索期判据链的延续：宿主侧结论不落字就等于没发生）。
        ExpressRelaySender.sendHostProbe(
            context,
            "discovery($reason): files=${files.size} found=${packages.size} sent=$sent",
        )
    }

    /**
     * 一个发现的包裹 → [ExpressRecord] → 投给模块进程落库。
     *
     * 字段取舍（**只送能证明的**）：
     * - 运单号 = 强标识，`hasIdentity` 必然通过；
     * - 取件码 / 手机尾号 / 订单号 / 驿站名+地址 / 取件提示按解析器的结论原样带上；
     * - 快递公司优先用 `tracking_num` 值里的中文前缀（宿主自己写好的），认不出再按
     *   运单号前缀兜底一次（`JT…` = 极兔这类字母前缀；纯数字号段认不出就 UNKNOWN）；
     * - 状态来自分栏/提示词（见 [PddCacheDiscovery] 类注释的刻意留白），推不出 UNKNOWN。
     */
    private fun sendEnrichment(context: Context, pkg: PddCacheDiscovery.Package) {
        val record = ExpressRecord(
            sourcePackage = ExpressRelay.PDD_PACKAGE,
            // rawText 留空：缓存里没有「通知原文」这回事，之前那串「拼多多取快递缓存（订单 …）」
            // 是探索期的诊断副标题，卡片退回原文首行时显示的就是它。有价值的字段
            // （驿站/取件码/动态）各有自己的格子，不需要靠 raw 携带。
            rawText = "",
            trackingNumber = pkg.trackingNumber,
            courier = if (pkg.courier != Courier.UNKNOWN) {
                pkg.courier
            } else {
                Courier.fromTrackingNumber(pkg.trackingNumber)
            },
            pickupCode = pkg.pickupCode,
            phoneTail = pkg.phoneTail,
            station = pkg.station,
            stationAddress = pkg.stationAddress,
            logisticsDetail = pkg.logisticsDetail,
            platform = "拼多多",
            status = pkg.status,
            origin = ExpressOrigin.ENRICHMENT,
            confidence = 100,
            timestamp = System.currentTimeMillis(),
        )
        ExpressRelaySender.sendEnrichment(record, context)
    }
}
