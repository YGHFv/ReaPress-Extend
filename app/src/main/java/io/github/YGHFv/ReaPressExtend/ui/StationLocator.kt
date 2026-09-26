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
 * 在驿站门口**采一次现场**：拿到当前定位 + 附近 WiFi，供「驿站管理」里那个「获取」按钮用。
 *
 * ## 为什么这个功能要精确定位，而身份码弹窗只要粗略定位
 *
 * 两者问的是两个不同精度的问题：
 *
 * - 身份码弹窗问的是「**哪个**驿站」—— 几百米到几公里的尺度，粗略定位（基站）完全够用，
 *   多要一份精确定位授权是白要的（那条边界写在 AndroidManifest 的注释里）；
 * - 这里问的是「**这一处**的门口」—— 要拿来跟以后再站到这儿时的定位比对，粗定位
 *   一两公里的误差会让相邻两个驿站根本无法区分，这个功能就失去意义了。
 *
 * ## 为什么 WiFi 和定位一起采
 *
 * 室内（驿站都在室内）GPS 几乎没有信号，最后落到的是蜂窝 / WiFi 混合定位；而驿站的 AP
 * 就在几米内，BSSID 是设备唯一的、不受定位精度影响。所以 WiFi 是**室内最强的那路证据**，
 * 定位给粗筛、WiFi 给确认 —— 只记一样，将来判「是不是又到这一站了」都不够用。
 *
 * ## 采到的每一样都可能缺，缺了不算失败
 *
 * 没开 WiFi、拒了权限、系统扫描限流（Android 10+ 每 app 两分钟 4 次）都会让 WiFi 列表为空；
 * Geocoder 在国内很多机器上没有服务可用，反查地址必然失败。三种都**照常落库**：
 * 定位在就能用，地址用坐标兜底。全空（连定位也没有）时由写入侧丢掉，不留空指纹。
 *
 * ## 采集动作是挂起函数，不是回调
 *
 * 界面要「点一下、转一会、出结果」，而这条路径上有三处异步（定位回调、扫描冷却、反查地址）。
 * 用 `suspend` 把三段串起来，界面侧就只剩一句 `StationLocator.capture(context)`，
 * 不用在 Activity 里维护三个互相嵌套的回调。
 */
internal object StationLocator {

    /**
     * 采一次。**调用方负责先拿到权限** —— 缺权限时这里直接返回
     * [Capture.permissionMissing]，由界面去申请，不由这里弹框（弹框只有 Activity 能弹）。
     */
    suspend fun capture(context: Context): Capture = withContext(Dispatchers.IO) {
        if (!hasFineLocation(context)) return@withContext Capture.needsPermission()

        // 位置先取：反查地址依赖它，WiFi 与它无关。
        val location = currentLocation(context)
        Capture(
            fingerprint = StationFingerprint(
                position = location?.let { GeoPoint(it.latitude, it.longitude) },
                accuracyMeters = location?.accuracy,
                wifi = nearbyWifi(context),
                capturedAt = System.currentTimeMillis(),
            ),
            addressText = location?.let { reverseGeocode(context, it) },
        )
    }

    /** 精确定位权限（采指纹这一路必须要它，见类注释）。 */
    fun hasFineLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 当前位置。
     *
     * 优先 `getCurrentLocation`（API 30+，会真的去测一次，**几秒内**给出结果），
     * 拿不到再退到「最后已知位置」。
     *
     * ## 为什么不直接沿用身份码弹窗那套 `getLastKnownLocation`
     *
     * 那边不需要新鲜度（「哪个驿站在八百米外」这件事几分钟前的定位照样成立），
     * 这边要 —— 用户是**站在驿站门口**点的按钮，而最后已知位置可能是几十分钟前在家里那次。
     * 拿它当驿站坐标，等于把这个驿站的指纹钉在了家里。
     *
     * 超时（[LOCATE_TIMEOUT_MS]）后退到旧位置而不是返回 null：一个带时间戳的旧坐标
     * 仍然比没有强（至少地理位置是对的），界面会把「定位可能不新鲜」如实说出来。
     */
    private suspend fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = bestProvider(manager)
            if (provider != null) {
                val fresh = withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        // runCatching：provider 在挑选与调用之间被关掉、或权限刚被撤销时，
                        // getCurrentLocation 会抛 —— 那正是要退到旧位置的时刻。
                        runCatching {
                            manager.getCurrentLocation(provider, null, DIRECT_EXECUTOR) { found ->
                                if (cont.isActive) cont.resume(found)
                            }
                        }.onFailure { if (cont.isActive) cont.resume(null) }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return lastKnown(manager)
    }

