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
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 通知审计（投递 + 拦截，同一份存储）。
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
 * ## 2026-09-27：从「一行审计」变成「能点开的一条」
 *
 * 每条记录现在同时留存**被识别的那条原通知的标题与正文**（[Entry.originTitle] /
 * [Entry.originText]），点开可以看到「原文 → 识别结果」的对照。识别判错的现场只能这样复现
 * —— 列表里那两行字是模块重组过的，看不出原文到底长什么样。
 *
 * ## 2026-09-27 之二：两种记录共存（[Entry.Kind]）
 *
 * [Entry.Kind.INTERCEPTED] 是「通知拦截」吞掉的那些：它们以前**一点痕迹都不留**
 * （既不发通知也不落任何记录，唯一判据是 LSPosed 日志里那行 `EXPRESS DROPPED`），
 * 于是用户想核对自己到底拦掉了什么、拦得对不对，只能去翻系统日志。
 *
 * 现在它们与投递记录**共用这一份存储**，靠 [Entry.kind] 分开：
 * 存储格式、上限裁剪、解析、清空、详情页全都只有一份实现。
 * （两个独立的 prefs 文件会让「上限」和「清空」各写一遍，迟早漂移。）
 *
 * ## 原通知的跳转
 *
 * 两条来源并存，见 [NotificationIntentLauncher]：
 * - 内存里的 `PendingIntent`（[NotificationIntentCache]）—— 保真但进程重启即失效；
 * - 记录里的 [Entry.intentUri] 快照串 —— 基础参数级，**永久有效**。
 *
 * 明文存储即可：这里只有快递标题/状态，不含凭据。
 */
object ExpressNotificationLog {

    private const val PREFS = "reapress_notification_log"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 100

    /** 一条审计记录属于哪一类。 */
    enum class Kind {
        /** 模块的替换通知发出去了（或尝试发但失败）。 */
        DELIVERED,

        /**
         * 按「通知拦截」设置**被吞掉**的原通知。
         *
         * 它没有可投递的内容（用户要的就是它别出现），所以详情页里 [Entry.delivered] 恒为 false
         * 且**不显示成「未发出」**（那是失败语义，这是主动行为）。
         */
        INTERCEPTED,
    }

    /** 一条审计记录。 */
    data class Entry(
        val at: Long,
        val sourcePackage: String,
        /** 模块**识别后**的标题（别人看到的那条替换通知的抬头）。 */
        val title: String,
        /** 模块**识别后**的正文（字段被拆成「取件码：…」那种）。 */
        val detail: String,
        val delivered: Boolean,
        val failureDetail: String = "",
        /**
         * 被识别的那条通知的**原文标题**（AOSP `android.title` 原样）。
         *
         * 与 [title] 是两件事：那个是模块重新组装的，这个是用户本来会看到的。
         * 「为什么这条被判成快递了」只能在原文上核对 —— 识别结果的措辞已经把线索洗掉了。
         */
        val originTitle: String = "",
        /**
         * 被识别的那条通知的**原文正文**（`title` + `subText` + `bigText`/`textLines` 按
         * AOSP 的取值顺序拼好，见 `ExpressTextExtractor.extractFullText`）。
         */
        val originText: String = "",
        /**
         * 稳定标识。跳转用的 `PendingIntent` 在内存里按它归口
         * （见 [NotificationIntentCache]）。旧记录读出来是空串 —— 那只是「点不开原通知」。
         */
        val id: String = "",
        /** 投递记录还是拦截记录。旧记录（没这个键）一律当 [Kind.DELIVERED]。 */
        val kind: Kind = Kind.DELIVERED,
        /**
         * 被拦下时属于哪一类（[io.github.YGHFv.ReaPressExtend.core.NotificationCategory] 的名字）。
         * 只有 [Kind.INTERCEPTED] 有值 —— 「为什么它被吞了」的答案就是这个分类。
         */
        val category: String = "",
        /**
         * 原通知跳转的可落盘快照（`Intent.toUri(URI_INTENT_SCHEME)`），见
         * [NotificationIntentLauncher]。空串 = 没有（旧记录 / 那条通知没有 contentIntent）。
         */
        val intentUri: String = "",
        /**
         * 这块跳转令牌在 **system_server 侧**的寄存句柄
         * （[io.github.YGHFv.ReaPressExtend.relay.ExpressRelay.EXTRA_INTENT_TOKEN]）。
         *
         * 与 [id] 是两把不同的钥匙，别混：
         * - [id] 开的是**模块进程内存**里那张表（[NotificationIntentCache]），快但没有持久性；
         * - 这个开的是 **system_server** 里那张表（hook 侧 `IntentTokenStore`）——
         *   模块进程重启后，详情页靠它把令牌**取回来**（`IntentTokenFetcher`）。
         *
         * 是个普通字符串（模块自己生成的 UUID），所以**可以落盘** —— 而令牌本体不行。
         * 拿到它不等于令牌还在（system_server 可能已 LRU 淘汰、或设备重启过），
         * 取回时要按「可能空手」处理。空串 = 没有（旧记录 / 那条通知没有 contentIntent）。
         */
        val tokenId: String = "",
    )

