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

package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 淘宝 cookie 的模块侧落盘（MODE_PRIVATE，不离开模块 / 不进日志 / 不上传），作为 [TraceCookieCache]
 * 的持久层：进程启动读一次灌进内存，每次同步写一次 —— 磁盘读不该出现在热路径上。
 */
object TraceCookieStore {

    /** `internal` 是为了让备份清单引用同一份来源。 */
    internal const val PREFS = "trace_cookie"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_UA = "ua"
    private const val KEY_AT = "at"

    private const val LOG_TAG = "ReaPress"

    /** 兜底上限：一个月（`sgcookie` 是月级有效期）。 */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** [syncedAt] 是宿主同步过来的时刻，不是 cookie 的签发时间。 */
    data class Stored(val cookie: String, val ua: String?, val syncedAt: Long)

    fun read(context: Context): Stored? {
        val prefs = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        val cookie = prefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        if (at <= 0L || System.currentTimeMillis() - at > MAX_AGE_MS) return null
        return Stored(cookie, prefs.getString(KEY_UA, null)?.takeIf { it.isNotBlank() }, at)
    }

    fun write(context: Context, cookie: String, ua: String?) {
        if (cookie.isBlank()) return
        runCatching {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_COOKIE, cookie)
                .putLong(KEY_AT, System.currentTimeMillis())
            if (!ua.isNullOrBlank()) editor.putString(KEY_UA, ua)
            // commit：同步完马上就要拿来发请求，异步落盘会让「同步了但这次还是没用上」。
            editor.commit()
        }.onFailure {
            ModuleAndroidLog.error(LOG_TAG, "cookie persist failed", it)
        }
    }

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_COOKIE)
                .remove(KEY_UA)
                .remove(KEY_AT)
                .commit()
        }
    }
}
