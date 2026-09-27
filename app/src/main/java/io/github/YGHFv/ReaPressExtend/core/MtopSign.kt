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

import java.security.MessageDigest

/** MTOP H5 通道请求签名：H5 通道的签名客户端可自算（APP 通道走 native，不可复刻）；真正的门槛是 _m_h5_tk cookie 与风控，不是签名。 */
object MtopSign {

    const val TAOBAO_H5_APP_KEY = "12574478"

    /** 从 _m_h5_tk 完整 cookie 值（形如 <32 位十六进制>_<13 位毫秒>）里取 `_` 前那 32 位。 */
    fun tokenOf(rawCookieValue: String): String = rawCookieValue.substringBefore('_')

    /** md5(token & t & appKey & data)，小写十六进制；t 是毫秒时间戳字符串。 */
    fun wapSign(token: String, timestamp: String, appKey: String, data: String): String =
        md5("$token&$timestamp&$appKey&$data")

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
