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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressTraceCodec
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 被注入进程 → 模块 App 进程的事件投递。
 * 广播 + 显式组件 + `FLAG_INCLUDE_STOPPED_PACKAGES`：模块装完长期 stopped，这个 flag 是必需的。
 */
internal object ExpressRelaySender {

    @Volatile private var contextFailureLogged = false

    /** system_server 侧投递。false 时 [SystemServerHook.deliver] 不可再吞原通知（否则静默丢通知）。 */
    fun send(
        record: ExpressRecord,
        thisObject: Any? = null,
        contentIntent: PendingIntent? = null,
        intentUri: String? = null,
        intentToken: String? = null,
    ): Boolean {
        // 运行期从 NMS 实例取 context 并缓存 —— 这是 system_server 里唯一可靠的途径。
        SystemContextHolder.upgrade(thisObject)
        val context = SystemContextHolder.acquire() ?: run {
            logContextFailure()
            return false
        }
        return deliver(context, record, ExpressRelay.ACTION_DELIVER, contentIntent, intentUri, intentToken)
    }

    /** 分类拦截的审计投递；未能提交到认证通道时返回 false，让调用方保留原通知。 */
    fun sendIntercepted(
        record: ExpressRecord,
        thisObject: Any? = null,
        category: String,
        contentIntent: PendingIntent? = null,
        intentUri: String? = null,
        intentToken: String? = null,
    ): Boolean {
        SystemContextHolder.upgrade(thisObject)
        val context = SystemContextHolder.acquire() ?: run {
            logContextFailure()
            return false
        }
        return deliver(context, record, ExpressRelay.ACTION_INTERCEPTED, contentIntent, intentUri, intentToken) {
            putExtra(ExpressRelay.EXTRA_CATEGORY, category)
        }
    }

    /** 宿主 App 进程侧富化投递。 */
    fun sendEnrichment(record: ExpressRecord, context: Context?): Boolean {
        val resolved = context ?: HostContextHolder.acquire()
        if (resolved == null) {
            logContextFailure()
            return false
        }
        return deliver(
            resolved,
            record,
            ExpressRelay.ACTION_ENRICH,
            contentIntent = null,
            intentUri = null,
            intentToken = null,
        )
    }

