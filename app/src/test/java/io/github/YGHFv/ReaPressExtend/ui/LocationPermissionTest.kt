package io.github.YGHFv.ReaPressExtend.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.CancellationSignal
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidSdk
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class LocationPermissionTest {
    @Test
    fun identityPositionWithoutPermissionDoesNotAccessLocationService() {
        val f = Fixture(fine = false, coarse = false)
        assertNull(currentPosition(f.context))
        verify(f.context, never()).getSystemService(Context.LOCATION_SERVICE)
        verifyNoInteractions(f.location)
    }

    @Test
    fun coarseOnlyIdentityPositionSelectsNewestAvailableProvider() {
        val f = Fixture(fine = false, coarse = true)
        val older = location(10)
        val newer = location(20, 31.2, 121.5)
        `when`(f.location.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)).thenReturn(older)
        `when`(f.location.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)).thenReturn(newer)
        `when`(f.location.getLastKnownLocation(LocationManager.GPS_PROVIDER)).thenThrow(SecurityException("precise denied"))
        val point = currentPosition(f.context)
        assertNotNull(point)
        assertEquals(31.2, point!!.lat, 0.0001)
    }

    @Test
    fun revokedPermissionAtBinderCallReturnsNoIdentityPosition() {
        val f = Fixture()
        `when`(f.location.getLastKnownLocation(anyString())).thenThrow(SecurityException("revoked"))
        assertNull(currentPosition(f.context))
    }

    @Test
    fun missingProviderDoesNotPreventOtherCachedPosition() {
        val f = Fixture()
        val good = location(20)
        `when`(f.location.getLastKnownLocation("missing")).thenThrow(IllegalArgumentException("provider"))
        `when`(f.location.getLastKnownLocation("good")).thenReturn(good)
        assertSame(good, LocationAccess.lastKnown(f.context, f.location, listOf("missing", "good")))
    }

    @Test
    fun preciseCaptureRejectsCoarseOnlyBeforeAccessingServices() = runBlocking {
        val f = Fixture(fine = false, coarse = true)
        assertTrue(StationLocator.capture(f.context).permissionMissing)
        verify(f.context, never()).getSystemService(Context.LOCATION_SERVICE)
        Unit
    }

    @Test
    fun captureReportsPermissionRevokedDuringLocationInsteadOfUsingPartialData() = withAndroidSdk(26) {
        runBlocking {
            val f = Fixture()
            val cached = location(20)
            `when`(f.location.getLastKnownLocation(anyString())).thenAnswer {
                `when`(f.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                    .thenReturn(PackageManager.PERMISSION_DENIED)
                cached
            }
            assertTrue(StationLocator.capture(f.context).permissionMissing)
            verify(f.context, never()).getSystemService(Context.WIFI_SERVICE)
            Unit
        }
    }

    @Test
    fun android26UsesCachedLocationWithoutCurrentLocationApi() = withAndroidSdk(26) {
        runBlocking {
            val f = Fixture()
            val expected = location(20)
            `when`(f.location.getLastKnownLocation(LocationManager.GPS_PROVIDER)).thenReturn(expected)
            assertSame(expected, StationLocator.currentLocation(f.context))
            assertFalse(mockingDetails(f.location).invocations.any { it.method.name == "getCurrentLocation" })
        }
    }

    @Test
    fun currentLocationDeniedFallsBackToPermittedCache() = withAndroidSdk(35) {
        mockConstruction(CancellationSignal::class.java).use {
            runBlocking {
                val f = Fixture()
                val cached = location(20)
                `when`(f.location.isProviderEnabled(anyString())).thenReturn(true)
                `when`(f.location.getLastKnownLocation(LocationManager.GPS_PROVIDER)).thenReturn(cached)
                doThrow(SecurityException("provider rejected")) .`when`(f.location).getCurrentLocation(
                    anyString(), any(CancellationSignal::class.java), any(Executor::class.java), anyConsumer(),
                )
                assertSame(cached, StationLocator.currentLocation(f.context))
            }
        }
    }

    @Test
    fun finePermissionRevokedDuringFreshRequestDoesNotReadCache() = withAndroidSdk(35) {
        mockConstruction(CancellationSignal::class.java).use {
            runBlocking {
                val f = Fixture()
                `when`(f.location.isProviderEnabled(anyString())).thenReturn(true)
                doAnswer {
                    `when`(f.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                        .thenReturn(PackageManager.PERMISSION_DENIED)
                    throw SecurityException("revoked")
                }.`when`(f.location).getCurrentLocation(anyString(), any(CancellationSignal::class.java), any(Executor::class.java), anyConsumer())
                assertNull(StationLocator.currentLocation(f.context))
                verify(f.location, never()).getLastKnownLocation(anyString())
                Unit
            }
        }
    }

    @Test
    fun freshLocationIsPreferredToCache() = withAndroidSdk(35) {
        mockConstruction(CancellationSignal::class.java).use {
            runBlocking {
                val f = Fixture()
                val fresh = location(30)
                `when`(f.location.isProviderEnabled(anyString())).thenReturn(true)
                doAnswer { it.getArgument<Consumer<Location>>(3).accept(fresh); null }.`when`(f.location)
                    .getCurrentLocation(anyString(), any(CancellationSignal::class.java), any(Executor::class.java), anyConsumer())
                assertSame(fresh, StationLocator.currentLocation(f.context))
                verify(f.location, never()).getLastKnownLocation(anyString())
                Unit
            }
        }
    }

    @Test
    fun timeoutCancelsPlatformRequestAndUsesCache() = withAndroidSdk(35) {
        mockConstruction(CancellationSignal::class.java).use { signals ->
            runBlocking {
                val f = Fixture()
                val cached = location(20)
                `when`(f.location.isProviderEnabled(anyString())).thenReturn(true)
                `when`(f.location.getLastKnownLocation(LocationManager.GPS_PROVIDER)).thenReturn(cached)
                assertSame(cached, StationLocator.currentLocation(f.context, timeoutMs = 100L))
                verify(signals.constructed().single()).cancel()
            }
        }
    }

    @Test
    fun coroutineCancellationCancelsRequestWithoutFallbackOrLateResume() = withAndroidSdk(35) {
        mockConstruction(CancellationSignal::class.java).use { signals ->
            runBlocking {
                val f = Fixture()
                var callback: Consumer<Location>? = null
                `when`(f.location.isProviderEnabled(anyString())).thenReturn(true)
                doAnswer { callback = it.getArgument(3); null }.`when`(f.location)
                    .getCurrentLocation(anyString(), any(CancellationSignal::class.java), any(Executor::class.java), anyConsumer())
                val job = launch(start = CoroutineStart.UNDISPATCHED) { StationLocator.currentLocation(f.context) }
                assertNotNull(callback)
                job.cancelAndJoin()
                callback!!.accept(location(40))
                verify(signals.constructed().single()).cancel()
                verify(f.location, never()).getLastKnownLocation(anyString())
                Unit
            }
        }
    }

    @Test
    fun wifiWithoutFinePermissionDoesNotReadScans() {
        val f = Fixture(fine = false, coarse = true)
        assertTrue(StationLocator.scannedBssids(f.context, f.wifi).isEmpty())
        verifyNoInteractions(f.wifi)
    }

    @Test
    fun wifiPermissionRevocationReturnsEmptyInsteadOfThrowing() {
        val f = Fixture()
        `when`(f.wifi.scanResults).thenThrow(SecurityException("revoked"))
        assertTrue(StationLocator.scannedBssids(f.context, f.wifi).isEmpty())
    }

    @Test
    fun wifiUnavailableReturnsEmptyInsteadOfThrowing() {
        val f = Fixture()
        `when`(f.wifi.scanResults).thenThrow(IllegalStateException("unavailable"))
        assertTrue(StationLocator.scannedBssids(f.context, f.wifi).isEmpty())
    }

    @Test
    fun wifiFiltersPlaceholderAndNormalizesBssid() {
        val f = Fixture()
        val placeholder = mock(ScanResult::class.java).apply { BSSID = "02:00:00:00:00:00" }
        val valid = mock(ScanResult::class.java).apply { BSSID = " AA:BB:CC:DD:EE:FF " }
        `when`(f.wifi.scanResults).thenReturn(listOf(placeholder, valid))
        assertEquals(listOf("aa:bb:cc:dd:ee:ff"), StationLocator.scannedBssids(f.context, f.wifi))
    }

    private class Fixture(fine: Boolean = true, coarse: Boolean = true) {
        val context = PreferencesContext().context
        val location = mock(LocationManager::class.java)
        val wifi = mock(WifiManager::class.java)

        init {
            `when`(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                .thenReturn(if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            `when`(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
                .thenReturn(if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(location)
        }
    }

    private fun location(time: Long, lat: Double = 31.0, lon: Double = 121.0): Location =
        mock(Location::class.java).also {
            `when`(it.time).thenReturn(time)
            `when`(it.latitude).thenReturn(lat)
            `when`(it.longitude).thenReturn(lon)
        }

    @Suppress("UNCHECKED_CAST")
    private fun anyConsumer(): Consumer<Location> =
        (any(Consumer::class.java) as Consumer<Location>?) ?: Consumer {}
}
