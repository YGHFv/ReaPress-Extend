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
 * 身份码取码器：在菜鸟进程里用菜鸟自己的会话取码。MTOP H5 通道上这条接口就是关着的
 * （两轮真机实证：预热即 `FAIL_SYS_SESSION_EXPIRED`、连 `_m_h5_tk` 都不下发，而同一时刻同一份
 * cookie 拉轨迹是成功的；换签名、换 appName、换 verifyVersion 都没用）。在菜鸟进程里直接调
 * 它自己的入口，就完全绕开了「复刻签名 / 猜 appKey / 撞风控」。
 *
 * 取码两条路（先便宜的再新鲜的）：挂 `IdentityBean#setIdentityCode`（实体字段是 JSON 映射名，
 * 不随混淆变）白得宿主已取好的码；缓存没有或过期时由 [triggerHost] 主动触发宿主取码入口。
 * `identity_code.api.a` 是这段代码里唯一的混淆名，可接受：拿不到只是退化成缓存那条路，
 * 失败在日志里可见。
 *
 * 三条不变量：身份码不进日志（只记长度与来源）；绝不在宿主主线程等网络回调（只有构造宿主对象
 * 并调用这一步 post 到主线程，回来由 latch 唤醒 executor）；异常只记日志，任何失败都不让菜鸟崩。
 */
internal object CainiaoIdentityBridge {

    private const val ONLINE_API = "com.cainiao.wireless.identity_code.api.a"

    private const val LISTENER = "com.cainiao.wireless.identity_code.listener.OnRequestResultListener"

    /** 响应实体（未混淆，JSON 映射字段名）。只挂它的 `setIdentityCode`，不扫描不枚举。 */
    private const val BEAN = "com.cainiao.wireless.identity_code.entity.IdentityBean"

    /** 等宿主取码上限 7 秒（正常一秒内，超时基本是宿主会话也失效了）。模块侧等待上限必须大于 HOST_TIMEOUT_MS + MAIN_POST_TIMEOUT_MS，否则「宿主超时」的回执回来时没人在听，与「广播没送到」同形。 */
    private const val HOST_TIMEOUT_MS = 7_000L

