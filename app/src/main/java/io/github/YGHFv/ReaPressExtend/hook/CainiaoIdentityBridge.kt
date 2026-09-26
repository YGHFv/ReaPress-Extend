package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 身份码的宿主侧取码器 —— **在菜鸟进程里用菜鸟自己的会话取码**。
 *
 * ## 为什么必须这么做（两轮真机实证，别再回 H5）
 *
 * | 通道 | 结果 |
 * |---|---|
 * | MTOP H5（`acs.m.taobao.com/h5/`，与轨迹完全同一条） | ⛔ 预热就回 `FAIL_SYS_SESSION_EXPIRED`，**连 `_m_h5_tk` 都不下发** |
 * | MTOP APP 通道 | 只有菜鸟自己的会话能走（`.native` 那个版本更是字面意义上的只用 native） |
 *
 * 决定性证据：**同一时刻、同一份 cookie 拉轨迹是成功的**
 * （`mtop.taobao.logisticstracedetailservice.queryalltrace`）。所以那个「会话过期」不是登录态
 * 的问题，是这条接口在 H5 通道上就是关着的 —— 换签名、换 appName、换 verifyVersion 都没用。
 *
 * 而在菜鸟进程里直接调它自己的入口，就完全绕开了「复刻签名 / 猜 appKey / 撞风控」三件事：
 * 那就是**菜鸟自己发出的那个请求**，服务端看到的是一个正常的、由宿主签出来的请求。
 *
 * ## 取码的两条路（先便宜的，再新鲜的）
 *
 * 1. **菜鸟进程内的缓存**（[captured]）—— 用户自己在菜鸟里打开过身份码页时，宿主会走
 *    `identity_code.api.a#onEvent(IdentityOnlineData)` 把它解析成一个 `IdentityBean` 并调
 *    `setIdentityCode(...)`。挂在**这个 setter** 上（实体字段是 JSON 映射的，名字不随混淆变），
 *    我们就白得一份「宿主已经取好并打算显示」的码，不用再发任何请求。
 * 2. **主动触发宿主的取码入口**（[triggerHost]）—— 缓存里没有或已过期时，构造宿主自己的
 *    `identity_code.api.a` 并调它那个收 `OnRequestResultListener` 的方法。
 *
 * ⚠️ **`triggerHost` 里那个类名 `identity_code.api.a` 是这段代码里唯一一处混淆名**。
 * 它之所以可接受：同包下的类（`GuoguoIdentityOfflineApi`、`MtopCainiaoNbpickupIdentitycodeGetCnRequest`、
 * `IdentityBean`）全是未混淆的，这个 `a` 是整个 `identity_code` 模块里唯一的短名；
 * 而且**拿不到它只是退化成路径 1**（仍然不是死路），失败会在日志里留下
 * `identity bridge: 触发宿主取码失败` —— 版本更新若把名字改了，日志里看得见。
 *
 * ## 三条不变量
 *
 * - **身份码不进日志**：它等于一次取件凭据，日志里只记长度与来源（`cache` / `host`）。
 * - **绝不阻塞宿主主线程**：取码在私有 executor 上；只有「构造宿主对象并调用」这一步
 *   post 到主线程（宿主的 MTOP 链路要求主线程），回来后由 latch 唤醒 executor。
 * - **异常只记日志**：这条链路是锦上添花，任何失败都不该让菜鸟崩。
 */
internal object CainiaoIdentityBridge {

    /** 宿主里那个「在线身份码」请求类。见类注释里对混淆名的说明。 */
    private const val ONLINE_API = "com.cainiao.wireless.identity_code.api.a"

    /** 回调接口（未混淆）：只有一个 `onResult(Object)`。 */
    private const val LISTENER = "com.cainiao.wireless.identity_code.listener.OnRequestResultListener"

    /**
     * 响应实体（未混淆，字段名由 JSON 映射决定）。
     *
     * 我们只挂它的 `setIdentityCode` —— **不扫描、不枚举**，所以不需要知道它在哪一层包着。
     */
    private const val BEAN = "com.cainiao.wireless.identity_code.entity.IdentityBean"

