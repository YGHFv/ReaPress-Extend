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
import android.os.Handler
import android.os.Looper
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.notification.ExpressChangeNotifier
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import java.util.UUID
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/** Cache query plus bounded network sync request; repeated cold-start broadcasts share one request ID. */
object HostRefreshRequester {

    private const val LOG_TAG = "ReaPress"

    private const val PREFS = "reapress_package_sync"
    private const val NEXT = "next_attempt"
    private const val RISK = "risk_until"
    private const val INTERVAL = 5 * 60_000L
    private var activeId: String? = null
    @Volatile private var status = "菜鸟取件码：尚未联网同步"
    @Volatile private var statusAt = 0L
    private var handler = Handler(Looper.getMainLooper())

    fun describe(): String = if (statusAt > 0L && status.contains("完成")) {
        "$status · ${((System.currentTimeMillis() - statusAt).coerceAtLeast(0L) / 60_000)} 分钟前"
    } else status

    @Synchronized
    fun request(context: Context) {
        runCatching { requestChecked(context) }.onFailure {
            activeId = null
            runCatching { update(context, "菜鸟取件码：请求未启动，保留已有数据") }
        }
    }

    private fun requestChecked(context: Context) {
        val app = context.applicationContext
        if (!ExpressSettings.read(app).isEnabled || activeId != null) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val risk = maxOf(prefs.getLong(RISK, 0),
            app.getSharedPreferences(ModuleTraceFetcher.PREFS, Context.MODE_PRIVATE).getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0),
            CainiaoTraceApi.riskBlockedUntil)
        if (risk > now) { update(app, "菜鸟取件码：风控退避中，保留已有数据"); return }
        if (prefs.getLong(NEXT, 0) > now) {
            if (status == "菜鸟取件码：尚未联网同步") update(app, "菜鸟取件码：请求冷却中，保留已有数据")
            return
        }
        if (!prefs.edit().putLong(NEXT, now + INTERVAL).commit()) {
            update(app, "菜鸟取件码：无法保存请求状态，未联网")
            return
        }
        val id = UUID.randomUUID().toString()
        activeId = id
        update(app, "菜鸟取件码：正在后台联网同步…")
        for (delay in listOf(0L, 3_000L, 8_000L)) {
            check(handler.postDelayed({
                synchronized(this) {
                    runCatching {
                        if (activeId == id && ExpressSettings.read(app).isEnabled) send(app, id, risk)
                    }
                }
            }, delay))
        }
        check(handler.postDelayed({
            synchronized(this) {
                if (activeId == id) {
                    activeId = null
                    runCatching { update(app, "菜鸟取件码：等待宿主超时，未确认联网更新") }
                }
            }
        }, 75_000L))
    }

    private fun send(context: Context, id: String, risk: Long) {
        runCatching {
            val now = System.currentTimeMillis()
            val currentRisk = maxOf(risk, CainiaoTraceApi.riskBlockedUntil,
                context.getSharedPreferences(ModuleTraceFetcher.PREFS, Context.MODE_PRIVATE).getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0))
            if (currentRisk > now) {
                activeId = null
                update(context, "菜鸟取件码：风控退避中，保留已有数据")
                return
            }
            HostWakePin.wake(context, "同步取件码")
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_REFRESH_REQUEST)
                    .setPackage(ExpressRelay.HOST_PACKAGE)
                    .putExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_ID, id)
                    .putExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_RISK_UNTIL, currentRisk)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
            )
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "host refresh request failed", it) }
    }

    @Synchronized
    fun onReport(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_ID) ?: return
        if (id != activeId) return
        val outcome = intent.getStringExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_STATUS)
        if (outcome == "busy") return
        activeId = null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retryAt = intent.getLongExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_RETRY_AT, 0L)
            .coerceIn(0L, System.currentTimeMillis() + 24 * 60 * 60_000L)
        if (outcome == "risk") CainiaoTraceApi.restoreRisk(retryAt)
        val persisted = runCatching {
            val edit = prefs.edit().putLong(NEXT, maxOf(retryAt, prefs.getLong(NEXT, 0)))
            if (outcome == "risk") {
                edit.putLong(RISK, maxOf(retryAt, prefs.getLong(RISK, 0)))
                ExpressRecordStore.withTransaction {
                    val riskPrefs = context.getSharedPreferences(ModuleTraceFetcher.PREFS, Context.MODE_PRIVATE)
                    check(riskPrefs.edit().putLong(ModuleTraceFetcher.KEY_RISK_UNTIL,
                        maxOf(retryAt, riskPrefs.getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0))).commit())
                }
            }
            check(edit.commit())
        }.isSuccess
        val text = when (outcome) {
            "synced" -> "菜鸟取件码：宿主联网同步完成（非实时监听）"
            "unsupported" -> "菜鸟取件码：当前菜鸟版本未适配，仅保留缓存"
            "login_required" -> "菜鸟取件码：菜鸟登录态不可用"
            "not_ready" -> "菜鸟取件码：宿主同步库未就绪，稍后再试"
            "risk", "blocked" -> "菜鸟取件码：风控退避中，保留已有数据"
            "cooling" -> "菜鸟取件码：请求冷却中，保留已有数据"
            "abi_mismatch" -> "菜鸟取件码：宿主方法签名不匹配，未完成同步"
            "schema_changed" -> "菜鸟取件码：服务端结构变化，未完成同步"
            "session_changed" -> "菜鸟取件码：同步期间账号或游标变化，请稍后再试"
            "invalid_response" -> "菜鸟取件码：响应格式不符，保留已有数据"
            "apply_unconfirmed" -> "菜鸟取件码：宿主落库未确认，保留已有数据"
            "timeout" -> "菜鸟取件码：联网请求超时，保留已有数据"
            "request_failed" -> "菜鸟取件码：宿主请求失败，保留已有数据"
            else -> "菜鸟取件码：同步未完成，保留已有数据"
        }
        update(context, text + if (persisted) "" else "；冷却状态未能保存")
    }

    private fun update(context: Context, text: String) {
        status = text
        statusAt = System.currentTimeMillis()
        ModuleAndroidLog.legacy(LOG_TAG, text)
        ExpressChangeNotifier.notify(context)
    }
}
