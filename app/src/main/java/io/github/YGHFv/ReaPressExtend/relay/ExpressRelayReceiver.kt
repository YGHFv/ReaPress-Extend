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

package io.github.YGHFv.ReaPressExtend.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.ExpressTraceCodec
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier
import io.github.YGHFv.ReaPressExtend.notification.ExpressDeliveryLedger
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.IntentTokenFetcher

/**
 * 接收被注入进程投来的事件，在本进程发替换通知、把包裹数据落库。
 * 通知必须由模块自己这个进程发（权限与渠道都按应用归属）；`onReceive` 内同步处理，几行即返回。
 */
class ExpressRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!RelayIngress.accept(context, intent)) return
        runCatching { handleAuthenticated(context, intent) }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "authenticated relay processing failed", it)
        }
    }

    private fun handleAuthenticated(context: Context, intent: Intent) {
        val app = context.applicationContext
        ModuleLogBuffer.attach(app)
        TraceCookieCache.attach(app)

        when (intent.action) {
            ExpressRelay.ACTION_DELIVER -> handleDeliver(app, intent)
            ExpressRelay.ACTION_ENRICH -> handleEnrich(app, intent)
            ExpressRelay.ACTION_INTERCEPTED -> handleIntercepted(app, intent)
            ExpressRelay.ACTION_COOKIE_SYNC -> handleCookieSync(app, intent)
            ExpressRelay.ACTION_IDENTITY_SYNC -> IdentityCodeFetcher.submitFromHost(intent)
            ExpressRelay.ACTION_PACKAGE_SYNC_REPORT -> HostRefreshRequester.onReport(app, intent)
            ExpressRelay.ACTION_HOST_QUERY_REPORT ->
                intent.getStringExtra(ExpressRelay.EXTRA_HOST_QUERY_REPORT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { report ->
                        CainiaoDirectFetcher.noteHostReport()
                        ModuleAndroidLog.legacy(LOG_TAG, "host self query: $report")
                        // 宿主侧 logcat 在 MIUI 上不可读，relay 落盘这行是本环唯一可读判据；
                        // 自查同步完成后才播报，且不节流——「界面此刻该是最新的」的最终保证。
                        ExpressChangeNotifier.notify(app)
                    }
                    ?: ModuleAndroidLog.error(LOG_TAG, "host query report with empty payload, dropped")
            ExpressRelay.ACTION_HOST_PROBE ->
                intent.getStringExtra(ExpressRelay.EXTRA_HOST_PROBE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ModuleAndroidLog.legacy(LOG_TAG, "host probe: $it") }
                    ?: ModuleAndroidLog.error(LOG_TAG, "host probe with empty payload, dropped")
            ExpressRelay.ACTION_WAKE_REPORT ->
                intent.getStringExtra(ExpressRelay.EXTRA_WAKE_REPORT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ModuleAndroidLog.legacy(LOG_TAG, "host wake: $it") }
                    ?: ModuleAndroidLog.error(LOG_TAG, "wake report with empty payload, dropped")
            ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED -> IntentTokenFetcher.submitFromSystemServer(app, intent)
            WatchdogReporter.ACTION_WATCHDOG_STATUS -> {
                WatchdogReporter.persistLocally(app, intent)
                val installed = intent.getBooleanExtra(WatchdogReporter.EXTRA_INSTALLED, false)
                ModuleAndroidLog.legacy(LOG_TAG, "watchdog state from system_server: installed=$installed")
            }
            else -> ModuleAndroidLog.legacy(LOG_TAG, "ignored action=${intent.action}")
        }
    }

    private fun handleDeliver(app: Context, intent: Intent) {
        val record = parseRecord(intent) ?: run {
            ModuleAndroidLog.error(LOG_TAG, "malformed relay intent, dropped")
            return
        }
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "relay received pkg=${record.sourcePackage} key=${record.dedupeKey} " +
                "status=${record.status} conf=${record.confidence}",
        )
        // 先落结构化记录再发通知：通知可能发不出去，但包裹信息要留住。
        val changed = ExpressRecordStore.upsert(app, record)
        // 双链路去重只挡通知、不挡落库。
        ExpressDeliveryLedger.deliver(record) {
            ExpressNotificationPoster.post(
                app,
                record,
                contentIntent = readContentIntent(intent),
                intentUri = readIntentUri(intent),
                intentToken = readIntentToken(intent),
            )
        }
        if (changed) ExpressChangeNotifier.notify(app)
    }

    /** 按「通知拦截」被吞掉的那条：只落一条审计，不发通知、不进包裹列表。 */
    private fun handleIntercepted(app: Context, intent: Intent) {
        val record = parseRecord(intent) ?: run {
            ModuleAndroidLog.error(LOG_TAG, "malformed intercepted intent, dropped")
            return
        }
        val category = intent.getStringExtra(ExpressRelay.EXTRA_CATEGORY).orEmpty()
        ExpressNotificationLog.recordIntercepted(
            app,
            record,
            category = category,
            contentIntent = readContentIntent(intent),
            intentUri = readIntentUri(intent),
            intentToken = readIntentToken(intent),
        )
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "intercepted recorded pkg=${record.sourcePackage} category=$category",
        )
        ExpressChangeNotifier.notify(app)
    }

    /** 宿主富化来的数据：不发通知但落记录（配不上已有通知就新建一条），提醒留给 handleDeliver。 */
    private fun handleEnrich(app: Context, intent: Intent) {
        val enrichment = parseRecord(intent) ?: run {
            ModuleAndroidLog.error(LOG_TAG, "malformed enrichment intent, dropped")
            return
        }
        val applied = ExpressRecordStore.enrich(app, enrichment)
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "enrichment received pkg=${enrichment.sourcePackage} " +
                "tn=${enrichment.trackingNumber} pickup=${enrichment.pickupCode} " +
                "station=${enrichment.station} applied=$applied",
        )
        // 自动更新模式对到站/派送中的件主动拉全轨迹；点击时获取模式留一条极低速保底。
        val packageSnapshot = enrichment.sourcePackage == ExpressRelay.HOST_PACKAGE &&
            intent.getBooleanExtra(ExpressRelay.EXTRA_PACKAGE_SNAPSHOT, false)
        // A package-list sync must not fan out into another network request for each row, including rows without codes.
        if (!packageSnapshot) {
            if (ExpressSettings.read(app).isTraceAutoFetch) {
                ModuleTraceFetcher.maybeAutoFetch(app, enrichment)
            } else {
                ModuleTraceFetcher.maybeBackstopFetch(app, enrichment)
            }
        }
        val hasTraceData = enrichment.trace.isNotEmpty() || enrichment.stationAddress != null
        if (applied && hasTraceData) {
            app.sendBroadcast(
                Intent(ExpressRelay.ACTION_TRACE_ARRIVED).setPackage(app.packageName),
            )
        }
        // 首页信号判据更宽（取件码/运单动态/驿站名/商品图被补上都要重读）；节流因宿主自查一次能连发十几条。
        if (applied) ExpressChangeNotifier.notify(app, throttled = true)
    }

    /** 宿主同步来的淘宝登录态 cookie：进缓存并落模块私有目录，不打内容日志；落盘后不广播内部信号。 */
    private fun handleCookieSync(app: Context, intent: Intent) {
        val cookie = intent.getStringExtra(ExpressRelay.EXTRA_COOKIE)?.takeIf { it.isNotBlank() }
        if (cookie == null) {
            // 有回执记回执；连回执都没有 = 广播没送到（宿主进程不在时系统静默丢弃，写不出任何日志）。
            intent.getStringExtra(ExpressRelay.EXTRA_COOKIE_ERROR)?.takeIf { it.isNotBlank() }
                ?.let { ModuleAndroidLog.legacy(LOG_TAG, "cookie sync 收到宿主回执：$it") }
                ?: ModuleAndroidLog.error(LOG_TAG, "cookie sync with empty payload, dropped")
            return
        }
        TraceCookieCache.attach(app)
        // UA 交给缓存一起存，这里不要再单独设一遍 preferredUa。
        TraceCookieCache.put(
            cookie,
            intent.getStringExtra(ExpressRelay.EXTRA_COOKIE_UA)?.takeIf { it.isNotBlank() },
        )
        ModuleAndroidLog.legacy(LOG_TAG, "cookie synced: ${TraceCookieCache.describe()}")
    }

    /** 原通知的点击跳转令牌。读失败只该少一个按钮，绝不能丢整条记录；正常情形就是 null。 */
    private fun readContentIntent(intent: Intent): android.app.PendingIntent? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                ExpressRelay.EXTRA_NOTIFICATION_INTENT,
                android.app.PendingIntent::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT) as? android.app.PendingIntent
        }
    }.getOrNull()

    /** 同一跳转的可落盘快照；读失败同理只少一个按钮。 */
    private fun readIntentUri(intent: Intent): String? = runCatching {
        intent.getStringExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 令牌在 system_server 侧的寄存句柄；读失败同理。 */
    private fun readIntentToken(intent: Intent): String? = runCatching {
        intent.getStringExtra(ExpressRelay.EXTRA_INTENT_TOKEN)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseRecord(intent: Intent): ExpressRecord? {
        val sourcePackage = intent.getStringExtra(ExpressRelay.EXTRA_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() } ?: return null
        val text = intent.getStringExtra(ExpressRelay.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return null

        return ExpressRecord(
            sourcePackage = sourcePackage,
            rawText = text,
            trackingNumber = intent.getStringExtra(ExpressRelay.EXTRA_TRACKING)?.takeIf { it.isNotBlank() },
            courier = runCatching {
                Courier.valueOf(intent.getStringExtra(ExpressRelay.EXTRA_COURIER).orEmpty())
            }.getOrDefault(Courier.UNKNOWN),
            pickupCode = intent.getStringExtra(ExpressRelay.EXTRA_PICKUP_CODE)?.takeIf { it.isNotBlank() },
            pickupCodeObservedAt = if (intent.action == ExpressRelay.ACTION_ENRICH && sourcePackage == ExpressRelay.HOST_PACKAGE)
                intent.getLongExtra(ExpressRelay.EXTRA_PICKUP_OBSERVED_AT, 0L).coerceIn(0L, System.currentTimeMillis()) else 0L,
            station = intent.getStringExtra(ExpressRelay.EXTRA_STATION)?.takeIf { it.isNotBlank() },
            status = runCatching {
                ExpressStatus.valueOf(intent.getStringExtra(ExpressRelay.EXTRA_STATUS).orEmpty())
            }.getOrDefault(ExpressStatus.UNKNOWN),
            title = intent.getStringExtra(ExpressRelay.EXTRA_TITLE),
            origin = runCatching {
                ExpressOrigin.valueOf(intent.getStringExtra(ExpressRelay.EXTRA_ORIGIN).orEmpty())
            }.getOrDefault(ExpressOrigin.NOTIFICATION),
            matchedKeywords = intent.getStringArrayExtra(ExpressRelay.EXTRA_KEYWORDS)?.toList().orEmpty(),
            confidence = intent.getIntExtra(ExpressRelay.EXTRA_CONFIDENCE, 0),
            timestamp = intent.getLongExtra(ExpressRelay.EXTRA_TIMESTAMP, 0L),
            platform = intent.getStringExtra(ExpressRelay.EXTRA_PLATFORM)?.takeIf { it.isNotBlank() },
            goodsName = intent.getStringExtra(ExpressRelay.EXTRA_GOODS_NAME)?.takeIf { it.isNotBlank() },
            arrivalAt = intent.getLongExtra(ExpressRelay.EXTRA_ARRIVAL_AT, 0L).takeIf { it > 0L },
            logisticsDetail = intent.getStringExtra(ExpressRelay.EXTRA_LOGISTICS_DETAIL)?.takeIf { it.isNotBlank() },
            stationHours = intent.getStringExtra(ExpressRelay.EXTRA_STATION_HOURS)?.takeIf { it.isNotBlank() },
            // 坐标用 NaN 传「没有」，进了模型就分不出「0,0」和「没给」。
            stationLat = intent.getDoubleExtra(ExpressRelay.EXTRA_STATION_LAT, Double.NaN)
                .takeIf { !it.isNaN() },
            stationLng = intent.getDoubleExtra(ExpressRelay.EXTRA_STATION_LNG, Double.NaN)
                .takeIf { !it.isNaN() },
            phoneTail = intent.getStringExtra(ExpressRelay.EXTRA_PHONE_TAIL)?.takeIf { it.isNotBlank() },
            parcelTail = intent.getStringExtra(ExpressRelay.EXTRA_PARCEL_TAIL)?.takeIf { it.isNotBlank() },
            previousPickupCode = intent.getStringExtra(ExpressRelay.EXTRA_PREVIOUS_PICKUP_CODE)
                ?.takeIf { it.isNotBlank() },
            stationAddress = intent.getStringExtra(ExpressRelay.EXTRA_STATION_ADDRESS)?.takeIf { it.isNotBlank() },
            goodsImage = intent.getStringExtra(ExpressRelay.EXTRA_GOODS_IMAGE)?.takeIf { it.isNotBlank() },
            // 解码失败退化为空表（「没有轨迹」）而不是丢整条记录。
            trace = ExpressTraceCodec.decode(intent.getStringExtra(ExpressRelay.EXTRA_TRACE)),
        )
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
    }
}
