package io.github.YGHFv.ReaPressExtend.core

import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CainiaoPickupInstructionTest {
    private fun row(brand: String = "DUODUOMAICAI") = JSONObject()
        .put("mailNo", "123456712345")
        .put("packageStation", JSONObject().put("siteBrandCode", brand))

    @Test fun brandedStationWithoutCodeUsesFiveDigitTail() {
        assertEquals("12345", CainiaoPickupInstruction.mailTail(row(), ExpressStatus.READY_FOR_PICKUP, null))
    }
    @Test fun ordinaryStationWithoutCodeDoesNotInventTail() {
        assertNull(CainiaoPickupInstruction.mailTail(row("CAINIAO"), ExpressStatus.READY_FOR_PICKUP, null))
    }
    @Test fun realShelfCodeWins() {
        assertNull(CainiaoPickupInstruction.mailTail(row(), ExpressStatus.READY_FOR_PICKUP, "1-2-345"))
    }
    @Test fun authCodeEqualToTailIsTailInstruction() {
        assertEquals("12345", CainiaoPickupInstruction.mailTail(row(), ExpressStatus.ARRIVED_STATION, "12345"))
    }
    @Test fun inTransitAndSignedAreNotPickupInstructions() {
        for (status in listOf(ExpressStatus.IN_TRANSIT, ExpressStatus.SIGNED)) {
            assertNull(CainiaoPickupInstruction.mailTail(row(), status, null))
        }
    }
    @Test fun missingTrackingDoesNotCreateCredential() {
        assertNull(CainiaoPickupInstruction.mailTail(row().put("mailNo", JSONObject.NULL), ExpressStatus.READY_FOR_PICKUP, null))
    }
    @Test fun featureBrandIsSupported() {
        val value = row("other").put("feature", JSONObject().put("siteBrandCode", "DUODUOMAICAI"))
        assertEquals("12345", CainiaoPickupInstruction.mailTail(value, ExpressStatus.READY_FOR_PICKUP, null))
    }
    @Test fun tailSurvivesStorageWithoutBecomingPickupCodeOrIdentity() {
        val record = ExpressRecord("com.cainiao.wireless", "synthetic", trackingNumber = "123456712345", pickupMailTail = "12345")
        val loaded = ExpressRecordStore.parse(ExpressRecordStore.serialize(listOf(record))).single()
        assertEquals("12345", loaded.pickupMailTail)
        assertNull(loaded.pickupCode)
        assertFalse(record.copy(trackingNumber = null).hasIdentity)
    }
    @Test fun invalidOrStaleInstructionIsNotShown() {
        val record = ExpressRecord("com.cainiao.wireless", "synthetic", trackingNumber = "123456712345",
            pickupMailTail = "12345", status = ExpressStatus.READY_FOR_PICKUP)
        assertEquals("12345", record.visiblePickupMailTail)
        assertNull(record.copy(status = ExpressStatus.SIGNED).visiblePickupMailTail)
        assertNull(record.copy(trackingNumber = "123456700000").visiblePickupMailTail)
        assertNull(record.copy(pickupCode = "1-2-345").visiblePickupMailTail)
    }
    @Test fun unrelatedParcelCannotSupplyTailDuringEnrichment() {
        val record = ExpressRecord("com.cainiao.wireless", "synthetic", trackingNumber = "123456712345")
        val unrelated = record.copy(trackingNumber = "7777700000", pickupMailTail = "00000", origin = ExpressOrigin.ENRICHMENT)
        assertNull(record.mergeEnrichment(unrelated).pickupMailTail)
    }
}
