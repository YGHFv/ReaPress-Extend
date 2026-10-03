package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.core.PackageSyncSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic host ABI, no Android account, socket, EventBus or production host classes. */
class CainiaoPackageSyncClientTest {
    @Before fun reset() {
        Env.account = "account"
        Manager.model = Model().apply { topicDO = Topic().apply { localSequence = "1" } }
        Business.requests.clear()
        Business.retry = -1
        Business.loginUi = true
        Business.cancelled = 0
        Business.error = null
        Business.silent = false
        Business.responseAccount = "account"
        Business.responseTopic = CainiaoPackageSyncClient.TOPIC
        Business.responseVersion = "1.3"
        Business.applied = true
        Business.startFailure = false
        Business.allowed = true
        Business.afterResponse = {}
        Business.needsAuth = false
        Business.duplicateError = false
    }

    @Test fun realReflectionPathRequestsSequenceThenDataAndConfirmsAppliedCursor() {
        assertEquals(1, PackageSyncSession(client()).run())
        assertEquals(listOf(3, 2), Business.requests)
        assertEquals("2", Manager.model.topicDO!!.localSequence)
        assertEquals(0, Business.retry)
        assertFalse(Business.loginUi)
        assertEquals(2, Business.cancelled)
    }
    @Test fun missingSessionDoesNotMakeARequest() {
        Env.account = ""
        assertFailure("login_required") { client().snapshot() }
        assertTrue(Business.requests.isEmpty())
    }
    @Test fun lockedTopicDoesNotMakeARequest() {
        Manager.model.locked = true
        assertFailure("not_ready") { client().snapshot() }
        assertTrue(Business.requests.isEmpty())
    }
    @Test fun missingSchemaDoesNotMakeARequest() {
        Manager.model.dbSchemaConfig = null
        assertFailure("not_ready") { client().snapshot() }
    }
    @Test fun otherAccountModelDoesNotMakeARequest() {
        Manager.model.topicDO!!.userId = "old-account"
        assertFailure("not_ready") { client().snapshot() }
    }
    @Test fun topicReinitRequiredDoesNotForceResetOrUnlock() {
        Manager.model.topicDO!!.needReinit = true
        assertFailure("not_ready") { client().snapshot() }
        assertTrue(Manager.model.topicDO!!.needReinit)
    }
    @Test fun riskResponseAbortsWithoutRetry() {
        Business.error = "FAIL_SYS_USER_VALIDATE"
        assertFailure("risk") { PackageSyncSession(client()).run() }
        assertEquals(listOf(3), Business.requests)
        assertEquals(1, Business.cancelled)
    }
    @Test fun timeoutCancelsRequest() {
        Business.silent = true
        assertFailure("timeout") { PackageSyncSession(client()).run() }
        assertEquals(1, Business.cancelled)
    }
    @Test fun ordinaryRequestFailureIsNotSuccess() {
        Business.error = "FAIL_SYS_SESSION_EXPIRED"
        assertFailure("request_failed") { PackageSyncSession(client()).run() }
        assertEquals(listOf(3), Business.requests)
    }
    @Test fun wrongResponseAccountNeverReachesDownwardProcessor() {
        Business.responseAccount = "other"
        assertFailure("session_changed") { PackageSyncSession(client()).run() }
        assertEquals("1", Manager.model.topicDO!!.localSequence)
    }
    @Test fun unexpectedTopicNeverReachesDownwardProcessor() {
        Business.responseTopic = "other-topic"
        assertFailure("invalid_response") { PackageSyncSession(client()).run() }
        assertEquals("1", Manager.model.topicDO!!.localSequence)
    }
    @Test fun schemaChangeDoesNotWriteOrReinitializeTables() {
        Business.responseVersion = "2.0"
        assertFailure("schema_changed") { PackageSyncSession(client()).run() }
        assertEquals(listOf(3), Business.requests)
    }
    @Test fun applyFailureDoesNotReportCompletion() {
        Business.applied = false
        assertFailure("apply_unconfirmed") { PackageSyncSession(client()).run() }
    }
    @Test fun accountSwitchAfterNetworkAbortsBeforeApply() {
        Business.afterResponse = { Env.account = "other" }
        assertFailure("not_ready") { PackageSyncSession(client()).run() }
        assertEquals("1", Manager.model.topicDO!!.localSequence)
    }
    @Test fun requestStartExceptionStillCancels() {
        Business.startFailure = true
        assertThrows(Exception::class.java) { PackageSyncSession(client()).run() }
        assertEquals(1, Business.cancelled)
    }
    @Test fun persistentRiskGatePreventsNetworkEntry() {
        Business.allowed = false
        assertFailure("blocked") { client().snapshot() }
        assertTrue(Business.requests.isEmpty())
    }
    @Test fun queuedMainThreadStartAfterTimeoutIsDiscarded() {
        val queued = mutableListOf<() -> Unit>()
        assertFailure("timeout") { PackageSyncSession(client { queued += it }).run() }
        queued.single().invoke()
        assertTrue(Business.requests.isEmpty())
        assertEquals(1, Business.cancelled)
    }
    @Test fun authorizationUiRequirementAbortsInsteadOfOpeningUi() {
        Business.needsAuth = true
        assertFailure("login_required") { PackageSyncSession(client()).run() }
        assertTrue(Business.requests.isEmpty())
        assertEquals(1, Business.cancelled)
    }
    @Test fun duplicateCallbackDoesNotOverwriteCompletedResult() {
        Business.duplicateError = true
        assertEquals(1, PackageSyncSession(client()).run())
    }

