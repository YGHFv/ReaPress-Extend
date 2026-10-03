package io.github.YGHFv.ReaPressExtend.relay

internal object RelayPayloadPolicy {
    const val MAX_FIELDS = 48
    const val MAX_TOTAL_CHARS = 262_144

    enum class OpaqueValue { PENDING_INTENT }

    private enum class Kind { STRING, INTEGER, LONG, DOUBLE, BOOLEAN, STRINGS, PENDING_INTENT }

    private data class Field(val kind: Kind, val limit: Int = 4_096)

    private val recordFields = mapOf(
        ExpressRelay.EXTRA_SOURCE_PACKAGE to Field(Kind.STRING, 255),
        ExpressRelay.EXTRA_TITLE to Field(Kind.STRING, 16_384),
        ExpressRelay.EXTRA_TEXT to Field(Kind.STRING, 65_536),
        ExpressRelay.EXTRA_TRACKING to Field(Kind.STRING, 256),
        ExpressRelay.EXTRA_COURIER to Field(Kind.STRING, 128),
        ExpressRelay.EXTRA_PICKUP_CODE to Field(Kind.STRING, 256),
        ExpressRelay.EXTRA_PICKUP_OBSERVED_AT to Field(Kind.LONG),
        ExpressRelay.EXTRA_STATION to Field(Kind.STRING),
        ExpressRelay.EXTRA_STATUS to Field(Kind.STRING, 128),
        ExpressRelay.EXTRA_CONFIDENCE to Field(Kind.INTEGER),
        ExpressRelay.EXTRA_KEYWORDS to Field(Kind.STRINGS, 256),
        ExpressRelay.EXTRA_TIMESTAMP to Field(Kind.LONG),
        ExpressRelay.EXTRA_PLATFORM to Field(Kind.STRING, 256),
        ExpressRelay.EXTRA_GOODS_NAME to Field(Kind.STRING),
        ExpressRelay.EXTRA_ARRIVAL_AT to Field(Kind.LONG),
        ExpressRelay.EXTRA_LOGISTICS_DETAIL to Field(Kind.STRING, 16_384),
        ExpressRelay.EXTRA_STATION_HOURS to Field(Kind.STRING),
        ExpressRelay.EXTRA_STATION_LAT to Field(Kind.DOUBLE),
        ExpressRelay.EXTRA_STATION_LNG to Field(Kind.DOUBLE),
        ExpressRelay.EXTRA_STATION_ADDRESS to Field(Kind.STRING),
        ExpressRelay.EXTRA_GOODS_IMAGE to Field(Kind.STRING, 16_384),
        ExpressRelay.EXTRA_TRACE to Field(Kind.STRING, 131_072),
        ExpressRelay.EXTRA_PHONE_TAIL to Field(Kind.STRING, 256),
        ExpressRelay.EXTRA_PARCEL_TAIL to Field(Kind.STRING, 256),
        ExpressRelay.EXTRA_PREVIOUS_PICKUP_CODE to Field(Kind.STRING, 256),
        ExpressRelay.EXTRA_ORIGIN to Field(Kind.STRING, 128),
    )

    private val notificationFields = mapOf(
        ExpressRelay.EXTRA_NOTIFICATION_INTENT to Field(Kind.PENDING_INTENT),
        ExpressRelay.EXTRA_NOTIFICATION_INTENT_URI to Field(Kind.STRING, 65_536),
        ExpressRelay.EXTRA_INTENT_TOKEN to Field(Kind.STRING, 256),
    )

