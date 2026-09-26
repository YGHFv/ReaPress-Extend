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
 * 「驿站管理」里那些手工规则的持久化。
 *
 * ## 为什么和 [ExpressRecordStore] 分开存
 *
 * 那里面是**采集到的数据**（哪天想清空就整批清空、重新攒），这里是**用户自己的判断**
 * （「这两个名字是同一个地方」「这个驿站我要叫它『家门口』」「这站缺码时用这个码」）。
 * 存一起的话，将来做「清空包裹记录」时很容易顺手把规则也一起抹掉，而用户重建数据后规则本该还在。
 *
 * ## 为什么用普通 prefs
 *
 * 规则只被模块 App 自己的界面读写 —— 分组（[ExpressHomeGrouper]）是在 App 进程里算的，
 * system_server 侧的 hook 只负责采集包裹、不碰驿站名。所以不需要 `RemotePreferences`
 * 那套跨进程 Binder，也就不必把键名加进那份跨进程契约。
 *
 * ## 一行 = 一组键
 *
 * 所有写入口收的是**一组**键（[ExpressStationSummary.ruleKeys]）而不是一个。理由见那个字段：
 * 用户把两处合并到一行之后，改名 / 填码 / 恢复默认都必须同时作用在链上的每一个键上，
 * 只动一个会让那一行当场裂回两行。
 *
 * ## 旧数据必须读得出来（硬约束）
 *
 * 第一版只有「改名」一张表，直接以**扁平映射**存在 prefs 的 `renames` 键上。
 * 现在三张表一起存进 `rules` 键。读的时候两条路都试（见 [parse]），**不写迁移脚本** ——
 * 下一次任何写入都会把读出来的结果按新格式存回去并向旧键 `remove`，老数据自然升级。
 */
object ExpressStationRuleStore {

    private const val PREFS = "reapress_station_rules"

    /** 现在用的键：值是一个对象，里面装 [FIELD_RENAMES] / [FIELD_PICKUP_CODES] / [FIELD_ADDRESSES]。 */
    private const val KEY_RULES = "rules"

    /**
     * 旧版（只有改名一张表）用的键 —— 值是一张**扁平**映射（驿站名 → 名字）。
     * 只读不写；一旦按新格式存过一次就会被删掉。
     */
    private const val LEGACY_KEY_RENAMES = "renames"

    private const val FIELD_RENAMES = "renames"
    private const val FIELD_PICKUP_CODES = "pickupCodes"
    private const val FIELD_ADDRESSES = "addresses"

    /** 第四张表（2026-09-26 加）。**缺这个键 = 老数据**，[sourceTable] 收到 null 会退成空表。 */
    private const val FIELD_IDENTITY_SOURCES = "identitySources"

    /** 第五张表（2026-09-26 加）。同样是「缺键 = 老数据」，[fingerprintTable] 收到 null 退空表。 */
    private const val FIELD_FINGERPRINTS = "fingerprints"

    /** 指纹对象内部的字段名。**短键**——理由见 [fingerprintJson]。 */
    private const val FIELD_LAT = "lat"
    private const val FIELD_LNG = "lng"
    private const val FIELD_ACCURACY = "acc"
    private const val FIELD_WIFI = "wifi"
    private const val FIELD_CAPTURED_AT = "at"

