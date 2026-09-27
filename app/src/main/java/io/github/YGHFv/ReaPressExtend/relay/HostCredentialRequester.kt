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

/** 向宿主索要淘宝登录态（ACTION_COOKIE_REQUEST）：一个 Intent 只能寻址一个包，所以对 CREDENTIAL_PACKAGES 逐个发；两边进程都不在时广播被静默丢弃，调用方按超时降级、不要指望回执。 */
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