    private val schemas = mapOf(
        ExpressRelay.ACTION_PACKAGE_SYNC_REPORT to mapOf(
            ExpressRelay.EXTRA_PACKAGE_SYNC_ID to Field(Kind.STRING, 36),
            ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS to Field(Kind.STRING, 40),
            ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT to Field(Kind.LONG),
        ),
        ExpressRelay.ACTION_DELIVER to recordFields + notificationFields,
        ExpressRelay.ACTION_INTERCEPTED to recordFields + notificationFields +
            (ExpressRelay.EXTRA_CATEGORY to Field(Kind.STRING, 128)),
        ExpressRelay.ACTION_ENRICH to recordFields + (ExpressRelay.EXTRA_PACKAGE_SNAPSHOT to Field(Kind.BOOLEAN)),
        ExpressRelay.ACTION_COOKIE_SYNC to mapOf(
            ExpressRelay.EXTRA_COOKIE to Field(Kind.STRING, 65_536),
            ExpressRelay.EXTRA_COOKIE_UA to Field(Kind.STRING),
            ExpressRelay.EXTRA_COOKIE_ERROR to Field(Kind.STRING),
        ),
        ExpressRelay.ACTION_IDENTITY_SYNC to mapOf(
            ExpressRelay.EXTRA_IDENTITY_CODE to Field(Kind.STRING, 512),
            ExpressRelay.EXTRA_IDENTITY_EXPIRE_AT to Field(Kind.LONG),
            ExpressRelay.EXTRA_IDENTITY_OFFLINE to Field(Kind.BOOLEAN),
            ExpressRelay.EXTRA_IDENTITY_PROVENANCE to Field(Kind.STRING, 256),
            ExpressRelay.EXTRA_IDENTITY_ERROR to Field(Kind.STRING),
            ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS to Field(Kind.STRING),
        ),
        ExpressRelay.ACTION_HOST_QUERY_REPORT to mapOf(
            ExpressRelay.EXTRA_HOST_QUERY_REPORT to Field(Kind.STRING, 16_384),
        ),
        ExpressRelay.ACTION_HOST_PROBE to mapOf(
            ExpressRelay.EXTRA_HOST_PROBE to Field(Kind.STRING, 65_536),
        ),
        ExpressRelay.ACTION_WAKE_REPORT to mapOf(
            ExpressRelay.EXTRA_WAKE_REPORT to Field(Kind.STRING, 16_384),
        ),
        ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED to mapOf(
            ExpressRelay.EXTRA_INTENT_ENTRY_ID to Field(Kind.STRING, 512),
            ExpressRelay.EXTRA_NOTIFICATION_INTENT to Field(Kind.PENDING_INTENT),
        ),
        WatchdogReporter.ACTION_WATCHDOG_STATUS to mapOf(
            WatchdogReporter.EXTRA_INSTALLED to Field(Kind.BOOLEAN),
            WatchdogReporter.EXTRA_DISABLED to Field(Kind.BOOLEAN),
            WatchdogReporter.EXTRA_REASON to Field(Kind.STRING),
            WatchdogReporter.EXTRA_DESCRIBE to Field(Kind.STRING, 16_384),
        ),
    )

    fun accepts(action: String?, payload: Map<String, Any?>): Boolean {
        val schema = schemas[action] ?: return false
        if (payload.size > MAX_FIELDS) return false
        var characters = 0
        for ((name, value) in payload) {
            val field = schema[name] ?: return false
            if (value == null) continue
            val valid = when (field.kind) {
                Kind.STRING -> value is String && value.length <= field.limit
                Kind.INTEGER -> value is Int
                Kind.LONG -> value is Long && value >= 0L
                Kind.DOUBLE -> value is Double && (value.isNaN() || value.isFinite())
                Kind.BOOLEAN -> value is Boolean
                Kind.STRINGS -> value is Array<*> && value.size <= 64 &&
                    value.all { it is String && it.length <= field.limit }
                Kind.PENDING_INTENT -> value == OpaqueValue.PENDING_INTENT
            }
            if (!valid) return false
            characters += when (value) {
                is String -> value.length
                is Array<*> -> value.sumOf { (it as String).length }
                else -> 0
            }
            if (characters > MAX_TOTAL_CHARS) return false
        }
        fun hasText(name: String): Boolean = (payload[name] as? String)?.isNotBlank() == true
        return when (action) {
            ExpressRelay.ACTION_PACKAGE_SYNC_REPORT ->
                (payload[ExpressRelay.EXTRA_PACKAGE_SYNC_ID] as? String)?.matches(
                    Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) == true &&
                payload[ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS] in setOf("busy", "synced", "unsupported", "login_required",
                    "not_ready", "risk", "blocked", "cooling", "failed", "schema_changed", "session_changed",
                    "invalid_response", "apply_unconfirmed", "timeout", "request_failed")
            ExpressRelay.ACTION_DELIVER, ExpressRelay.ACTION_ENRICH, ExpressRelay.ACTION_INTERCEPTED ->
                hasText(ExpressRelay.EXTRA_SOURCE_PACKAGE) && hasText(ExpressRelay.EXTRA_TEXT)
            ExpressRelay.ACTION_COOKIE_SYNC ->
                hasText(ExpressRelay.EXTRA_COOKIE) || hasText(ExpressRelay.EXTRA_COOKIE_ERROR)
            ExpressRelay.ACTION_IDENTITY_SYNC ->
                hasText(ExpressRelay.EXTRA_IDENTITY_CODE) || hasText(ExpressRelay.EXTRA_IDENTITY_ERROR) ||
                    hasText(ExpressRelay.EXTRA_IDENTITY_BRIDGE_STATUS)
            ExpressRelay.ACTION_HOST_QUERY_REPORT -> hasText(ExpressRelay.EXTRA_HOST_QUERY_REPORT)
            ExpressRelay.ACTION_HOST_PROBE -> hasText(ExpressRelay.EXTRA_HOST_PROBE)
            ExpressRelay.ACTION_WAKE_REPORT -> hasText(ExpressRelay.EXTRA_WAKE_REPORT)
            ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED -> hasText(ExpressRelay.EXTRA_INTENT_ENTRY_ID)
            WatchdogReporter.ACTION_WATCHDOG_STATUS -> payload[WatchdogReporter.EXTRA_INSTALLED] is Boolean
            else -> false
        }
    }
}