    /**
     * 等宿主自己取码回来的上限。
     *
     * 7 秒：宿主这条链路是「MTOP 请求 → 回调 → EventBus → setter」，正常在一秒内；
     * 超过 7 秒基本就是它自己的会话也失效了（那要用户去菜鸟里重新登录，等更久没有意义）。
     *
     * ⚠️ **模块侧的等待上限必须大于 [HOST_TIMEOUT_MS] + [MAIN_POST_TIMEOUT_MS]**
     * （`IdentityCodeFetcher.WAIT_MS` = 12s，这里 7 + 2 = 9s）。否则宿主还在等它自己的
     * 网络回调，模块已经先超时了 —— 那条「宿主取码超时」的回执回来时没人在听，
     * 日志里看起来就会像「广播压根没送到」，而这正是排查时最怕的两种故障同形。
     */
    private const val HOST_TIMEOUT_MS = 7_000L

    /** post 到主线程这一步的上限 —— 主线程被卡住时不能把这条线程一起拖死。 */
    private const val MAIN_POST_TIMEOUT_MS = 2_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-identity").apply { isDaemon = true }
    }

    @Volatile private var installed = false

    /** 宿主 ClassLoader。触发宿主取码要它来 findClass；[install] 时存下。 */
    @Volatile private var loader: ClassLoader? = null

    /**
     * 我们正在主动触发宿主取码。
     *
     * 用途只有一处：**别把自己触发的那一份当成「宿主主动取到了」再推一次**。
     * 主动触发那条路上，宿主的响应同样会经过 `setIdentityCode`（挂点会命中），而结果已经由
     * 应答路径回传了 —— 不设这道闸，模块会收到同一份码的两条广播。
     */
    @Volatile private var triggering = false

    // ---- 宿主上一次取到的码（进程内缓存）。字段全部用 volatile：写在主线程（setter 回调），
    //      读在 executor 线程。 ----

    @Volatile private var cachedCode: String? = null

    @Volatile private var cachedExpireAt = 0L

    @Volatile private var cachedOffline = false

    @Volatile private var cachedAt = 0L

    /**
     * 装 hook：只挂 `IdentityBean#setIdentityCode`。
     *
     * ⚠️ **只在菜鸟主进程调用**（由 [CainiaoPackageHook.install] 的 `isMainProcess` 把关，
     * 不是在这里判断的）。理由：身份码模块活在主进程，而非主进程既没有它的初始化环境、
     * 也一样能收到模块发来的广播 —— 它会回一条「取不到」，那会盖掉主进程刚取到的码。
     * 「每个进程各装一份」在别的 hook 上是对的（各进程数据独立），在这里是错的。
     *
     * 挂点选 `setIdentityCode` 而不是「宿主的取码方法」，是因为它是**数据落地的唯一必经之路**：
     * 在线（`api/a#onEvent`）与离线（`GuoguoIdentityOfflineApi#onEvent`）两条路都会走到这里，
     * 而实体字段名由 JSON 契约决定、不随混淆变 —— 这正是本项目选挂点的一贯标准。
     *
     * ⚠️ 这里会 `findClass(BEAN)`（提前把实体类加载进来），但**不是在类加载路径上挂**
     * （调用方是 `onPackageReady`），也不是在类加载器 hook 的回调里做反射 —— 那个坑
     * 踩过一次（见 [CainiaoPackageHook] 类注释第 5 代），表现是菜鸟卡在启动闪屏。
     */
    fun install(classLoader: ClassLoader): Boolean {
        if (installed) return true
        installed = true
        loader = classLoader

        val bean = runCatching { XposedHelpers.findClass(BEAN, classLoader) }.getOrElse { error ->
            XposedBridge.logError("identity bridge: $BEAN 找不到，缓存那条路不可用", error)
            return false
        }

        val hooked = XposedBridge.hookAllMethods(
            bean,
            "setIdentityCode",
            object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    runCatching { onIdentityCodeSet(param) }
                        .onFailure {
                            XposedBridge.logError("identity bridge: setIdentityCode hook failed", it)
                        }
                }
            },
        ).size

        XposedBridge.logAlways("identity bridge installed: $BEAN#setIdentityCode hooked=$hooked")
        return hooked > 0
    }

    @Volatile private var receiverRegistered = false

    /**
     * 注册模块发来的索取请求（幂等）。
     *
     * 权限闸与 Android 13 的导出性 flag 都交给 [HostReceiverRegistrar]（它要求发送方持有
     * signature 级权限）—— 没这道闸，设备上任何应用都能拿用户的身份码。
     *
     * ## 注册成功要**说一声**（2026-09-26 加）
     *
     * 这条通道以前是「立起来了也看不出来」：模块发的广播没人接时，两边都写不出日志
     * —— 而这正是最难查的故障（真机上为此白跑过一轮，以为是唤醒销没生效，其实是通道压根没注册）。
     * 现在注册成功就发一条**状态回执**给模块，它会记成一行 `identity bridge ready: …`。
     * 判据从此变成「`identity wake` 之后有没有 `identity bridge ready`」：
     * 有 → 销把宿主叫醒了且通道立好了；没有 → 销没叫醒（或宿主没能走到 `Application` 创建）。
     */
    fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        // 桥没装 = 本进程不是身份码的宿主（非主进程，或 install 还没走到）。注册上去也只会
        // 回一条「取不到」，那会盖掉主进程刚取到的码 —— 干脆不注册，等真正的宿主进程去注册。
        if (!installed) {
            XposedBridge.logAlways("identity bridge: 桥未装，跳过索取通道注册（本进程不取身份码）")
            return
        }
        synchronized(this) {
            if (receiverRegistered) return
            val ok = HostReceiverRegistrar.register(
                context,
                requestReceiver,
                ExpressRelay.ACTION_IDENTITY_REQUEST,
            )
            receiverRegistered = ok
            XposedBridge.logAlways("identity bridge receiver registered=$ok")
            if (ok) sendStatus(context, "索取通道已就绪（宿主进程 ${android.os.Process.myPid()}）")
        }
    }

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ExpressRelay.ACTION_IDENTITY_REQUEST) return
            // 一行「收到了」：把「通道没注册」和「通道注册了但宿主取不到码」分开
            // —— 后者会继续走到应答或失败回执，前者只有这一行之前的静默。
            XposedBridge.logAlways("identity bridge: 收到模块索取")
            // 取码要等宿主自己的网络回调（最多 8 秒），**绝不能**在主线程等 ——
            // onReceive 只有 10 秒预算，而这里还只是入口。
            executor.execute {
                runCatching { answer(context.applicationContext) }
                    .onFailure { XposedBridge.logError("identity bridge: answer failed", it) }
            }
        }
    }

    // ---------------------------------------------------------------- 取码

    /** 一次「宿主已取到的码」。字段与 [ExpressRelay] 的 extras 一一对应。 */
    private class Captured(val code: String, val expireAt: Long, val offline: Boolean)

    /**
     * 回一条应答。
     *
     * 顺序是「先便宜的，再新鲜的」：缓存里那份还有效就直接用（零请求、零延迟），
     * 否则才去驱动宿主的取码入口。缓存过期的判据用**宿主自己给的有效期**（`expireTime`），
     * 不是我们自己拍一个 —— 服务端才知道它什么时候作废。
     */
    private fun answer(context: Context) {
        cached()?.let { cached ->
            XposedBridge.logAlways("identity bridge: 用宿主缓存应答 (${cached.code.length} 位)")
            send(context, cached, PROVENANCE_CACHE)
            return
        }
        val fresh = runCatching { triggerHost() }.getOrElse { error ->
            XposedBridge.logError("identity bridge: 触发宿主取码失败", error)
            null
        }
        if (fresh != null) {
            XposedBridge.logAlways("identity bridge: 用宿主现取应答 (${fresh.code.length} 位)")
            send(context, fresh, PROVENANCE_HOST)
            return
        }
        // 到这里说明「缓存没有 + 主动触发也没拿到」。原因如实写出来给用户看 ——
        // 这一句会原样出现在弹窗里（`IdentityCodeFetcher.describe` 的 HostFailed 分支）。
        sendFailure(context, "菜鸟没能给出身份码（可能是它自己的登录态也要刷新了）")
    }

    /** 进程内缓存里那份还能不能用。 */
    private fun cached(): Captured? {
        val code = cachedCode?.takeIf { it.isNotBlank() } ?: return null
        // 0 表示宿主没给有效期 —— 那时一律当作可用（与 core 那边的规则一致）。
        if (cachedExpireAt > 0L && System.currentTimeMillis() >= cachedExpireAt) return null
        return Captured(code, cachedExpireAt, cachedOffline)
    }

    /**
     * 宿主自己解析出一条身份码了（它要显示的那一份）。
     *
     * 除了填缓存，**还主动推给模块**：这条广播不依赖模块正在等（`ACTION_IDENTITY_SYNC`
     * 的三种来路之一）。理由是小米上那个环境陷阱 —— 菜鸟不在后台时，模块发来的请求广播
     * 会被系统**静默丢弃**，那时模块手里什么都没有；而用户刚在菜鸟里看过身份码，
     * 推送能让这份码落在模块侧，成为「连不上菜鸟」时的兜底。
     */
    private fun onIdentityCodeSet(param: XC_MethodHook.MethodHookParam) {
        val code = param.args.firstOrNull() as? String ?: return
        if (code.isBlank()) return
        val bean = param.thisObject
        cachedCode = code
        cachedExpireAt = readExpireAt(bean)
        cachedOffline = readOffline(bean)
        cachedAt = System.currentTimeMillis()

        // 我们自己刚触发的那一次：结果走应答路径回传，这里不重复推（见 [triggering]）。
        if (triggering) return
        val context = HostContextHolder.acquire() ?: return
        send(context, Captured(code, cachedExpireAt, cachedOffline), PROVENANCE_CACHE)
    }

    /**
     * 驱动宿主自己的取码入口：`identity_code.api.a#a(OnRequestResultListener)`。
     *
     * 回调是**接口**，所以用动态代理接住 —— 不需要知道回调方法叫什么、也不需要知道
     * 它把结果包成什么类型：参数里那个带 `identityCode` 的对象就是答案（[readCode] 按字段名找）。
     * 这是「只依赖接口契约、不依赖混淆名」的又一例。
     *
     * 线程安排：[executor] 上调进来，构造宿主对象与调用 post 到主线程（宿主这条链路
     * 建 MTOP 业务对象、要主线程 looper），本线程用 latch 等结果。
     *
     * @return 取到的码；超时 / 宿主没回结果时 null。
     */
    private fun triggerHost(): Captured? {
        val classLoader = loader ?: return null
        val listenerClass = XposedHelpers.findClass(LISTENER, classLoader)
        val apiClass = XposedHelpers.findClass(ONLINE_API, classLoader)

        // 只要「参数恰好是这个回调接口、返回 void」的那个方法 —— 名字是混淆的，形状不是。
        val invoke = apiClass.methods.firstOrNull {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == listenerClass &&
                it.returnType == Void.TYPE
        } ?: run {
            XposedBridge.logAlways("identity bridge: $ONLINE_API 上没有「收 $LISTENER」的方法，跳过触发")
            return null
        }

        val slot = AtomicReference<Any?>()
        val resultLatch = CountDownLatch(1)
        val proxy = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, args ->
            // 动态代理也会接住 equals/hashCode/toString —— 按「声明在目标接口上」筛掉它们。
            if (method.declaringClass == listenerClass) {
                slot.compareAndSet(null, args?.firstOrNull())
                resultLatch.countDown()
            }
            null
        }

        val apiRef = AtomicReference<Any?>()
        val posted = CountDownLatch(1)

        // 闸门必须在**发请求之前**就落下：响应快的时候会先于本线程的下一行代码回到
        // `setIdentityCode`，那时若闸门还没落下，我们就会把自己触发的那份又当推送发一次。
        triggering = true
        try {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    // 构造 + 调用必须在主线程一起做：基类构造函数里就把自己注册进 EventBus 了，
                    // 响应也是经 EventBus 回到这个对象上的 —— 两边必须同一个实例。
                    apiRef.set(apiClass.getDeclaredConstructor().newInstance())
                    invoke.invoke(apiRef.get(), proxy)
                }.onFailure { XposedBridge.logError("identity bridge: 调用 $ONLINE_API 失败", it) }
                posted.countDown()
            }

            if (!posted.await(MAIN_POST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                XposedBridge.logAlways("identity bridge: 主线程 ${MAIN_POST_TIMEOUT_MS}ms 没排上队，放弃触发")
                return null
            }
            if (!resultLatch.await(HOST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                XposedBridge.logAlways("identity bridge: 等宿主取码超时（${HOST_TIMEOUT_MS}ms）")
                return null
            }
        } finally {
            // 先解除闸门再退订：超时之后才回来的响应仍然会被 [onIdentityCodeSet] 捕获并推给
            // 模块（那时它已经不是「我们这次的结果」而是「宿主自己的新结果」，推出去是对的）。
            triggering = false
            releaseHostObject(apiClass, apiRef.get())
        }

        val bean = slot.get() ?: return null
        val code = readCode(bean) ?: run {
            XposedBridge.logAlways("identity bridge: 宿主回了结果但没有 identityCode")
            return null
        }
        return Captured(code, readExpireAt(bean), readOffline(bean))
    }

    /**
     * 把临时构造出来的宿主对象从 EventBus 上摘掉。
     *
     * 不摘的代价：每次索取都留一个对象挂在全局 EventBus 上，而它的 `onEvent` 会继续被
     * 后续的响应驱动 —— 一个慢慢变长的泄漏，且每次响应都会多唤醒一堆死对象。
     * 方法名（`onDestroy`）来自 [CainiaoTraceApi] 同级的反编译证据；取不到就直接放弃，
     * 反正宿主自己的实例也是这么退订的。
     */
    private fun releaseHostObject(apiClass: Class<*>, api: Any?) {
        if (api == null) return
        Handler(Looper.getMainLooper()).post {
            runCatching { apiClass.getMethod("onDestroy").invoke(api) }
        }
    }

    // ---------------------------------------------------------------- 反射读取

    /**
     * 从宿主给的对象里读身份码。
     *
     * 按**字段名**找而不是按类型：宿主在不同路径上交给我们的可能是 `IdentityBean`，
     * 也可能是响应内层那个 `IdentityResponseData`（字段名相同）。任何一个对象上
     * 有非空 `identityCode` 就是我们想要的，这比「认类型 + 认路径」稳。
     */
    private fun readCode(target: Any?): String? {
        val value = readField(target, "identityCode") as? String
        return value?.takeIf { it.isNotBlank() }
    }

    /**
     * 有效期。
     *
     * ⚠️ **单位在不同对象上不一样**：`IdentityBean.expireTime` 是毫秒（宿主在 `onEvent` 里
     * 已经 `* 1000` 换算过），而 `IdentityResponseData.expireTime` 是服务端原样的**秒**。
     * 拿秒当毫秒喂进去，结果是「刚拿到手就已经过期」—— 缓存那条路会静默失效。
     * 1e11 这个门槛把两者分得很干净（epoch 秒 ~1.7e9，epoch 毫秒 ~1.7e12）。
     */
    private fun readExpireAt(target: Any?): Long {
        val raw = (readField(target, "expireTime") as? Number)?.toLong() ?: return 0L
        if (raw <= 0L) return 0L
        return if (raw < 100_000_000_000L) raw * 1000L else raw
    }

    /** 是不是离线码。宿主这个字段是**字符串**（在线 `"2"`、离线 `"1"`），不要按布尔读。 */
    private fun readOffline(target: Any?): Boolean =
        (readField(target, "isOffLine") as? String) == "1"

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            var type: Class<*>? = target.javaClass
            while (type != null && type != Any::class.java) {
                val field = type.declaredFields.firstOrNull { it.name == name }
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.get(target)
                }
                type = type.superclass
            }
            null
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 回传

    private const val PROVENANCE_CACHE = "cache"
    private const val PROVENANCE_HOST = "host"

    private fun send(context: Context, captured: Captured, provenance: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_IDENTITY_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_CODE, captured.code)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_EXPIRE_AT, captured.expireAt)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_OFFLINE, captured.offline)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_PROVENANCE, provenance)
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            // ⚠️ 只记长度与来源，**绝不记内容**（身份码 = 一次取件凭据）。
            XposedBridge.log("identity synced to module (${captured.code.length} 位, from=$provenance)")
        }.onFailure { XposedBridge.logError("identity bridge: 回传身份码失败", it) }
    }

    private fun sendFailure(context: Context, reason: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_IDENTITY_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_ERROR, reason)
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            XposedBridge.logAlways("identity bridge: 取不到，已发回执（$reason）")
        }.onFailure { XposedBridge.logError("identity bridge: 回传失败原因时出错", it) }
    }

    /**
     * 纯状态通知（不含码、不含任何用户数据）。
     *
     * 走 [ExpressRelay.ACTION_IDENTITY_SYNC] 同一条通道，靠 [ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS]
     * 与「码 / 失败回执」区分 —— 模块侧对它的处理只有一件事：**记一行日志**。
     * 单独开一条 action 也行，但那样模块的接收器要多一个分支，而这条消息本就是同一件事的状态面。
     */
    private fun sendStatus(context: Context, status: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_IDENTITY_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS, status)
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            XposedBridge.logAlways("identity bridge: 已告知模块「$status」")
        }.onFailure { XposedBridge.logError("identity bridge: 回传状态时出错", it) }
    }

    /** 诊断用（设置页 / 日志汇总）。 */
    fun describe(): String =
        "installed=$installed cached=${cachedCode != null} at=$cachedAt receiver=$receiverRegistered"
}
