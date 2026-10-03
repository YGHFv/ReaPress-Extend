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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.YGHFv.ReaPressExtend.core.Courier
import io.github.YGHFv.ReaPressExtend.core.ExpressOrigin
import io.github.YGHFv.ReaPressExtend.core.ExpressParser
import io.github.YGHFv.ReaPressExtend.core.ExpressPlatform
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.ExpressStatus
import io.github.YGHFv.ReaPressExtend.core.geoPointOf
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.xposed.XC_MethodHook
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.YGHFv.ReaPressExtend.xposed.XposedHelpers
import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 菜鸟本地包裹数据采集，挂 hybrid 层查询门面 [DORADO_API] 的 `query` / `queryByTableName`。
 *
 * 真机结论（2026-09-26，菜鸟 8.11.923）：
 * - 首页包裹数据不在任何 MTOP 响应里（querypackagedynlist 的 result 只有 id/uuid/packageDynInfo），
 *   fastjson 入口与 ORM 的 `DataUtil#injectDataToObject` 全部零命中 —— 只有这个门面走首页路径，
 *   且类名 / 方法签名 / 返回类型全部未混淆（接口契约，发版不变），返回值原样透传 ORM 结果。
 * - 类加载路径（BaseDexClassLoader#findClass）上不挂任何东西，也不做可能触发类加载的反射
 *   —— 冷启动数千次回调直接把菜鸟卡死在启动闪屏。
 * - `DoradoQueryModel.topic` 不是表名（真表名要经 SchemaConfigDO → DBInfoDO → buildMappingInfo
 *   才映射得到），按行字段形状判包裹，见 [looksLikePackageRow]。
 */
internal object CainiaoPackageHook {

    private const val CAINIAO = "com.cainiao.wireless"

    /** 宿主 hybrid 层查询门面，全名未混淆（依据见类注释）。 */
    private const val DORADO_API = "com.cainiao.wireless.components.hybrid.api.HybridDoradoApi"

    /** 查询模型；三个字段 public 且未混淆（fastjson 从 JS 反序列化，字段名即契约）。 */
    private const val DORADO_QUERY_MODEL =
        "com.cainiao.wireless.components.hybrid.model.DoradoQueryModel"

    /** 两个入口都挂，均落到同一 ORM 漏斗，返回值形状一致，判定逻辑共用。 */
    private val QUERY_METHODS = listOf("query", "queryByTableName")

    // 入参的三个 public 字段，只用于诊断日志，不参与判定。
    private const val FIELD_TOPIC = "topic"
    private const val FIELD_TABLE_NAME = "tableName"
    private const val FIELD_WHERE = "where"

    // 包裹行形状判据（抄自真机 dump）：三重与是为了不误收「寄件」「搜索历史」这类带运单号的表。
    // 用 has() 不用取值：运单号可能还没下发，但 key 结构稳定。
    private const val SHAPE_MAIL_NO = "mailNo"
    private const val SHAPE_STATUS = "logisticsStatusDesc"
    private val SHAPE_EXTRA = listOf("orderCode", "partnerCode", "packageStation", "logisticsStatus")

    // 主表内嵌驿站对象：取件码、驿站名、营业时间、坐标都在里面。
    private const val FIELD_STATION = "packageStation"
    private const val FIELD_AUTH_CODE = "authCode"
    private const val FIELD_STATION_NAME = "stationName"
    private const val FIELD_SHOW_AUTH_CODE = "showAuthCode"
    private const val FIELD_OFFICE_TIME = "officeTime"

    // 宿主常不给坐标（真机很常见），缺 = 没有，不猜。
    private const val FIELD_STATION_LAT = "stationLat"
    private const val FIELD_STATION_LNG = "stationLng"

    private const val FIELD_LOGISTICS_DETAIL = "lastLogisticDetail"

    private const val FIELD_PARTNER_CODE = "partnerCode"

    /** 快递公司中文名，比 [FIELD_PARTNER_CODE] 更该先用：代码枚举要靠服务端码表，中文名是宿主已映射好直接显示的。 */
    private const val FIELD_PARTNER_NAME = "partnerName"

