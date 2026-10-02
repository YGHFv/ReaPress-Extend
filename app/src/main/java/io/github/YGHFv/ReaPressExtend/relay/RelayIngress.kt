package io.github.YGHFv.ReaPressExtend.relay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.core.RelayCredential

internal object RelayIngress {
    fun accept(context: Context, intent: Intent): Boolean = runCatching {
        if (!RelayCredential.matches(
                RelayCredentialStore.read(context),
                intent.getStringExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL),
            )
        ) return false
        if (!hasValidPayload(intent)) return false
        intent.removeExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL)
        true
    }.getOrDefault(false)

    fun hasValidPayload(intent: Intent): Boolean = runCatching {
        val extras = intent.extras ?: return false
        if (extras.size() > RelayPayloadPolicy.MAX_FIELDS + 1) return false
        val payload = extras.keySet()
            .filterNot { it == ExpressRelay.EXTRA_RELAY_CREDENTIAL }
            .associateWith { name ->
                @Suppress("DEPRECATION")
                val value = extras.get(name)
                if (value is RelayPayloadPolicy.OpaqueValue) return false
                if (value is PendingIntent) RelayPayloadPolicy.OpaqueValue.PENDING_INTENT else value
            }
        RelayPayloadPolicy.accepts(intent.action, payload)
    }.getOrDefault(false)
}