    private fun assertFailure(kind: String, block: () -> Unit) {
        assertEquals(kind, assertThrows(CainiaoPackageSyncClient.Failure::class.java) { block() }.kind)
    }

    private fun client(dispatch: (() -> Unit) -> Unit = { it() }) = CainiaoPackageSyncClient({ name ->
        when (name) {
            CainiaoPackageSyncClient.ENV -> Env::class.java
            CainiaoPackageSyncClient.MANAGER -> Manager::class.java
            CainiaoPackageSyncClient.TOPIC_DO -> Topic::class.java
            CainiaoPackageSyncClient.TOPIC_MODEL -> Model::class.java
            CainiaoPackageSyncClient.PROTOCOL -> Protocol::class.java
            CainiaoPackageSyncClient.TRANSFORM -> Transform::class.java
            CainiaoPackageSyncClient.REQUEST -> Request::class.java
            CainiaoPackageSyncClient.RESPONSE -> Response::class.java
            CainiaoPackageSyncClient.BUSINESS_UTILS -> Business::class.java
            CainiaoPackageSyncClient.LISTENER -> Listener::class.java
            CainiaoPackageSyncClient.METHOD -> Method::class.java
            CainiaoPackageSyncClient.DOWNWARD -> Downward::class.java
            else -> error("Unexpected host class $name")
        }
    }, { Business.allowed }, timeoutMs = 10, dispatch = dispatch)

    class Env { companion object {
        var account = "account"
        @JvmField val utdid = "synthetic-device"
        @JvmStatic fun Wq() = account
    } }
    class Manager {
        fun pL(topic: String): Model? = model.takeIf { topic == CainiaoPackageSyncClient.TOPIC }
        companion object {
            var model = Model()
            @JvmStatic fun WQ() = Manager()
        }
    }
    class Topic {
        @JvmField var topic = CainiaoPackageSyncClient.TOPIC
        @JvmField var version = "1.3"
        @JvmField var localSequence = "1"
        @JvmField var needLogin = true
        @JvmField var userId = "account"
        @JvmField var childListValid = true
        @JvmField var initStatus = 1
        @JvmField var needReinit = false
    }
    class Model {
        @JvmField var topicDO: Topic? = null
        @JvmField var locked = false
        @JvmField var dbSchemaConfig: Any? = Any()
    }
    class Protocol { companion object {
        @JvmStatic fun g(models: List<Model>, login: Boolean) = build(models, login, 3)
        @JvmStatic fun f(models: List<Model>, login: Boolean) = build(models, login, 2)
        private fun build(models: List<Model>, login: Boolean, type: Int): String {
            assertTrue(login)
            assertEquals(1, models.size)
            assertNotSame(Manager.model, models.single())
            assertEquals(Manager.model.topicDO!!.localSequence, models.single().topicDO!!.localSequence)
            return JSONObject().put("type", type).toString()
        }
    } }
    class Transform { companion object { @JvmStatic fun vZ(body: String) = body } }
    class Request { @JvmField var requestContent = ""; @JvmField var utdid: String? = null }
    enum class Method { POST }
    interface Listener {
        fun onSuccess(type: Int, response: Error, out: Response, context: Any?)
        fun onError(type: Int, response: Error, context: Any?)
    }
    class Error(private val code: String = "") { fun getRetCode() = code }
    class Data(@JvmField val result: String)
    class Response(private val data: Data) { fun getData() = data }
    class Business(private val request: Request) {
        private lateinit var listener: Listener
        fun registerListener(value: Listener): Business { listener = value; return this }
        fun reqMethod(method: Method): Business { assertEquals(Method.POST, method); return this }
        fun retryTime(times: Int): Business { retry = times; return this }
        fun showLoginUI(show: Boolean): Business { loginUi = show; return this }
        fun isNeedAuth() = needsAuth
        fun useWua() = this
        fun cancelRequest() { cancelled++ }
        fun startRequest(type: Int, response: Class<*>) {
            if (startFailure) error("synthetic start failure")
            assertEquals(0, type)
            assertEquals(Response::class.java, response)
            val requestType = JSONObject(request.requestContent).getInt("type")
            requests += requestType
            if (silent) return
            if (error != null) listener.onError(0, Error(error!!), null) else {
                val item = JSONObject().put("topic", responseTopic).put("version", responseVersion).put("sequence", "2").put("childList", JSONArray())
                val body = JSONObject().put("protocol", "csp").put("content", JSONObject()
                    .put("response_type", if (requestType == 3) 1 else 2)
                    .put("response_content", JSONObject().put("user_id", responseAccount).put("data", JSONArray().put(item))))
                listener.onSuccess(0, Error(), Response(Data(body.toString())), null)
                if (duplicateError) listener.onError(0, Error("DUPLICATE"), null)
                afterResponse()
            }
        }
        companion object {
            val requests = mutableListOf<Int>()
            var retry = -1
            var loginUi = true
            var cancelled = 0
            var error: String? = null
            var silent = false
            var responseAccount = "account"
            var responseTopic = CainiaoPackageSyncClient.TOPIC
            var responseVersion = "1.3"
            var applied = true
            var startFailure = false
            var allowed = true
            var afterResponse: () -> Unit = {}
            var needsAuth = false
            var duplicateError = false
            @JvmStatic fun obtainCNMtopBusiness(request: Request) = Business(request)
        }
    }
    class Downward { companion object {
        @JvmStatic fun bQ(body: String, id: String) {
            assertEquals("reapress-package-data", id)
            if (Business.applied) Manager.model.topicDO!!.localSequence = JSONObject(body).getJSONObject("content")
                .getJSONObject("response_content").getJSONArray("data").getJSONObject(0).getString("sequence")
        }
    } }
}
