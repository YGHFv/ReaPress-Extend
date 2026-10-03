package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.core.PackageSyncSession
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Verified against 8.11.923 by MT MCP. Never changes CDSS foreground state or replaces its listeners. */
internal class CainiaoPackageSyncClient(
    private val load: (String) -> Class<*>,
    private val allowed: () -> Boolean,
    private val timeoutMs: Long = 12_000L,
    private val dispatch: (() -> Unit) -> Unit = { it() },
    private val pace: () -> Unit = {},
) : PackageSyncSession.Port {
    class Failure(val kind: String) : Exception(kind)

    override fun snapshot(): PackageSyncSession.Snapshot {
        if (!allowed()) throw Failure("blocked")
        val account = static(ENV, "Wq") as? String
        if (account.isNullOrBlank()) throw Failure("login_required")
        val manager = static(MANAGER, "WQ") ?: throw Failure("not_ready")
        val model = invoke(manager, "pL", TOPIC) ?: throw Failure("not_ready")
        val topic = field(model, "topicDO") ?: throw Failure("not_ready")
        if (field(model, "locked") != false || field(model, "dbSchemaConfig") == null ||
            field(topic, "initStatus") != 1 || field(topic, "needReinit") != false ||
            field(topic, "userId") != account || field(topic, "topic") != TOPIC
        ) throw Failure("not_ready")
        return PackageSyncSession.Snapshot(account, field(topic, "version") as? String ?: "", field(topic, "localSequence") as? String ?: "")
    }

    override fun remoteSequence(snapshot: PackageSyncSession.Snapshot): String {
        val result = request(snapshot, "g")
        val item = responseItem(result, snapshot, 1)
        if (item.optString("version") != snapshot.version) throw Failure("schema_changed")
        return item.optString("sequence").takeIf { it.isNotBlank() } ?: throw Failure("invalid_response")
    }

    override fun pullAndApply(snapshot: PackageSyncSession.Snapshot): String {
        pace()
        val result = request(snapshot, "f")
        val item = responseItem(result, snapshot, 2)
        if (item.optJSONArray("childList") == null) throw Failure("invalid_response")
        val sequence = item.optString("sequence").takeIf { it.isNotBlank() } ?: throw Failure("invalid_response")
        if (sequence == snapshot.sequence && item.getJSONArray("childList").length() > 0) throw Failure("invalid_response")
        if (snapshot() != snapshot) throw Failure("session_changed")
        // Use the same synchronous downward processor as rpc/b$1, not a guessed table writer.
        static(DOWNWARD, "bQ", result, "reapress-package-data")
        if (snapshot().sequence != sequence) throw Failure("apply_unconfirmed")
        return sequence
    }

    private fun responseItem(body: String, snapshot: PackageSyncSession.Snapshot, type: Int): JSONObject {
        if (snapshot() != snapshot) throw Failure("session_changed")
        val root = JSONObject(body)
        if (root.optString("protocol") != "csp") throw Failure("invalid_response")
        val content = root.getJSONObject("content")
        if (content.optInt("response_type") != type) throw Failure("invalid_response")
        val response = content.getJSONObject("response_content")
        if (response.optString("user_id") != snapshot.account) throw Failure("session_changed")
        val rows = response.getJSONArray("data")
        // Only this topic was requested; never apply unrelated account/topic data.
        if (rows.length() != 1) throw Failure("invalid_response")
        return rows.getJSONObject(0).also {
            if (it.optString("topic") != TOPIC) throw Failure("invalid_response")
        }
    }

    private fun request(snapshot: PackageSyncSession.Snapshot, builder: String): String {
        if (snapshot() != snapshot) throw Failure("session_changed")
        // Build from a detached cursor snapshot. Do not mutate the live TopicModel or init/lock flags.
        val topic = load(TOPIC_DO).getConstructor().newInstance()
        set(topic, "topic", TOPIC)
        set(topic, "version", snapshot.version)
        set(topic, "localSequence", snapshot.sequence)
        set(topic, "needLogin", true)
        set(topic, "userId", snapshot.account)
        set(topic, "childListValid", true)
        val model = load(TOPIC_MODEL).getConstructor().newInstance()
        set(model, "topicDO", topic)
        val envelope = static(PROTOCOL, builder, listOf(model), true) as? String ?: throw Failure("not_ready")
        val content = static(TRANSFORM, "vZ", envelope) as? String
        if (content.isNullOrBlank()) throw Failure("not_ready")
        val request = load(REQUEST).getConstructor().newInstance()
        set(request, "requestContent", content)
        set(request, "utdid", load(ENV).getField("utdid").get(null))
        val business = static(BUSINESS_UTILS, "obtainCNMtopBusiness", request) ?: throw Failure("not_ready")
        val result = AtomicReference<String?>()
        val failure = AtomicReference<String?>()
        val completed = AtomicBoolean()
        val done = CountDownLatch(1)
        val startLock = Any()
        var ended = false
        val listenerClass = load(LISTENER)
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ReaPressPackageSyncListener"
                "onSuccess", "onError", "onSystemError" -> {
                    if (!completed.compareAndSet(false, true)) return@newProxyInstance null
                    try {
                        if (method.name == "onSuccess") {
                            val out = args?.getOrNull(2) ?: throw Failure("invalid_response")
                            val data = invoke(out, "getData") ?: throw Failure("invalid_response")
                            val text = field(data, "result") as? String ?: throw Failure("invalid_response")
                            if (text.length > MAX_RESPONSE_CHARS) throw Failure("invalid_response")
                            result.compareAndSet(null, text)
                        } else {
                            val response = args?.getOrNull(1)
                            val code = response?.let { invoke(it, "getRetCode") as? String }.orEmpty()
                            failure.compareAndSet(null, if (isRiskCode(code)) "risk" else "request_failed")
                        }
                    } catch (_: Throwable) {
                        failure.compareAndSet(null, "invalid_response")
                    } finally { done.countDown() }
                    null
                }
                else -> null
            }
        }
        try {
            invoke(business, "registerListener", listener)
            invoke(business, "reqMethod", load(METHOD).getField("POST").get(null))
            invoke(business, "retryTime", 0)
            invoke(business, "showLoginUI", false)
            if (invoke(business, "isNeedAuth") != false) throw Failure("login_required")
            invoke(business, "useWua")
            if (snapshot() != snapshot) throw Failure("session_changed")
            dispatch {
                synchronized(startLock) {
                    if (!ended) {
                        try {
                            if (snapshot() != snapshot) throw Failure("session_changed")
                            invoke(business, "startRequest", 0, load(RESPONSE))
                        } catch (_: Throwable) {
                            failure.compareAndSet(null, "request_failed")
                            done.countDown()
                        }
                    }
                }
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw Failure("timeout")
            failure.get()?.let { throw Failure(it) }
            return result.get() ?: throw Failure("invalid_response")
        } finally {
            completed.set(true)
            synchronized(startLock) {
                ended = true
                runCatching { invoke(business, "cancelRequest") }
            }
        }
    }

    private fun static(owner: String, name: String, vararg args: Any?): Any? =
        method(load(owner), name, args).invoke(null, *args)

    private fun invoke(owner: Any, name: String, vararg args: Any?): Any? =
        method(owner.javaClass, name, args).invoke(owner, *args)

    private fun method(owner: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method {
        val candidates = owner.methods.filter { candidate ->
        !candidate.isBridge && candidate.name == name && candidate.parameterCount == args.size && candidate.parameterTypes.zip(args).all { (type, arg) ->
            arg == null && !type.isPrimitive || arg != null && (type.isInstance(arg) ||
                type == Integer.TYPE && arg is Int || type == java.lang.Boolean.TYPE && arg is Boolean)
        }
        }.distinctBy { it.parameterTypes.toList() }
        // IRemoteListener extends MtopListener: both overloads accept the same proxy.
        return candidates.singleOrNull { candidate ->
            candidates.all { other -> other.parameterTypes.zip(candidate.parameterTypes).all { (broad, narrow) ->
                broad.isAssignableFrom(narrow)
            } }
        } ?: throw Failure("abi_mismatch")
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getField(name).get(owner)
    private fun set(owner: Any, name: String, value: Any?) { owner.javaClass.getField(name).set(owner, value) }

    companion object {
        const val TOPIC = "package_list_v4"
        const val SUPPORTED_VERSION = "8.11.923"
        const val ENV = "com.cainiao.wireless.cdss.d"
        const val MANAGER = "com.cainiao.wireless.cdss.core.e"
        const val TOPIC_DO = "com.cainiao.wireless.cdss.core.TopicDO"
        const val TOPIC_MODEL = "com.cainiao.wireless.cdss.core.TopicModel"
        const val PROTOCOL = "com.cainiao.wireless.cdss.protocol.a"
        const val TRANSFORM = "com.cainiao.wireless.dorado.module.channel.mtop.c"
        const val REQUEST = "com.cainiao.wireless.dorado.module.channel.mtop.MtopCainiaoNewDoradoClientRequestServiceRequestRequest"
        const val RESPONSE = "com.cainiao.wireless.dorado.module.channel.mtop.MtopCainiaoDoradoClientRequestServiceRequestResponse"
        const val BUSINESS_UTILS = "com.cainiao.wireless.network.CNMtopBusinessUtils"
        const val LISTENER = "com.taobao.tao.remotebusiness.IRemoteBaseListener"
        const val METHOD = "mtopsdk.mtop.domain.MethodEnum"
        const val DOWNWARD = "com.cainiao.wireless.cdss.core.DownwardSync"
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024

        fun isRiskCode(code: String): Boolean = listOf("RGV", "LIMIT", "RISK", "CAPTCHA", "VALIDATE", "USER_VALIDATE")
            .any { code.contains(it, ignoreCase = true) }
    }
}
