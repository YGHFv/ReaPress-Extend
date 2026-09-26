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
 * 一个取件地点的**现场指纹** —— 用户站在驿站门口点「获取」时记下来的东西。
 *
 * ## 为什么不能只有经纬度
 *
 * 定位在驿站这个场景里是**最弱的一路证据**，三条原因叠在一起：
 *
 * - 模块只申请到粗略定位（省电、少要一份授权），基站定位在楼群里能差一两公里 ——
 *   两个驿站隔一条街时它分不开；
 * - Android 的「最后已知位置」可能几分钟甚至几十分钟前的，用户已经走开了它还在原地；
 * - 室内（驿站全在室内）GPS 基本没有信号，最后落到的就是蜂窝 / WiFi 混合定位。
 *
 * 而 WiFi 恰好是**室内最强的信号**：驿站的 AP 就在几米内，BSSID 是设备唯一的、
 * 不受定位精度影响，且「同一个 AP 的覆盖范围」本身就等价于「到了这个门口」。
 * 所以两者一起记 —— 定位给粗筛，WiFi 给确认。
 *
 * ## 为什么现在只记不用
 *
 * 这一版只做**采集**（2026-09-26 用户明确要求「当前仅做位置获取和附近wifi功能」）。
 * 判定逻辑不在这里，因为它还缺两样东西才能做对：
 *
 * 1. **负样本**：只有「到过」而没有「不在场」的记录时，任何阈值都调不出来；
 * 2. **触发时机**：后台持续定位是另一个功能（前台服务 + 电量代价），得单独设计。
 *
 * 所以字段先按「将来够用」的样子存下来：将来要判「又到驿站了」，需要的就是
 * 位置 + 精度 + AP 列表这三样。存少了到时候还得让用户重新去每个驿站踩一遍。
 *
 * ## 纯数据
 *
 * 不碰 Android、不碰时钟（[capturedAt] 由调用方给）。它可以被单测直接构造，
 * 也能进 `ExpressStationRules` 的链式查询。
 */
data class StationFingerprint(
    /** 采集时的定位结果；没有定位权限 / 拿不到位置时 null（WiFi 可能仍然有价值）。 */
    val position: GeoPoint? = null,
    /** 定位的**水平精度半径**（米，Android `Location.getAccuracy()`）。用来给距离设一个合理的容差。 */
    val accuracyMeters: Float? = null,
    /**
     * 采集时能看到的 WiFi 的 BSSID（`aa:bb:cc:dd:ee:ff` 小写），已去重。
     *
     * 混了两路来源，且**顺序即优先级**：当前连接的那个排最前（它最确定是「此处」），
     * 后面是扫描到的周围 AP。两者都可能为空 —— 没开 WiFi、没给权限、系统限流，
     * 任一都会让扫描结果为空，而那不是错误。
     */
    val wifi: List<String> = emptyList(),
    /** 采集时刻（epoch 毫秒）。指纹会过期（换 AP、换手机），将来判定要按新旧加权。 */
    val capturedAt: Long = 0L,
) {
    /** 什么都没记到（既没位置也没 WiFi）—— 写入侧据此拒绝落库，不留一条空指纹。 */
    val isEmpty: Boolean get() = position == null && wifi.isEmpty()
}
