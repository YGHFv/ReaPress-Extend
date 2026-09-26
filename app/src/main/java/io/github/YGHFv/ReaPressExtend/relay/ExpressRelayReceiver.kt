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
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore

/**
 * 接收被注入进程投来的事件，在本进程发替换通知、把包裹数据落库。
 *
 * ## 为什么必须由模块自己的进程发通知
 *
 * `POST_NOTIFICATIONS` 是**按应用**授予的，通知渠道也归发起的应用所有。从 system_server
 * 或以宿主 App 的身份发，通知会挂在别人名下、用别人的渠道和图标 —— 用户看到的还是原来那个
 * App 的通知，替换就失去意义。所以这里绕一圈：system_server 判定 → 广播 → 模块进程发。
 *
 * ## 为什么在 onReceive 里同步处理
 *
 * `onReceive` 有 10 秒上限，发一条通知远不到。开 Service 或 goAsync 反而引入新的失败点
 * （后台启动限制、进程被冻结）。这里就是「收广播 → 落库 / 发通知」几行，做完即返回。
 */
class ExpressRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // 日志缓冲要绑定落盘位置，否则模块进程的日志重启就丢。
        ModuleLogBuffer.attach(app)

        when (intent.action) {
            ExpressRelay.ACTION_DELIVER -> handleDeliver(app, intent)
            ExpressRelay.ACTION_ENRICH -> handleEnrich(app, intent)
            ExpressRelay.ACTION_COOKIE_SYNC -> handleCookieSync(app, intent)
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
        // 先落结构化记录再发通知：通知可能因权限/系统限制发不出去，但包裹信息要留住 ——
        // 首页靠它聚合展示，丢一次用户就少一个包裹。
        ExpressRecordStore.upsert(app, record)
        ExpressNotificationPoster.post(app, record)
    }

    /**
     * 宿主进程富化来的包裹数据。
     *
     * **不发通知，但会落记录**：配得上已有通知就补字段，配不上就新建一条 —— 宿主已经知道的
     * 包裹不该因为「通知没拦到」而从首页消失。规则和取舍见 [ExpressRecordStore.enrich]。
     *
     * 不发通知是刻意的：富化的触发时机是**用户自己打开了菜鸟的包裹详情页**，此刻弹通知既没
     * 必要（人已经在宿主里了）又烦人；而 `getData()` 一个详情页会走好几次，宿主侧的去重窗口
     * 只压得住「同状态重复」，压不住「每次进详情页都提醒一次」。提醒留给真正的事件源
     * （[handleDeliver]），这里只负责让数据不丢。
     */
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
        // 「自动更新」模式：富化到达时对到站 / 派送中的件主动拉全轨迹。
        // 「点击时获取」模式也留一条**极低速保底**（每 3 分钟最多一件）：菜鸟运行时
        // 数据自己慢慢补齐，详情页多半在点开前就有数了。两种模式都受同一套引擎闸门
        // 约束（成功表 / 冷却 / 风控退避），叠加不会重复请求。设置只在模块进程读，
        // hook 侧不掺和（拉取已收拢到模块，hook 只同步 cookie + 兜底 receiver）。
        if (ExpressSettings.read(app).isTraceAutoFetch) {
            ModuleTraceFetcher.maybeAutoFetch(app, enrichment)
        } else {
            ModuleTraceFetcher.maybeBackstopFetch(app, enrichment)
        }
        // 详情页正等着的信号（模块进程内部广播）：本进程直拉、自动拉、以及菜鸟兜底拉
        // 回来的结果都汇到这条路上 —— UI 收到就重读。只在真有轨迹数据时发，
        // 否则每次普通富化都会让详情页白重读一遍。
        if (applied && (enrichment.trace.isNotEmpty() || enrichment.stationAddress != null)) {
            app.sendBroadcast(
                Intent(ExpressRelay.ACTION_TRACE_ARRIVED).setPackage(app.packageName),
            )
        }
    }

    /**
     * 宿主同步过来的淘宝登录态 cookie（[ExpressRelay.ACTION_COOKIE_SYNC]）。
     *
     * 只进内存缓存（[TraceCookieCache]），**不落盘、不打内容日志** —— 它是登录态，
     * 任何静态留痕都会放大泄露面。extras 为空直接忽略：宿主侧读不到时不会发，
     * 防御性起见这里也拦一道。
     */
    private fun handleCookieSync(app: Context, intent: Intent) {
        val cookie = intent.getStringExtra(ExpressRelay.EXTRA_COOKIE)?.takeIf { it.isNotBlank() }
        if (cookie == null) {
            ModuleAndroidLog.error(LOG_TAG, "cookie sync with empty payload, dropped")
            return
        }
        TraceCookieCache.put(
            cookie,
            intent.getStringExtra(ExpressRelay.EXTRA_COOKIE_UA)?.takeIf { it.isNotBlank() },
        )
        // UA 交给请求引擎：让 MTOP 请求的 UA 与这批登录态的画像（菜鸟 WebView）一致，
        // 浏览器 UA 配菜鸟 cookie 是风控眼里的异常组合。
        CainiaoTraceApi.preferredUa = TraceCookieCache.hostUa
        ModuleAndroidLog.legacy(LOG_TAG, "cookie synced: ${TraceCookieCache.describe()}")
    }

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
            // 发送端用 0 表示「没有」（见 ExpressRelaySender）。
            arrivalAt = intent.getLongExtra(ExpressRelay.EXTRA_ARRIVAL_AT, 0L).takeIf { it > 0L },
            logisticsDetail = intent.getStringExtra(ExpressRelay.EXTRA_LOGISTICS_DETAIL)?.takeIf { it.isNotBlank() },
            stationHours = intent.getStringExtra(ExpressRelay.EXTRA_STATION_HOURS)?.takeIf { it.isNotBlank() },
            phoneTail = intent.getStringExtra(ExpressRelay.EXTRA_PHONE_TAIL)?.takeIf { it.isNotBlank() },
            stationAddress = intent.getStringExtra(ExpressRelay.EXTRA_STATION_ADDRESS)?.takeIf { it.isNotBlank() },
            goodsImage = intent.getStringExtra(ExpressRelay.EXTRA_GOODS_IMAGE)?.takeIf { it.isNotBlank() },
            // 轨迹编在字符串里，解码失败退化为空表（「没有轨迹」）而不是丢整条记录。
            trace = ExpressTraceCodec.decode(intent.getStringExtra(ExpressRelay.EXTRA_TRACE)),
        )
    }

    private companion object {
        const val LOG_TAG = "ReaPress"
    }
}