    /**
     * 读规则。**读坏了就当没有规则**，不让一条损坏的 JSON 把整个首页拦住 ——
     * 最坏的结果是驿站又变回两张卡，而不是首页打不开。
     */
    fun load(context: Context): ExpressStationRules {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_RULES, null)?.let { raw ->
            return runCatching { parse(raw) }.getOrDefault(ExpressStationRules.EMPTY)
        }
        // 没有新格式 → 看看有没有第一版留下的那一份。它只可能是改名表。
        val legacy = prefs.getString(LEGACY_KEY_RENAMES, null)
            ?: return ExpressStationRules.EMPTY
        return runCatching {
            ExpressStationRules(renames = table(JSONObject(legacy)))
        }.getOrDefault(ExpressStationRules.EMPTY)
    }

    /**
     * 设置或清除一组驿站的显示名。
     *
     * @param keys 归一化驿站名（整行的 [ExpressStationSummary.ruleKeys]）
     * @param display 用户设定的名字；**null / 空白 = 删掉这些键上的改名规则**
     */
    fun setRename(context: Context, keys: Collection<String>, display: String?) {
        val value = display?.trim().takeIf { !it.isNullOrBlank() }
        write(context) { it.copy(renames = patch(it.renames, keys, value)) }
    }

    /**
     * 设置或清除一组驿站的**默认取件码**（该站的件没有码时用它）。
     * `null / 空白 = 删掉`。
     */
    fun setPickupCode(context: Context, keys: Collection<String>, code: String?) {
        val value = code?.trim().takeIf { !it.isNullOrBlank() }
        write(context) { it.copy(pickupCodes = patch(it.pickupCodes, keys, value)) }
    }

    /**
     * 设置或清除一组驿站的**精确地址**（楼栋 / 门牌）。
     * `null / 空白 = 删掉`。
     */
    fun setAddress(context: Context, keys: Collection<String>, address: String?) {
        val value = address?.trim().takeIf { !it.isNullOrBlank() }
        write(context) { it.copy(addresses = patch(it.addresses, keys, value)) }
    }

    /**
     * 记下一组驿站的**现场指纹**（在驿站门口点「获取当前位置」时得到）。
     *
     * ⚠️ **空指纹当作「删掉」处理**：一次什么都没采到的记录（没定位、也没 WiFi）
     * 写进去只会在界面上显示成「已记录」而实际什么都判不了。让写入侧收口，
     * 界面就不必自己判一遍。
     */
    fun setFingerprint(
        context: Context,
        keys: Collection<String>,
        fingerprint: StationFingerprint?,
    ) {
        val value = fingerprint?.takeIf { !it.isEmpty }
        write(context) { it.copy(fingerprints = patch(it.fingerprints, keys, value)) }
    }

    /**
     * 设置或清除一组驿站的**默认身份码来源**（[IdentitySource]：菜鸟 / 拼多多）。
     *
     * 这不是「一串码」而是「用哪个平台的码」—— 平台之间互不通用，所以必须按驿站记。
     * `null = 删掉规则`（回到默认平台）。
     */
    fun setIdentitySource(
        context: Context,
        keys: Collection<String>,
        source: IdentitySource?,
    ) {
        write(context) { it.copy(identitySources = patch(it.identitySources, keys, source)) }
    }

    /**
     * 把一组驿站的**全部**规则清掉（几张表一起）。界面上的「恢复默认」。
     *
     * 必须一起清：用户点的是「这个驿站回到原样」，只清改名而留下默认码 / 精确地址 / 身份码来源，
     * 会得到一个「名字回去了、卡片上却还挂着用户填的码」的半吊子状态。现场指纹同理 ——
     * 它是用户在这站门口「踩」出来的记录，属于这站的一部分。
     */
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
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_RULES)
            .remove(LEGACY_KEY_RENAMES)
            .commit()
    }

    private fun write(context: Context, transform: (ExpressStationRules) -> ExpressStationRules) {
        save(context, transform(load(context)))
    }

    /**
     * 在 `keys` 上把 `table` 的值统一成 `value`（null = 删掉）。
     *
     * 空白键直接跳过：归一化后的驿站名不该为空白，真出现了也不是能写进表的东西。
     *
     * 泛型是为了让字符串表和枚举表（[ExpressStationRules.identitySources]）共用 ——
     * 各写一遍的话，「空白键跳过」这类细节必然会漏掉一处。
     */
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

    /**
     * 解析新格式。
     *
     * 新旧格式靠**值是不是对象**来区分：新格式三张表都是对象，旧格式是一张值全是字符串的
     * 扁平映射。比存版本号稳 —— 第一版写下的数据里不可能有对象值，而版本号得先有地方记。
     */
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
            // 缺这个键的老「新格式」数据（三张表那版）退成空表 —— 不必写迁移。
            identitySources = sourceTable(json.optJSONObject(FIELD_IDENTITY_SOURCES)),
            // 同上：四张表那版没有这个键。
            fingerprints = fingerprintTable(json.optJSONObject(FIELD_FINGERPRINTS)),
        )
    }

    /** 把一张 JSON 表读成 map，顺手滤掉空值 —— 空值等于没规则，连键都不必留。 */
    private fun table(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) {
            val value = json.optString(key)
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    /**
     * 身份码来源表。值存的是枚举**名字**（`CAINIAO` / `PDD`），认不出来的键直接丢掉
     * （[IdentitySource.parse] 返 null）—— 将来删掉一个平台时，老数据不会变成非法状态。
     */
    private fun sourceTable(json: JSONObject?): Map<String, IdentitySource> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, IdentitySource>()
        for (key in json.keys()) {
            IdentitySource.parse(json.optString(key))?.let { out[key] = it }
        }
        return out
    }

    /**
     * 现场指纹表。值是**对象**（`{lat,lng,acc,wifi[],at}`），所以用不了 [table] 那套字符串读法。
     *
     * 坐标一律过 [geoPointOf]：它是幂等的归一化（宿主那种 1e5 倍的历史值、缺一半、(0,0)、
     * 越界都收敛到同一个结果），读的时候再走一遍才不会留下「写入侧修过、读取侧没修」的缝。
     *
     * 空指纹（既没位置也没 WiFi）不回收 —— 它等价于「没记过」，留着只会让界面显示成已记录。
     */
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
        // 负的精度不是「很准」而是脏数据，当没有 —— 它会被当成容差去用。
        accuracyMeters = json.optDouble(FIELD_ACCURACY, Double.NaN)
            .takeIf { !it.isNaN() && it >= 0.0 }
            ?.toFloat(),
        wifi = wifiOf(json),
        capturedAt = json.optLong(FIELD_CAPTURED_AT, 0L),
    )

    /** WiFi 列表：小写去重后读出来（BSSID 大小写不敏感，不归一化会让同一台 AP 变成两条）。 */
    private fun wifiOf(json: JSONObject): List<String> {
        val array = json.optJSONArray(FIELD_WIFI) ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { index ->
                array.optString(index).trim().lowercase().takeIf { it.isNotBlank() }
            }
            .distinct()
    }

    /**
     * 现场指纹表 → JSON。键名全是**短键**（`lat`/`lng`/`acc`/`wifi`/`at`）：
     * 一张表里几十个驿站各自带一列 WiFi，长键名会让这个文件膨胀得更快。
     *
     * 空字段**不写**而不是写 null —— 读回来时「缺键」和「值是 null」本来就走同一条路
     * （`optDouble` 给 NaN、`optJSONArray` 给 null），不必为它们在文件里留位置。
     */
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
        // commit 而不是 apply：调用方改完会立刻重新读一遍来重组界面，
        // apply 的异步落盘会让我们读回旧值（记录那边同理）。
        // 顺手把旧键删掉：这一次写入已经把老数据读出来并写进新格式了，留着只会让
        // 「到底哪份是准的」变得可疑。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RULES, json.toString())
            .remove(LEGACY_KEY_RENAMES)
            .commit()
    }
}
