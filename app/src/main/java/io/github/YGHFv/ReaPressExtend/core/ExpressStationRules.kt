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

/**
 * 用户对驿站的手工调整（设置页「驿站管理」）。合并与改名共用 [renames] 一张表——
 * 把 A 的名字换成 B 的名字，两者分组时自然并成一张卡；[pickupCodes] / [addresses] /
 * [identitySources] / [fingerprints] 各管默认取件码 / 精确地址 / 身份码平台 / 现场指纹，
 * 键同为归一化驿站名，查询要跟着改名链走（理由见 [pickupCodeFor]）。
 * 身份码菜鸟与拼多多互不通用（[IdentitySource]）。规则只影响本机显示，不改 [ExpressRecord.station]，
 * 删掉规则即恢复原样。
 */
data class ExpressStationRules(
    /** 归一化驿站名 → 用户设定的名字（合并与改名都在这张表上）。 */
    val renames: Map<String, String> = emptyMap(),
    /** 归一化驿站名 → 该站缺取件码时用的默认码（菜鸟对部分包裹不下发 authCode）。 */
    val pickupCodes: Map<String, String> = emptyMap(),
    val addresses: Map<String, String> = emptyMap(),
    /**
     * 归一化驿站名 → 该站出示哪个平台的身份码（[IdentitySource]）。没设过 = 走默认（当前即菜鸟），
     * 不必写一条 CAINIAO 进去——默认值写成显式数据，将来改默认就改不动已存的那批。
     */
    val identitySources: Map<String, IdentitySource> = emptyMap(),
    /**
     * 归一化驿站名 → 该站的现场指纹（定位 + 附近 WiFi，[StationFingerprint]）。与 [addresses]
     * 别合并：那个是给人念的地址，这个是给机器比对「是不是又到这一站了」的。
     */
    val fingerprints: Map<String, StationFingerprint> = emptyMap(),
) {

    /**
     * 沿改名链一路走到底（A 并到 B、B 又改了名，A 也该跟着显示）；[MAX_HOPS] 兜住
     * 用户把 A、B 互相指过去形成的死环。空白一律当「没有规则」——清空输入框应回到原样，
     * 而不是让驿站名变成空字符串被扔进「未知取件地点」。
     */
    fun apply(normalized: String): String {
        var current = normalized
        repeat(MAX_HOPS) {
            val next = renames[current]?.takeIf { it.isNotBlank() } ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    /** 该站有没有被用户动过，UI 靠它决定要不要显示「恢复默认」。 */
    fun hasRule(normalized: String): Boolean =
        !renames[normalized].isNullOrBlank() ||
            !pickupCodes[normalized].isNullOrBlank() ||
            !addresses[normalized].isNullOrBlank() ||
            identitySources[normalized] != null ||
            fingerprints[normalized] != null

    /**
     * 该站缺取件码时用的默认码；没设过返回 null。沿改名链找：用户是在「管理页那一行」上填的，
     * 而一行可能覆盖多个键（合并过来的那些），只查一跳会漏掉一半件（「设了默认码但卡片没变」）。
     */
    fun pickupCodeFor(normalized: String): String? = lookup(normalized, pickupCodes)

    /** 该站的精确地址；没设过返回 null。链式规则同 [pickupCodeFor]。 */
    fun addressFor(normalized: String): String? = lookup(normalized, addresses)

    /** 该站出示哪个平台的身份码；没设过返回 null（调用方按默认平台处理）。链式规则同 [pickupCodeFor]。 */
    fun identitySourceFor(normalized: String): IdentitySource? = lookup(normalized, identitySources)

    /** 该站记下的现场指纹；没记过返回 null。链式规则同 [pickupCodeFor]。 */
    fun fingerprintFor(normalized: String): StationFingerprint? = lookup(normalized, fingerprints)

    /** 各表沿改名链找同一个键（理由见 [pickupCodeFor]）；泛型让枚举表与字符串表共用。 */
    private fun <T : Any> lookup(normalized: String, table: Map<String, T>): T? {
        var current = normalized
        repeat(MAX_HOPS) {
            table[current]?.let { value ->
                if (value !is String || value.isNotBlank()) return value
            }
            val next = renames[current]?.takeIf { it.isNotBlank() } ?: return null
            if (next == current) return null
            current = next
        }
        return null
    }

    val isEmpty: Boolean
        get() = renames.isEmpty() && pickupCodes.isEmpty() && addresses.isEmpty() &&
            identitySources.isEmpty() && fingerprints.isEmpty()

    companion object {
        val EMPTY = ExpressStationRules()

        private const val MAX_HOPS = 4
    }
}