    /** 三个 provider 里最新的一条。全都没有（设备刚开机、定位一直关着）时返回 null。 */
    private fun lastKnown(manager: LocationManager): Location? =
        PROVIDER_PREFERENCE.asSequence()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    /**
     * 挑一个能用的 provider。
     *
     * `fused` 排最前：它是 Android 12+ 的融合定位，自己会选 GPS / WiFi / 基站，
     * 精度与耗电都是系统调好的，比我们按顺序硬试 GPS 更合适（室内 GPS 会一直等不到结果，
     * 白白耗掉 [LOCATE_TIMEOUT_MS]）。
     *
     * `PASSIVE` 只在最后：它不主动测，只被动接收别的应用请求到的位置 ——
     * 取当前值它给不出，但作为「最后已知位置」的来源它最省电也最常有值。
     */
    private fun bestProvider(manager: LocationManager): String? =
        PROVIDER_PREFERENCE.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }

    /**
     * 反查地址文本（「XX路XX号」）。**拿不到就返回 null**，由界面用坐标兜底 ——
     * 国内很多设备没有 Geocoder 服务，失败是常态而不是异常。
     *
     * 同步版本在 API 33 上被标记废弃（推荐用异步回调），但它仍然可用，而且省掉了
     * 一层把回调再包回挂起函数的样板。整个调用外面已经裹了 `runCatching`。
     */
    private fun reverseGeocode(context: Context, location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val address = Geocoder(context, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?: return null
        // getAddressLine(0) 是「完整地址」那一行，最接近用户心里的「地址」。
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
     * 附近的 WiFi BSSID 列表。
     *
     * **当前连接的那个排最前**：它最确定「人在此处」（用户连上了驿站的 WiFi 就是铁证），
     * 而扫描结果只是「能看见」，几百米外的 AP 也会进来。
     *
     * 扫描结果为空时**主动触发一次扫描**再读一遍：`scanResults` 返回的是系统缓存，
     * 可能是几分钟前那次扫描留下的（用户在别处走过留下的列表），而系统限流又让我们不能
     * 每次都主动扫 —— 所以只在「缓存是空的」这个明确信号下才付这一次代价。
     */
    private suspend fun nearbyWifi(context: Context): List<String> {
        val manager = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return emptyList()

        val out = LinkedHashSet<String>()
        connectedBssid(manager)?.let { out += it }
        out += scannedBssids(manager)

        if (out.isEmpty() && requestScan(manager)) {
            // 扫描是异步的，系统扫描完才写缓存 —— 给一小段时间再读，读不到就认了。
            delay(SCAN_SETTLE_MS)
            out += scannedBssids(manager)
        }
        return out.toList()
    }

    /**
     * 当前连接的 AP。没连 WiFi、或系统不给（无权限 / 位置服务关着）时返回 null。
     *
     * `02:00:00:00:00:00` 是 Android 在「读不到真实 BSSID」时给的占位值，
     * 当成没有 —— 把它存下来会让所有读不到 BSSID 的设备看起来像连在同一个 AP 上。
     */
    private fun connectedBssid(manager: WifiManager): String? = runCatching {
        manager.connectionInfo?.bssid?.normalizeBssid()
    }.getOrNull()

    private fun scannedBssids(manager: WifiManager): List<String> = runCatching {
        // 无权限 / 位置服务关闭时这里抛 SecurityException —— 当成「没扫到」，不是错误。
        manager.scanResults.orEmpty().mapNotNull { it.BSSID?.normalizeBssid() }
    }.getOrDefault(emptyList())

    /** 请求一次主动扫描。返回 false 表示系统拒绝（限流 / 没有权限 / 后台）—— 不必重试。 */
    private fun requestScan(manager: WifiManager): Boolean =
        runCatching { manager.startScan() }.getOrDefault(false)

    /** BSSID 归一到小写、去掉占位值与空串。不归一化会让同一台 AP 因为大小写算成两条。 */
    private fun String.normalizeBssid(): String? =
        trim().lowercase().takeIf { it.isNotBlank() && it != UNAVAILABLE_BSSID }

    /** 定位等待上限。超了就退到「最后已知位置」，不让用户对着转圈等下去。 */
    private const val LOCATE_TIMEOUT_MS = 8_000L

    /** 主动扫描后等系统写缓存的时间。短了读不到，长了白让用户等。 */
    private const val SCAN_SETTLE_MS = 1_500L

    /** 读不到真实 BSSID 时系统给的占位值（见 [connectedBssid]）。 */
    private const val UNAVAILABLE_BSSID = "02:00:00:00:00:00"

    /**
     * provider 的尝试顺序。`FUSED_PROVIDER` 是 API 31 加的**字符串常量**（编译期内联），
     * 在低版本上取到它也只是个没用到的 provider 名，不会崩。
     */
    private val PROVIDER_PREFERENCE = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    /** 直接在当前线程执行的回调执行器：定位回调里只读几个字段，不值得再切一次线程。 */
    private val DIRECT_EXECUTOR = java.util.concurrent.Executor { it.run() }
}

/**
 * 一次采集的结果。
 *
 * @param fingerprint 采到的指纹。**可能只有一半**（没 WiFi 或没定位），界面要按实际内容说话。
 * @param addressText 反查到的地址（「XX路XX号」）；没查到是 null，写入侧会退回坐标文本。
 * @param permissionMissing 缺精确定位权限 —— 调用方应去申请，而不是把这次当成失败。
 */
internal data class Capture(
    val fingerprint: StationFingerprint,
    val addressText: String? = null,
    val permissionMissing: Boolean = false,
) {
    companion object {
        /** 缺权限的这一次：什么都没采，界面该做的是去申请，不是报失败。 */
        fun needsPermission() = Capture(StationFingerprint(), permissionMissing = true)
    }
}
