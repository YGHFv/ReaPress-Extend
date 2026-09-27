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
 * 从淘宝物流 SSR 页里抠出来的东西 —— 实际**只有运单号是有用的**。
 *
 * [courierName] 是顺手带的：轨迹接口偶尔不给 `cp`，那时它能当兜底。
 *
 * 为什么不把 SSR 页里的轨迹 / 状态也一起解析出来：那页面给的是给手机看的缩写版本，
 * 而 `queryalltrace` 给的是同一个后端的完整结构（全轨迹 + 商品图 + 驿站地址，
 * 见 [CainiaoTraceInfo]）。**运单号一旦到手，后面就该走查询接口那条路**，
 * 两套轨迹解析并存只会让「详情页显示的是哪一份」变成新的谜题。
 */
data class SsrParcel(
    val mailNo: String,
    val courierName: String? = null,
)

/**
 * 淘宝物流 SSR 页（`pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail`）的解析。
 *
 * 纯函数、不碰网络 —— 网络在 `hook/TaobaoOrderApi`。
 *
 * ## 那个标记是什么
 *
 * 服务端把首屏数据以一段内联脚本写进 HTML：`...['__ICE_SUSPENSE_LOADER__']['undefined'] = {...}`
 * （ICE 是淘系的前端框架）。所以做法是找到这段标记、把它后面的那一个 JSON 值读出来 ——
 * `JSONTokener` 正好只读**一个完整值**就停，后面的 `</script>` 与其余 HTML 不会干扰它。
 *
 * ⚠️ 只认标记、不认别的：SSR 页对未登录 / cookie 失效的请求会返回**登录页**，
 * 那时 HTML 里没有这段，解析失败返回 null —— 这正是我们要的（不是「解析出空数据」）。
 *
 * 字段路径照 Halo0sama/ExpressAssistant（MIT）的 `api/TbOrders.kt` 核对过。
 */
object SsrLogisticsParser {

    /** ICE 首屏数据的标记。`window['__ICE_SUSPENSE_LOADER__']['undefined'] = ` 的后半段。 */
    private const val MARKER = "__ICE_SUSPENSE_LOADER__']['undefined'] = "

    /** @return 没有标记 / 结构不符 / 没有运单号时返回 null（调用方退化为「没拿到」）。 */
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

    /**
     * 运单号。两个位置都见过：`fields.mailNo` 与外层的 `logisticCompany.mailNo`
     * （对照项目里也是这么判的先后）。
     */
    private fun readMailNo(fields: JSONObject): String = fields.optString("mailNo")
        .ifBlank { fields.optJSONObject("logisticCompany")?.optString("mailNo").orEmpty() }

    /**
     * 定位并读出 ICE 首屏数据里那段物流 fields。
     *
     * 两个外层键：`newLogistics` 是新版、`logisticsDetailH5` 是旧版 —— 页面改版时**先看这里**：
     * 表现会是「订单列表明明有订单，却一个运单号都拿不到」。
     */
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