    private const val FIELD_PACKAGE_ITEM = "packageItem"
    private const val FIELD_ITEM_TITLE = "itemTitle"

    /** 电商来源。用 pkgSourceDesc：featureObj.eComPlatform 在 8.11.923 上实测是空串。 */
    private const val FIELD_PLATFORM = "pkgSourceDesc"

    /** 「最后一次状态变更时间」（毫秒），到站件上即入站时间。 */
    private const val FIELD_LOGISTICS_GMT_MODIFIED = "logisticsGmtModified"

    private const val MAX_DELIVERED = 512
    private const val MAX_SHAPE_LOGS = 12
    private const val MAX_QUERY_LOGS = 24
    private const val MAX_GOODS_LOGS = 8

    // 指纹带上状态：状态推进（运输中→待取件）必须能再投一次，否则会自己吞掉自己。
    private val delivered = Collections.synchronizedSet(LinkedHashSet<String>())
    private val shapeSeen = Collections.synchronizedSet(HashSet<String>())
    private val querySeen = Collections.synchronizedSet(HashSet<String>())
    private val goodsSeen = Collections.synchronizedSet(HashSet<String>())
    private val platformDropped = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var hooked = false

    @Volatile private var contextFailureLogged = false

    @Volatile private var installed = false

    /** [install] 收到的宿主 ClassLoader：receiver 在别的调用栈上触发，捕获不到 install 的局部变量，自查反射必须用它。 */
    @Volatile private var hookedClassLoader: ClassLoader? = null

    /** 身份码只在主进程取，非主进程注册只会回一条「取不到」。 */
    @Volatile private var mainProcess = true

    @Volatile private var recordsSeen = 0

