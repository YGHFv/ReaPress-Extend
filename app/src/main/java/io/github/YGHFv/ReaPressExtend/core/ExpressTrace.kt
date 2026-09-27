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

/** 一个物流轨迹点。[time] 保留原样字符串不解析（core 不碰系统时钟）；同源格式固定，按字符串倒序即时间倒序。 */
data class ExpressTracePoint(
    val time: String,
    val text: String,
)

/** 最新一条轨迹文案；宿主只在打开菜鸟时才更新，用它兜首页副行。 */
fun latestTraceDetail(points: List<ExpressTracePoint>): String? =
    points.lastOrNull()?.text?.takeIf { it.isNotBlank() }

/** 轨迹文案清洗 —— 只删「括号包住且含广告特征词」的整段：误删真实轨迹比留着广告糟得多，网点名也长着方括号。 */
object ExpressTraceText {

    private val AD_MARKERS = listOf(
        "物流问题无需找",
        "无需找商家",
        "为您解决",
        "请放心接听",
        "勿找平台",
        "少一次投诉",
        "专属号码",
        "外呼",
    )

    private val BRACKETS = listOf(
        '（' to '）',
        '(' to ')',
        '【' to '】',
        '[' to ']',
    )

    private val WHITESPACE = Regex("\\s+")

    /** 返回空串表示整条都是广告，调用方应跳过它而不是退回原文。 */
    fun clean(raw: String): String {
        var text = raw
        for ((open, close) in BRACKETS) text = stripAdSegments(text, open, close)
        return WHITESPACE.replace(text, " ").trim()
    }

    private fun stripAdSegments(text: String, open: Char, close: Char): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(open, index)
            if (start < 0) {
                out.append(text, index, text.length)
                break
            }
            val end = text.indexOf(close, start + 1)
            if (end < 0) {
                out.append(text, index, text.length)
                break
            }
            val segment = text.substring(start, end + 1)
            out.append(text, index, start)
            if (!isAd(segment)) out.append(segment)
            index = end + 1
        }
        return out.toString()
    }

    private fun isAd(segment: String): Boolean = AD_MARKERS.any { segment.contains(it) }
}

/** 轨迹编解码，跨「被注入进程 → 模块 App」与落盘共用；编码成 `[[time, text], …]` 数组对而不是对象（键名会占掉近一半体积）。 */
object ExpressTraceCodec {

    fun encode(points: List<ExpressTracePoint>): String {
        val array = JSONArray()
        for (point in points) {
            array.put(JSONArray().put(point.time).put(point.text))
        }
        return array.toString()
    }

    /** 解不出来就当空表 —— 轨迹是附属信息，读不出不该影响整条记录。 */
    fun decode(raw: String?): List<ExpressTracePoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val pair = array.optJSONArray(index) ?: return@mapNotNull null
                val text = pair.optString(1).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ExpressTracePoint(time = pair.optString(0), text = text)
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
