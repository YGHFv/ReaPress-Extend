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
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 向宿主索要淘宝登录态（`ACTION_COOKIE_REQUEST`）。
 *
 * ## 为什么单独一个对象
 *
 * 它本来是 `IdentityCodeFetcher` 里的一个方法，而身份码链路**已经不再需要登录态**
 * （2026-09-26 起改为请菜鸟用自己的会话取，见 [ExpressRelay.ACTION_IDENTITY_REQUEST]）——
 * 留在那里会让那个类的注释和它的实际职责对不上，而读注释的人正是下一个要改它的人。
 * 现在还用它的是**轨迹**那条路（[ModuleTraceFetcher.onDemand]）：模块手里没有 cookie 时
 * 先回退给菜鸟代拉，同时要一份，下一次点开就能自己拉了。
 *
 * ## 为什么要向「每一个可能的宿主」各发一次
 *
 * 凭据在哪一边事先并不知道 —— 菜鸟绑过淘宝账号时它自己 WebView 里就有，没绑过则只有
 * 淘宝 App 里有（见 [ExpressRelay.CREDENTIAL_PACKAGES]）。而**一个 Intent 只能寻址一个包**，
 * 所以逐个发；哪边有就哪边回，后到覆盖先到、重复投递无害。
 *
 * 两边进程都不在后台时这条广播没人收，且系统会**静默丢弃**（小米上的实测行为）——
 * 调用方一律按超时降级，不要指望有回执。
 */
object HostCredentialRequester {

    private const val LOG_TAG = "ReaPress"

    fun requestFromHosts(context: Context) {
        for (target in ExpressRelay.CREDENTIAL_PACKAGES) {
            runCatching {
                context.sendBroadcast(Intent(ExpressRelay.ACTION_COOKIE_REQUEST).setPackage(target))
            }.onFailure { ModuleAndroidLog.error(LOG_TAG, "cookie request to $target failed", it) }
        }
    }
}
