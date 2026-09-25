package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import io.github.YGHFv.ReaPressExtend.core.ExpressStationRules
import org.json.JSONObject

/**
 * 「驿站管理」里那些手工规则的持久化。
 *
 * ## 为什么和 [ExpressRecordStore] 分开存
 *
 * 那里面是**采集到的数据**（哪天想清空就整批清空、重新攒），这里是**用户自己的判断**
 * （「这两个名字是同一个地方」「这个驿站我要叫它『家门口』」）。存一起的话，将来做
 * 「清空包裹记录」时很容易顺手把规则也一起抹掉，而用户重建数据后规则本该还在。
 *
 * ## 为什么用普通 prefs
 *
 * 规则只被模块 App 自己的界面读写 —— 分组（[ExpressHomeGrouper]）是在 App 进程里算的，
 * system_server 侧的 hook 只负责采集包裹、不碰驿站名。所以不需要 `RemotePreferences`
 * 那套跨进程 Binder，也就不必把键名加进那份跨进程契约。
 */
object ExpressStationRuleStore {

    private const val PREFS = "reapress_station_rules"
    private const val KEY_RENAMES = "renames"

    /**
     * 读规则。**读坏了就当没有规则**，不让一条损坏的 JSON 把整个首页拦住 ——
     * 最坏的结果是驿站又变回两张卡，而不是首页打不开。
     */
    fun load(context: Context): ExpressStationRules {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RENAMES, null)
            ?: return ExpressStationRules.EMPTY
        return runCatching {
            val json = JSONObject(raw)
            val renames = LinkedHashMap<String, String>()
            for (key in json.keys()) {
                val value = json.optString(key)
                // 空值等于没规则，连键都不必留 —— 读的时候就把它滤掉。
                if (value.isNotBlank()) renames[key] = value
            }
            ExpressStationRules(renames)
        }.getOrElse { ExpressStationRules.EMPTY }
    }

    /**
     * 设置或清除一个驿站的规则。
     *
     * @param normalized 归一化后的驿站名（[ExpressStationRules.renames] 的键）
     * @param display 用户设定的名字；**null / 空白 = 恢复默认**（删掉这条规则）
     */
    fun setRename(context: Context, normalized: String, display: String?) {
        if (normalized.isBlank()) return
        val renames = load(context).renames.toMutableMap()
        if (display.isNullOrBlank()) {
            renames.remove(normalized)
        } else {
            renames[normalized] = display.trim()
        }
        save(context, renames)
    }

    /** 清掉全部规则（驿站回到「靠自动归一化分组」的状态）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_RENAMES)
            .commit()
    }

    private fun save(context: Context, renames: Map<String, String>) {
        val json = JSONObject()
        for ((key, value) in renames) json.put(key, value)
        // commit 而不是 apply：调用方改完会立刻重新读一遍来重组界面，
        // apply 的异步落盘会让我们读回旧值（记录那边同理）。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RENAMES, json.toString())
            .commit()
    }
}
