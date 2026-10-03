package io.github.YGHFv.ReaPressExtend.core

import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import org.junit.Assert.*
import org.junit.Test

class PickupCodeRefreshTest {
    private val old = ExpressRecord("com.cainiao.wireless", "synthetic", trackingNumber = "7730000001", pickupCode = "old", pickupCodeObservedAt = 100)
    private val fresh = old.copy(pickupCode = "new", pickupCodeObservedAt = 200, origin = ExpressOrigin.ENRICHMENT)

    @Test fun confirmedNewCodeReplacesOldAndKeepsPrevious() {
        val result = old.mergeEnrichment(fresh)
        assertEquals("new", result.pickupCode)
        assertEquals("old", result.previousPickupCode)
        assertEquals(200L, result.pickupCodeObservedAt)
    }
    @Test fun cacheCannotOverwriteConfirmedCode() { assertEquals("old", old.mergeEnrichment(fresh.copy(pickupCodeObservedAt = 0)).pickupCode) }
    @Test fun olderNetworkResultCannotRegressCode() { assertEquals("old", old.mergeEnrichment(fresh.copy(pickupCodeObservedAt = 99)).pickupCode) }
    @Test fun hiddenOrMissingCodeDoesNotClearExistingCode() { assertEquals("old", old.mergeEnrichment(fresh.copy(pickupCode = null)).pickupCode) }
    @Test fun partialTrackingMatchCannotReplaceCredential() { assertEquals("old", old.mergeEnrichment(fresh.copy(trackingNumber = "0001")).pickupCode) }
    @Test fun notificationCannotClaimNetworkAuthority() { assertEquals("old", old.mergeEnrichment(fresh.copy(origin = ExpressOrigin.NOTIFICATION)).pickupCode) }
    @Test fun unrelatedHostCannotClaimCainiaoAuthority() { assertEquals("old", old.mergeEnrichment(fresh.copy(sourcePackage = "other")).pickupCode) }
    @Test fun newerNotificationIsNotOverwrittenByOlderNetworkSnapshot() {
        assertEquals("old", old.copy(timestamp = 300).mergeEnrichment(fresh).pickupCode)
    }
    @Test fun signedAndManuallyPickedStateIsPreserved() {
        val result = old.copy(status = ExpressStatus.SIGNED, pickedUpAt = 1L).mergeEnrichment(fresh)
        assertEquals(ExpressStatus.SIGNED, result.status)
        assertEquals(1L, result.pickedUpAt)
    }
    @Test fun lateStatusNotificationCannotRestoreOldCodeThroughUpsert() {
        val incoming = old.copy(timestamp = 150, pickupCodeObservedAt = 0, status = ExpressStatus.SIGNED)
        val merged = ExpressRecordStore.applyUpsert(listOf(fresh), incoming)!!.single()
        assertEquals("new", merged.pickupCode)
        assertEquals(200L, merged.pickupCodeObservedAt)
        assertEquals(ExpressStatus.SIGNED, merged.status)
    }
    @Test fun newerNotificationUpdatesCodeAndCarriesFreshnessForward() {
        val incoming = old.copy(timestamp = 300, pickupCodeObservedAt = 0, pickupCode = "newest")
        val merged = ExpressRecordStore.applyUpsert(listOf(fresh), incoming)!!.single()
        assertEquals("newest", merged.pickupCode)
        assertEquals(300L, merged.pickupCodeObservedAt)
        assertEquals("new", merged.previousPickupCode)
        assertEquals("newest", merged.mergeEnrichment(fresh.copy(pickupCodeObservedAt = 250)).pickupCode)
    }
    @Test fun statusWithoutCodeKeepsConfirmedCodeAndFreshness() {
        val incoming = old.copy(timestamp = 300, pickupCodeObservedAt = 0, pickupCode = null)
        val merged = ExpressRecordStore.applyUpsert(listOf(fresh), incoming)!!.single()
        assertEquals("new", merged.pickupCode)
        assertEquals(200L, merged.pickupCodeObservedAt)
    }
    @Test fun oldTailNotificationCannotOverrideConfirmedCode() {
        val claimant = old.copy(trackingNumber = null, parcelTail = "0001", timestamp = 150, pickupCodeObservedAt = 0)
        val merged = ExpressRecordStore.applyTailMatches(listOf(fresh, claimant)).records
        assertEquals("new", merged.first { it.trackingNumber != null }.pickupCode)
    }
    @Test fun snapshotTimestampSurvivesStorageAndLegacyDataStillReads() {
        val result = ExpressRecordStore.parse(ExpressRecordStore.serialize(listOf(fresh))).single()
        assertEquals(200L, result.pickupCodeObservedAt)
        assertEquals("new", result.pickupCode)
        val legacy = ExpressRecordStore.serialize(listOf(old)).replace("\"pickupObservedAt\":100,", "").replace(",\"pickupObservedAt\":100", "")
        assertEquals(0L, ExpressRecordStore.parse(legacy).single().pickupCodeObservedAt)
    }
}
