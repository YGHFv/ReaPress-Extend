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

package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * 拼多多快递数据采集：挂宿主网络回调 JSON 出口 `CommonCallback` 的 parse 系方法做富化探针（「取快递」页
 * 整个是 H5，真机 2026-09-27 实锤，实体只在「响应字符串 → 实体」那一刻存在）；历史数据靠发现腿扫
 * HTTP 响应缓存 `cache/pdd_cache`（见 [PddCacheScanner]）。
 * 外部契约（反编译 8.26.0）：实体包 `com.xunmeng.pinduoduo.express.entry.*` 整体未混淆，字段名即 Gson JSON 契约
 * （`NewShipping.trackingNumber/shippingName/shippingStatus/traces/cabinetInfo`、`NewTrace.info/time/status` 等）；
 * 业务侧混淆短名（`db1/a$a`、`bb1/w` 等消费方）绝不能挂（发版必变），唯一稳定锚点是 basekit 层的 `CommonCallback`；
 * `anti_content` 在 native 层算不出，只能复用宿主已解析好的对象。
 * 真机结论（2026-09-27）：`traces` 新的在前；`NewTrace.status` 是字符串枚举，状态映射以它为准；取件信息在
 * `CabinetInfo` 独立接口（有码/出示手机号/扫码三种方式）；详情页单件装在 `shipping`（list 为 null）；
 * `parseResponseString` 是全 App 最热路径之一，判定只看 `Class#getName()` 前缀，非 `express.entry.*` 立即 return。
 */
internal object PddPackageHook {

    private const val COMMON_CALLBACK = "com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback"

    /** 三个解析出口都要挂，漏一条就漏一类接口（真机实测列表页零探针）；wrapper 与 parse 会因虚调用报两遍，靠内容指纹去重。 */
    private val PARSE_METHODS = arrayOf(
        "parseResponseStringWrapper",
        "parseResponseString",
        "parseResponseStringToEmbeddedList",
    )

    private const val ENTRY_PREFIX = "com.xunmeng.pinduoduo.express.entry."

    private const val MAX_ITEMS_PER_REPORT = 3

    private const val MAX_PART = 90

    private const val MAX_SHAPE_FIELDS = 16

    private const val MAX_STATUSES = 12

