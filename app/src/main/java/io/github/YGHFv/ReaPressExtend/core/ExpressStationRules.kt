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
 * 用户对驿站的手工调整（设置页「驿站管理」改的就是这几张表）。
 *
 * ## 为什么合并 / 改名只有一张表
 *
 * 「合并」和「改外显名」看着是两件事，其实是**同一件事**：把驿站 A 的名字换成另一个名字。
 *
 * - 换成「家附近的驿站」（一个不存在于任何记录里的名字）→ 就是改名；
 * - 换成驿站 B 的名字 → A 和 B 的记录在分组时算出同一个名字，自然并成一张卡 = 合并。
 *
 * 所以只需要 `归一化名 → 想显示的名字` 一张表。拆成两张（合并表 + 改名表）会立刻带来
 * 「先改名还是先合并」的优先级问题，而那个问题没有正确答案 —— 一张表就没有这个歧义。
 *
 * ## 另外三张表：默认取件码 / 精确地址 / 默认身份码
 *
 * 这几样**不是**名字，不能塞进 [renames]（那会让驿站名变成一串取件码），所以各开一张。
 * 它们回答的是另外几个问题：
 *
 * - [pickupCodes]：**这站的件没有取件码时，用哪个码**。菜鸟对一部分包裹（`普通收件` /
 *   `末端非淘包裹` 这类）不下发 `authCode`，而驿站墙上/小程序里其实是有码的 —— 让用户自己
 *   填一次，之后这站所有缺码的件都能显示出来。这也是「手动添加取件码」那个功能的地基。
 * - [addresses]：**这站到底在哪**。宿主的 `packageStation.stationDeliveryAddress` 也常常空着，
 *   而「去哪个楼哪个门」正是用户要去的地方。
 * - [identitySources]：**这站出示哪个平台的身份码**。菜鸟驿站要菜鸟的「出库码」，拼多多驿站
 *   要拼多多的 —— 两者互不通用（[IdentitySource]）。菜鸟那条路能走通是靠**复用宿主自带的
 *   淘宝登录态**，而那份凭据宿主也可能没有（2026-09-26 真机实测：菜鸟 cookie 库里没有
 *   `.taobao.com` 域），所以「取不到」是常态、要有明确的降级表现。
 * - [fingerprints]：**这站在哪**（机器判据版）。与 [addresses] 是同一件事的两个面向 ——
 *   那个是给人念的地址，这个是给机器比的「定位 + 附近 WiFi」。由同一次「获取当前位置」
 *   一起写入，但读的地方完全不同（见 [StationFingerprint] 的类注释）。
 *
 * 几张表的键与 [renames] 同一套（驿站名），查询要**跟着改名链走** —— 理由见 [pickupCodeFor]。
 *
 * ## 自动归一化 + 手工规则，两道工序
 *
 * [ExpressStationName] 处理的是**机器能看出来**的同一驿站（品牌前缀、括号、地址详略）。
 * 这里处理的是**只有用户知道**的：两个名字看起来毫无关系，但其实是同一个取件点，或者
 * 用户就是想把某个驿站叫成别的。前者不猜，后者完全交给用户。
 *
 * ## 只影响本机显示
 *
 * 规则**不改** [ExpressRecord.station]，原始写法永远留在记录里。所以改错了、或者想换回来，
 * 删掉规则就回到原样 —— 「恢复默认」不需要备份任何东西，这也是不把新名字写回记录的原因。
 */
