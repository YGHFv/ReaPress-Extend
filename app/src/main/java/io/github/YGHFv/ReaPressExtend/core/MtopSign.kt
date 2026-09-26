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

/**
 * MTOP H5 通道的请求签名。
 *
 * ## 为什么能自己算
 *
 * MTOP 有**两个通道**，签名门槛完全不同（2026-09-26 实测确认，证据见
 * `express-source-research.md`）：
 *
 * | 通道 | 地址 | 签名 |
 * |---|---|---|
 * | H5 | `acs.m.taobao.com/h5/` · `h5api.m.taobao.com` | `md5(token & t & appKey & data)`，客户端可算 |
 * | APP | MTOP SDK + sec | native，**不可复刻** |
 *
 * 所以「MTOP 签名在 native」这句话**只对 APP 通道成立**，是个曾经写进项目记忆的过宽结论。
 * 本条走 H5 通道，真正的门槛不是签名，而是 `_m_h5_tk` cookie（要有登录态才发得下来）
 * 以及服务端风控 —— 实测带假 token 会直接吃 `RGV587_ERROR::SM` 处罚页。
 */
object MtopSign {

    /** 淘宝 H5 各页面共用的 appKey。 */
    const val TAOBAO_H5_APP_KEY = "12574478"

    /**
     * 从 `_m_h5_tk` 的**完整 cookie 值**里取出参与签名的 token。
     *
     * 服务端下发的是 `token_时间戳`（如 `e2009accb92774855df71be085be21f8_1790410278831`），
     * 签名只用 `_` 前面那 32 位。
     */
    fun tokenOf(rawCookieValue: String): String = rawCookieValue.substringBefore('_')

    /** `md5(token & t & appKey & data)`，小写十六进制。`t` 是毫秒时间戳字符串。 */
    fun wapSign(token: String, timestamp: String, appKey: String, data: String): String =
        md5("$token&$timestamp&$appKey&$data")

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
