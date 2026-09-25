package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知投递审计。
 *
 * ## 为什么需要
 *
 * 模块进程常年没有界面，通知有没有真的发出去在设备上完全看不出来。更麻烦的是
 * **拦截与重发是两跳**：system_server 拦下原通知、广播出去，模块进程发新的 ——
 * 第二跳失败时用户什么都看不到（静默丢通知），而日志里只有一行「relayed」，
 * 看起来一切正常。
 *
 * 把每次投递（含失败原因）落进模块自己的 prefs，主界面就能直接回答
 * 「这条到底发出去了没有」。排查「拦截了但没收到通知」时这是第一手证据。
 *
 * 明文存储即可：这里只有快递标题/状态，不含凭据。
 */
object ExpressNotificationLog {

    private const val PREFS = "reapress_notification_log"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 100

    /** 一条投递记录。 */
    data class Entry(
        val at: Long,
        val sourcePackage: String,
        val title: String,
        val detail: String,
        val delivered: Boolean,
        val failureDetail: String = "",
    )

    fun record(context: Context, record: ExpressRecord, delivered: Boolean, detail: String = "") {
        runCatching {
            val entry = Entry(
                at = System.currentTimeMillis(),
                sourcePackage = record.sourcePackage,
                title = io.github.YGHFv.ReaPressExtend.core.ExpressFormatter.title(record),
                detail = io.github.YGHFv.ReaPressExtend.core.ExpressFormatter.body(record)
                    .replace('\n', ' '),
                delivered = delivered,
                failureDetail = detail,
            )
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val updated = trim(parse(prefs.getString(KEY_RECORDS, null)) + entry)
            prefs.edit().putString(KEY_RECORDS, serialize(updated)).apply()
        }
        // 审计失败绝不能影响发通知本身 —— 它只是旁路记录。
    }

    /** 最新在前。 */
    fun snapshot(context: Context): List<Entry> =
        runCatching {
            parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
                .asReversed()
        }.getOrDefault(emptyList())

    /**
     * 清空投递记录。
     *
     * ⚠️ 2026-09-26：记录页那张摘要卡已按用户要求撤掉，清空入口暂时没有落点
     * （放置办法待定）。函数保留完整语义，入口回来时直接用。
     *
     * 用 `commit()` 与 [ExpressRecordStore.clear] 同理：用户点完很可能立刻退出甚至杀进程，
     * 异步落盘会让这次删除丢掉，下次进来旧记录又回来了。
     */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS).commit()
        }
    }

    /** 裁剪到上限，保留最新的。抽成 internal 纯函数以便单测。 */
    internal fun trim(entries: List<Entry>): List<Entry> =
        if (entries.size <= MAX_RECORDS) entries else entries.takeLast(MAX_RECORDS)

    internal fun serialize(entries: List<Entry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject().apply {
                    put("at", entry.at)
                    put("pkg", entry.sourcePackage)
                    put("title", entry.title)
                    put("detail", entry.detail)
                    put("delivered", entry.delivered)
                    put("failure", entry.failureDetail)
                },
            )
        }
        return array.toString()
    }

    internal fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                Entry(
                    at = obj.optLong("at"),
                    sourcePackage = obj.optString("pkg"),
                    title = obj.optString("title"),
                    detail = obj.optString("detail"),
                    delivered = obj.optBoolean("delivered"),
                    failureDetail = obj.optString("failure"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun formatTime(at: Long): String = timeFormat.format(Date(at))
}
