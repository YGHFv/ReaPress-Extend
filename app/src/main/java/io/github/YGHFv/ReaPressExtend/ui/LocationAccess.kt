package io.github.YGHFv.ReaPressExtend.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

internal object LocationAccess {
    fun hasPermission(context: Context, precise: Boolean = false): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            (!precise && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)

    fun lastKnown(
        context: Context,
        manager: LocationManager,
        providers: List<String>,
        precise: Boolean = false,
    ): Location? = providers.asSequence().mapNotNull { provider ->
        if (!hasPermission(context, precise)) return@mapNotNull null
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and the Binder call.
            null
        } catch (_: RuntimeException) {
            null
        }
    }.maxByOrNull { it.time }
}
