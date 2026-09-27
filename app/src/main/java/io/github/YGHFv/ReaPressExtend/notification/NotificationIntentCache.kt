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

/**
 * 被拦下来的那条原通知的**点击跳转令牌**，按审计记录的 id 归口（内存）。
 *
 * ## 为什么只在内存里
 *
 * `PendingIntent` 是一个 binder 令牌（服务端拿它查 AMS 里的 `PendingIntentRecord`），
 * 它**没有**可序列化的表示 —— 写进 prefs 只会留下一串解不回来的字节。
 * 所以这份令牌的寿命 = 模块进程的寿命。
 *
 * ## 但它不是「打开原通知」的唯一来源（2026-09-27 修订）
 *
 * 令牌**内部装的那个 `Intent`** 是可序列化的：`Intent.toUri(Intent.URI_INTENT_SCHEME)`
 * 是系统自己用的那套编码。hook 侧把它拆出来当字符串随记录落盘
 * （[io.github.YGHFv.ReaPressExtend.hook.NotificationIntentReader]，存在
 * [ExpressNotificationLog.Entry.intentUri]），模块进程重启后 `Intent.parseUri` 重建一个
 * 普通 Intent 照样能跳 —— 这就是别的通知记录软件「过很久还能打开」的做法。
 *
 * 两者的分工见 [NotificationIntentLauncher]：**令牌优先**（连启动身份都是对的），
 * 快照兜底（有损，但不会消失）。所以这个表空了不再等于「按钮消失」。
 *
 * ## 为什么值得做
 *
 * 拦截模式下原通知被吞掉，用户手里唯一的入口是模块自己那条替换通知，而它点开的是**本模块**。
 * 想回到菜鸟/淘宝那个具体页面（比如某个包裹的取件码页）就只能自己去 App 里翻。
 * 揣着原通知的令牌，等于把「原通知本来能做的事」还给用户。
 *
 * ## 为什么是 LRU 而不是无限存
 *
 * 每个令牌都在 system_server 里挂着一份记录，攒着不放没有意义（上限 100 条，
 * 与 `ExpressNotificationLog.MAX_RECORDS` 同量级）。
 */
object NotificationIntentCache {

    private const val MAX_ENTRIES = 100

    /** accessOrder=true 的 LinkedHashMap 天然就是 LRU。 */
    private val entries = object : LinkedHashMap<String, PendingIntent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean =
            size > MAX_ENTRIES
    }

    fun remember(id: String, intent: PendingIntent) {
        if (id.isBlank()) return
        synchronized(entries) { entries[id] = intent }
    }

    /**
     * 取这条记录的原跳转令牌。null = 这里没有（老记录 / 这条不是通知 / 模块进程重启过）——
     * **不等于点不开**：记录里那份快照串还能用（见类注释与 [NotificationIntentLauncher]）。
     *
     * **不消费**：用户可能反复点。清理交给 LRU 与 [clear]。
     */
    fun get(id: String): PendingIntent? {
        if (id.isBlank()) return null
        return synchronized(entries) { entries[id] }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
