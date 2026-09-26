package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 淘宝 App 进程里的登录态采集 —— 「宿主可能是菜鸟，也可能是淘宝」这条思路的落地。
 *
 * ## 为什么会有这个类
 *
 * 2026-09-26 真机实测：菜鸟自己的 WebView cookie 库里**没有** `.taobao.com` 登录态
 * （用户没在菜鸟里绑过淘宝账号，菜鸟就不需要那份 cookie）。于是整条 MTOP 通道哑火，
 * 而菜鸟侧再怎么改都救不回来 —— **凭据根本不在那个进程里**。
 *
 * 淘宝 App 是另一回事：只要用户登录过淘宝，它的 WebView 里必然有 `.taobao.com` 域
 * 的完整登录态。取到之后经 [ExpressRelaySender.sendCookieSync] 送回模块，
 * 模块就能拿它发 `acs.m.taobao.com` 的请求（同域，cookie 直接生效）。
 *
 * ## 为什么是「主动同步 + 反向索要」两条，而不是只留一条
 *
 * - **主动同步**：进程起来后延迟一次（[FIRST_SYNC_DELAY_MS]）。覆盖「用户开过淘宝 →
 *   模块随后要用」的常见路径，模块侧什么都察觉不到。
 * - **反向索要**（[ExpressRelay.ACTION_COOKIE_REQUEST]）：覆盖「模块刚重启、磁盘那份不可用，
 *   而淘宝正躺在后台」——主动同步早就在半小时前发生过了，节流不会因为接收方丢了而放行。
 *
 * ## 只做主进程
 *
 * 淘宝有多进程（`:channel`、`:tools`…），而 `onPackageReady` 每个进程各来一次。
 * 全部进程都发一遍是重复的广播；而 [HostCredentialSource] 读的是**应用级** dataDir
 * （不是进程级），任何进程读到的都是同一份。所以只让主进程干活。
 *
 * ⚠️ 这条路要求用户在 LSPosed 里**把本模块的作用域勾上淘宝**。没勾就不会有
 * `onPackageReady`，整个类不会被执行 —— 界面上有对应的提示文案。
 */
internal object TaobaoCredentialHook {

    private const val TAG = "taobao credential"

    /**
     * 冷启动后等多久才碰 WebView。
     *
     * 淘宝冷启动那几秒主线程很紧，而 `CookieManager.getInstance()` 首次调用会**同步初始化
     * WebView**（几十到几百毫秒）。被注入进程里的铁律是不给宿主添负担，所以错峰。
     */
    private const val FIRST_SYNC_DELAY_MS = 8_000L

    /** `onPackageReady` 早于 Application 创建，拿不到 context 时按这个间隔重试。 */
    private const val ACQUIRE_RETRY_MS = 3_000L

    /** 重试上限（约 30 秒）。超过就放弃 —— 那说明这个进程本来就不跑 UI，不需要凭据。 */
    private const val MAX_ACQUIRE_ATTEMPTS = 10

    @Volatile private var installed = false

    @Volatile private var receiverRegistered = false

    @Volatile private var firstSyncScheduled = false

    /**
     * @param isMainProcess 由 [ExpressHookDispatcher] 判定后传入（它本来就要解析进程名），
     *   非主进程直接不装 —— 理由见类注释。
     */
    fun install(classLoader: ClassLoader, isMainProcess: Boolean): Boolean {
        if (installed) return true
        installed = true

        // 菜鸟那边的捕获钩子是幂等的；这里再调一次是为了「先被注入的是淘宝」时也能拿到 context。
        HostContextHolder.installCapture(classLoader)

        if (!isMainProcess) {
            XposedBridge.logAlways("$TAG: 非主进程，只装 context 捕获（不采集凭据）")
            return true
        }

        // 此刻 Application 常常还没创建，acquire 会返回 null —— 交给重试。
        val context = HostContextHolder.acquire()
        if (context != null) {
            onContextReady(context)
        } else {
            retryAcquire(attempt = 0)
        }

        XposedBridge.logAlways("$TAG: 淘宝凭据采集已装上（主动同步 + 反向索要）")
        return true
    }

    /**
     * 拿到 context 之后才谈得上干活：注册反向索要、排一次主动同步。
     *
     * 两件都只做一次 —— [retryAcquire] 可能把它叫来多次。
     */
    private fun onContextReady(context: Context) {
        synchronized(this) {
            if (!receiverRegistered) {
                receiverRegistered = HostReceiverRegistrar.register(
                    context,
                    cookieRequestReceiver,
                    ExpressRelay.ACTION_COOKIE_REQUEST,
                )
                XposedBridge.logAlways("$TAG: 反向索要接收器 registered=$receiverRegistered")
            }
            if (!firstSyncScheduled) {
                firstSyncScheduled = true
                scheduleFirstSync(context)
            }
        }
    }

    private fun retryAcquire(attempt: Int) {
        if (attempt >= MAX_ACQUIRE_ATTEMPTS) {
            XposedBridge.logAlways("$TAG: 等了 ${attempt * ACQUIRE_RETRY_MS}ms 仍拿不到 context，放弃采集")
            return
        }
        val posted = runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                val context = HostContextHolder.acquire()
                if (context != null) onContextReady(context) else retryAcquire(attempt + 1)
            }, ACQUIRE_RETRY_MS)
        }.isSuccess
        if (!posted) return
    }

    private fun scheduleFirstSync(context: Context) {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                // 非 force：lastCookieSyncAt 进程内初始为 0，冷启动这一次必然放行；
                // 之后同一进程内再启动也只是重复调用同一个节流，不会反复刷广播。
                runCatching { ExpressRelaySender.sendCookieSync(context) }
                    .onFailure { XposedBridge.logError("$TAG: 主动同步失败", it) }
            }, FIRST_SYNC_DELAY_MS)
        }.onFailure { XposedBridge.logError("$TAG: 排主动同步失败", it) }
    }

    /**
     * 模块索要一次登录态。
     *
     * 与菜鸟侧的区别：这里**只有 cookie 一件事**（拉轨迹、ORM 富化都是菜鸟特有的），
     * 所以接收器只管这一个 action，多一个都不注册。
     */
    private val cookieRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ExpressRelay.ACTION_COOKIE_REQUEST) return
            runCatching { ExpressRelaySender.sendCookieSync(context, force = true) }
                .onFailure { XposedBridge.logError("$TAG: 响应索要失败", it) }
        }
    }
}
