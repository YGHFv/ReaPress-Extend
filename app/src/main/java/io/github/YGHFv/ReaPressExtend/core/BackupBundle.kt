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

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份文件编解码：把 `SharedPreferences.getAll()` 整份搬成自包含 JSON —— 照当下字段清单手写序列化，
 * 字段增删后会悄悄少东西，通用键值不用改这里。代价是 JSON 分不出 Long/Int 与 Set，所以每个值带
 * 类型标记 `{"t":"l","v":…}`，恢复时精确写回（类型对不上会抛 ClassCastException）。
 * 不碰 Context、不读时钟：时间戳与版本由调用方传。
 */
internal object BackupBundle {

    /** 文件格式版本。改结构时 +1，并在 [decode] 里补迁移；比当前高的一律拒绝（旧版读不懂新版，静默丢一半比重来一次更糟）。 */
    const val FORMAT_VERSION = 1

    const val FILE_PREFIX = "reapress-backup-"
    const val FILE_SUFFIX = ".json"
    const val MIME_TYPE = "application/json"

    private const val KEY_FORMAT = "format"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_PREFS = "prefs"

    private const val KEY_TYPE = "t"
    private const val KEY_VALUE = "v"

    private const val TYPE_STRING = "s"
    private const val TYPE_LONG = "l"
    private const val TYPE_INT = "i"
    private const val TYPE_BOOL = "b"
    private const val TYPE_FLOAT = "f"
    private const val TYPE_STRING_SET = "ss"

    /** 备份文件读不懂时抛出：恢复是破坏性动作，必须把原因说出来而不是返回 null。 */
    class BackupFormatException(message: String) : IllegalArgumentException(message)

    class Payload(
        val version: Int,
        val exportedAt: Long,
        val appVersion: String,
        val prefs: Map<String, Map<String, Any?>>,
    ) {
        val entryCount: Int get() = prefs.values.sumOf { it.size }
    }

    fun encode(payload: Payload): String {
        val root = JSONObject()
        root.put(KEY_FORMAT, payload.version)
        root.put(KEY_EXPORTED_AT, payload.exportedAt)
        root.put(KEY_APP_VERSION, payload.appVersion)
        val prefs = JSONObject()
        payload.prefs.forEach { (name, values) ->
            val holder = JSONObject()
            values.forEach { (key, value) -> holder.put(key, encodeValue(key, value)) }
            prefs.put(name, holder)
        }
        root.put(KEY_PREFS, prefs)
        return root.toString()
    }

    /** 解析备份文本；不是 JSON、缺字段、版本过高、类型不认识时抛 [BackupFormatException]。 */
    fun decode(raw: String?): Payload {
        if (raw.isNullOrBlank()) throw BackupFormatException("文件是空的")
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw BackupFormatException("不是有效的备份文件（JSON 解析失败）")
        }

        val version = root.optInt(KEY_FORMAT, -1)
        if (version <= 0) throw BackupFormatException("缺少格式版本号，可能不是本模块的备份")
        if (version > FORMAT_VERSION) {
            throw BackupFormatException(
                "备份来自更新的版本（格式 $version），当前只能认到 $FORMAT_VERSION —— " +
                    "请先把模块升级到同版本再恢复",
            )
        }

        val prefsNode = root.optJSONObject(KEY_PREFS)
            ?: throw BackupFormatException("备份里没有数据段")

        val prefs = LinkedHashMap<String, Map<String, Any?>>()
        val names = prefsNode.keys()
        while (names.hasNext()) {
            val name = names.next()
            val holder = prefsNode.optJSONObject(name)
                ?: throw BackupFormatException("「$name」不是一份有效的键值表")
            val values = LinkedHashMap<String, Any?>()
            val keys = holder.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                values[key] = decodeValue(name, key, holder.opt(key))
            }
            prefs[name] = values
        }

        return Payload(
            version = version,
            exportedAt = root.optLong(KEY_EXPORTED_AT, 0L),
            appVersion = root.optString(KEY_APP_VERSION, ""),
            prefs = prefs,
        )
    }

    private fun encodeValue(key: String, value: Any?): JSONObject {
        val node = JSONObject()
        when (value) {
            is String -> {
                node.put(KEY_TYPE, TYPE_STRING)
                node.put(KEY_VALUE, value)
            }
            is Long -> {
                node.put(KEY_TYPE, TYPE_LONG)
                node.put(KEY_VALUE, value)
            }
            is Int -> {
                node.put(KEY_TYPE, TYPE_INT)
                node.put(KEY_VALUE, value)
            }
            is Boolean -> {
                node.put(KEY_TYPE, TYPE_BOOL)
                node.put(KEY_VALUE, value)
            }
            is Float -> {
                node.put(KEY_TYPE, TYPE_FLOAT)
                node.put(KEY_VALUE, value.toDouble())
            }
            is Set<*> -> {
                node.put(KEY_TYPE, TYPE_STRING_SET)
                val array = JSONArray()
                value.filterIsInstance<String>().forEach { array.put(it) }
                node.put(KEY_VALUE, array)
            }
            else -> throw BackupFormatException(
                "键「$key」的类型（${value?.let { it::class.java.name } ?: "null"}）无法备份",
            )
        }
        return node
    }

    private fun decodeValue(prefsName: String, key: String, node: Any?): Any? {
        val holder = node as? JSONObject
            ?: throw BackupFormatException("「$prefsName」里的「$key」不是带类型标记的值")
        return when (val type = holder.optString(KEY_TYPE, "")) {
            TYPE_STRING -> holder.optString(KEY_VALUE, "")
            TYPE_LONG -> holder.optLong(KEY_VALUE, 0L)
            TYPE_INT -> holder.optInt(KEY_VALUE, 0)
            TYPE_BOOL -> holder.optBoolean(KEY_VALUE, false)
            TYPE_FLOAT -> holder.optDouble(KEY_VALUE, 0.0).toFloat()
            TYPE_STRING_SET -> {
                val array = holder.optJSONArray(KEY_VALUE)
                    ?: throw BackupFormatException("「$prefsName」里的「$key」应当是字符串集合")
                val set = LinkedHashSet<String>(array.length())
                for (i in 0 until array.length()) set.add(array.optString(i, ""))
                set
            }
            else -> throw BackupFormatException(
                "「$prefsName」里的「$key」类型标记「$type」不认识（备份损坏，或来自更新的版本）",
            )
        }
    }
}
