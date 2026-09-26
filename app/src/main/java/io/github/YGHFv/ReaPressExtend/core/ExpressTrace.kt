package io.github.YGHFv.ReaPressExtend.core

import org.json.JSONArray

/**
 * 一个物流轨迹点。
 *
 * [time] 保留**外部原样给的字符串**（`2026-09-26 13:19:31`），不在 core 里解析成时间戳 ——
 * 相对时间（「今天 13:19」/「昨天」/ 日期）属于展示层，而 core 不碰系统时钟（项目约定）。
 * 排序也不要自己比大小：同一个来源的格式固定，按字符串倒序就是时间倒序。
 */
data class ExpressTracePoint(
    val time: String,
    val text: String,
)

/**
 * 轨迹文案清洗 —— 去掉快递公司夹在动态里的广告。
 *
 * 真机证据（极兔 JT3178691239988 第 13 条 `desc`）：
 *
 * ```
 * 【阜阳颍泉双河社区网点】的兔兔快递员：董力（13951517595）正在为您派件
 * （有事先呼我，勿找平台，少一次投诉，多一份感恩！），投诉电话（0558-5011429/13515572187）。
 * 【952300为极兔快递员外呼专属号码，请放心接听】
 * ```
 *
 * 两处方括号 / 圆括号全是广告，真正的信息（谁在派件、电话）只占前半句。不清掉的话，
 * 通知和详情页里一条轨迹能有六七行，用户根本看不到「快件到达【颍东集散点】」这种真动态。
 *
 * ## 只删「括号包住、且含广告特征词」的整段
 *
 * 保守到近乎偏执，因为**误删真实轨迹比留着广告糟得多**：`【上海嘉定曹安路网点】` 是网点名，
 * 长得和广告段一模一样（同为方括号），但一个特征词都不含 —— 靠特征词兜住它。
 * 特征词表只收真机见过、且不会出现在地名 / 网点名 / 状态描述里的写法。
 */
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

    /** 中英文两套括号都要处理 —— 不同快递公司的模板不一样。 */
    private val BRACKETS = listOf(
        '（' to '）',
        '(' to ')',
        '【' to '】',
        '[' to ']',
    )

    private val WHITESPACE = Regex("\\s+")

    /**
     * 清洗一条轨迹文案。
     *
     * 返回空串表示「整条都是广告」——调用方应该跳过它，而不是退回原文。
     */
    fun clean(raw: String): String {
        var text = raw
        for ((open, close) in BRACKETS) text = stripAdSegments(text, open, close)
        return WHITESPACE.replace(text, " ").trim()
    }

    /**
     * 扫描删除 [open]..[close] 之间、含广告特征词的段。
     *
     * 不做嵌套括号（真机没见过，模板都是平铺的）；找不到配对括号时原样保留剩下的部分 ——
     * 半个括号多半意味着格式和我们预期的不一样，此时**什么都不动**比猜着删安全。
     */
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

/**
 * 轨迹的编解码 —— 跨越「被注入进程 → 模块 App」那道进程边界，以及落盘。
 *
 * 一个入口一个出口：广播的 extra 与 SharedPreferences 的字段都用它，两边不会各自漂出
 * 一套格式。空的输入解码成空表（不是 null）：轨迹的「没有」只有一种表示，
 * 调用方不需要判两种。
 *
 * 编码成 `[["2026-09-26 13:19:31","快件已到达【颍东集散点】"], …]` —— 用数组而不是对象，
 * 因为一条轨迹只有两个字段，`{"t":…,"d":…}` 的键名会占掉将近一半的体积，
 * 而这个字符串会跟着每条到站件一起落盘。
 */
object ExpressTraceCodec {

    fun encode(points: List<ExpressTracePoint>): String {
        val array = JSONArray()
        for (point in points) {
            array.put(JSONArray().put(point.time).put(point.text))
        }
        return array.toString()
    }

    /** 解不出来（格式变了、被截断）就当空表 —— 轨迹是附属信息，读不出不该影响整条记录。 */
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
