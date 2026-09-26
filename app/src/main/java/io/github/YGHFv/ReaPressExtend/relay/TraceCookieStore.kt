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
 * 淘宝登录态 cookie 的**模块侧落盘**。
 *
 * ## 为什么破例落盘（2026-09-26 修订）
 *
 * 最初的设计是「cookie 只进内存」（[TraceCookieCache] 的注释写着「不落盘、不进日志」），
 * 理由是「运行时凭证不变成静态资产」。真机实证证明那条路走不通：
 *
 * - hook 侧的 `sendCookieSync` 有 **30 分钟节流**；
 * - 模块进程在真机日志里一天重启了**六次**（`module main ui opened` 的六条），每次内存缓存归零；
 * - 两者叠加的结果是模块侧**永远没有 cookie** —— 27 条记录全是 `trace=0`、
 *   `cookie synced` 一条都没打过、`trace_fetch.xml` 根本没生成。用户看到的就是
 *   「包裹详情怎么都获取不了」。
 *
 * 所以用户拍板：**允许把 cookie 落到模块私有目录**。收窄后的边界不变的部分是
 * 「不离开模块、不进日志、不上传」—— 文件本身是 `MODE_PRIVATE`（同 `reapress_records.xml`），
 * 只有模块自己读得到；日志里只打「有没有、什么时候同步的」。
 *
 * ## 与内存缓存的分工
 *
 * [TraceCookieCache] 是热路径（每次拉取都要读），这里是它的**持久层**：进程启动时读一次灌进内存，
 * 每次同步时写一次。分开是因为拉取在后台线程反复发生，而磁盘读不该出现在那条路径上。
 *
 * ## 过期怎么办
 *
 * 不做 TTL 判断，只留一条兜底上限（[MAX_AGE_MS]）—— 真过期了服务端会回
 * `FAIL_SYS_SESSION_EXPIRED`，那一处会主动清掉（比在这里猜「还新不新」准）。
 */
object TraceCookieStore {

    private const val PREFS = "trace_cookie"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_UA = "ua"
    private const val KEY_AT = "at"

    private const val LOG_TAG = "ReaPress"

    /**
     * 兜底上限：一个月。`sgcookie` 这类是月级有效期，超过这个岁数基本只可能是「用户早已
     * 换了账号 / 卸载重装过」，而留着它只会让每次拉取都白撞一次服务端。
     */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** 落盘的一份登录态。[syncedAt] 是**宿主同步过来**的时刻，不是 cookie 自己的签发时间。 */
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
