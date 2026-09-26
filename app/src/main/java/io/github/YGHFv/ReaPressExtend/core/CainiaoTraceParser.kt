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

/**
 * 一次 `queryalltrace` 查询拿到的信息。
 *
 * 字段全部对应真机实测的响应路径（2026-09-26，极兔 JT3178691239988，15 条轨迹），
 * 证据见 `express-source-research.md`。拿不到一律 null / 空表，**不编造**。
 */
data class CainiaoTraceInfo(
    val trackingNumber: String? = null,
    /** 承运商中文名（`cp.tpName`），如「极兔速递」。 */
    val courierName: String? = null,
    /** 承运商代码（`cp.tpCode`），如 `HTKY`。 */
    val courierCode: String? = null,
    /** 承运商客服电话（`cp.tpContact`）。 */
    val courierPhone: String? = null,
    /** 最新状态描述（`packageStatus.newStatusDesc` 优先）。 */
    val statusDesc: String? = null,
    /**
     * 驿站**完整地址**（末条轨迹的 `address`），如 `颖滨23号楼109颖滨花园菜拼多多驿站`。
     *
     * ⚠️ **只在末条轨迹属于「到站类」状态时才有值**：运输中件的末条 `address` 是转运中心
     * 或网点的地址（实测拿到的就是 `泰瑞建材城S11栋113-115`），把它当取件地点写进记录，
     * 用户会照着找错地方。判据见 [ARRIVAL_STATUSES]。
     *
     * 与 `ExpressRecord.station` 是两回事：那个是**驿站名**（参与聚类），这个是**去哪取的地址**。
     */
    val stationAddress: String? = null,
    /** 商品名（`packageItems[0].goodsName`）—— 完整名，宿主那份可能会截断。 */
    val goodsName: String? = null,
    /** 商品图（`packageItems[0].allPicUrl`）。 */
    val goodsImage: String? = null,
    /** 全轨迹，按响应顺序（最早 → 最新）。 */
    val points: List<ExpressTracePoint> = emptyList(),
)

/**
 * `mtop.taobao.logisticstracedetailservice.queryalltrace` 的响应解析。
 *
 * 纯函数、不碰网络也不碰时钟 —— 网络调用在 hook 侧（`CainiaoTraceApi`），
 * 拆开是为了能在 JVM 单测里钉住字段路径。
 */
object CainiaoTraceParser {

    /**
     * 末条轨迹处于这些状态时，它的 `address` 才是**取件地点**。
     *
     * 「派送中」不算：那时 `address` 还是派件网点（实测 `泰瑞建材城S11栋113-115`）。
     * 「已签收」也不收 —— 已经拿到的件不再需要取件地址，收了反而可能覆盖掉更早写对的驿站名。
     */
    private val ARRIVAL_STATUSES = setOf(
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
    )

    /** @return 解析失败 / 结构不符 / `result` 为空时返回 null（调用方退化为「没拿到」，而不是空对象）。 */
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
        val status = node.optJSONObject("packageStatus")
        val traces = node.optJSONArray("fullTraceDetail")

        return CainiaoTraceInfo(
            trackingNumber = node.optStringOrNull("mailNo"),
            courierName = company?.optStringOrNull("tpName"),
            courierCode = company?.optStringOrNull("tpCode"),
            courierPhone = company?.optStringOrNull("tpContact"),
            // `newStatusDesc` 比 `status` 新：实测同一件上 status=派送中 而 newStatusDesc=待取件
            // （包裹已经卸到代收点了，只是主状态还没翻）。取新的那个。
            statusDesc = status?.optStringOrNull("newStatusDesc")
                ?: status?.optStringOrNull("status"),
            stationAddress = stationAddress(traces),
            goodsName = node.optJSONArray("packageItems")
                ?.optJSONObject(0)?.optStringOrNull("goodsName")?.takeIf { it.isNotBlank() },
            goodsImage = node.optJSONArray("packageItems")
                ?.optJSONObject(0)?.optStringOrNull("allPicUrl")?.takeIf { it.isNotBlank() },
            points = points(traces),
        )
    }

    /** 末条轨迹的地址，但只在它确实是「到站」那一跳时才认（见 [ARRIVAL_STATUSES]）。 */
    private fun stationAddress(traces: JSONArray?): String? {
        if (traces == null || traces.length() == 0) return null
        val last = traces.optJSONObject(traces.length() - 1) ?: return null
        val description = last.optStringOrNull("statusDesc").orEmpty()
        if (ExpressParser.parseStatus(description) !in ARRIVAL_STATUSES) return null
        return last.optStringOrNull("address")?.takeIf { it.isNotBlank() }
    }

    /**
     * 轨迹点。整条清洗后为空（纯广告）的直接丢掉 —— 留着会让详情页出现一行空白。
     *
     * `desc` 而不是 `standerdDesc`：真机上两者内容一致，取用户可见的那份。
     */
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
