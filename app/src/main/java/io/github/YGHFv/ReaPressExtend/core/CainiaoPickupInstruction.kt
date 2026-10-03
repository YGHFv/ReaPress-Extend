package io.github.YGHFv.ReaPressExtend.core

import org.json.JSONObject

/** 8.11.923 homepage JS uses DUODUOMAICAI and the last five mail-number characters, not a shelf code. */
object CainiaoPickupInstruction {
    fun mailTail(row: JSONObject, status: ExpressStatus, visibleCode: String?): String? {
        if (status !in setOf(ExpressStatus.ARRIVED_STATION, ExpressStatus.READY_FOR_PICKUP)) return null
        val station = row.optJSONObject("packageStation")
        val feature = row.optJSONObject("feature") ?: row.optJSONObject("featureObj")
        if (station?.optString("siteBrandCode") != "DUODUOMAICAI" && feature?.optString("siteBrandCode") != "DUODUOMAICAI") return null
        val mail = row.optString("mailNo").takeIf { it.length >= 5 && it.matches(Regex("[A-Za-z0-9]+")) } ?: return null
        val tail = mail.takeLast(5)
        return tail.takeIf { visibleCode.isNullOrBlank() || visibleCode == tail }
    }
}
