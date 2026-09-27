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

import org.json.JSONObject
import org.json.JSONTokener

/**
 * SSR 页里实际只取运单号（[courierName] 是轨迹接口不给 `cp` 时的兜底）—— 轨迹与状态一律走查询接口。
 */
data class SsrParcel(
    val mailNo: String,
    val courierName: String? = null,
)

/**
 * SSR 页内联的 ICE 首屏 JSON 解析（纯函数，不碰网络）。只认 `__ICE_SUSPENSE_LOADER__` 标记：
 * 未登录 / cookie 失效时返回的是登录页，没有标记 → null（不是解析出空数据）。
 * 外层键 `newLogistics` 是新版、`logisticsDetailH5` 是旧版 —— 页面改版先看这里。
 */
object SsrLogisticsParser {

    /** `window['__ICE_SUSPENSE_LOADER__']['undefined'] = ` 的后半段。 */
    private const val MARKER = "__ICE_SUSPENSE_LOADER__']['undefined'] = "

    /** 没有标记 / 结构不符 / 没有运单号时返回 null。 */
    fun parse(html: String): SsrParcel? = try {
        val fields = readFields(html)
        val mailNo = fields?.let { readMailNo(it) }
        if (mailNo.isNullOrBlank()) {
            null
        } else {
            SsrParcel(
                mailNo = mailNo,
                courierName = fields.optJSONObject("logisticCompany")
                    ?.optString("name")?.takeIf { it.isNotBlank() },
            )
        }
    } catch (e: Throwable) {
        null
    }

    /** 两个位置都见过：`fields.mailNo` 与外层的 `logisticCompany.mailNo`。 */
    private fun readMailNo(fields: JSONObject): String = fields.optString("mailNo")
        .ifBlank { fields.optJSONObject("logisticCompany")?.optString("mailNo").orEmpty() }

    /** 读出内联 JSON 里的物流 fields。 */
    internal fun readFields(html: String): JSONObject? {
        val start = html.indexOf(MARKER)
        if (start < 0) return null
        val value = JSONTokener(html.substring(start + MARKER.length)).nextValue()
        val data = (value as? JSONObject)
            ?.optJSONObject("result")
            ?.optJSONObject("data")
            ?: return null
        return data.optJSONObject("newLogistics")?.optJSONObject("fields")
            ?: data.optJSONObject("logisticsDetailH5")?.optJSONObject("fields")
    }
}