    private const val MAX_SEEN = 256

    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())

    private val otherEntities = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 已摊过值的富文本元素类：字段是 R8 短名且每件不同，每个类只摊一次。 */
    private val shapeSeen = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile private var installed = false

    @Volatile private var hooked = false

    @Volatile private var reports = 0

    @Volatile private var mainProcess = true

    @Volatile private var contextFailureLogged = false

    fun install(classLoader: ClassLoader, isMainProcess: Boolean = true): Boolean {
        if (installed) {
            XposedBridge.logAlways("pdd package hook already installed, skipping")
            return true
        }
        installed = true
        mainProcess = isMainProcess

        // onPackageReady 早于 Application#onCreate，此刻 acquire() 必为空。
        HostContextHolder.installCapture(classLoader)

        val callback = runCatching { XposedHelpers.findClass(COMMON_CALLBACK, classLoader) }
            .getOrElse { error ->
                XposedBridge.logError("pdd package hook: $COMMON_CALLBACK not found, aborted", error)
                return false
            }

        val count = PARSE_METHODS.sumOf { name ->
            runCatching {
                XposedBridge.hookAllMethods(
                    callback,
                    name,
                    object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            // 跑在宿主网络回调线程上，抛异常会带崩拼多多，必须全包住。
                            runCatching { consume(param.result) }
                                .onFailure {
                                    XposedBridge.logError("pdd package hook body failed (fail-open)", it)
                                }
                        }
                    },
                ).size
            }.getOrElse { error ->
                XposedBridge.logError("pdd package hook: hook $name threw", error)
                0
            }
        }

        hooked = count > 0

        PddCacheScanner.scanOnInstallAsync()

        XposedBridge.logAlways(
            "pdd package hook installed: $COMMON_CALLBACK hooked=$count " +
                "methods=${PARSE_METHODS.joinToString("/")} " +
                "main=$isMainProcess process=${currentProcessName()} " +
                "(this layer is not on the class-loading path, safe to keep)",
        )
        return hooked
    }

    /** 一次响应解析落地。热路径：先看类名前缀，不是快递实体立刻返回。 */
    private fun consume(result: Any?) {
        result ?: return
        if (result is List<*>) {
            consumeList(result)
            return
        }
        val className = result.javaClass.name
        if (!className.startsWith(ENTRY_PREFIX)) return

        PddCacheScanner.noteExpressActivity()

        val text = runCatching { describe(result, className) }.getOrNull() ?: return
        dedupeAndReport(text)
    }

    private fun consumeList(list: List<*>) {
        val entries = list.filterNotNull().filter { it.javaClass.name.startsWith(ENTRY_PREFIX) }
        if (entries.isEmpty()) return

        PddCacheScanner.noteExpressActivity()

        val text = runCatching {
            "embeddedList n=${entries.size} " +
                entries.take(MAX_ITEMS_PER_REPORT).joinToString(" ; ") { describeShipping(it) }
        }.getOrNull() ?: return
        dedupeAndReport(text)
    }

    private fun dedupeAndReport(text: String) {
        if (!seen.add(text)) return
        if (seen.size > MAX_SEEN) {
            seen.clear()
            seen.add(text)
        }
        reports++
        report(text)
    }

    /** 把实体摊成一行探针文本。只读固定字段名，不做全字段反射（慢，且会把无关隐私带进日志）。 */
    private fun describe(result: Any, className: String): String {
        val simple = className.substringAfterLast('.')
        val scene = call(result, "getRequestScene")?.toString().orEmpty().scene()

        if (simple == CABINET_INFO) return "$simple$scene ${describeCabinet(result)}"

        val list = field(result, FIELD_LIST) as? List<*>
        if (list != null) {
            val head = list.filterNotNull().take(MAX_ITEMS_PER_REPORT)
            val items = head.joinToString(" ; ") { describeShipping(it) }
            return "$simple$scene list=${list.size} hasMore=${field(result, "hasMore")} $items"
        }

        field(result, "shipping")?.let { return "$simple$scene ${describeShipping(it)}" }

        if (field(result, FIELD_TRACKING) != null || field(result, FIELD_TRACES) != null) {
            return "$simple$scene ${describeShipping(result)}"
        }

        if (otherEntities.add(simple)) {
            return "$simple$scene (首次见到) ${describeShape(result)}"
        }
        return "$simple"
    }

    /** `CabinetInfo` 的取值 —— 取件码就是这里。字段多为 public 稳定名，少数 private + getter。 */
    private fun describeCabinet(entity: Any): String {
        val result = field(entity, "result") ?: return "result=null"
        val parts = mutableListOf<String>()
        str(result, "code")?.let { parts += "code=$it" }
        str(result, "pickupCodeTip")?.let { parts += "tip=${it.trunc()}" }
        intOf(result, "status")?.let { parts += "st=$it" }
        str(result, "address")?.let { parts += "addr=${it.trunc()}" }
        boolOf(result, "showQrCodePickUp")?.let { parts += "showQr=$it" }
        boolOf(result, "displayShareEntry")?.let { parts += "share=$it" }
        str(result, "shareEntryDesc")?.let { parts += "shareDesc=${it.trunc()}" }
        call(result, "isNeedDisplayMobile")?.let { parts += "needMobile=$it" }
        callStr(result, "getCompanyName")?.let { parts += "cp=${it.trunc()}" }
        callStr(result, "getDelivery")?.let { parts += "delivery=${it.trunc()}" }
        field(result, "scanCodeInfo")?.let { scan ->
            str(scan, "scanCodeDisplay")?.let { parts += "scan=${it.trunc()}" }
            boolOf(scan, "showScan")?.let { parts += "showScan=$it" }
        }
        return parts.joinToString(" ").ifEmpty { "result=(无已核字段)" }
    }

    /** 未知实体的形状：`字段名:类型简名` 外加一层内嵌，只记形状不记值（语义未核）。 */
    private fun describeShape(entity: Any): String {
        val fields = runCatching { entity.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) } }
            .getOrDefault(emptyList())
        val own = shapeOf(fields)
        val nested = fields.asSequence()
            .mapNotNull { runCatching { it.get(entity) }.getOrNull() }
            .firstOrNull { it.javaClass.name.startsWith(ENTRY_PREFIX) }
            ?.let { value ->
                val inner = runCatching {
                    shapeOf(value.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) })
                }.getOrNull()
                inner?.let { "${value.javaClass.simpleName}{$it}" }
            }
        return "fields=[$own]" + (nested?.let { " → $it" } ?: "")
    }

    private fun shapeOf(fields: List<java.lang.reflect.Field>): String =
        fields.take(MAX_SHAPE_FIELDS).joinToString(",") { "${it.name}:${it.type.simpleName.take(24)}" }

    private fun describeShipping(item: Any): String {
        val parts = mutableListOf<String>()
        str(item, "trackingNumber")?.let { parts += "tn=$it" }
        str(item, "shippingId")?.let { parts += "sid=$it" }
        str(item, "shippingName")?.let { parts += "cp=$it" }
        intOf(item, "shippingStatus")?.let { parts += "st=$it" }
        str(item, "title")?.let { parts += "ttl=${it.trunc()}" }
        str(item, "shippingTitle")?.let { parts += "stl=${it.trunc()}" }
        str(item, "address")?.let { parts += "addr=${it.trunc()}" }
        str(item, "addressTip")?.let { parts += "addrTip=${it.trunc()}" }
        str(item, "orderSn")?.let { parts += "sn=$it" }
        callStr(item, "getPackageInfo")?.let { parts += "pkg=${it.trunc()}" }

        field(item, "cabinetInfo")?.let { cabinet ->
            str(cabinet, "code")?.let { parts += "cabCode=$it" }
            str(cabinet, "address")?.let { parts += "cabAddr=${it.trunc()}" }
        }
        // signCodeInfo 只有公开 getter，内是 a/b/c 短名：仅本版探针读，定位后必须换稳定判据。
        call(item, "getSignCodeInfo")?.let { sign ->
            val abc = listOf("a", "b", "c").mapNotNull { k -> str(sign, k)?.let { "$k=$it" } }
            if (abc.isNotEmpty()) parts += "signCode(${abc.joinToString("/")})"
        }
        richTextProbe(item, "titleRichText")?.let { parts += "ttlRT={$it}" }
        richTextProbe(item, "headStatusRichText")?.let { parts += "headRT={$it}" }
        richTextProbe(item, "titleJumpText")?.let { parts += "ttlJump={$it}" }

        (field(item, FIELD_TRACES) as? List<*>)?.let { traces ->
            parts += "traces=${traces.size}"
            traces.filterNotNull().mapNotNull { str(it, "status") }.distinct().take(MAX_STATUSES).let { codes ->
                if (codes.isNotEmpty()) parts += "codes=[${codes.joinToString("/")}]"
            }
            traces.firstOrNull()?.let { parts += "newest=${traceLine(it)}" }
            if (traces.size > 1) traces.lastOrNull()?.let { parts += "oldest=${traceLine(it)}" }
        }
        str(item, "thumbUrl")?.let { parts += "img=1" }
        if (parts.isEmpty()) parts += "(无已核字段)"
        return parts.joinToString(" ")
    }

    private fun traceLine(trace: Any): String {
        val time = str(trace, "time") ?: str(trace, "display_time") ?: "?"
        val info = str(trace, "info").orEmpty().trunc()
        val status = str(trace, "status")?.let { "/$it" }.orEmpty()
        return "$time|$info$status"
    }

    /** 富文本取值探针：每个「字段名+元素类名」只摊一次；元素无 toString、字段是短名，只能从内容反推语义。 */
    private fun richTextProbe(item: Any, name: String): String? {
        val list = field(item, name) as? List<*> ?: return null
        val first = list.firstOrNull() ?: return null
        if (!shapeSeen.add("$name:${first.javaClass.name}")) return null
        val values = runCatching {
            first.javaClass.fields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .take(MAX_SHAPE_FIELDS)
                .mapNotNull { f ->
                    runCatching { f.get(first) }.getOrNull()?.let { v -> "${f.name}=${v.toString().trunc()}" }
                }
        }.getOrDefault(emptyList())
        return "n=${list.size} ${values.joinToString(" ")}"
    }

    /** 把探针结论送回模块进程落 `files/module-log.txt`。MIUI / HyperOS 的 logcat 整条不可读，不落字等于没发生。 */
    private fun report(text: String) {
        XposedBridge.logAlways("pdd express: $text")
        val context = runCatching { HostContextHolder.acquire() }.getOrNull()
        if (context == null) {
            if (!contextFailureLogged) {
                contextFailureLogged = true
                XposedBridge.logError("pdd package hook: no host context yet, reports will be dropped")
            }
            return
        }
        ExpressRelaySender.sendHostProbe(context, text)
    }

    /** 读宿主对象 public 字段（Gson 形状：public 字段无 getter）。认不出返回 null，不能猜。 */
    private fun field(instance: Any, name: String): Any? =
        runCatching { instance.javaClass.getField(name).get(instance) }.getOrNull()

    /** 调公开无参 getter（packageInfo/signCodeInfo 等是 private 字段 + 公开 getter，字段反射拿不到）。 */
    private fun call(instance: Any, name: String): Any? =
        runCatching { instance.javaClass.getMethod(name).invoke(instance) }.getOrNull()

    private fun callStr(instance: Any, name: String): String? =
        (call(instance, name) as? String)?.takeIf { it.isNotBlank() }

    private fun str(instance: Any, name: String): String? =
        (field(instance, name) as? String)?.takeIf { it.isNotBlank() }

    private fun intOf(instance: Any, name: String): Int? =
        (field(instance, name) as? Number)?.toInt()

    private fun boolOf(instance: Any, name: String): Boolean? =
        field(instance, name) as? Boolean

    private fun String.trunc(): String = if (length <= MAX_PART) this else take(MAX_PART) + "…"

    private fun String.scene(): String = if (isBlank()) "" else " scene=$this"

    private fun currentProcessName(): String =
        runCatching { java.io.File("/proc/self/cmdline").readText() }
            .getOrNull()
            .orEmpty()
            .substringBefore('\u0000')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "?"

    private const val FIELD_LIST = "list"
    private const val FIELD_TRACKING = "trackingNumber"
    private const val FIELD_TRACES = "traces"

    private const val CABINET_INFO = "CabinetInfo"

    fun describe(): String =
        "installed=$installed hooked=$hooked reports=$reports others=${otherEntities.size}"
}
