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

package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import io.github.YGHFv.ReaPressExtend.core.IdentitySource
import io.github.YGHFv.ReaPressExtend.core.StationFingerprint
import io.github.YGHFv.ReaPressExtend.core.geoPointOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「驿站管理」手工规则的持久化。与采集到的包裹数据分开存：清空数据不能抹掉用户的判断。
 * 只被模块 App 读写，不需要跨进程契约。所有写入口收一组键，只动一个会让合并行裂回两行。
 * 旧格式（`renames` 扁平键）必须读得出，不写迁移脚本。
 */
object ExpressStationRuleStore {

    /** `internal` 供备份清单引用同一份来源。 */
    internal const val PREFS = "reapress_station_rules"

    private const val KEY_RULES = "rules"

    /** 旧版（只有改名表）用的扁平映射键，只读；按新格式存过一次就会被删掉。 */
    private const val LEGACY_KEY_RENAMES = "renames"

    private const val FIELD_RENAMES = "renames"
    private const val FIELD_PICKUP_CODES = "pickupCodes"
    private const val FIELD_ADDRESSES = "addresses"

    /** 缺键 = 老数据，读时退空表。 */
    private const val FIELD_IDENTITY_SOURCES = "identitySources"

    private const val FIELD_FINGERPRINTS = "fingerprints"

    /** 指纹对象内部的短键，理由见 fingerprintJson。 */
    private const val FIELD_LAT = "lat"
    private const val FIELD_LNG = "lng"
    private const val FIELD_ACCURACY = "acc"
    private const val FIELD_WIFI = "wifi"
    private const val FIELD_CAPTURED_AT = "at"

