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
 * 字段全部对应真机实测的响应路径（2026-09-26，极兔 JT0000000000000，15 条轨迹），
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
     * 这一条**可以用来推进记录状态**的结局（见 [TRACE_ADVANCE_STATUSES]）。null = 轨迹这
     * 一侧没有可采信的状态。
     *
     * 与 [statusDesc] 分开：那个是**接口原话**（进日志、进界面都不加工），这个是**判据**。
     * 界面上的「运输中 / 派送中」原来只有宿主富化会给，宿主不刷新就永远停在旧词上
     * （2026-09-27 用户报的「打开详情拉到新轨迹、卡片右上角还是旧状态」）——
     * 轨迹里其实每次都带着状态，只是没人读。
     */
    val status: ExpressStatus? = null,
    /**
     * 驿站**完整地址**（末条轨迹的 `address`），如 `阳光23号楼109阳光花园菜鸟驿站`。
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

    /**
     * 轨迹可以拿来**推进记录**的状态白名单。
     *
     * 刻意**不收** `IN_TRANSIT`：宿主对还没揽收的件也会笼统地写「运输中」，而
     * [ExpressRecord.mergeEnrichment] 里那条「CREATED 纠正 IN_TRANSIT」正是为这件事
     * 存在的 —— 轨迹这份 `newStatusDesc` 来自同一个服务端字段，收下它等于把那句纠正
     * 原样盖回去（界面会从「已下单」跳回「运输中」）。
     *
     * 剩下的五个都是**结论型**状态（件到了哪、要不要用户动手、闭环没有），它们比宿主的
     * 描述字段更早、也更具体：轨迹一拿到就能让卡片换档（运输中 → 到站包裹），
     * 而这正是用户要的「不用点开详情也能自己更新」。
     *
     * `CREATED` / `PICKED_UP` 也不收：它们的判据在通知文案与宿主描述里更可靠，
     * 轨迹的这两句（「等待揽收」）随时可能被快递公司写成别的措辞。
     */
    private val TRACE_ADVANCE_STATUSES = setOf(
        ExpressStatus.DELIVERING,
        ExpressStatus.ARRIVED_STATION,
        ExpressStatus.READY_FOR_PICKUP,
        ExpressStatus.SIGNED,
        ExpressStatus.FAILED,
    )

    /** 描述文案 → 可采信的状态。认不出来（措辞变了）时返回 null，绝不硬猜一个出来。 */
    private fun traceAdvanceStatus(desc: String?): ExpressStatus? =
        desc?.let { ExpressParser.parseStatus(it) }?.takeIf { it in TRACE_ADVANCE_STATUSES }

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
        val packageStatus = node.optJSONObject("packageStatus")
        val traces = node.optJSONArray("fullTraceDetail")
        // `newStatusDesc` 比 `status` 新：实测同一件上 status=派送中 而 newStatusDesc=待取件
        // （包裹已经卸到代收点了，只是主状态还没翻）。取新的那个。
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
