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

/** 菜鸟的身份码。这个接口在 MTOP H5 通道上是关着的（同一份 cookie 拉轨迹成功、取身份码却会话过期），只能请宿主用自己的会话取；模块绝不自己算离线码（算错和算对看起来完全一样，无法证伪），只展示宿主写进 `identityCode` 的值。 */
data class CainiaoIdentity(
    val code: String,
    val expireAt: Long? = null,
    val offline: Boolean = false,
) {
    fun isValidAt(now: Long): Boolean = expireAt == null || now < expireAt
}

/** 三态而不是「对象或 null」：失败原因必须能区分，用户该做的动作完全不同 —— 两种失败唯一有效的动作都是去把菜鸟打开一次。 */
sealed interface CainiaoIdentityResult {

    data class Success(val identity: CainiaoIdentity) : CainiaoIdentityResult

    data class HostFailed(val ret: String) : CainiaoIdentityResult {
        /** 被风控挡下；只能等，重试只会把处罚窗口撞长。 */
        val riskBlocked: Boolean
            get() = ret.contains("FAIL_SYS_USER_VALIDATE") || ret.contains("RGV587")
    }

    /** 广播没人接 —— 菜鸟进程不在后台 / 没起来（小米会把这种投递静默丢弃）。 */
    data object HostUnavailable : CainiaoIdentityResult
}