    /** 读规则。读坏了当没有规则，别让一条损坏的 JSON 把整个首页拦住。 */
    fun load(context: Context): ExpressStationRules = ExpressRecordStore.withTransaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_RULES, null)?.let { raw ->
            return@withTransaction runCatching { parse(raw) }.getOrDefault(ExpressStationRules.EMPTY)
        }
        val legacy = prefs.getString(LEGACY_KEY_RENAMES, null)
            ?: return@withTransaction ExpressStationRules.EMPTY
        runCatching {
            ExpressStationRules(renames = table(JSONObject(legacy)))
        }.getOrDefault(ExpressStationRules.EMPTY)
    }

    /** 设置或清除一组驿站的显示名；null / 空白 = 删掉。 */
    fun setRename(context: Context, keys: Collection<String>, display: String?) {
        val value = display?.trim().takeIf { !it.isNullOrBlank() }
        write(context) { it.copy(renames = patch(it.renames, keys, value)) }
    }

    /** 设置或清除一组驿站的默认取件码；null / 空白 = 删掉。 */
    fun setPickupCode(context: Context, keys: Collection<String>, code: String?) {
        val value = code?.trim().takeIf { !it.isNullOrBlank() }
        write(context) { it.copy(pickupCodes = patch(it.pickupCodes, keys, value)) }
    }

    /** 设置或清除一组驿站的精确地址；null / 空白 = 删掉。 */
    fun setAddress(context: Context, keys: Collection<String>, address: String?) {
        val value = address?.trim().takeIf { !it.isNullOrBlank() }
        write(context) { it.copy(addresses = patch(it.addresses, keys, value)) }
    }

    /** 记下一组驿站的现场指纹。空指纹当「删掉」处理：什么都没采到的记录写进去只会显示成已记录。 */
    fun setFingerprint(
        context: Context,
        keys: Collection<String>,
        fingerprint: StationFingerprint?,
    ) {
        val value = fingerprint?.takeIf { !it.isEmpty }
        write(context) { it.copy(fingerprints = patch(it.fingerprints, keys, value)) }
    }

    /** 设置或清除一组驿站的默认身份码来源；null = 回到默认平台。 */
    fun setIdentitySource(
        context: Context,
        keys: Collection<String>,
        source: IdentitySource?,
    ) {
        write(context) { it.copy(identitySources = patch(it.identitySources, keys, source)) }
    }

    /** 把一组驿站的全部规则清掉（几张表一起，界面上的「恢复默认」）。 */
    fun clearRules(context: Context, keys: Collection<String>) {
        write(context) { rules ->
            rules.copy(
                renames = patch(rules.renames, keys, null),
                pickupCodes = patch(rules.pickupCodes, keys, null),
                addresses = patch(rules.addresses, keys, null),
                identitySources = patch(rules.identitySources, keys, null),
                fingerprints = patch(rules.fingerprints, keys, null),
            )
        }
    }

    /** 清掉全部规则（驿站回到「靠自动归一化分组」的状态）。两个键都要删 —— 旧格式也读得到。 */
    fun clear(context: Context): Unit = ExpressRecordStore.withTransaction {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_RULES)
            .remove(LEGACY_KEY_RENAMES)
            .commit()
    }

    private fun write(context: Context, transform: (ExpressStationRules) -> ExpressStationRules): Unit = ExpressRecordStore.withTransaction {
        save(context, transform(load(context)))
    }

    /** 在 keys 上把值统一成 value（null = 删掉）；空白键跳过。泛型让字符串表与枚举表共用。 */
    private fun <T> patch(
        table: Map<String, T>,
        keys: Collection<String>,
        value: T?,
    ): Map<String, T> {
        val next = LinkedHashMap(table)
        for (key in keys) {
            if (key.isBlank()) continue
            if (value == null) next.remove(key) else next[key] = value
        }
        return next
    }

    /** 解析新格式。新旧格式靠值是不是对象区分，比存版本号稳。 */
    private fun parse(raw: String): ExpressStationRules {
        val json = JSONObject(raw)
        val newFormat = listOf(
            FIELD_RENAMES, FIELD_PICKUP_CODES, FIELD_ADDRESSES, FIELD_IDENTITY_SOURCES,
            FIELD_FINGERPRINTS,
        ).any { json.optJSONObject(it) != null }
        if (!newFormat) return ExpressStationRules(renames = table(json))
        return ExpressStationRules(
            renames = table(json.optJSONObject(FIELD_RENAMES)),
            pickupCodes = table(json.optJSONObject(FIELD_PICKUP_CODES)),
            addresses = table(json.optJSONObject(FIELD_ADDRESSES)),
            identitySources = sourceTable(json.optJSONObject(FIELD_IDENTITY_SOURCES)),
            fingerprints = fingerprintTable(json.optJSONObject(FIELD_FINGERPRINTS)),
        )
    }

    private fun table(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) {
            val value = json.optString(key)
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    /** 身份码来源表。值存枚举名，认不出的键丢掉 —— 删平台时老数据不会非法。 */
    private fun sourceTable(json: JSONObject?): Map<String, IdentitySource> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, IdentitySource>()
        for (key in json.keys()) {
            IdentitySource.parse(json.optString(key))?.let { out[key] = it }
        }
        return out
    }

    /** 现场指纹表。坐标一律再过 geoPointOf（幂等归一化，含宿主 1e5 倍历史值），不留「写侧修了读侧没修」的缝。 */
    private fun fingerprintTable(json: JSONObject?): Map<String, StationFingerprint> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, StationFingerprint>()
        for (key in json.keys()) {
            val value = json.optJSONObject(key) ?: continue
            val fingerprint = fingerprintOf(value)
            if (!fingerprint.isEmpty) out[key] = fingerprint
        }
        return out
    }

    private fun fingerprintOf(json: JSONObject): StationFingerprint = StationFingerprint(
        position = geoPointOf(
            json.optDouble(FIELD_LAT, Double.NaN).takeIf { !it.isNaN() },
            json.optDouble(FIELD_LNG, Double.NaN).takeIf { !it.isNaN() },
        ),
        // 负精度是脏数据，当没有。
        accuracyMeters = json.optDouble(FIELD_ACCURACY, Double.NaN)
            .takeIf { !it.isNaN() && it >= 0.0 }
            ?.toFloat(),
        wifi = wifiOf(json),
        capturedAt = json.optLong(FIELD_CAPTURED_AT, 0L),
    )

    /** WiFi 列表：小写去重（BSSID 大小写不敏感）。 */
    private fun wifiOf(json: JSONObject): List<String> {
        val array = json.optJSONArray(FIELD_WIFI) ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { index ->
                array.optString(index).trim().lowercase().takeIf { it.isNotBlank() }
            }
            .distinct()
    }

    /** 指纹表 → JSON，短键防膨胀；空字段不写（缺键与 null 读回同路）。 */
    private fun fingerprintJson(table: Map<String, StationFingerprint>): JSONObject {
        val out = JSONObject()
        for ((key, value) in table) {
            val node = JSONObject()
            value.position?.let {
                node.put(FIELD_LAT, it.lat)
                node.put(FIELD_LNG, it.lng)
            }
            value.accuracyMeters?.let { node.put(FIELD_ACCURACY, it.toDouble()) }
            if (value.wifi.isNotEmpty()) node.put(FIELD_WIFI, JSONArray(value.wifi))
            node.put(FIELD_CAPTURED_AT, value.capturedAt)
            out.put(key, node)
        }
        return out
    }

    private fun save(context: Context, rules: ExpressStationRules) {
        val json = JSONObject().apply {
            put(FIELD_RENAMES, JSONObject(rules.renames))
            put(FIELD_PICKUP_CODES, JSONObject(rules.pickupCodes))
            put(FIELD_ADDRESSES, JSONObject(rules.addresses))
            put(
                FIELD_IDENTITY_SOURCES,
                JSONObject(rules.identitySources.mapValues { it.value.name }),
            )
            put(FIELD_FINGERPRINTS, fingerprintJson(rules.fingerprints))
        }
        // 保留同步落盘边界；apply 也会立即更新内存，区别在于不等待磁盘写入。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RULES, json.toString())
            .remove(LEGACY_KEY_RENAMES)
            .commit()
    }
}
