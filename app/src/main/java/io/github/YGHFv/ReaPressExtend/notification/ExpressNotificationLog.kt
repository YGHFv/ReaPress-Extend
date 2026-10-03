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

import android.app.PendingIntent
import android.content.Context
import androidx.core.content.edit
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import org.json.JSONArray
import org.json.JSONObject
import io.github.YGHFv.ReaPressExtend.core.LocalTimeFormatter
import java.util.UUID

/**
 * 通知审计（投递 + 拦截，同一份存储）：排查「拦截了但没收到通知」的第一手证据。
 * 每条记录留存被识别的原通知标题与正文，识别判错的现场只能在原文上核对。明文存储即可，不含凭据。
 */
object ExpressNotificationLog {

    /** `internal` 供备份清单引用同一份来源。 */
    internal const val PREFS = "reapress_notification_log"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 100

    enum class Kind {
        /** 模块的替换通知发出去了（或尝试发但失败）。 */
        DELIVERED,

        /** 按「通知拦截」被吞掉的原通知；详情页不显示成「未发出」（那是失败语义，这是主动行为）。 */
        INTERCEPTED,
    }

    /** 一条审计记录。 */
    data class Entry(
        val at: Long,
        val sourcePackage: String,
        /** 模块识别后的标题/正文（用户看到的那条替换通知）。 */
        val title: String,
        val detail: String,
        val delivered: Boolean,
        val failureDetail: String = "",
        /** 被识别那条通知的原文标题/正文：识别措辞已把线索洗掉，判错现场只能在原文核对。 */
        val originTitle: String = "",
        val originText: String = "",
        /** 稳定标识，跳转令牌在内存里按它归口（NotificationIntentCache）。 */
        val id: String = "",
        /** 投递还是拦截；旧记录（没这个键）一律当 DELIVERED。 */
        val kind: Kind = Kind.DELIVERED,
        /** 被拦下的分类（枚举名，不用序号——加分类会让旧记录序号错位）。只有 INTERCEPTED 有值。 */
        val category: String = "",
        /** 原通知跳转的可落盘快照（永久有效）；空串 = 没有。 */
        val intentUri: String = "",
        /**
         * 跳转令牌在 system_server 侧的寄存句柄：可落盘而令牌本体不行。
         * 取回时按「可能空手」处理（system_server 可能已淘汰或重启）；空串 = 没有。
         */
        val tokenId: String = "",
    )

    /** 记一条投递审计。contentIntent 进 NotificationIntentCache，intentUri 是可落盘的那份。 */
    fun record(
        context: Context,
        record: ExpressRecord,
        delivered: Boolean,
        detail: String = "",
        contentIntent: PendingIntent? = null,
        intentUri: String? = null,
        intentToken: String? = null,
    ) {
        runCatching {
            val entry = Entry(
                at = System.currentTimeMillis(),
                sourcePackage = record.sourcePackage,
                title = io.github.YGHFv.ReaPressExtend.core.ExpressFormatter.title(record),
                detail = io.github.YGHFv.ReaPressExtend.core.ExpressFormatter.body(record)
                    .replace('\n', ' '),
                delivered = delivered,
                failureDetail = detail,
                originTitle = record.title.orEmpty(),
                originText = record.rawText,
                id = UUID.randomUUID().toString(),
                kind = Kind.DELIVERED,
                intentUri = intentUri.orEmpty(),
                tokenId = intentToken.orEmpty(),
            )
            append(context, entry, contentIntent)
        }
        // 审计只是旁路记录，失败绝不能影响发通知本身。
    }

    /** 记一条拦截审计；被吞掉的通知没有投递语义，但跳转快照一样要存。 */
    fun recordIntercepted(
        context: Context,
        record: ExpressRecord,
        category: String,
        contentIntent: PendingIntent? = null,
        intentUri: String? = null,
        intentToken: String? = null,
    ) {
        runCatching {
            val entry = Entry(
                at = System.currentTimeMillis(),
                sourcePackage = record.sourcePackage,
                title = io.github.YGHFv.ReaPressExtend.core.ExpressFormatter.title(record),
                detail = io.github.YGHFv.ReaPressExtend.core.ExpressFormatter.body(record)
                    .replace('\n', ' '),
                delivered = false,
                failureDetail = "",
                originTitle = record.title.orEmpty(),
                originText = record.rawText,
                id = UUID.randomUUID().toString(),
                kind = Kind.INTERCEPTED,
                category = category,
                intentUri = intentUri.orEmpty(),
                tokenId = intentToken.orEmpty(),
            )
            append(context, entry, contentIntent)
        }
    }

    /** 投递与拦截两条路共用的收尾。先存跳转再落记录，反了界面会看到灰的「打开原通知」。 */
    private fun append(context: Context, entry: Entry, contentIntent: PendingIntent?): Unit = ExpressRecordStore.withTransaction {
        contentIntent?.let { NotificationIntentCache.remember(entry.id, it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = trim(parse(prefs.getString(KEY_RECORDS, null)) + entry)
        prefs.edit { putString(KEY_RECORDS, serialize(updated)) }
    }

    /** 全部记录，最新在前。 */
    fun snapshot(context: Context): List<Entry> = snapshot(context, null)

    fun snapshot(context: Context, kind: Kind?): List<Entry> =
        ExpressRecordStore.withTransaction {
            runCatching {
                parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
                    .filter { kind == null || it.kind == kind }
                    .asReversed()
            }.getOrDefault(emptyList())
        }

    /**
     * 清空记录（两类一起清）。用 `commit()`：用户点完可能立刻杀进程，异步落盘会让删除丢掉。
     * 目前没有界面入口，函数保留完整语义。
     */
    fun clear(context: Context): Unit = ExpressRecordStore.withTransaction {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS).commit()
            NotificationIntentCache.clear()
        }
    }

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
                    put("otitle", entry.originTitle)
                    put("otext", entry.originText)
                    put("id", entry.id)
                    put("kind", entry.kind.name)
                    put("cat", entry.category)
                    put("iuri", entry.intentUri)
                    put("tk", entry.tokenId)
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
                    // 旧 JSON 必须读得出（项目约定），一律 opt 而不是 get；缺键退默认值。
                    originTitle = obj.optString("otitle"),
                    originText = obj.optString("otext"),
                    id = obj.optString("id"),
                    // 缺键 / 认不出的值一律当投递记录（加 kind 之前唯一存在的一类）。
                    kind = if (obj.optString("kind") == Kind.INTERCEPTED.name) {
                        Kind.INTERCEPTED
                    } else {
                        Kind.DELIVERED
                    },
                    category = obj.optString("cat"),
                    intentUri = obj.optString("iuri"),
                    tokenId = obj.optString("tk"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private val timeFormat = LocalTimeFormatter("MM-dd HH:mm:ss")

    fun formatTime(at: Long): String = timeFormat.format(at)
}
