package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import android.content.SharedPreferences
import io.github.YGHFv.ReaPressExtend.core.RelayCredential

internal object RelayCredentialStore {
    fun read(context: Context): String? = runCatching {
        preferences(context).getString(RelayCredential.KEY, null)?.takeIf(RelayCredential::isValid)
    }.getOrNull()

    @Synchronized
    fun ensure(context: Context): String? {
        return runCatching {
            val credential = read(context) ?: RelayCredential.generate()
            if (preferences(context).edit().putString(RelayCredential.KEY, credential).commit()) {
                credential
            } else {
                null
            }
        }.getOrNull()
    }

    fun publish(context: Context, remote: SharedPreferences): Boolean = runCatching {
        val credential = ensure(context) ?: return false
        remote.edit().putString(RelayCredential.KEY, credential).commit()
    }.getOrDefault(false)

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(RelayCredential.LOCAL_PREFS, Context.MODE_PRIVATE)
}
