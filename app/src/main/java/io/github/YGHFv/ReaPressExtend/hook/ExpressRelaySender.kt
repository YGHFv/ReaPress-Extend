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

    /**
     * 宿主 App 进程侧：把「本地包裹表自查」的结论送回模块进程
     * （[ExpressRelay.ACTION_HOST_QUERY_REPORT]）。
     *
     * ## 为什么这条日志不能就地打
     *
     * 自查跑在**宿主进程**里，那里的 `XposedBridge.logAlways` 走 libxposed 的出口 → logcat；
     * 而 MIUI / HyperOS 上 logcat 读不出来（`logcat -g` 报 `0 B readable`），LSPosed 的
     * 日志文件 adb 也碰不到。结果是「自查跑没跑成」在宿主侧完全不可观测 ——
     * 而排查时需要的恰恰就是这一句：跑没跑 / 跑了但查成空 / 压根没跑，三种情况的修法不同。
     *
     * 所以走 relay 送回模块进程，由它记进 `files/module-log.txt`（唯一一处
     * `adb shell run-as` 能直接读、且不受「简洁日志」开关影响的地方）。
     *
     * **只送结论，不送包裹内容** —— 数据走 [sendEnrichment] 那条老路。
     * 失败只记日志：这是诊断信息，丢了不影响任何功能。
     */
    fun sendHostQueryReport(context: Context, text: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_HOST_QUERY_REPORT)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ExpressRelay.EXTRA_HOST_QUERY_REPORT, text)
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            XposedBridge.logAlways("host self query report sent: $text")
        }.onFailure { XposedBridge.logError("host self query report failed", it) }
    }

    /** cookie 同步的最小间隔。cookie 会轮换，但不值得追着每次投递都发 —— 半小时足够新。 */
    private const val COOKIE_SYNC_INTERVAL_MS = 30 * 60_000L

    @Volatile private var lastCookieSyncAt = 0L

    /**
     * 宿主 App 进程侧：把淘宝登录态 cookie 同步给模块进程（[ExpressRelay.ACTION_COOKIE_SYNC]）。
     *
     * 模块进程自己发 MTOP 请求要有登录态，而 cookie 只在菜鸟私有目录里，只能由这里读出来送。
     * 接收侧落内存 + 模块私有目录（[io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache]）。
     *
     * @param force 模块主动索要时传 true，跳过 [COOKIE_SYNC_INTERVAL_MS] 节流。
     *
     * **为什么需要 force**：节流是按「宿主什么时候刷新首页」算的，而模块可能**刚重启**
     * （内存缓存归零、磁盘那份又过期或被清），此刻它是真没有登录态 —— 而节流不会因为
     * 「接收方丢了」而放行。用户正站在驿站门口点「获取身份码」，等 30 分钟是不行的。
     * 节流防的是「每次首页查询都传一遍登录态」，不是防「对方明确要了一次」。
     */
    fun sendCookieSync(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCookieSyncAt < COOKIE_SYNC_INTERVAL_MS) return
        lastCookieSyncAt = now
        runCatching {
            val cookie = HostCredentialSource.cookie(context)
            val intent = Intent(ExpressRelay.ACTION_COOKIE_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            if (cookie.isNullOrBlank()) {
                // 读不到也**发一条只带原因的广播**，而不是静默返回。
                //
                // 静默的代价（2026-09-26 实测）：模块侧看到的是一片空白 —— 没有 `cookie synced`、
                // 没有轨迹、没有报错，与「宿主这半天没刷新过首页」完全同形，用户报的「这台设备
                // 怎么都获取不了」就卡在无法区分上。带上原因之后，模块日志里能直接读到
                // 「宿主说它没有登录态」，而不是继续猜。
                intent.putExtra(
                    ExpressRelay.EXTRA_COOKIE_ERROR,
                    // 具体原因由读取侧给出（文件不见了 / 库里没有这个域 / 打开失败），
                    // 这里只兜住「连原因都没记下来」的情况。
                    HostCredentialSource.lastReason
                        .ifBlank { "宿主侧没有可用登录态（读取方没记下原因）" },
                )
                context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
                XposedBridge.logAlways("cookie sync: 宿主侧没有可用登录态，已发回执")
                return
            }
            intent.putExtra(ExpressRelay.EXTRA_COOKIE, cookie)
                // 菜鸟 WebView 的真实 UA 随 cookie 一起送过去：模块进程发 MTOP 请求时用它，
                // 让 UA 与 cookie 的画像一致（浏览器 UA 配菜鸟登录态本身就是风控特征）。
                // getDefaultUserAgent 首次调用会起 WebView 进程，可能要几百毫秒 —— 但这条
                // 链路 30 分钟才走一次，且在 hook 回调线程上，不卡宿主主线程。
                .putExtra(ExpressRelay.EXTRA_COOKIE_UA, webviewUa(context))
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            XposedBridge.log("cookie synced to module (${cookie.length} chars, force=$force)")
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
            // 坐标用 NaN 表示「没有」：putExtra 收不了 null，而 NaN 本身也不是合法坐标
            // （0 是几内亚湾上的一个点，接收端会把它当成有效值）。
            putExtra(ExpressRelay.EXTRA_STATION_LAT, record.stationLat ?: Double.NaN)
            putExtra(ExpressRelay.EXTRA_STATION_LNG, record.stationLng ?: Double.NaN)
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
