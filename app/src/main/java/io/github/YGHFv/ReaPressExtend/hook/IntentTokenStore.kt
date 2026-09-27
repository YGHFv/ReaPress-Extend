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

package io.github.YGHFv.ReaPressExtend.hook

import android.app.PendingIntent
import java.util.UUID

/**
 * 原通知跳转令牌在 **system_server 侧的寄存处**。
 *
 * ## 为什么要有第二份（模块侧那份是 `NotificationIntentCache`）
 *
 * 令牌是 binder 句柄，**没有可落盘的表示** —— 它在哪，寿命就在哪：
 *
 * | 存哪 | 谁在用 | 寿命 |
 * |---|---|---|
 * | 模块进程内存（`NotificationIntentCache`） | 详情页点击时 `send()` | 模块进程 —— 清后台 / 被系统回收即失效 |
 * | **这里**（system_server） | 模块进程来取（[IntentTokenRelay]） | **设备本次开机** |
 *
 * 模块进程常年没有界面（它靠宿主通知才被拉起来），被回收是常态 —— 只有模块侧那份的话，
 * 用户过一会儿再来看记录，「打开原通知」就已经退化成「打开菜鸟」了。
 *
 * ## 上限与「不是永久」
 *
 * 上限 [MAX_ENTRIES]（LRU）：令牌在 AMS 里各挂着一份 `PendingIntentRecord`，攒着不放没有意义，
 * 量级与 `ExpressNotificationLog.MAX_RECORDS` 对齐。
 *
 * ⚠️ **设备一重启，这里和模块侧一起归零** —— system_server 也重来，AMS 里的记录同样重建。
 * 所以这是「延长到设备开机周期」，不是「永久」（后者做不到，除非把 Intent 本体存下来，
 * 那就是快照那条有损的路 —— 两条路在 `NotificationIntentLauncher` 里合流）。
 *
 * ## 谁在这里跑
 *
 * 被注入的 system_server（[SystemServerHook] 拦到通知时 `stash`，[IntentTokenRelay] 取回）。
 * 它与 [io.github.YGHFv.ReaPressExtend.notification.NotificationIntentCache] 是两个进程里
 * **同名不同命**的两份表 —— 类名上刻意不共用「cache」这个词，免得读代码时以为是一处。
 */
internal object IntentTokenStore {

    private const val MAX_ENTRIES = 200

    /** accessOrder=true 的 LinkedHashMap 天然就是 LRU。 */
    private val entries = object : LinkedHashMap<String, PendingIntent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 寄存一块令牌，返回它在**记录里**要存的那个句柄（[io.github.YGHFv.ReaPressExtend.relay.ExpressRelay.EXTRA_INTENT_TOKEN]）。
     *
     * @return 句柄；令牌为 null（那条通知没有 contentIntent）时返回 null —— 调用方据此
     *   不发这个 extra，记录里也就是空串（表现是「这条没有跳转信息」，见详情页）。
     *
     * 句柄是**随机 UUID**：不要求跨投递稳定（同一条通知更新几次就寄存几份，
     * 模块侧那些记录与之一一对应），要求的是**不可猜** —— 虽然只有持 [io.github.YGHFv.ReaPressExtend.relay.ExpressRelay.PERMISSION_TRACE_REQUEST]
     * 的发送方能请求，但能猜的句柄等于多留一道敞口，没必要。
     */
    fun stash(intent: PendingIntent?): String? {
        if (intent == null) return null
        val id = UUID.randomUUID().toString()
        synchronized(entries) { entries[id] = intent }
        return id
    }

    /**
     * 取回一块令牌。null = 这里没有（被 LRU 淘汰 / 设备重启过 / 句柄是伪造的）——
     * **不等于点不开**：记录里那份快照串还能打开宿主 App（见 `NotificationIntentLauncher`）。
     *
     * 取回**不消费**：用户可能反复点同一条记录，清理由 LRU 负责。
     */
    fun get(id: String?): PendingIntent? {
        if (id.isNullOrBlank()) return null
        return synchronized(entries) { entries[id] }
    }
}
