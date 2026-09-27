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

package io.github.YGHFv.ReaPressExtend.notification

import io.github.YGHFv.ReaPressExtend.core.ExpressFormatter
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import org.json.JSONObject

/**
 * 小米焦点通知 / 超级岛的 `miui.focus.param` 参数。字段来自小米官方《开发指南》与《超级岛推送指南》，
 * 内层字段参照 HyperCeiler 的真机用法。系统对这份 JSON 的解析宽容与否没有公开说明，
 * 所以宁缺勿猜：只在有依据时写 key —— `baseInfo.type` 的取值含义只在未公开的模板库里，
 * 猜错可能把快递渲染成计时器模样的卡片，不写让系统用默认模板；岛（OS3）字段等真有设备再补。
 */
object MiuiFocusPayload {

    const val EXTRA_PARAM = "miui.focus.param"

    private const val BUSINESS = "express"

    private const val COLOR_SUB_CONTENT = "#3482FF"
    private const val COLOR_SUB_CONTENT_DARK = "#277AF7"

    /** [protocol] 必须回填系统自己报的那个数：它告诉系统参数按哪一代模板写，写死可能让高版本系统按错的模板解析。 */
    fun build(record: ExpressRecord, protocol: Int): String {
        val title = ExpressFormatter.title(record)
        val subtitle = ExpressFormatter.summaryLine(record)

        val paramV2 = JSONObject()
            .put("protocol", protocol)
            .put("business", BUSINESS)
            .put("updatable", true)
            .put("enableFloat", false)
            // 显式写 false 不吃默认值：通知替代了被吞掉的原通知，丢一条就是全没了。
            .put("filterWhenNoPermission", false)
            .put("baseInfo", infoOf(title, subtitle))

        // OS2 起用 ticker，OS1 忽略，版本差异交给系统。
        paramV2.put("ticker", title)
        subtitle?.let { paramV2.put("aodTitle", it) }

        return JSONObject().put("param_v2", paramV2).toString()
    }

    private fun infoOf(title: String, subtitle: String?): JSONObject = JSONObject()
        .put("title", title)
        .apply {
            subtitle?.takeIf { it.isNotBlank() }?.let {
                put("subContent", it)
                put("colorSubContent", COLOR_SUB_CONTENT)
                put("colorSubContentDark", COLOR_SUB_CONTENT_DARK)
            }
        }
}