    fun install(classLoader: ClassLoader, isMainProcess: Boolean = true): Boolean {
        if (installed) {
            XposedBridge.logAlways("cainiao package hook already installed, skipping")
            return true
        }

        // 先存下来：接收器（模块发来的刷新请求）在别的调用栈上要用它做反射。
        hookedClassLoader = classLoader

        val api = runCatching { XposedHelpers.findClass(DORADO_API, classLoader) }.getOrElse {
            // 找不到就整个不装：装上去只会空转，还会留一行像「装好了」的日志把排查带偏。
            XposedBridge.logError("cainiao package hook: $DORADO_API not found, aborted")
            return false
        }

        var count = 0
        for (name in QUERY_METHODS) {
            count += XposedBridge.hookAllMethods(
                api,
                name,
                object : XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // 跑在宿主的查询线程上，抛异常会带崩菜鸟 —— 全包住，出错就退化成不干预。
                        runCatching { consumeQuery(param) }
                            .onFailure {
                                XposedBridge.logError("cainiao package hook body failed (fail-open)", it)
                            }
                    }
                },
            ).size
        }

        hooked = count > 0
        installed = true
        mainProcess = isMainProcess
        // 反向通道都需要宿主 Context。onPackageReady 早于 Application#onCreate，此刻 acquire 必然
        // 为 null，注册动作必须挂到「context 到手」回调；且要在 CainiaoIdentityBridge.install 之后
        // —— context 就绪时回调会同步执行，桥必须已装好。
        HostContextHolder.installCapture(classLoader)
        // 身份码：挂宿主 IdentityBean#setIdentityCode 白得一份缓存，另备「用宿主会话现取」那条路。
        // 只在主进程装：非主进程同样收得到广播、同样会回「取不到」，会盖掉主进程刚取到的码。
        val identityOk = if (isMainProcess) {
            runCatching { CainiaoIdentityBridge.install(classLoader) }.getOrElse { error ->
                XposedBridge.logError("cainiao identity bridge install threw", error)
                false
            }
        } else {
            XposedBridge.logAlways("cainiao identity bridge: 非主进程，跳过（身份码只在主进程取）")
            false
        }
        HostContextHolder.onReady { context ->
            ensureTraceReceiver(context)
            if (isMainProcess) CainiaoIdentityBridge.ensureReceiver(context)
            // 挂点只在用户打开菜鸟首页时被调用，宿主进程起来就替它查一次本地包裹表。
            if (isMainProcess) scheduleSelfQuery(classLoader)
        }
        XposedBridge.logAlways("cainiao identity bridge installed=$identityOk")
        XposedBridge.logAlways(
            "cainiao package hook installed: $DORADO_API hooked=$count " +
                "(this layer is not on the class-loading path, safe to keep)",
        )
        return hooked
    }

    /** 宿主本地包裹表的查询主题（DoradoQueryModel.topic，不是表名 —— 见类注释）。 */
    private const val SELF_QUERY_TOPIC = "package_list_v4"

    /*
     * 自查不传 where（全表查询）。这个字段是裸 SQL 片段，会被直接拼到 SELECT * FROM <表> 后面：
     * 传 `logistics_gmt_modified > 0` 会拼出非法 SQL，异常被 ORM 吞掉、静默返回空数组；
     * 传空则得到干净的全表，且不依赖任何列名。刻意没有常量 —— invokeSelfQuery 不给 where 赋值。
     */

    private const val SELF_QUERY_INITIAL_DELAY_MS = 2_000L
    private const val SELF_QUERY_RETRY_MS = 6_000L
    private const val SELF_QUERY_ATTEMPTS = 3

    /** 两次自查最小间隔；必须大于自查内部重试窗口（2+6+6 秒），否则只剩「不并发」没有「不重复」。 */
    private const val SELF_QUERY_MIN_INTERVAL_MS = 20_000L

    /** 冷启动那一发每进程只做一次（context 到手回调有多条路会并发进来）；只管触发，模块刷新请求不受它影响。 */
    private val selfQueryStarted = AtomicBoolean(false)

    @Volatile private var lastSelfQueryAt = 0L

    private val selfQueryGate = Any()

    /** 自查跑在单线程 daemon 上：同步全表查询不能占宿主主线程，也不能用宿主的线程池。 */
    private val selfQueryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cainiao-self-query").apply { isDaemon = true }
    }

    /** 宿主进程冷启动时替它查一次本地包裹表：只有用户打开首页模块才会收到数据，而用户要的恰恰是不打开。 */
    private fun scheduleSelfQuery(classLoader: ClassLoader) {
        if (!selfQueryStarted.compareAndSet(false, true)) return
        submitSelfQuery(classLoader, "宿主冷启动")
    }

    /**
     * 提交一发自查（可重入，只受 [SELF_QUERY_MIN_INTERVAL_MS] 节流）。
     * 「查到 0 行」也算失败继续重试：ORM 未就绪时失败表现为空数组而非异常，区分不了就一律当未就绪再试。
     */
    private fun submitSelfQuery(classLoader: ClassLoader, reason: String) {
        val now = System.currentTimeMillis()
        synchronized(selfQueryGate) {
            if (now - lastSelfQueryAt < SELF_QUERY_MIN_INTERVAL_MS) {
                XposedBridge.logAlways(
                    "cainiao self query: 距上一发不足 ${SELF_QUERY_MIN_INTERVAL_MS}ms，跳过（$reason）",
                )
                return
            }
            lastSelfQueryAt = now
        }
        selfQueryExecutor.execute {
            repeat(SELF_QUERY_ATTEMPTS) { attempt ->
                Thread.sleep(
                    if (attempt == 0) SELF_QUERY_INITIAL_DELAY_MS else SELF_QUERY_RETRY_MS,
                )
                val rows = runCatching { invokeSelfQuery(classLoader) }
                    .onFailure { error ->
                        XposedBridge.logError(
                            "cainiao self query failed (attempt ${attempt + 1}/$SELF_QUERY_ATTEMPTS)",
                            error,
                        )
                    }
                    .getOrNull()
                if (rows != null && rows > 0) {
                    reportSelfQuery("rows=$rows（$reason）")
                    return@execute
                }
                if (attempt == SELF_QUERY_ATTEMPTS - 1) {
                    reportSelfQuery(
                        if (rows != null) {
                            "rows=0（$reason，查通了但本地表是空的）"
                        } else {
                            "没查成，已试 $SELF_QUERY_ATTEMPTS 次（$reason）"
                        },
                    )
                }
            }
        }
    }

    /** 自查结论送回模块进程：MIUI / HyperOS 的 logcat 读不出来，宿主侧不落字就等于没发生，唯一可读处是模块的 files/module-log.txt。 */
    private fun reportSelfQuery(text: String) {
        XposedBridge.logAlways("cainiao self query: $text")
        val context = runCatching { HostContextHolder.acquire() }.getOrNull() ?: return
        ExpressRelaySender.sendHostQueryReport(context, text)
    }

    /** 真调一次宿主查询门面，只取行数；结果由挂在 query 上的 [consumeQuery] 接管，不给 where 赋值（裸 SQL 片段，见上）。 */
    private fun invokeSelfQuery(classLoader: ClassLoader): Int {
        // 两个类的无参构造都是 public（反编译已核），直接 newInstance 安全。
        val apiClass = XposedHelpers.findClass(DORADO_API, classLoader)
        val modelClass = XposedHelpers.findClass(DORADO_QUERY_MODEL, classLoader)
        val model = modelClass.getDeclaredConstructor().newInstance().also {
            XposedHelpers.setObjectField(it, FIELD_TOPIC, SELF_QUERY_TOPIC)
        }
        val api = apiClass.getDeclaredConstructor().newInstance()
        // 返回值是 fastjson 的 JSONArray（implements List）。
        val result = apiClass.getMethod("query", modelClass).invoke(api, model)
        return (result as? List<*>)?.size ?: 0
    }

    /** Called only after this account's network cursor is confirmed applied. Ordinary cache reads cannot replace codes. */
    internal fun collectSyncedSnapshot(classLoader: ClassLoader, observedAt: Long, accountUnchanged: () -> Boolean): Boolean {
        val apiClass = XposedHelpers.findClass(DORADO_API, classLoader)
        val modelClass = XposedHelpers.findClass(DORADO_QUERY_MODEL, classLoader)
        val model = modelClass.getDeclaredConstructor().newInstance().also {
            XposedHelpers.setObjectField(it, FIELD_TOPIC, SELF_QUERY_TOPIC)
        }
        val result = try {
            collectingSynced.set(true)
            apiClass.getMethod("query", modelClass).invoke(apiClass.getDeclaredConstructor().newInstance(), model)
                as? List<*> ?: return false
        } finally { collectingSynced.remove() }
        if (!accountUnchanged() || result.size > 500) return false
        val context = HostContextHolder.acquire() ?: return false
        var submitted = true
        val records = result.mapNotNull { row ->
            val json = runCatching { JSONObject(row.toString()) }.getOrNull() ?: return false
            if (!looksLikePackageRow(json)) return false
            buildRecord(json)
        }
        for (record in records) {
            if (!accountUnchanged()) return false
            val fresh = record.copy(pickupCodeObservedAt = observedAt.takeIf { !record.pickupCode.isNullOrBlank() } ?: 0L)
            if (!ExpressRelaySender.sendEnrichment(fresh, context, packageSnapshot = true)) submitted = false
        }
        return submitted
    }

    private val collectingSynced = ThreadLocal<Boolean>()

    /** 模块发来的三个请求共用这一个接收器：拉轨迹、索要登录态、请宿主重查本地包裹表。 */
    private val traceRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ExpressRelay.ACTION_TRACE_REQUEST -> {
                    val tracking = intent.getStringExtra(ExpressRelay.EXTRA_TRACE_TRACKING)
                        ?.takeIf { it.isNotBlank() } ?: return
                    // 顺手补登录态（非 force，走它自己的 30 分钟节流）：模块会发这个请求就说明它手里没有。
                    runCatching { ExpressRelaySender.sendCookieSync(context) }
                    // 网络 IO 丢给 Fetcher 自己的 executor；全包住，任何异常都不该带崩宿主。
                    runCatching {
                        CainiaoTraceFetcher.requestFetch(
                            cookieProvider = { HostCredentialSource.cookie(context) },
                            tracking = tracking,
                            deliver = {
                                check(ExpressRelaySender.sendEnrichment(it, context)) {
                                    "authenticated enrichment was not submitted"
                                }
                            },
                        )
                    }.onFailure { XposedBridge.logError("cainiao trace request dispatch failed", it) }
                }
                ExpressRelay.ACTION_COOKIE_REQUEST ->
                    runCatching { ExpressRelaySender.sendCookieSync(context, force = true) }
                        .onFailure { XposedBridge.logError("cainiao cookie request failed", it) }
                ExpressRelay.ACTION_REFRESH_REQUEST -> {
                    // 模块侧已按分钟级节流，宿主侧不再叠一层。丢给自查单线程（全表查询不能站广播的 10 秒预算）。
                    // 只在主进程查：接收器每个进程各注册一份，:channel 没有业务库、稳定回 rows=0，
                    // 会在日志里与主进程的回报互相矛盾。
                    val loader = hookedClassLoader
                    if (!mainProcess) {
                        XposedBridge.logAlways("cainiao refresh request: 非主进程，跳过自查")
                    } else if (loader == null) {
                        XposedBridge.logError("cainiao refresh request: 还没有 ClassLoader，忽略")
                    } else {
                        submitSelfQuery(loader, "模块刷新请求（缓存）")
                        val id = intent.getStringExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_ID)
                        if (id != null) runCatching {
                            CainiaoPackageSync.request(context, loader, id,
                                intent.getLongExtra(ExpressRelay.EXTRA_PACKAGE_SYNC_RISK_UNTIL, 0L))
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    @Volatile private var traceReceiverRegistered = false

    /** 注册反向请求 receiver（幂等）。权限是 signature 级：没有它任何 app 都能索要用户登录态或替自己查任意单号。 */
    private fun ensureTraceReceiver(context: Context) {
        if (traceReceiverRegistered) return
        synchronized(this) {
            if (traceReceiverRegistered) return
            // 权限与导出性 flag 由 HostReceiverRegistrar 统一处理（淘宝进程注册同一个 action）。
            traceReceiverRegistered = HostReceiverRegistrar.register(
                context,
                traceRequestReceiver,
                ExpressRelay.ACTION_TRACE_REQUEST,
                ExpressRelay.ACTION_COOKIE_REQUEST,
                ExpressRelay.ACTION_REFRESH_REQUEST,
            )
            XposedBridge.logAlways("cainiao reverse request receiver registered=$traceReceiverRegistered")
        }
    }

    /** 一次 hybrid 查询落地。只在第一行做形状判定：整表序列化比「看一行」贵两个数量级。 */
    private fun consumeQuery(param: XC_MethodHook.MethodHookParam) {
        if (collectingSynced.get() == true) return
        val rows = param.result as? List<*> ?: return
        if (rows.isEmpty()) return

        val model = param.args.firstOrNull()
        val topic = model?.let { stringField(it, FIELD_TOPIC) }
        val tableName = model?.let { stringField(it, FIELD_TABLE_NAME) }

        val head = rows.firstOrNull()?.let { asJsonObject(it) } ?: return
        if (!looksLikePackageRow(head)) {
            noteUnmatched(param.method.name, rows, head, topic, tableName)
            return
        }

        val where = model?.let { stringField(it, FIELD_WHERE) }
        val probe = "${param.method.name}|$topic|$tableName|${where?.take(48)}|${rows.size}"
        if (querySeen.size < MAX_QUERY_LOGS && querySeen.add(probe)) {
            XposedBridge.logAlways(
                "cainiao pkg: ${param.method.name} topic=$topic table=$tableName " +
                    "rows=${rows.size} where=${where?.take(60)}",
            )
        }

        for (row in rows) {
            val node = row?.let { asJsonObject(it) } ?: continue
            emit(node)
        }
    }

    private fun noteUnmatched(
        method: String,
        rows: List<*>,
        head: JSONObject,
        topic: String?,
        tableName: String?,
    ) {
        if (shapeSeen.size >= MAX_SHAPE_LOGS) return

        val keys = StringBuilder()
        val iterator = head.keys()
        while (iterator.hasNext()) {
            if (keys.isNotEmpty()) keys.append(',')
            keys.append(iterator.next())
            if (keys.length > 200) break
        }

        val fingerprint = "$method|${head.length()}|$keys"
        if (!shapeSeen.add(fingerprint)) return
        XposedBridge.logAlways(
            "cainiao pkg: $method rows=${rows.size} topic=$topic table=$tableName " +
                "not-a-package-row keys=[$keys]",
        )
    }

    private fun looksLikePackageRow(row: JSONObject): Boolean =
        row.has(SHAPE_MAIL_NO) && row.has(SHAPE_STATUS) && SHAPE_EXTRA.any { row.has(it) }

    /** 「有运单号却读不出商品名」按缺法记一次：没有 / 空的 packageItem 与有元素但缺 itemTitle 的处置完全不同，指纹里不带运单号。 */
    private fun noteMissingGoods(mailNo: String, items: JSONArray?) {
        if (goodsSeen.size >= MAX_GOODS_LOGS) return

        val detail = when {
            items == null -> "no-packageItem"
            items.length() == 0 -> "empty-packageItem"
            else -> {
                val first = items.optJSONObject(0)
                val keys = StringBuilder()
                val iterator = first?.keys()
                while (iterator != null && iterator.hasNext()) {
                    if (keys.isNotEmpty()) keys.append(',')
                    keys.append(iterator.next())
                    if (keys.length > 160) break
                }
                "keys=[$keys] itemTitle=${first?.opt(FIELD_ITEM_TITLE)}"
            }
        }

        if (!goodsSeen.add(detail)) return
        XposedBridge.logAlways("cainiao pkg: 商品名读不出 tn=${mailNo.take(6)}… $detail")
    }

    private fun emit(node: JSONObject) {
        val record = buildRecord(node) ?: return
        if (!deliver(record)) return

        recordsSeen++
        XposedBridge.logAlways(
            "cainiao pkg: tn=${record.trackingNumber} pickup=${record.pickupCode} " +
                "station=${record.station} cp=${record.courier.displayName} " +
                "pn=${stringOf(node, FIELD_PARTNER_NAME)} " +
                "status=${record.status.displayName} platform=${record.platform} " +
                "goods=${record.goodsName?.take(18)} at=${record.arrivalAt} " +
                "hours=${record.stationHours} dyn=${record.logisticsDetail?.take(24)} " +
                "geo=${record.stationLat},${record.stationLng}",
        )
    }

    private fun buildRecord(row: JSONObject): ExpressRecord? {
        val stationObject = row.optJSONObject(FIELD_STATION)

        val mailNo = row.opt(SHAPE_MAIL_NO)?.toString()?.takeIf { it.isNotBlank() }

        // 尊重宿主开关：showAuthCode=false 时菜鸟自己不显示，我们也不透出 —— 取件码是照着拿件的凭据，写错比留空糟。
        val pickupCode = stationObject
            ?.takeIf { it.optBoolean(FIELD_SHOW_AUTH_CODE, false) }
            ?.opt(FIELD_AUTH_CODE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        val station = stationObject
            ?.opt(FIELD_STATION_NAME)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        val stationHours = stationObject
            ?.opt(FIELD_OFFICE_TIME)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        // optDouble 缺键给 NaN，一律当「没有」；宿主下发的是放大 1e5 的整数（stationLat=3177340 实为
        // 31.77340），归一化交给 geoPointOf（幂等，历史记录读侧还会再过一遍）。
        val stationPosition = geoPointOf(
            stationObject?.optDouble(FIELD_STATION_LAT, Double.NaN)?.takeIf { !it.isNaN() },
            stationObject?.optDouble(FIELD_STATION_LNG, Double.NaN)?.takeIf { !it.isNaN() },
        )

        if (mailNo == null && pickupCode == null && station == null) return null

        val statusDesc = stringOf(row, SHAPE_STATUS)
        val partnerCode = stringOf(row, FIELD_PARTNER_CODE)
        val partnerName = stringOf(row, FIELD_PARTNER_NAME)

        // pkgSourceDesc 对非淘包裹装的是「普通收件」这类收件类型词，不洗掉会占住商品名的位置。
        val rawPlatform = stringOf(row, FIELD_PLATFORM)
        val platform = ExpressPlatform.normalize(rawPlatform)
        if (platform == null && rawPlatform != null && platformDropped.add(rawPlatform)) {
            XposedBridge.logAlways(
                "cainiao pkg: 来源字段是收件类型词，按「没有来源」处理 raw=$rawPlatform",
            )
        }

        val items = row.optJSONArray(FIELD_PACKAGE_ITEM)
        val goodsName = items
            ?.optJSONObject(0)
            ?.opt(FIELD_ITEM_TITLE)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        if (goodsName == null && mailNo != null) noteMissingGoods(mailNo, items)

        val arrivalAt = row.optLong(FIELD_LOGISTICS_GMT_MODIFIED).takeIf { it > 0L }

        val logisticsDetail = stringOf(row, FIELD_LOGISTICS_DETAIL)

        return ExpressRecord(
            sourcePackage = CAINIAO,
            rawText = buildRawText(mailNo, station, pickupCode),
            trackingNumber = mailNo,
            courier = Courier.resolve(partnerName, partnerCode, mailNo),
            pickupCode = pickupCode,
            station = station,
            platform = platform,
            goodsName = goodsName,
            arrivalAt = arrivalAt,
            logisticsDetail = logisticsDetail,
            stationHours = stationHours,
            stationLat = stationPosition?.lat,
            stationLng = stationPosition?.lng,
            status = resolveStatus(statusDesc, logisticsDetail),
            origin = ExpressOrigin.ENRICHMENT,
            // 宿主库里读出来的不是推断，给满分：通知缺失时它是唯一来源。
            confidence = 100,
            timestamp = System.currentTimeMillis(),
        )
    }

    /** desc 为主，detail 失真时纠偏：2026-09-26 真机（极兔）未揽收件 desc 笼统写「运输中」，detail 是更具体的「等待揽收」时信 detail。 */
    private fun resolveStatus(statusDesc: String?, logisticsDetail: String?): ExpressStatus {
        val fromDesc = statusDesc?.let { ExpressParser.parseStatus(it) } ?: ExpressStatus.UNKNOWN
        if (fromDesc == ExpressStatus.IN_TRANSIT || fromDesc == ExpressStatus.UNKNOWN) {
            val fromDetail = logisticsDetail?.let { ExpressParser.parseStatus(it) }
            if (fromDetail == ExpressStatus.CREATED) return ExpressStatus.CREATED
        }
        return fromDesc
    }

    private fun buildRawText(mailNo: String?, station: String?, pickupCode: String?): String =
        listOfNotNull(
            mailNo?.let { "运单号 $it" },
            station?.let { "站点 $it" },
            pickupCode?.let { "取件码 $it" },
        ).joinToString(" ")

    private fun deliver(record: ExpressRecord): Boolean {
        val fingerprint =
            "${record.trackingNumber}|${record.pickupCode}|${record.station}|${record.status.name}"
        if (!delivered.add(fingerprint)) return false
        if (delivered.size > MAX_DELIVERED) {
            delivered.clear()
            delivered.add(fingerprint)
        }

        val context = HostContextHolder.acquire()
        if (context == null) {
            if (!contextFailureLogged) {
                contextFailureLogged = true
                XposedBridge.logError(
                    "cainiao package hook: no host context yet, drop ${record.trackingNumber}",
                )
            }
            // 指纹已占位会吞掉后续重试，撤掉等下一次查询。
            delivered.remove(fingerprint)
            return false
        }

        val submitted = ExpressRelaySender.sendEnrichment(record, context)
        if (!submitted) delivered.remove(fingerprint)
        // 轨迹收拢到模块进程，cookie 由这里同步过去（30 分钟节流）；请求 receiver 在此兜底立起来。
        ExpressRelaySender.sendCookieSync(context)
        ensureTraceReceiver(context)
        // 身份码索取通道同理，只有主进程才注册。
        if (mainProcess) CainiaoIdentityBridge.ensureReceiver(context)
        return submitted
    }

    /** 行是 fastjson 的 JSONObject，toString 即 JSON 文本；模块不引 fastjson，走 toString + org.json。 */
    private fun asJsonObject(row: Any): JSONObject? =
        runCatching { JSONObject(row.toString()) }.getOrNull()

    private fun stringOf(node: JSONObject, name: String): String? =
        node.opt(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun stringField(instance: Any, name: String): String? =
        runCatching { XposedHelpers.getObjectField(instance, name) as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun describe(): String =
        "installed=$installed hooked=$hooked records=$recordsSeen delivered=${delivered.size}"
}
