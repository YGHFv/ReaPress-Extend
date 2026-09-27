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

/** 拼多多缓存扫描器（解析在 [PddCacheDiscovery]）：用宿主 uid 读 `cache/pdd_cache` 扫包裹投给模块进程，落库规矩（只填空不覆盖）在接收侧；宿主起来扫一次，parse 出口见快递实体按 10 分钟节流重扫；按「运单号 + 全部可变字段」记指纹，内容没变不重投。 */
internal object PddCacheScanner {

    private const val CACHE_DIR = "cache/pdd_cache"

    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private const val MAX_FILES = 2000

    private const val RESCAN_INTERVAL_MS = 10 * 60_000L

    private const val MAX_FINGERPRINTS = 512

    private val fingerprints = HashMap<String, String>()

    private val lastRescanAt = AtomicLong(0L)

    @Volatile private var scannedOnce = false

    fun scanOnInstallAsync() {
        if (scannedOnce) return
        scannedOnce = true
        scanAsync("install")
    }

    fun noteExpressActivity() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastRescanAt.get()
        if (last != 0L && now - last < RESCAN_INTERVAL_MS) return
        if (!lastRescanAt.compareAndSet(last, now)) return
        scanAsync("activity")
    }

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

        // 相对路径以宿主 data 目录为基准 —— 解析器按「路径含 pdd_cache」过滤，基准选浅了就没这个标记了。
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
        val contents = HashMap<String, String>()
        for (f in files) {
            contents[f.relativeTo(dataDir).path] =
                runCatching { f.readBytes().toString(Charsets.ISO_8859_1) }.getOrDefault("")
        }
        val packages = PddCacheDiscovery.parse(contents)

        var sent = 0
        for (pkg in packages) {
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
        ExpressRelaySender.sendHostProbe(
            context,
            "discovery($reason): files=${files.size} found=${packages.size} sent=$sent",
        )
    }

    private fun sendEnrichment(context: Context, pkg: PddCacheDiscovery.Package) {
        val record = ExpressRecord(
            sourcePackage = ExpressRelay.PDD_PACKAGE,
            rawText = "",
            trackingNumber = pkg.trackingNumber,
            orderSn = pkg.orderSn,
            // 订单日期 → arrivalAt：「这件是什么时候的」唯一可证明的时刻；老订单的未知件靠它获得归档资格。
            arrivalAt = pkg.orderSn?.let {
                PddCacheDiscovery.orderDateMillis(it, System.currentTimeMillis())
            },
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