    /** post 到主线程这一步的上限 —— 主线程被卡住时不能把这条线程一起拖死。 */
    private const val MAIN_POST_TIMEOUT_MS = 2_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reapress-identity").apply { isDaemon = true }
    }

    @Volatile private var installed = false

    @Volatile private var loader: ClassLoader? = null

    /** 正在主动触发宿主取码：别把自己触发的那份再当「宿主主动取到」推一次（结果已由应答路径回传）。 */
    @Volatile private var triggering = false

    // 进程内缓存。字段全 volatile：写在主线程（setter 回调），读在 executor 线程。

    @Volatile private var cachedCode: String? = null

    @Volatile private var cachedExpireAt = 0L

    @Volatile private var cachedOffline = false

    @Volatile private var cachedAt = 0L

    /**
     * 装 hook：只挂 `IdentityBean#setIdentityCode` —— 在线与离线两条路都必经这里，
     * 且字段名由 JSON 契约决定不随混淆变。只在菜鸟主进程调用（[CainiaoPackageHook.install]
     * 的 `isMainProcess` 把关）：非主进程既没有初始化环境，又会回「取不到」盖掉主进程刚取到的码。
     * 这里会 `findClass(BEAN)`，但调用方是 `onPackageReady`，不在类加载路径上挂钩。
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
     * 注册模块发来的索取请求（幂等）。权限闸与 Android 13 导出性 flag 交给 [HostReceiverRegistrar]
     * （要求发送方持 signature 级权限，没这道闸设备上任何应用都能拿用户的身份码）。
     * 注册成功就发一条状态回执：排查判据是「identity wake 之后有没有 identity bridge ready」。
     */
    fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        // 桥没装说明本进程不取身份码，注册上去只会回「取不到」，盖掉主进程刚取到的码。
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
            // 一行「收到了」：把「通道没注册」和「注册了但宿主取不到码」分开。
            XposedBridge.logAlways("identity bridge: 收到模块索取")
            // 取码要等宿主自己的网络回调，绝不能在主线程等（onReceive 只有 10 秒预算）。
            executor.execute {
                runCatching { answer(context.applicationContext) }
                    .onFailure { XposedBridge.logError("identity bridge: answer failed", it) }
            }
        }
    }

    // ---------------------------------------------------------------- 取码

    private class Captured(val code: String, val expireAt: Long, val offline: Boolean)

    /** 先缓存后现取；过期判据用宿主自己给的有效期（`expireTime`），不是自己拍。 */
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
        // 失败原因会原样出现在弹窗里（IdentityCodeFetcher 的 HostFailed 分支）。
        sendFailure(context, "菜鸟没能给出身份码（可能是它自己的登录态也要刷新了）")
    }

    private fun cached(): Captured? {
        val code = cachedCode?.takeIf { it.isNotBlank() } ?: return null
        // 0 表示宿主没给有效期，一律当作可用（与 core 那边规则一致）。
        if (cachedExpireAt > 0L && System.currentTimeMillis() >= cachedExpireAt) return null
        return Captured(code, cachedExpireAt, cachedOffline)
    }

    /** 宿主自己解析出身份码了。除填缓存还主动推给模块：菜鸟不在后台时小米会静默丢弃请求广播，推送是「连不上菜鸟」时的兜底。 */
    private fun onIdentityCodeSet(param: XC_MethodHook.MethodHookParam) {
        val code = param.args.firstOrNull() as? String ?: return
        if (code.isBlank()) return
        val bean = param.thisObject
        cachedCode = code
        cachedExpireAt = readExpireAt(bean)
        cachedOffline = readOffline(bean)
        cachedAt = System.currentTimeMillis()

        // 我们自己刚触发的那一次：结果走应答路径回传，这里不重复推。
        if (triggering) return
        val context = HostContextHolder.acquire() ?: return
        send(context, Captured(code, cachedExpireAt, cachedOffline), PROVENANCE_CACHE)
    }

    /**
     * 驱动宿主取码入口 `identity_code.api.a#a(OnRequestResultListener)`。回调是接口，用动态代理接住
     * —— 只依赖接口契约不依赖混淆名，参数里带 `identityCode` 的对象就是答案（[readCode] 按字段名找）。
     * 线程安排：[executor] 上调进来，构造宿主对象与调用 post 到主线程（宿主链路要主线程 looper），
     * 本线程 latch 等结果。@return 超时/宿主没回结果时 null。
     */
    private fun triggerHost(): Captured? {
        val classLoader = loader ?: return null
        val listenerClass = XposedHelpers.findClass(LISTENER, classLoader)
        val apiClass = XposedHelpers.findClass(ONLINE_API, classLoader)

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
            // 动态代理也会接住 equals/hashCode/toString，按「声明在目标接口上」筛掉。
            if (method.declaringClass == listenerClass) {
                slot.compareAndSet(null, args?.firstOrNull())
                resultLatch.countDown()
            }
            null
        }

        val apiRef = AtomicReference<Any?>()
        val posted = CountDownLatch(1)

        // 闸门必须在发请求之前落下：响应快时会先于下一行代码回到 setIdentityCode，
        // 否则会把自己触发的那份再当推送发一次。
        triggering = true
        try {
            Handler(Looper.getMainLooper()).post {
                runCatching {
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
            // 先解除闸门再退订：超时后才回来的响应是宿主自己的新结果，照常推给模块。
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

    /** 把临时构造的宿主对象从 EventBus 摘掉（`onDestroy`，方法名来自反编译），否则每次索取泄漏一个被响应驱动的死对象。 */
    private fun releaseHostObject(apiClass: Class<*>, api: Any?) {
        if (api == null) return
        Handler(Looper.getMainLooper()).post {
            runCatching { apiClass.getMethod("onDestroy").invoke(api) }
        }
    }

    // ---------------------------------------------------------------- 反射读取

    /** 按字段名找身份码（IdentityBean 或响应内层 IdentityResponseData 字段名相同），不认类型不认路径。 */
    private fun readCode(target: Any?): String? {
        val value = readField(target, "identityCode") as? String
        return value?.takeIf { it.isNotBlank() }
    }

    /** 有效期。单位陷阱：`IdentityBean.expireTime` 是毫秒、`IdentityResponseData.expireTime` 是秒，拿秒当毫秒会「刚到手就过期」；1e11 门槛区分（epoch 秒 ~1.7e9，毫秒 ~1.7e12）。 */
    private fun readExpireAt(target: Any?): Long {
        val raw = (readField(target, "expireTime") as? Number)?.toLong() ?: return 0L
        if (raw <= 0L) return 0L
        return if (raw < 100_000_000_000L) raw * 1000L else raw
    }

    /** 是不是离线码。宿主该字段是字符串（在线 "2"、离线 "1"），不要按布尔读。 */
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
            // 只记长度与来源，绝不记内容（身份码 = 取件凭据）。
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

    /** 纯状态通知（不含码、不含任何用户数据），走同一条通道靠 [ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS] 区分，模块侧只记一行日志。 */
    private fun sendStatus(context: Context, status: String) {
        runCatching {
            val intent = Intent(ExpressRelay.ACTION_IDENTITY_SYNC)
                .setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                .putExtra(ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS, status)
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
            XposedBridge.logAlways("identity bridge: 已告知模块「$status」")
        }.onFailure { XposedBridge.logError("identity bridge: 回传状态时出错", it) }
    }

    fun describe(): String =
        "installed=$installed cached=${cachedCode != null} at=$cachedAt receiver=$receiverRegistered"
}
