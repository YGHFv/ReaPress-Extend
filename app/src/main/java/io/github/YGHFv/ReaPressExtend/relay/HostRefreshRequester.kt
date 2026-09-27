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

/** 请宿主立刻重查本地包裹表（ACTION_REFRESH_REQUEST）：接收器是动态注册的，只在宿主进程存活时存在，且无回执 —— 调用方一律按「没有后续」处理。与 HostWakePin.wake 必须两条都发（前者能拉起死进程，这条能叫醒活进程）。 */
object HostRefreshRequester {

    private const val LOG_TAG = "ReaPress"

    fun request(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_REFRESH_REQUEST)
                    .setPackage(ExpressRelay.HOST_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
            )
            ModuleAndroidLog.legacy(LOG_TAG, "host refresh request: 已请宿主重查本地包裹表")
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "host refresh request failed", it) }
    }
}
