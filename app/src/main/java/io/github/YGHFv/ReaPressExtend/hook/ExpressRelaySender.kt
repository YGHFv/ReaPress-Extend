package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressTraceCodec
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 被注入进程 → 模块 App 进程的事件投递。
 *
 * ## 两个来源，同一份协议
 *
 * - [send]：system_server 拦到通知后投递（`ACTION_DELIVER`）
 * - [sendEnrichment]：宿主 App 进程富化出包裹后投递（`ACTION_ENRICH`）
 *
 * 两者的 Context 来源完全不同，这是这个类里唯一需要分情况的地方：
 * system_server 没有现成 Context（见 [SystemContextHolder]），而宿主进程里
 * `ActivityThread.currentApplication()` 直接就能拿到（见 [HostContextHolder]）。
 * Intent 的构造、显式组件寻址、flag 组合则完全共用 —— 协议只有一份，
 * 两个发送端如果各写一份 `putExtra` 迟早会漏字段。
 *
 * ## 为什么是广播
 *
 * - `RemotePreferences` 是给设置用的（hooked 侧只读），不适合流式事件
 * - `XposedService` 的 binder 只递到模块 App 进程，别的进程里拿不到
 * - 广播 + **显式组件** + `FLAG_INCLUDE_STOPPED_PACKAGES` 是唯一能在「模块 App 从没启动过」
 *   的情况下把事件送到的通道。模块装完长期处于 stopped 状态，这个 flag 是必需的。
 */
internal object ExpressRelaySender {

    /** 取不到 context 时只记一次日志，避免刷屏。 */
    @Volatile private var contextFailureLogged = false

    /** system_server 侧：拦到通知之后投递。 */
    fun send(record: ExpressRecord, thisObject: Any? = null) {
        // 运行期从 NMS 实例取 context 并缓存 —— 这是 system_server 里唯一可靠的途径。
        SystemContextHolder.upgrade(thisObject)
        val context = SystemContextHolder.acquire() ?: return logContextFailure()
        deliver(context, record, ExpressRelay.ACTION_DELIVER)
    }

    /** 宿主 App 进程侧：富化出包裹之后投递。 */
    fun sendEnrichment(record: ExpressRecord, context: Context?) {
        val resolved = context ?: HostContextHolder.acquire()
        if (resolved == null) return logContextFailure()
        deliver(resolved, record, ExpressRelay.ACTION_ENRICH)
    }

    /** cookie 同步的最小间隔。cookie 会轮换，但不值得追着每次投递都发 —— 半小时足够新。 */
    private const val COOKIE_SYNC_INTERVAL_MS = 30 * 60_000L

    @Volatile private var lastCookieSyncAt = 0L

    /**
     * 宿主 App 进程侧：把淘宝登录态 cookie 同步给模块进程（[ExpressRelay.ACTION_COOKIE_SYNC]）。
     *
     * 轨迹拉取收拢到模块进程的前提：模块进程自己发 MTOP 请求要有登录态，而 cookie 只在
     * 菜鸟私有目录里，只能由这里读出来送。**只内存、不落盘**（接收侧 TraceCookieCache），
     * 30 分钟节流 —— cookie 会轮换，但轮换周期远长于半小时，不值得每次投递都传一遍。
     * 这是对「cookie 不外传」原则的修订，权衡见 relay 契约里该 action 的注释。
     */
    fun sendCookieSync(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCookieSyncAt < COOKIE_SYNC_INTERVAL_MS) return
        lastCookieSyncAt = now
        runCatching {
            val cookie = CainiaoCookieSource.cookie(context)
            if (cookie.isNullOrBlank()) return
            val intent = Intent(ExpressRelay.ACTION_COOKIE_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .putExtra(ExpressRelay.EXTRA_COOKIE, cookie)
                // 菜鸟 WebView 的真实 UA 随 cookie 一起送过去：模块进程发 MTOP 请求时用它，
                // 让 UA 与 cookie 的画像一致（浏览器 UA 配菜鸟登录态本身就是风控特征）。
                // getDefaultUserAgent 首次调用会起 WebView 进程，可能要几百毫秒 —— 但这条
                // 链路 30 分钟才走一次，且在 hook 回调线程上，不卡宿主主线程。
                .putExtra(ExpressRelay.EXTRA_COOKIE_UA, webviewUa(context))
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            XposedBridge.log("cookie synced to module (${cookie.length} chars)")
        }.onFailure { XposedBridge.logError("cookie sync failed", it) }
    }

    /** 菜鸟进程内 WebView 的默认 UA；拿不到返回 null（模块侧退回内置的浏览器 UA）。 */
    private fun webviewUa(context: Context): String? = runCatching {
        android.webkit.WebSettings.getDefaultUserAgent(context)
    }.getOrNull()

    private fun logContextFailure() {
        if (!contextFailureLogged) {
            contextFailureLogged = true
            XposedBridge.logError("cannot obtain context for relay — events will be dropped")
        }
    }

    private fun deliver(context: Context, record: ExpressRecord, action: String) {
        runCatching {
            val intent = buildIntent(record, action)
            // 显式指定组件：模块 App 的接收器。隐式广播在 Android 8+ 受限，且我们本来就知道
            // 目标是谁 —— 显式投递既可靠又不会被别的应用截获。
            intent.setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            // 投到**发起方所在用户**。system_server 里用 Process.myUserHandle()；
            // 宿主进程里用自己进程的 user —— 两者语义一致，避免写死 UserHandle.of(0)
            // （后者在部分 SDK 上是 @hide，编译期过不去）。
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())

            XposedBridge.log(
                "relayed to module: action=${action.substringAfterLast('.')} " +
                    "pkg=${record.sourcePackage} key=${record.dedupeKey} status=${record.status}",
            )
        }.onFailure {
            // 投递失败只记日志，绝不让异常冒到宿主/系统的调用栈上。
            XposedBridge.logError("relay failed", it)
        }
    }

    private fun buildIntent(record: ExpressRecord, action: String): Intent =
        Intent(action).apply {
            // 模块 App 可能处于 stopped 状态（装完没打开过），没有这个 flag 收不到广播。
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // 提升优先级：快递通知有时效性，别排在后台广播队列末尾。
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
            // 0 表示「没有」：putExtra 收不了 null，而时间戳本身不可能是 0。
            putExtra(ExpressRelay.EXTRA_ARRIVAL_AT, record.arrivalAt ?: 0L)
            putExtra(ExpressRelay.EXTRA_LOGISTICS_DETAIL, record.logisticsDetail)
            putExtra(ExpressRelay.EXTRA_STATION_HOURS, record.stationHours)
            putExtra(ExpressRelay.EXTRA_STATION_ADDRESS, record.stationAddress)
            putExtra(ExpressRelay.EXTRA_GOODS_IMAGE, record.goodsImage)
            // 轨迹条数不定，编成一个字符串传（编解码只有 ExpressTraceCodec 一处）。
            // 空轨迹传 null —— 接收端解出来就是空表，与「没查过」等价。
            putExtra(
                ExpressRelay.EXTRA_TRACE,
                record.trace.takeIf { it.isNotEmpty() }?.let { ExpressTraceCodec.encode(it) },
            )
            putExtra(ExpressRelay.EXTRA_PHONE_TAIL, record.phoneTail)
            putExtra(ExpressRelay.EXTRA_ORIGIN, record.origin.name)
        }
}