    /** 宿主自查结论送回模块进程；MIUI logcat 不可读，结论必须 relay 落盘。只送结论，不送包裹内容。 */
    fun sendHostQueryReport(context: Context, text: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_HOST_QUERY_REPORT)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ExpressRelay.EXTRA_HOST_QUERY_REPORT, text)
            if (!AuthenticatedRelaySender.send(context, intent)) return
            XposedBridge.logAlways("host self query report sent: $text")
        }.onFailure { XposedBridge.logError("host self query report failed", it) }
    }

    /** 同上送回拼多多探针结论；不共用 action（那条接收侧会顺手给菜鸟直连兜底报到）。 */
    fun sendHostProbe(context: Context, text: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_HOST_PROBE)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ExpressRelay.EXTRA_HOST_PROBE, text)
            if (!AuthenticatedRelaySender.send(context, intent)) return
            XposedBridge.logAlways("host probe report sent: $text")
        }.onFailure { XposedBridge.logError("host probe report failed", it) }
    }

    /** 唤醒销代发结果回执；这一跳失败的形态是静默，故注册播报一次、每次代发给回执。 */
    fun sendWakeReport(context: Context, text: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_WAKE_REPORT)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ExpressRelay.EXTRA_WAKE_REPORT, text)
            if (!AuthenticatedRelaySender.send(context, intent)) return
            XposedBridge.logAlways("wake report sent: $text")
        }.onFailure { XposedBridge.logError("wake report failed", it) }
    }

    /** 把模块索要的跳转令牌还给它。token 为 null 也要发：空手回执把「通道没注册上」和「没找到」分开。 */
    fun sendIntentToken(context: Context, entryId: String, token: PendingIntent?) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ExpressRelay.EXTRA_INTENT_ENTRY_ID, entryId)
            token?.let { intent.putExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT, it) }
            if (!AuthenticatedRelaySender.send(context, intent)) return
            XposedBridge.logAlways("intent token returned: found=${token != null}")
        }.onFailure { XposedBridge.logError("intent token return failed", it) }
    }

    /** cookie 同步的最小间隔。 */
    private const val COOKIE_SYNC_INTERVAL_MS = 30 * 60_000L

    @Volatile private var lastCookieSyncAt = 0L

    /** 宿主侧把淘宝登录态 cookie 同步给模块进程；force 供模块主动索要时跳过节流。 */
    fun sendCookieSync(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCookieSyncAt < COOKIE_SYNC_INTERVAL_MS) return
        runCatching {
            val cookie = HostCredentialSource.cookie(context)
            val intent = Intent(ExpressRelay.ACTION_COOKIE_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            if (cookie.isNullOrBlank()) {
                // 读不到也发一条只带原因的回执：静默与「宿主没刷新过首页」完全同形、无法区分。
                intent.putExtra(
                    ExpressRelay.EXTRA_COOKIE_ERROR,
                    // 具体原因由读取侧给出，这里只兜住「连原因都没记下来」的情况。
                    HostCredentialSource.lastReason
                        .ifBlank { "宿主侧没有可用登录态（读取方没记下原因）" },
                )
                if (!AuthenticatedRelaySender.send(context, intent)) return
                lastCookieSyncAt = now
                XposedBridge.logAlways("cookie sync: 宿主侧没有可用登录态，已发回执")
                return
            }
            intent.putExtra(ExpressRelay.EXTRA_COOKIE, cookie)
                // UA 随 cookie 一起送：让 MTOP 请求的 UA 与 cookie 画像一致。
                .putExtra(ExpressRelay.EXTRA_COOKIE_UA, webviewUa(context))
            if (!AuthenticatedRelaySender.send(context, intent)) return
            lastCookieSyncAt = now
            XposedBridge.log("cookie synced to module (${cookie.length} chars, force=$force)")
        }.onFailure { XposedBridge.logError("cookie sync failed", it) }
    }

    private fun webviewUa(context: Context): String? = runCatching {
        android.webkit.WebSettings.getDefaultUserAgent(context)
    }.getOrNull()

    private fun logContextFailure() {
        if (!contextFailureLogged) {
            contextFailureLogged = true
            XposedBridge.logError("cannot obtain context for relay — events will be dropped")
        }
    }

    /** 统一投递出口。true 只代表认证广播已提交，不代表最终送达（系统仍可能静默丢弃）。 */
    private fun deliver(
        context: Context,
        record: ExpressRecord,
        action: String,
        contentIntent: PendingIntent?,
        intentUri: String?,
        intentToken: String?,
        extra: (Intent.() -> Unit)? = null,
    ): Boolean {
        return runCatching {
            val intent = buildIntent(record, action)
            contentIntent?.let { intent.putExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT, it) }
            intentUri?.takeIf { it.isNotBlank() }
                ?.let { intent.putExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI, it) }
            intentToken?.takeIf { it.isNotBlank() }
                ?.let { intent.putExtra(ExpressRelay.EXTRA_INTENT_TOKEN, it) }
            extra?.invoke(intent)
            intent.setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            if (!AuthenticatedRelaySender.send(context, intent)) return false

            XposedBridge.log(
                "relayed to module: action=${action.substringAfterLast('.')} " +
                    "pkg=${record.sourcePackage} key=${record.dedupeKey} status=${record.status}",
            )
            true
        }.getOrElse {
            XposedBridge.logError("relay failed", it)
            false
        }
    }

    private fun buildIntent(record: ExpressRecord, action: String): Intent =
        Intent(action).apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ExpressRelay.EXTRA_SOURCE_PACKAGE, record.sourcePackage)
            putExtra(ExpressRelay.EXTRA_TITLE, record.title)
            putExtra(ExpressRelay.EXTRA_TEXT, record.rawText)
            putExtra(ExpressRelay.EXTRA_TRACKING, record.trackingNumber)
            putExtra(ExpressRelay.EXTRA_COURIER, record.courier.name)
            putExtra(ExpressRelay.EXTRA_PICKUP_CODE, record.pickupCode)
            putExtra(ExpressRelay.EXTRA_STATION, record.station)
            putExtra(ExpressRelay.EXTRA_STATUS, record.status.name)
            putExtra(ExpressRelay.EXTRA_CONFIDENCE, record.confidence)
            putExtra(ExpressRelay.EXTRA_KEYWORDS, record.matchedKeywords.toTypedArray())
            putExtra(ExpressRelay.EXTRA_TIMESTAMP, record.timestamp)
            putExtra(ExpressRelay.EXTRA_PLATFORM, record.platform)
            putExtra(ExpressRelay.EXTRA_GOODS_NAME, record.goodsName)
            putExtra(ExpressRelay.EXTRA_ARRIVAL_AT, record.arrivalAt ?: 0L)
            putExtra(ExpressRelay.EXTRA_LOGISTICS_DETAIL, record.logisticsDetail)
            putExtra(ExpressRelay.EXTRA_STATION_HOURS, record.stationHours)
            // 坐标用 NaN 表示「没有」：接收端会把 0 当有效值。
            putExtra(ExpressRelay.EXTRA_STATION_LAT, record.stationLat ?: Double.NaN)
            putExtra(ExpressRelay.EXTRA_STATION_LNG, record.stationLng ?: Double.NaN)
            putExtra(ExpressRelay.EXTRA_STATION_ADDRESS, record.stationAddress)
            putExtra(ExpressRelay.EXTRA_GOODS_IMAGE, record.goodsImage)
            putExtra(
                ExpressRelay.EXTRA_TRACE,
                record.trace.takeIf { it.isNotEmpty() }?.let { ExpressTraceCodec.encode(it) },
            )
            putExtra(ExpressRelay.EXTRA_PHONE_TAIL, record.phoneTail)
            putExtra(ExpressRelay.EXTRA_PARCEL_TAIL, record.parcelTail)
            putExtra(ExpressRelay.EXTRA_PREVIOUS_PICKUP_CODE, record.previousPickupCode)
            putExtra(ExpressRelay.EXTRA_ORIGIN, record.origin.name)
        }
}
