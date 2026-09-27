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

package io.github.YGHFv.ReaPressExtend.core

import org.json.JSONArray
import org.json.JSONObject

/** 一次 `queryalltrace` 查询拿到的信息；字段对应真机实测响应路径，拿不到一律 null / 空表，不编造。 */
data class CainiaoTraceInfo(
    val trackingNumber: String? = null,
    val courierName: String? = null,
    val courierCode: String? = null,
    val courierPhone: String? = null,
    val statusDesc: String? = null,
    val status: ExpressStatus? = null,
    val stationAddress: String? = null,
    /** 完整商品名（宿主那份可能截断）。 */
    val goodsName: String? = null,
    val goodsImage: String? = null,
    /** 全轨迹，按响应顺序（最早 → 最新）。 */
    val points: List<ExpressTracePoint> = emptyList(),
)

/** `mtop.taobao.logisticstracedetailservice.queryalltrace` 的响应解析；纯函数，网络调用在 hook 侧。 */
object CainiaoTraceParser {

    /** 末条轨迹处于这些状态时它的 `address` 才是取件地点（派送中还是网点地址、已签收都不算）。 */
    private val ARRIVAL_STATUSES = setOf(
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
    )

    /** 可推进记录的状态白名单。刻意不收 IN_TRANSIT（会把 [ExpressRecord.mergeEnrichment] 里「CREATED 纠正 IN_TRANSIT」盖回去，状态回退）；CREATED / PICKED_UP 在通知文案与宿主描述里判据更可靠。 */
    private val TRACE_ADVANCE_STATUSES = setOf(
        ExpressStatus.DELIVERING,
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    private fun traceAdvanceStatus(desc: String?): ExpressStatus? =
        desc?.let { ExpressParser.parseStatus(it) }?.takeIf { it in TRACE_ADVANCE_STATUSES }

    fun parse(body: String): CainiaoTraceInfo? = try {
        JSONObject(body)
            .optJSONObject("data")
            ?.optJSONArray("result")
            ?.optJSONObject(0)
            ?.let { readNode(it) }
    } catch (e: Throwable) {
        null
    }

    private fun readNode(node: JSONObject): CainiaoTraceInfo {
        val company = node.optJSONObject("cp")
        val packageStatus = node.optJSONObject("packageStatus")
        val traces = node.optJSONArray("fullTraceDetail")
        // newStatusDesc 比 status 新（实测同一件 status=派送中 而 newStatusDesc=待取件），取新的那个
        val statusDesc = packageStatus?.optStringOrNull("newStatusDesc")
            ?: packageStatus?.optStringOrNull("status")

        return CainiaoTraceInfo(
            trackingNumber = node.optStringOrNull("mailNo"),
            courierName = company?.optStringOrNull("tpName"),
            courierCode = company?.optStringOrNull("tpCode"),
            courierPhone = company?.optStringOrNull("tpContact"),
            statusDesc = statusDesc,
            status = traceAdvanceStatus(statusDesc),
            stationAddress = stationAddress(traces),
            goodsName = node.optJSONArray("packageItems")
                ?.optJSONObject(0)?.optStringOrNull("goodsName")?.takeIf { it.isNotBlank() },
            goodsImage = node.optJSONArray("packageItems")
                ?.optJSONObject(0)?.optStringOrNull("allPicUrl")?.takeIf { it.isNotBlank() },
            points = points(traces),
        )
    }

    private fun stationAddress(traces: JSONArray?): String? {
        if (traces == null || traces.length() == 0) return null
        val last = traces.optJSONObject(traces.length() - 1) ?: return null
        val description = last.optStringOrNull("statusDesc").orEmpty()
        if (ExpressParser.parseStatus(description) !in ARRIVAL_STATUSES) return null
        return last.optStringOrNull("address")?.takeIf { it.isNotBlank() }
    }

    /** 清洗后为空（纯广告）的直接丢掉；用 `desc` 而非 `standerdDesc`，真机上两者内容一致。 */
    private fun points(traces: JSONArray?): List<ExpressTracePoint> {
        if (traces == null) return emptyList()
        val out = ArrayList<ExpressTracePoint>(traces.length())
        for (index in 0 until traces.length()) {
            val item = traces.optJSONObject(index) ?: continue
            val raw = item.optStringOrNull("desc") ?: continue
            val text = ExpressTraceText.clean(raw)
            if (text.isEmpty()) continue
            out += ExpressTracePoint(time = item.optStringOrNull("time").orEmpty(), text = text)
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
