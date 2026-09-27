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
import android.os.SystemClock
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.ExpressTraceCodec
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationPoster
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.notification.IntentTokenFetcher

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
        // 登录态缓存同理要绑一次：进程重启后内存是空的，得把落盘的那份读回来
        // （轨迹拉取靠它，见 TraceCookieCache —— 身份码那条路已经不用 cookie 了）。
        TraceCookieCache.attach(app)

        when (intent.action) {
            ExpressRelay.ACTION_DELIVER -> handleDeliver(app, intent)
            ExpressRelay.ACTION_ENRICH -> handleEnrich(app, intent)
            // 按「通知拦截」设置被吞掉的那条通知。**只落一条审计**，不发通知、不进包裹列表 ——
            // 用户勾那个开关要的正是「这类别再出现」，把它塞进首页等于换个地方出现。
            ExpressRelay.ACTION_INTERCEPTED -> handleIntercepted(app, intent)
            ExpressRelay.ACTION_COOKIE_SYNC -> handleCookieSync(app, intent)
            // 身份码：菜鸟用**它自己的会话**取来的结果（应答 / 主动推送 / 失败回执三种来路）。
            // 到这里只需要「翻译成 CainiaoIdentityResult 交给等待方」，界面的等待逻辑在
            // IdentityCodeFetcher 那一侧。
            ExpressRelay.ACTION_IDENTITY_SYNC -> IdentityCodeFetcher.submitFromHost(intent)
            // 宿主自查本地包裹表之后的一句播报。**只记日志**（外加给直连兜底报个到）——
            // 不碰别的状态。它是这一环的唯一可读判据（宿主侧 logcat 在 MIUI 上读不出来，
            // 见 ExpressRelay.ACTION_HOST_QUERY_REPORT 的说明），
            // 所以哪怕将来觉得这行「没什么用」也别删：删掉之后
            // 「打开模块到底有没有真的去查」就又回到只能猜的状态了。
            //
            // [CainiaoDirectFetcher.noteHostReport] 是 2026-09-27 加的：这一行同时是
            // 「宿主**还活着**」的证据 —— 打开模块后它没来，才轮到直连兜底上场（见那个类的
            // 类注释）。**别把它挪到 `if` 里**：这条播报只在这一个地方到达，挪了就再也报不上到。
            ExpressRelay.ACTION_HOST_QUERY_REPORT ->
                intent.getStringExtra(ExpressRelay.EXTRA_HOST_QUERY_REPORT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { report ->
                        CainiaoDirectFetcher.noteHostReport()
                        ModuleAndroidLog.legacy(LOG_TAG, "host self query: $report")
                        // 那一批已经全部投递并落库了（自查是同步的，宿主是调用返回之后才发的播报）。
                        // 首页若是刚打开就渲染的，此刻手里的还是旧快照 —— 叫它重读一遍，
                        // 否则功能成了用户也看不出来。理由见 ACTION_RECORDS_CHANGED 的注释。
                        //
                        // 这一条**不节流**：它是「界面此刻该是最新的」的最终保证 —— 富化那十几条
                        // 被节流掉的那些，最终状态由它兜住。
                        notifyRecordsChanged(app, throttled = false)
                    }
                    ?: ModuleAndroidLog.error(LOG_TAG, "host query report with empty payload, dropped")
            // 新接平台（拼多多）的字段探针：**只记日志**，不碰任何状态。
            //
            // 与上面那条分开的理由见 ExpressRelay.ACTION_HOST_PROBE 的注释（上面那条会顺手给
            // 菜鸟直连兜底报到，按到拼多多头上是错的）。这里也**不**发 RECORDS_CHANGED ——
            // 探针不落任何记录，界面没什么可重读的。
            ExpressRelay.ACTION_HOST_PROBE ->
                intent.getStringExtra(ExpressRelay.EXTRA_HOST_PROBE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ModuleAndroidLog.legacy(LOG_TAG, "host probe: $it") }
                    ?: ModuleAndroidLog.error(LOG_TAG, "host probe with empty payload, dropped")
            // system_server 对「借它身份代发唤醒销」的应答（[ACTION_WAKE_REQUEST]）：
            // 通道就绪播报一次、每次代发一行回执。**只记日志**。
            //
            // 这两行是「必须打开菜鸟才能刷新」那件事的唯一判据链中间那环：
            //   `已请系统代发`（我们发出去了）→ `系统代发通道已就绪` / `系统代发唤醒销（…）`
            //   （system_server 真的发了）→ `host self query: rows=`（宿主被拉起来自查了）。
            // 少了中间这一环，「通道没注册上」和「销发了但 ROM 还是拦」在日志上长得一样。
            ExpressRelay.ACTION_WAKE_REPORT ->
                intent.getStringExtra(ExpressRelay.EXTRA_WAKE_REPORT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ModuleAndroidLog.legacy(LOG_TAG, "host wake: $it") }
                    ?: ModuleAndroidLog.error(LOG_TAG, "wake report with empty payload, dropped")
            // system_server 把模块索要的跳转令牌还回来了（[ACTION_INTENT_RESOLVE_REQUEST] 的应答）。
            // 收到就填进进程内的令牌表，然后叫界面重算一次措辞 —— 见 IntentTokenFetcher。
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
        // 先落结构化记录再发通知：通知可能因权限/系统限制发不出去，但包裹信息要留住 ——
        // 首页靠它聚合展示，丢一次用户就少一个包裹。
        val changed = ExpressRecordStore.upsert(app, record)
        ExpressNotificationPoster.post(
            app,
            record,
            contentIntent = readContentIntent(intent),
            intentUri = readIntentUri(intent),
            intentToken = readIntentToken(intent),
        )
        // 界面正开着时要能看见这一条（用户收到通知后没退出模块是常态）。
        // 这条**不节流**：通知是一对一的、分钟级的事件，不会十几条一起来 ——
        // 不值得为极端的批量情形牺牲「新包裹立刻出现在首页」。
        if (changed) notifyRecordsChanged(app, throttled = false)
    }

    /**
     * 按「通知拦截」设置被吞掉的那条通知（[ExpressRelay.ACTION_INTERCEPTED]）。
     *
     * ## 只落审计，别的什么都不做
     *
     * - **不发通知**：用户勾这个分类要的正是「别再出现」，换个名字再发一条等于没做；
     * - **不进包裹列表**（`ExpressRecordStore`）：那些件如果真的在途，宿主自己会富化进来；
     *   而把一条被明确拒绝的通知变成首页卡片，等于把用户的选择绕过去。
     *
     * ## 为什么现在才有
     *
     * 拦截以前是**纯静默**的：模块侧连一行记录都没有，唯一判据是 LSPosed 日志里的
     * `EXPRESS DROPPED`。用户想核对「我到底拦掉了什么」只能去翻系统日志（2026-09-27 要求补上）。
     *
     * 载荷与投递那条同构：原文（[ExpressNotificationLog.Entry.originTitle] / `originText`）、
     * 分类、跳转令牌与它的快照。分类认不出时只少一行说明，记录照样留。
     */
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
        // 叫界面重读一遍。**这一条不能省**：用户勾上开关之后多半会盯着模块看有没有生效，
        // 而拦截的后果是「通知不出现」—— 界面不刷的话他连「拦到了没有」都看不到，
        // 只能退出再进来。不节流：拦截是逐个通知发生的事，不是成批灌进来的。
        notifyRecordsChanged(app, throttled = false)
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
        val hasTraceData = enrichment.trace.isNotEmpty() || enrichment.stationAddress != null
        if (applied && hasTraceData) {
            app.sendBroadcast(
                Intent(ExpressRelay.ACTION_TRACE_ARRIVED).setPackage(app.packageName),
            )
        }
        // 首页那条信号，**判据比上面宽**：不只是轨迹 —— 取件码、运单动态、驿站名、商品图，
        // 任何一个被补上，卡片上的字就变了，界面都该重读。
        //
        // 之前这里只跟着「带轨迹」那一种发，于是宿主把自己表里的取件码 / 新动态灌进来时，
        // 首页一点反应都没有（2026-09-27 用户报的「首页的快递动态和取件码不会自动更新」）。
        // 节流：这是高频来源，宿主自查一次能连发十几条，逐条发就是把列表重读十几遍 ——
        // 中间被丢掉的最终由 [ExpressRelay.ACTION_HOST_QUERY_REPORT] 那条不节流的兜住。
        if (applied) notifyRecordsChanged(app, throttled = true)
    }

    /**
     * 宿主同步过来的淘宝登录态 cookie（[ExpressRelay.ACTION_COOKIE_SYNC]）。
     *
     * 进内存缓存 + **落模块私有目录**（[TraceCookieStore]，2026-09-26 用户拍板的修订，
     * 理由见那里的注释）—— **不打内容日志**。extras 为空直接忽略：宿主侧读不到时不会发，
     * 防御性起见这里也拦一道。
     *
     * 落定之后**不再广播内部信号**：唯一会等它的地方（身份码弹窗）用短轮询等这一份
     * （见 `IdentityCodeDialog`）—— 为一个 3 秒的等待引入一条广播契约不值得。
     */
    private fun handleCookieSync(app: Context, intent: Intent) {
        val cookie = intent.getStringExtra(ExpressRelay.EXTRA_COOKIE)?.takeIf { it.isNotBlank() }
        if (cookie == null) {
            // 两种「没有 cookie」要分开：
            //   - 收到**回执**（宿主明确说了原因）→ 记下来。这是「这台设备拉不到轨迹」最常见
            //     的成因，用户在菜鸟里没登录过淘宝系账号时就是这条；
            //   - 连回执都没有 → 广播压根没送到（宿主进程不在时系统静默丢弃），这里写不出
            //     任何日志。**这条链路的沉默本身就是结论**。
            intent.getStringExtra(ExpressRelay.EXTRA_COOKIE_ERROR)?.takeIf { it.isNotBlank() }
                ?.let { ModuleAndroidLog.legacy(LOG_TAG, "cookie sync 收到宿主回执：$it") }
                ?: ModuleAndroidLog.error(LOG_TAG, "cookie sync with empty payload, dropped")
            return
        }
        TraceCookieCache.attach(app)
        TraceCookieCache.put(
            cookie,
            intent.getStringExtra(ExpressRelay.EXTRA_COOKIE_UA)?.takeIf { it.isNotBlank() },
        )
        // UA 交给请求引擎：让 MTOP 请求的 UA 与这批登录态的画像（菜鸟 WebView）一致，
        // 浏览器 UA 配菜鸟 cookie 是风控眼里的异常组合。
        CainiaoTraceApi.preferredUa = TraceCookieCache.hostUa
        ModuleAndroidLog.legacy(LOG_TAG, "cookie synced: ${TraceCookieCache.describe()}")
    }

    /**
     * 叫界面重读一遍包裹列表（[ExpressRelay.ACTION_RECORDS_CHANGED]）。
     *
     * 触发点与理由见那条 action 的注释；这里只说节流这一件事。
     *
     * @param throttled true = 同一窗口内只放第一条。只有**富化**这一条路需要它：宿主自查是
     *   一个循环里连发十几条，逐条叫界面重读就是拿十几遍 SharedPreferences 的 JSON 解析
     *   去刷同一个列表。用 `elapsedRealtime` 而不是墙钟 —— 这个窗口量的是「间隔」，与
     *   用户改没改系统时间无关。
     *
     *   被丢掉的中间状态**不会留在界面上**：自查这条路末尾一定有
     *   [ExpressRelay.ACTION_HOST_QUERY_REPORT]，那条不节流、紧跟着发，兜住最终状态。
     */
    private fun notifyRecordsChanged(app: Context, throttled: Boolean) {
        if (throttled) {
            val now = SystemClock.elapsedRealtime()
            synchronized(recordsChangedLock) {
                if (now - lastRecordsChangedAt < RECORDS_CHANGED_MIN_INTERVAL_MS) return
                lastRecordsChangedAt = now
            }
        }
        runCatching {
            app.sendBroadcast(
                Intent(ExpressRelay.ACTION_RECORDS_CHANGED).setPackage(app.packageName),
            )
        }.onFailure {
            // 发不出去只是「界面晚一步更新」，不是数据丢 —— 数据已经在上一行落库了。
            ModuleAndroidLog.error(LOG_TAG, "records-changed broadcast failed", it)
        }
    }

    /**
     * 原通知的点击跳转令牌（内存里那份）。
     *
     * **必须独立 try 一遍**，不能跟着 [parseRecord] 走：这个 extra 在富化那条路上压根不存在，
     * 而且它是个 Parcelable —— 万一解不回来，`getExtras()` 层面就可能抛。
     * 读失败的正确后果是「这条记录点不开原界面」，绝不能升级成「整条记录丢掉」。
     *
     * 不做任何日志：正常情形（富化 / 老版本发送端）下它就是 null，写日志只会刷屏。
     */
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

    /**
     * 原通知跳转的**可落盘快照**（[ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI]）。
     *
     * 与 [readContentIntent] 是同一件事的两份表示：那份是令牌本体（保真、但只活在这一跳），
     * 这份是 `Intent.toUri` 出来的字符串（有损、但能随记录落盘）。详情页优先用令牌、
     * 令牌没了才用它（见 `NotificationIntentLauncher`）。
     *
     * 是个普通 `String` extra，读失败的概率极低 —— 仍然 `runCatching` 包一层：
     * 这条路径上「读不出来」的正确后果同样是「少一个按钮」，不是「整条记录丢掉」。
     */
    private fun readIntentUri(intent: Intent): String? = runCatching {
        intent.getStringExtra(ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 令牌在 **system_server** 侧的寄存句柄（[ExpressRelay.EXTRA_INTENT_TOKEN]）。
     *
     * 与 [readIntentUri] 同构的一份「字符串 extra」：它是模块自己生成的 UUID，读失败的概率
     * 极低 —— 仍然 `runCatching` 包一层，理由与那两个一样（读不出来只该让「跨进程重启后
     * 还能跳转」这一个能力消失，不该丢掉整条记录）。
     *
     * 不做日志：富化那条路没有它，正常情形下就是 null，写日志只会刷屏。
     */
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
            // 坐标用 NaN 传「没有」（见发送端）。NaN 一律当 null —— 它在 `ExpressRecord` 里
            // 是「没有坐标」的表示法，进了模型就再也分不出「0,0」和「没给」了。
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
            // 轨迹编在字符串里，解码失败退化为空表（「没有轨迹」）而不是丢整条记录。
            trace = ExpressTraceCodec.decode(intent.getStringExtra(ExpressRelay.EXTRA_TRACE)),
        )
    }

    private companion object {
        const val LOG_TAG = "ReaPress"

        /**
         * [notifyRecordsChanged] 的节流窗口（毫秒）。
         *
         * 取得短：它是「界面多快跟上」的上限，用户停在首页时一次自查的十几条富化几乎全落在
         * 同一个窗口里，所以再放大也没有额外收益；取得长则会让「用户刚在菜鸟里点开的那个包裹」
         * 迟迟不出现。600ms 已经足够挡住一轮自查里的重复重读。
         */
        const val RECORDS_CHANGED_MIN_INTERVAL_MS = 600L

        /** 节流用（进程级：接收器是每次广播新建一个实例，状态必须挂在类上）。 */
        private val recordsChangedLock = Any()
        private var lastRecordsChangedAt = 0L
    }
}
