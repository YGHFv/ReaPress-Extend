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
 * 小米焦点通知 / 超级岛的 `miui.focus.param` 参数。
 *
 * ## 依据
 *
 * 字段全部来自小米官方《开发指南》（`dev.mi.com/xiaomihyperos/documentation/detail?pId=2131`）
 * 与小米官方《超级岛推送指南》的参数表；`baseInfo` 的内层字段（`title` / `subContent` /
 * `colorSubContent` / `colorSubContentDark`）与 `protocol` 一并参照 HyperCeiler
 * （`ReChronoRain/HyperCeiler` 的 `NotificationHelper#createMiuiFoccusAction`，一个已公开的
 * 小米 ROM 工具模块）的真实用法 —— 官方文档只说「模板组件结构见《模板库》」，那份模板库的
 * 字段表没有公开页，所以这几个 key 以「第三方模块在真机上用过」为准。
 *
 * ## 只写有依据的字段
 *
 * 系统对这份 JSON 的解析是**宽容还是严格**没有公开说明；解析失败时通知会怎样也不确定。
 * 所以这里**宁缺勿猜**：只在有依据时才写某个 key，绝不添加「看起来应该有」的字段。
 * 尤其是 `baseInfo.type`（模板类型）—— 它的取值含义只在《模板库》里，公开渠道查不到，
 * 猜错可能把快递渲染成计时器模样的卡片，因此**不写**，让系统用默认模板。
 *
 * 岛（OS3）的 `param_island` 同理：本机（HyperOS 1.0 / 协议 1）不支持岛，写了也无从验证，
 * 等真有 OS3 设备时按官方模板库补。
 */
object MiuiFocusPayload {

    /** 承载这份 JSON 的 extras 键（官方文档原文）。 */
    const val EXTRA_PARAM = "miui.focus.param"

    /**
     * 业务场景名，官方文档里是**必选**（用于数据统计）。
     *
     * 取 `express` 而不是中文：它是分类标签，不是给用户看的文案。
     */
    private const val BUSINESS = "express"

    /**
     * `subContent` 的强调色（浅色 / 深色）。
     *
     * 与 HyperCeiler 同值。用系统强调蓝而不是主题色：焦点通知卡片的底色由系统决定，
     * 蓝在这套 ROM 的深浅两套底色上都读得清。
     */
    private const val COLOR_SUB_CONTENT = "#3482FF"
    private const val COLOR_SUB_CONTENT_DARK = "#277AF7"

    /**
     * 构造 `param_v2`。
     *
     * @param protocol [FocusNotificationCapability.Snapshot.protocol] 探测到的协议版本。
     *   **必须回填成系统自己报的那个数**：它告诉系统「这份参数是按哪一代模板写的」，
     *   写死成 1 或 3 都可能让高版本系统按错的模板解析。
     */
    fun build(record: ExpressRecord, protocol: Int): String {
        val title = ExpressFormatter.title(record)
        val subtitle = ExpressFormatter.summaryLine(record)

        val paramV2 = JSONObject()
            .put("protocol", protocol)
            .put("business", BUSINESS)
            // 持续性通知：同一个包裹的状态推进要能**更新同一条**（模块的通知 ID 由 dedupeKey 派生，
            // 本来就是覆盖语义），所以允许后续更新。
            .put("updatable", true)
            // 更新时不要自动展开成展开态 —— 用户没在看的时候弹开是打扰。
            .put("enableFloat", false)
            // 焦点通知权限被关掉时退化为普通通知（false = 正常显示、不被过滤）。
            // 显式写出来，不吃默认值的运气：模块的通知替代了被吞掉的原通知，丢一条就是全没了。
            .put("filterWhenNoPermission", false)
            .put("baseInfo", infoOf(title, subtitle))

        // 状态栏与息屏文案。OS2 起用 ticker，OS1 忽略这两个键 —— 一并写上，
        // 版本差异交给系统（这正是「按版本走合适渠道」的落点：字段是同一份，能力由 protocol 决定）。
        paramV2.put("ticker", title)
        subtitle?.let { paramV2.put("aodTitle", it) }

        return JSONObject().put("param_v2", paramV2).toString()
    }

    /**
     * 焦点通知正文模板。
     *
     * `title` 是主文案（公司 + 状态），`subContent` 是次文案（取件码 + 驿站 / 运单动态）——
     * 与 [ExpressFormatter.title] / [ExpressFormatter.summaryLine] 一一对应，界面和通知不会各说各的。
     */
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
