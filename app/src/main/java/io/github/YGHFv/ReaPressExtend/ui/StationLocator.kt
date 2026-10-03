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

package io.github.YGHFv.ReaPressExtend.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import io.github.YGHFv.ReaPressExtend.core.GeoPoint
import io.github.YGHFv.ReaPressExtend.core.StationFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 在驿站门口采一次现场（当前定位 + 附近 WiFi），供「驿站管理」的「获取」按钮。
 * 这里必须精确定位——身份码弹窗问「哪个驿站」粗定位就够，这里问「这一处的门口」，
 * 要拿去跟以后再站到这儿时比对。WiFi 与定位一起采：驿站都在室内，GPS 几乎没信号，
 * 而 AP 就在几米内、BSSID 不受定位精度影响，是室内最强的证据。缺哪样都不算失败，照常落库，
 * 全空（连定位也没有）由写入侧丢掉。
 */
internal object StationLocator {

    /** 调用方负责先拿到权限；缺权限返回 [Capture.permissionMissing]，由界面去申请。 */
    suspend fun capture(context: Context): Capture = withContext(Dispatchers.IO) {
        if (!hasFineLocation(context)) return@withContext Capture.needsPermission()

        val location = currentLocation(context)
        if (!hasFineLocation(context)) return@withContext Capture.needsPermission()
        val wifi = nearbyWifi(context)
        if (!hasFineLocation(context)) return@withContext Capture.needsPermission()
        Capture(
            fingerprint = StationFingerprint(
                position = location?.let { GeoPoint(it.latitude, it.longitude) },
                accuracyMeters = location?.accuracy,
                wifi = wifi,
                capturedAt = System.currentTimeMillis(),
            ),
            addressText = location?.let { reverseGeocode(context, it) },
        )
    }

    fun hasFineLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 优先 getCurrentLocation（真的去测一次）再退最后已知位置——用户是站在驿站门口点的按钮，
     * 最后已知位置可能几十分钟前在家里。超时退旧位置而非 null：带时间戳的旧坐标仍比没有强。
     */
    internal suspend fun currentLocation(context: Context, timeoutMs: Long = LOCATE_TIMEOUT_MS): Location? {
        if (!hasFineLocation(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = bestProvider(manager)
            if (provider != null) {
                val fresh = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val cancellation = CancellationSignal()
                        cont.invokeOnCancellation { cancellation.cancel() }
                        // provider 在挑选与调用之间被关掉、或权限刚被撤时 getCurrentLocation 会抛——正是退旧位置的时刻。
                        try {
                            manager.getCurrentLocation(provider, cancellation, DIRECT_EXECUTOR) { found ->
                                if (cont.isActive) cont.resume(found)
                            }
                        } catch (_: SecurityException) {
                            if (cont.isActive) cont.resume(null)
                        } catch (_: RuntimeException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return LocationAccess.lastKnown(context, manager, PROVIDER_PREFERENCE, precise = true)
    }

    /** fused 排最前（系统自选 GPS/WiFi/基站，室内硬试 GPS 会白耗超时）；PASSIVE 只被动接收，取当前值给不出。 */
    private fun bestProvider(manager: LocationManager): String? =
        PROVIDER_PREFERENCE.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }

    /** 反查地址（「XX路XX号」），拿不到返回 null 由界面用坐标兜底——国内很多设备没有 Geocoder 服务，失败是常态。 */
    private fun reverseGeocode(context: Context, location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val address = Geocoder(context, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?: return null
        address.getAddressLine(0)?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                address.adminArea,
                address.locality,
                address.thoroughfare,
                address.subThoroughfare,
                address.featureName,
            ).distinct().joinToString("").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 附近 WiFi BSSID 列表。当前连接的排最前（用户连上了驿站的 WiFi 就是「人在此处」的铁证，
     * 扫描结果几百米外的 AP 也会进来）。扫描缓存为空才主动触发一次扫描再读——
     * 系统限流，不能每次都扫。
     */
    private suspend fun nearbyWifi(context: Context): List<String> {
        if (!hasFineLocation(context)) return emptyList()
        val manager = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return emptyList()

        val out = LinkedHashSet<String>()
        connectedBssid(manager)?.let { out += it }
        out += scannedBssids(context, manager)

        if (out.isEmpty() && hasFineLocation(context) && requestScan(manager)) {
            delay(SCAN_SETTLE_MS)
            out += scannedBssids(context, manager)
        }
        return out.toList()
    }

    /**
     * 当前连接的 AP，没连 WiFi 或系统不给时返回 null。
     * `02:00:00:00:00:00` 是读不到真实 BSSID 时的占位值，当成没有——存下来会让所有设备看起来连同一个 AP。
     */
    private fun connectedBssid(manager: WifiManager): String? = runCatching {
        manager.connectionInfo?.bssid?.normalizeBssid()
    }.getOrNull()

    internal fun scannedBssids(context: Context, manager: WifiManager): List<String> {
        if (!hasFineLocation(context)) return emptyList()
        return try {
            manager.scanResults.orEmpty().mapNotNull { it.BSSID?.normalizeBssid() }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun requestScan(manager: WifiManager): Boolean =
        runCatching { manager.startScan() }.getOrDefault(false)

    private fun String.normalizeBssid(): String? =
        trim().lowercase().takeIf { it.isNotBlank() && it != UNAVAILABLE_BSSID }

    private const val LOCATE_TIMEOUT_MS = 8_000L

    private const val SCAN_SETTLE_MS = 1_500L

    private const val UNAVAILABLE_BSSID = "02:00:00:00:00:00"

    private val PROVIDER_PREFERENCE: List<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

    private val DIRECT_EXECUTOR = java.util.concurrent.Executor { it.run() }
}

/** 一次采集的结果。fingerprint 可能只有一半（没 WiFi 或没定位），界面按实际内容说话；permissionMissing 时应去申请而不是当失败。 */
internal data class Capture(
    val fingerprint: StationFingerprint,
    val addressText: String? = null,
    val permissionMissing: Boolean = false,
) {
    companion object {
        fun needsPermission() = Capture(StationFingerprint(), permissionMissing = true)
    }
}