data class ExpressStationRules(
    /** 归一化驿站名 → 用户设定的名字。见类注释：合并与改名都长在这张表上。 */
    val renames: Map<String, String> = emptyMap(),
    /** 归一化驿站名 → 该站缺取件码时用作默认的取件码。 */
    val pickupCodes: Map<String, String> = emptyMap(),
    /** 归一化驿站名 → 该站的精确地址（楼栋 / 门牌）。 */
    val addresses: Map<String, String> = emptyMap(),
    /**
     * 归一化驿站名 → 该站出示哪个平台的**身份码**（[IdentitySource]）。
     *
     * 没设过 = null = 走默认（当前即菜鸟）—— **不必写一条 `CAINIAO` 进去**：
     * 默认值写成显式数据的话，将来改默认就改不动已经存过的那批。
     */
    val identitySources: Map<String, IdentitySource> = emptyMap(),
    /**
     * 归一化驿站名 → 该站的**现场指纹**（定位 + 附近 WiFi，[StationFingerprint]）。
     *
     * 与 [addresses] 不是一回事，别合并：[addresses] 是**给人念的**地址（「5 号楼 2 单元 101」），
     * 这里是**给机器比的**「上次站在这里时我在哪、周围有哪些 AP」。同一次「获取当前位置」
     * 把两者一起写进去，但用途完全不同 —— 地址印在包裹详情页上，指纹将来用来判
     * 「用户是不是又到这一站了」（到站提醒的地基）。
     */
    val fingerprints: Map<String, StationFingerprint> = emptyMap(),
) {

    /**
     * 把一个驿站名换成它该显示的名字。
     *
     * ## 为什么是链式而不是查一次
     *
     * 规则可以叠起来用：A 合并到 B，之后又把 B 改名成「家门口」。这时候 A 的包裹也该跟着
     * 显示「家门口」—— 它们本来就在同一张卡片上，名字却分成两种是不可接受的。
     * 所以这里跟着映射一路走到底，而不是只查一跳。
     *
     * `MAX_HOPS` 是防死循环的：用户先把 A 并到 B、再反过来把 B 并到 A，就会走成一个环。
     * 走到上限就停在当前值上 —— 环是用户的输入错误，界面不该因此卡死。
     *
     * 空值 / 空白一律当「没有规则」处理而不是当成空名字：用户清空输入框时应该回到原样，
     * 而不是让整个驿站的名字变成空字符串（那会把它扔进「未知取件地点」，看着像数据丢了）。
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

    /**
     * 这个驿站有没有被用户动过（改过名，或填过默认码 / 精确地址 / 身份码来源 / 现场指纹）。
     * UI 靠它决定要不要显示「恢复默认」。
     */
    fun hasRule(normalized: String): Boolean =
        !renames[normalized].isNullOrBlank() ||
            !pickupCodes[normalized].isNullOrBlank() ||
            !addresses[normalized].isNullOrBlank() ||
            identitySources[normalized] != null ||
            fingerprints[normalized] != null

    /**
     * 该站缺取件码时用的默认码；没设过返回 null。
     *
     * ## 为什么跟着改名链找
     *
     * 用户是**在管理页那一行**上填的码，而管理页一行可能对应多个身份键（合并过来的那些）。
     * 比如「阜阳颍滨花园店」并进了「颖滨23号楼109…」，界面上只剩一行、主键是后者 ——
     * 用户在那一行填的码存在后者的键上。而「阜阳颍滨花园店」的件拿去查表时，键是它自己。
     * 只查一跳就会漏掉一半件（另一半有码、这一半没有），表现为「设了默认码但卡片没变」。
     *
     * 所以这里和 [apply] 一样沿链走：先看自己的键，再顺着 [renames] 往目标键找。
     */
    fun pickupCodeFor(normalized: String): String? = lookup(normalized, pickupCodes)

    /** 该站的精确地址；没设过返回 null。链式规则同 [pickupCodeFor]。 */
    fun addressFor(normalized: String): String? = lookup(normalized, addresses)

    /**
     * 该站出示哪个平台的**身份码**；没设过返回 null（调用方按默认的平台处理）。
     * 链式规则同 [pickupCodeFor]。
     */
    fun identitySourceFor(normalized: String): IdentitySource? = lookup(normalized, identitySources)

    /**
     * 该站记下的**现场指纹**（定位 + 附近 WiFi）；没记过返回 null。
     *
     * 链式规则同 [pickupCodeFor]：用户是在「管理页那一行」上点获取的，而一行可能覆盖
     * 多个键（合并过来的那些），只查一跳会让链里另一半记录的站点对不上指纹。
     */
    fun fingerprintFor(normalized: String): StationFingerprint? = lookup(normalized, fingerprints)

    /**
     * 各张表沿着改名链找同一个键。见 [pickupCodeFor] 里「为什么要跟着链走」。
     *
     * 泛型是为了让枚举表（[identitySources]）和字符串表共用这一段 —— 两条链一旦各写一遍，
     * 将来改跳数上限或环的处理就必然漏一处。
     */
    private fun <T : Any> lookup(normalized: String, table: Map<String, T>): T? {
        var current = normalized
        repeat(MAX_HOPS) {
            table[current]?.let { value ->
                // 字符串表里的空白值等于「没填」（同 [apply] 的注释）；枚举表没有这个问题。
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

        /** 链式解析的最大跳数。见 [apply]：纯粹用来兜住 A↔B 互相指的死环。 */
        private const val MAX_HOPS = 4
    }
}
