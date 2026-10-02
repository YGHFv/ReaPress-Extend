package io.github.YGHFv.ReaPressExtend.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPayloadPolicyTest {
    @Test
    fun `所有十类合法回传都存在明确的载荷规则`() {
        val record = recordPayload()
        val payloads = mapOf(
            ExpressRelay.ACTION_DELIVER to record,
            ExpressRelay.ACTION_ENRICH to record,
            ExpressRelay.ACTION_INTERCEPTED to record + (ExpressRelay.EXTRA_CATEGORY to "PROMOTION"),
            ExpressRelay.ACTION_COOKIE_SYNC to mapOf(ExpressRelay.EXTRA_COOKIE to "synthetic-cookie"),
            ExpressRelay.ACTION_IDENTITY_SYNC to mapOf(ExpressRelay.EXTRA_IDENTITY_CODE to "123456"),
            ExpressRelay.ACTION_HOST_QUERY_REPORT to mapOf(ExpressRelay.EXTRA_HOST_QUERY_REPORT to "rows=0"),
            ExpressRelay.ACTION_HOST_PROBE to mapOf(ExpressRelay.EXTRA_HOST_PROBE to "synthetic probe"),
            ExpressRelay.ACTION_WAKE_REPORT to mapOf(ExpressRelay.EXTRA_WAKE_REPORT to "synthetic wake"),
            ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED to mapOf(ExpressRelay.EXTRA_INTENT_ENTRY_ID to "entry"),
            WatchdogReporter.ACTION_WATCHDOG_STATUS to mapOf(WatchdogReporter.EXTRA_INSTALLED to false),
        )

        payloads.forEach { (action, payload) ->
            assertTrue(action, RelayPayloadPolicy.accepts(action, payload))
        }
    }

    @Test
    fun `合法记录载荷可带完整可选字段和通知令牌`() {
        val payload = recordPayload() + mapOf(
            ExpressRelay.EXTRA_TITLE to "快递",
            ExpressRelay.EXTRA_TRACKING to "SF123456789000",
            ExpressRelay.EXTRA_COURIER to "SF",
            ExpressRelay.EXTRA_PICKUP_CODE to "1-2-3",
            ExpressRelay.EXTRA_STATION to "合成驿站",
            ExpressRelay.EXTRA_STATUS to "READY_FOR_PICKUP",
            ExpressRelay.EXTRA_CONFIDENCE to 100,
            ExpressRelay.EXTRA_KEYWORDS to arrayOf("快递", "取件码"),
            ExpressRelay.EXTRA_TIMESTAMP to 1_700_000_000_000L,
            ExpressRelay.EXTRA_PLATFORM to "淘宝",
            ExpressRelay.EXTRA_GOODS_NAME to "合成商品",
            ExpressRelay.EXTRA_ARRIVAL_AT to 0L,
            ExpressRelay.EXTRA_LOGISTICS_DETAIL to "已到站",
            ExpressRelay.EXTRA_STATION_HOURS to null,
            ExpressRelay.EXTRA_STATION_LAT to Double.NaN,
            ExpressRelay.EXTRA_STATION_LNG to 0.0,
            ExpressRelay.EXTRA_STATION_ADDRESS to null,
            ExpressRelay.EXTRA_GOODS_IMAGE to "https://example.invalid/image",
            ExpressRelay.EXTRA_TRACE to "[]",
            ExpressRelay.EXTRA_PHONE_TAIL to "1234",
            ExpressRelay.EXTRA_PARCEL_TAIL to "0000",
            ExpressRelay.EXTRA_PREVIOUS_PICKUP_CODE to null,
            ExpressRelay.EXTRA_ORIGIN to "NOTIFICATION",
            ExpressRelay.EXTRA_NOTIFICATION_INTENT to RelayPayloadPolicy.OpaqueValue.PENDING_INTENT,
            ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI to "intent:#Intent;end",
            ExpressRelay.EXTRA_INTENT_TOKEN to "synthetic-token",
        )

        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER, payload))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_COOKIE_SYNC, payload))
    }

    @Test
    fun `空手回执以及未知有效期仍可正常通过`() {
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_COOKIE_SYNC,
            mapOf(ExpressRelay.EXTRA_COOKIE_ERROR to "not logged in")))
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_IDENTITY_SYNC,
            mapOf(ExpressRelay.EXTRA_IDENTITY_ERROR to "unavailable")))
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_IDENTITY_SYNC,
            mapOf(ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS to "ready")))
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_IDENTITY_SYNC,
            mapOf(ExpressRelay.EXTRA_IDENTITY_CODE to "123456", ExpressRelay.EXTRA_IDENTITY_EXPIRE_AT to 0L)))
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED,
            mapOf(ExpressRelay.EXTRA_INTENT_ENTRY_ID to "entry", ExpressRelay.EXTRA_NOTIFICATION_INTENT to null)))
    }

    @Test
    fun `未知操作与模块内部操作不得通过外部回传入口`() {
        listOf(null, "unknown", ExpressRelay.ACTION_TRACE_REQUEST, ExpressRelay.ACTION_RECORDS_CHANGED)
            .forEach { action -> assertFalse(RelayPayloadPolicy.accepts(action, recordPayload())) }
    }

    @Test
    fun `缺少必要字段或只有空白的消息被拒绝`() {
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER, emptyMap()))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER,
            recordPayload() + (ExpressRelay.EXTRA_TEXT to " ")))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_COOKIE_SYNC,
            mapOf(ExpressRelay.EXTRA_COOKIE_UA to "agent")))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_IDENTITY_SYNC,
            mapOf(ExpressRelay.EXTRA_IDENTITY_OFFLINE to true)))
        assertFalse(RelayPayloadPolicy.accepts(WatchdogReporter.ACTION_WATCHDOG_STATUS,
            mapOf(WatchdogReporter.EXTRA_DESCRIBE to "installed")))
    }

    @Test
    fun `类型错误与嵌套对象不应进入业务读取`() {
        val malformed = listOf(
            ExpressRelay.EXTRA_CONFIDENCE to "100",
            ExpressRelay.EXTRA_TIMESTAMP to 123,
            ExpressRelay.EXTRA_TRACKING to 1234L,
            ExpressRelay.EXTRA_STATION_LAT to Double.POSITIVE_INFINITY,
            ExpressRelay.EXTRA_KEYWORDS to arrayOf(123),
            ExpressRelay.EXTRA_KEYWORDS to intArrayOf(123),
            ExpressRelay.EXTRA_TRACE to mapOf("trace" to "untrusted"),
            ExpressRelay.EXTRA_NOTIFICATION_INTENT to "untrusted parcelable",
        )
        malformed.forEach { field ->
            assertFalse(field.first, RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER, recordPayload() + field))
        }
    }

    @Test
    fun `未知字段和跨类型敏感字段不能混入消息`() {
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_COOKIE_SYNC,
            mapOf(ExpressRelay.EXTRA_COOKIE to "cookie", "unknown" to "value")))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_HOST_PROBE,
            mapOf(ExpressRelay.EXTRA_HOST_PROBE to "probe", ExpressRelay.EXTRA_COOKIE to "cookie")))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_IDENTITY_SYNC,
            mapOf(ExpressRelay.EXTRA_IDENTITY_CODE to "123456",
                ExpressRelay.EXTRA_NOTIFICATION_INTENT to RelayPayloadPolicy.OpaqueValue.PENDING_INTENT)))
    }

    @Test
    fun `字符串与数组长度都有明确上限`() {
        assertTrue(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_COOKIE_SYNC,
            mapOf(ExpressRelay.EXTRA_COOKIE to "a".repeat(65_536))))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_COOKIE_SYNC,
            mapOf(ExpressRelay.EXTRA_COOKIE to "a".repeat(65_537))))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER,
            recordPayload() + (ExpressRelay.EXTRA_KEYWORDS to Array(65) { "keyword" })))
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER,
            recordPayload() + (ExpressRelay.EXTRA_KEYWORDS to arrayOf("a".repeat(257)))))
    }

    @Test
    fun `多个单项合法的长字段仍受总大小限制`() {
        val payload = recordPayload() + mapOf(
            ExpressRelay.EXTRA_TEXT to "a".repeat(65_536),
            ExpressRelay.EXTRA_TRACE to "a".repeat(131_072),
            ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI to "a".repeat(65_536),
        )
        assertFalse(RelayPayloadPolicy.accepts(ExpressRelay.ACTION_DELIVER, payload))
    }

    private fun recordPayload(): Map<String, Any?> = mapOf(
        ExpressRelay.EXTRA_SOURCE_PACKAGE to ExpressRelay.HOST_PACKAGE,
        ExpressRelay.EXTRA_TEXT to "synthetic notification",
    )
}