    /**
     * 记一条**投递**审计（模块的替换通知）。
     *
     * @param contentIntent 原通知的点击跳转（`Notification.contentIntent`）。存进
     *   [NotificationIntentCache]：它是 binder 令牌，本体没有可落盘的表示 ——
     *   跨进程重启还能用的那份是 [intentUri]，两者在 [NotificationIntentLauncher] 里合流。
     * @param intentUri 同一跳转的可落盘快照串（由 hook 侧 [io.github.YGHFv.ReaPressExtend.hook.NotificationIntentReader]
     *   从令牌里拆出来）。有了它，「打开原通知」就不再随进程重启消失。
     */
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
        // 审计失败绝不能影响发通知本身 —— 它只是旁路记录。
    }

    /**
     * 记一条**拦截**审计（按设置被吞掉的原通知）。
     *
     * ## 为什么投递与拦截共用一份存储
     *
     * 见类注释。这里只补一条：被吞掉的通知**没有 contentIntent 的投递语义**，
     * 但它的跳转令牌样样有用 —— 用户看到「这条被我拦掉了」之后最可能想做的一件事，
     * 就是「那我去原 App 看一眼」，所以 [intentUri] 一样要存。
     *
     * @param category 拦截分类的枚举名（[io.github.YGHFv.ReaPressExtend.core.NotificationCategory]）。
     *   用名字而不是序号：枚举顺序在 [io.github.YGHFv.ReaPressExtend.core.NotificationCategory]
     *   里是显示顺序，加一个分类就会让所有旧记录的序号错位。
     */
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
                // 恒 false 且**不是失败**：详情页对 INTERCEPTED 另有渲染（见 Kind 的注释）。
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

    /**
     * 落盘一条。投递与拦截两条路共用的收尾（先存跳转再落记录：反过来的话，
     * 界面读到这条时跳转还没就位，用户手快就会看到「打开原通知」是灰的，明明刚拦到）。
     */
    private fun append(context: Context, entry: Entry, contentIntent: PendingIntent?) {
        contentIntent?.let { NotificationIntentCache.remember(entry.id, it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = trim(parse(prefs.getString(KEY_RECORDS, null)) + entry)
        prefs.edit().putString(KEY_RECORDS, serialize(updated)).apply()
    }

    /** 全部记录，最新在前。 */
    fun snapshot(context: Context): List<Entry> = snapshot(context, null)

    /** 只要某一类的记录，最新在前。传 null 表示不过滤。 */
    fun snapshot(context: Context, kind: Kind?): List<Entry> =
        runCatching {
            parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
                .filter { kind == null || it.kind == kind }
                .asReversed()
        }.getOrDefault(emptyList())

    /**
     * 清空记录（两类一起清）。
     *
     * 用 `commit()` 与 [ExpressRecordStore.clear] 同理：用户点完很可能立刻退出甚至杀进程，
     * 异步落盘会让这次删除丢掉，下次进来旧记录又回来了。
     *
     * ⚠️ 目前**没有界面入口**（记录页那张摘要卡在 2026-09-26 按用户要求撤掉了，清空按钮
     * 暂时没有落点）。函数保留完整语义，入口回来时直接用。
     */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDS).commit()
            // 记录没了，那些跳转令牌也就没有主人了 —— 一起丢，免得留着一个再也匹配不上的表。
            NotificationIntentCache.clear()
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
                    put("otitle", entry.originTitle)
                    put("otext", entry.originText)
                    put("id", entry.id)
                    put("kind", entry.kind.name)
                    put("cat", entry.category)
                    put("iuri", entry.intentUri)
                    // 「tk」= system_server 侧的令牌句柄。短键是既有约定（见本文件的历史），
                    // 而且它进的是 prefs 里那个 JSON 串 —— 100 条记录每条多 36 字节不值得。
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
                    // 2026-09-27 之前落盘的记录没有这几个键：读出来是空串 / 默认值，
                    // 表现就是详情页里「原文」那一段为空、「打开原通知」由快照兜底。
                    // 旧 JSON 必须读得出（项目约定），所以一律走 opt 而不是 get。
                    originTitle = obj.optString("otitle"),
                    originText = obj.optString("otext"),
                    id = obj.optString("id"),
                    // 缺键 / 认不出的值一律当投递记录：那是加入 kind 之前唯一存在的一类。
                    kind = if (obj.optString("kind") == Kind.INTERCEPTED.name) {
                        Kind.INTERCEPTED
                    } else {
                        Kind.DELIVERED
                    },
                    category = obj.optString("cat"),
                    intentUri = obj.optString("iuri"),
                    // 2026-09-27 加的键：旧记录读出来是空串，表现是「这条的令牌取不回来」
                    // （本来就取不回来，它们落盘时这个键还不存在）。
                    tokenId = obj.optString("tk"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun formatTime(at: Long): String = timeFormat.format(Date(at))
}
