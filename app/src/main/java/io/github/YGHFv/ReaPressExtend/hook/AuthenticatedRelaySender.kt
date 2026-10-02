package io.github.YGHFv.ReaPressExtend.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import io.github.YGHFv.ReaPressExtend.core.RelayCredential
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.RelayIngress
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

internal object AuthenticatedRelaySender {
    fun send(context: Context, intent: Intent): Boolean = runCatching {
        val credential = XposedBridge.framework()
            ?.getRemotePreferences(RelayCredential.REMOTE_GROUP)
            ?.getString(RelayCredential.KEY, null)
            ?.takeIf(RelayCredential::isValid)
        if (credential == null) {
            XposedBridge.logError("relay credential unavailable; open the module to initialize trusted delivery")
            return false
        }
        if (!RelayIngress.hasValidPayload(intent)) {
            XposedBridge.logError("relay payload rejected before delivery")
            return false
        }
        intent.setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
            .setPackage(ExpressRelay.MODULE_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, credential)
        if (Process.myUid() == Process.SYSTEM_UID) {
            sendFromSystem(context, intent)
        } else {
            context.sendBroadcast(intent)
        }
        true
    }.getOrElse {
        runCatching { XposedBridge.logError("authenticated relay failed (${it.javaClass.simpleName})") }
        false
    }

    @SuppressLint("MissingPermission")
    private fun sendFromSystem(context: Context, intent: Intent) {
        val identity = Binder.clearCallingIdentity()
        try {
            context.sendBroadcastAsUser(intent, Process.myUserHandle())
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
