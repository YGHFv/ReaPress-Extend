package io.github.YGHFv.ReaPressExtend.core

import java.security.MessageDigest
import java.security.SecureRandom

internal object RelayCredential {
    const val LOCAL_PREFS = "reapress_relay_auth"
    const val REMOTE_GROUP = "relay_auth_v1"
    const val KEY = "credential"

    fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun isValid(value: String?): Boolean =
        value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun matches(expected: String?, supplied: String?): Boolean {
        if (!isValid(expected) || !isValid(supplied)) return false
        return MessageDigest.isEqual(
            expected!!.toByteArray(Charsets.US_ASCII),
            supplied!!.toByteArray(Charsets.US_ASCII),
        )
    }
}
