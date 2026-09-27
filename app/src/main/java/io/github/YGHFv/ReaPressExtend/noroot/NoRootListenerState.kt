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

package io.github.YGHFv.ReaPressExtend.noroot

/** 免 root 采集服务在本进程里有没有真的被系统叫过：onListenerConnected / 收到通知置真、解绑置假，诊断的是「链路动不动」而不是「权限有没有」。必须进程内不落盘 —— 落盘会把「上次回过调」误读成「现在也在线」。 */
internal object NoRootListenerState {

    @Volatile private var seenCallback = false

    fun markCallback() {
        seenCallback = true
    }

    fun markDisconnected() {
        seenCallback = false
    }

    fun hasCallback(): Boolean = seenCallback
}
