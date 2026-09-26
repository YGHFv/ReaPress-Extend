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
 * 菜鸟的**账号身份码**（在驿站自助取件机上扫 / 报给店员的那串东西）。
 *
 * ## 它是什么
 *
 * 宿主里「身份码」是一个独立模块（`com.cainiao.wireless.identity_code`，未混淆），界面上
 * 那串数字 + 条码就是 `IdentityBean.identityCode`（实体字段全 public，由 JSON 映射决定名字）。
 *
 * ## 这条数据从哪来（两轮真机实证的结论，别再往回改）
 *
 * | 通道 | 结果 |
 * |---|---|
 * | MTOP H5（`acs.m.taobao.com/h5/`，与轨迹完全同一条） | ⛔ 预热就回 `FAIL_SYS_SESSION_EXPIRED`，**连 `_m_h5_tk` 都不下发** |
 * | MTOP APP 通道（`mtop.cainiao.nbpickup.identitycode.get.cn`，还有 `.native` 那个版本） | 只有菜鸟自己的会话能走 |
 *
 * 决定性证据是**同一时刻、同一份 cookie 拉轨迹是成功的**
 * （`mtop.taobao.logisticstracedetailservice.queryalltrace`）—— 所以那个「会话过期」不是登录态
 * 的问题，是这条接口在 H5 通道上就是关着的。
 *
 * 于是改成：模块请菜鸟**用它自己的会话**取一次（`ACTION_IDENTITY_REQUEST`，
 * 宿主侧实现在 `CainiaoIdentityBridge`）。这样拿到的就是**与菜鸟界面上显示的那个码逐字相同**
 * 的东西 —— 而这一点至关重要：报一个错的取件码会让用户站在柜台前出丑，
 * 比「这次没显示」严重得多。
 *
 * ## ⛔ 模块绝不自己算离线码
 *
 * 宿主的离线码是 `finalKeyStr` + TOTP（`identity_code/service/a` 里那段 HmacSHA1）算出来的。
 * 复刻它要精确还原宿主的密钥编码与摘要细节，而**算错的码和算对的码看起来完全一样** ——
 * 没有真机对照就无法证伪。所以只展示宿主自己算出并写进 `identityCode` 的值（那是它要显示的
 * 东西），「我们自己推演一遍」这一步不做。
 */
data class CainiaoIdentity(
    /** 身份码本体。非空才有展示价值。 */
    val code: String,
    /** 有效期（epoch ms）；null 表示宿主没给 —— 那时一律当作可用（有码总比没码强）。 */
    val expireAt: Long? = null,
    /** 是不是离线码（宿主 `IdentityBean.isOffLine`；在线是 `"2"`）。 */
    val offline: Boolean = false,
) {
    /** 这条身份码还算不算新鲜。拿不到有效期时**一律当作可用**。 */
    fun isValidAt(now: Long): Boolean = expireAt == null || now < expireAt
}

/**
 * 取身份码的结果。三态而不是「对象或 null」——**失败原因必须能区分**，
 * 因为用户该做的动作完全不同：
 *
 * - [HostUnavailable] → 去**打开一次菜鸟**（广播没人接，重试没有意义）；
 * - [HostFailed] → 看原因：风控要**等**，其它情况可以在菜鸟里开一次「身份码」页再试；
 * - [Success] → 显示。
 *
 * 把它们压成一个 null，界面上就只能说一句「获取失败」——用户唯一能做的是一直点重试，
 * 而其中两种情况重试是**完全无效**的。
 */
sealed interface CainiaoIdentityResult {

    data class Success(val identity: CainiaoIdentity) : CainiaoIdentityResult

    /**
     * 宿主回了话，但它自己也取不到。
     *
     * @param ret 原话（`CainiaoIdentityBridge` 或宿主给出的原因）。界面按内容给文案，
     *   同时照原样留档 —— 排查时「宿主到底说了什么」只有这里能看到。
     */
    data class HostFailed(val ret: String) : CainiaoIdentityResult {
        /**
         * 被风控挡下（`FAIL_SYS_USER_VALIDATE` / `RGV587`）。
         *
         * **只能等**，重试只会把处罚窗口撞长 —— 界面上这两句话不一样，动作也不一样，
         * 所以这个判断要留在数据层而不是让界面去 `contains` 字符串。
         */
        val riskBlocked: Boolean
            get() = ret.contains("FAIL_SYS_USER_VALIDATE") || ret.contains("RGV587")
    }

    /**
     * 广播没人接 —— 菜鸟进程不在后台 / 没起来。
     *
     * 与 [HostFailed] 分开是因为这里**连「宿主说它取不到」都没听到**：小米上系统会把
     * 收不到广播的投递**静默丢弃**（不报错、不打日志），所以「没回」本身既可能是宿主不在，
     * 也可能是宿主在但没来得及回 —— 两种情况唯一有效的动作都是**去把菜鸟打开一次**。
     */
    data object HostUnavailable : CainiaoIdentityResult
}
