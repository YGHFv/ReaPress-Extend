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

/**
 * 拦截事件从 system_server 投递到模块进程的广播协议。两侧（注入 system_server 的代码与模块 App 进程）
 * 类加载器不同，只能靠这份字符串契约对齐；走广播 + 显式组件 + FLAG_INCLUDE_STOPPED_PACKAGES，
 * 是模块 App 未启动（stopped）时唯一可达的通道。加键可以、改键名 = 破坏兼容。
 */
object ExpressRelay {

    const val MODULE_PACKAGE = "io.github.YGHFv.ReaPressExtend"

    const val RECEIVER_CLASS = "io.github.YGHFv.ReaPressExtend.relay.ExpressRelayReceiver"

    const val EXTRA_RELAY_CREDENTIAL = "io.github.YGHFv.ReaPressExtend.extra.RELAY_CREDENTIAL"

    /** system_server 拦到一条快递通知。 */
    const val ACTION_DELIVER = "io.github.YGHFv.ReaPressExtend.DELIVER_EXPRESS"

    /** 宿主富化出一条包裹信息。与 [ACTION_DELIVER] 分开：enrich 只落记录不发通知，用 action 区分漏判时落 else 被忽略而不是误发通知。 */
    const val ACTION_ENRICH = "io.github.YGHFv.ReaPressExtend.ENRICH_EXPRESS"

    /** system_server 按设置吞掉了原通知：只落一条审计记录，不发任何通知。 */
    const val ACTION_INTERCEPTED = "io.github.YGHFv.ReaPressExtend.INTERCEPTED_EXPRESS"

    /**
     * 模块 → 宿主：拉某单号全轨迹。2026-09-26 起按需单拉（首页刷新批量拉吃过淘宝 RGV587 风控）。
     * 依赖宿主进程存活：菜鸟不在后台时广播没人收，详情页按超时降级。
     */
    const val ACTION_TRACE_REQUEST = "io.github.YGHFv.ReaPressExtend.TRACE_REQUEST"

    /** 轨迹拉取完成落库（模块进程内部广播，UI 收到后收掉详情页刷新指示器）。 */
    const val ACTION_TRACE_ARRIVED = "io.github.YGHFv.ReaPressExtend.TRACE_ARRIVED"

    /**
     * 存储记录变了，界面重读。不能复用 [ACTION_TRACE_ARRIVED]：那条还会收掉详情页刷新指示器，
     * 接收期望不同就不共用。没有它，宿主自查晚到的数据会让首页一直停在打开时的旧快照上。
     */
    const val ACTION_RECORDS_CHANGED = "io.github.YGHFv.ReaPressExtend.RECORDS_CHANGED"

    /**
     * 宿主 → 模块：同步淘宝登录态 cookie。边界（2026-09-26 收窄 + 落盘修订）：cookie 只进模块进程
     * 内存与私有目录（[TraceCookieCache] / TraceCookieStore），不进日志；广播显式组件寻址，截不到 extras。
     */
    const val ACTION_COOKIE_SYNC = "io.github.YGHFv.ReaPressExtend.COOKIE_SYNC"

    /** 模块 → 宿主：索要登录态。宿主只在首页查询时主动发且有节流，模块要在需要的那一刻直接问一次。 */
    const val ACTION_COOKIE_REQUEST = "io.github.YGHFv.ReaPressExtend.COOKIE_REQUEST"

    /**
     * 模块 → 宿主：重查本地表；附 packageSyncId 时再做受限联网同步。与唤醒销互补：宿主不在时只有唤醒销（清单接收者）
     * 能拉起进程；宿主存活时（小米上常年以推送进程活着，唤醒销是空操作）只有这条能让它立刻查。
     */
    const val ACTION_REFRESH_REQUEST = "io.github.YGHFv.ReaPressExtend.REFRESH_REQUEST"

    /**
     * 模块 → system_server：借 system uid 替模块把唤醒销投给菜鸟。ROM 按发送方拦第三方拉起
     * （HyperOS 实测：模块直投 12 秒无进程，system uid 投 8 秒内起来；PendingIntent 的广播仍算创建者发的）。
     * 只能发隐式广播（system_server 不是包），安全靠 [PERMISSION_TRACE_REQUEST] 权限闸。
     */
    const val ACTION_WAKE_REQUEST = "io.github.YGHFv.ReaPressExtend.WAKE_REQUEST"

    /** system_server → 模块：[ACTION_WAKE_REQUEST] 的回执与通道就绪播报。「通道没注册上」和「销发了被 ROM 拦」在日志上长得一样，缺了回执无法区分。 */
    const val ACTION_WAKE_REPORT = "io.github.YGHFv.ReaPressExtend.WAKE_REPORT"

    /**
     * 模块 → system_server：取回原通知跳转令牌。令牌不能落盘（binder 句柄）但可以留在 system_server
     * （寿命 = 设备本次开机），模块进程重启后仍能取回。不是请 system_server 启动 —— 取回后由模块自己 send。
     */
    const val ACTION_INTENT_RESOLVE_REQUEST = "io.github.YGHFv.ReaPressExtend.INTENT_RESOLVE_REQUEST"

    /** system_server → 模块：[ACTION_INTENT_RESOLVE_REQUEST] 的应答。令牌被 LRU 淘汰或重启过也要空手回，否则又是静默失败。 */
    const val ACTION_INTENT_TOKEN_ARRIVED = "io.github.YGHFv.ReaPressExtend.INTENT_TOKEN_ARRIVED"

    /** 模块进程内部：令牌到货，详情页重算措辞。system_server 那条是显式广播，详情页动态注册的接收器收不到。 */
    const val ACTION_INTENT_TOKEN_READY = "io.github.YGHFv.ReaPressExtend.INTENT_TOKEN_READY"

    /** 宿主 → 模块：自查结论播报。MIUI / HyperOS 的 logcat 读不出来，宿主侧不落字就等于没发生，唯一可读处是模块的 files/module-log.txt。 */
    const val ACTION_HOST_QUERY_REPORT = "io.github.YGHFv.ReaPressExtend.HOST_QUERY_REPORT"
    const val ACTION_PACKAGE_SYNC_REPORT = "io.github.YGHFv.ReaPressExtend.PACKAGE_SYNC_REPORT"
    const val EXTRA_PACKAGE_SYNC_ID = "packageSyncId"
    const val EXTRA_PACKAGE_SYNC_STATUS = "packageSyncStatus"
    const val EXTRA_PACKAGE_SYNC_RETRY_AT = "packageSyncRetryAt"
    const val EXTRA_PACKAGE_SYNC_RISK_UNTIL = "packageSyncRiskUntil"
    const val EXTRA_PICKUP_OBSERVED_AT = "pickupCodeObservedAt"
    const val EXTRA_PACKAGE_SNAPSHOT = "packageSnapshot"
    const val EXTRA_PICKUP_MAIL_TAIL = "pickupMailTail"

    /**
     * 宿主 → 模块：新接平台探针结论。不复用 [ACTION_HOST_QUERY_REPORT]：那条的接收侧会调
     * CainiaoDirectFetcher.noteHostReport（菜鸟存活证据），探针按到那会误判宿主还活着。
     * 载荷含运单号等真机数据，只进模块私有日志，不进仓库。
     */
    const val ACTION_HOST_PROBE = "io.github.YGHFv.ReaPressExtend.HOST_PROBE"

    /**
     * 模块 → 菜鸟：用宿主自己的会话取一份身份码。身份码接口在 H5 通道上是关的（连 _m_h5_tk 都不下发，
     * 直接 FAIL_SYS_SESSION_EXPIRED，而同一份 cookie 拉轨迹成功），只能请宿主自己发那个请求。
     * 与 [ACTION_COOKIE_REQUEST] 不同：那条要的是登录态原材料，这条要的是结果（短时效凭据不出宿主进程）。
     */
    const val ACTION_IDENTITY_REQUEST = "io.github.YGHFv.ReaPressExtend.IDENTITY_REQUEST"

    /**
     * 菜鸟 → 模块：身份码 / 取不到的原因 / 通道就绪播报（只带 [EXTRA_IDENTITY_BRIDGE_STATUS]）。
     * 宿主主动推送不是冗余：小米上宿主不在后台时模块发去的广播会被静默丢弃，那一刻只有这份推送过的码可用。
     */
    const val ACTION_IDENTITY_SYNC = "io.github.YGHFv.ReaPressExtend.IDENTITY_SYNC"

    // ---- extras ----
    const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_TRACKING = "trackingNumber"
    const val EXTRA_COURIER = "courier"
    const val EXTRA_PICKUP_CODE = "pickupCode"
    const val EXTRA_STATION = "station"
    const val EXTRA_STATUS = "status"
    const val EXTRA_CONFIDENCE = "confidence"
    const val EXTRA_KEYWORDS = "matchedKeywords"
    const val EXTRA_TIMESTAMP = "timestamp"

    // ---- 只有宿主富化给得出的字段（通知文案里没有），缺省一律表示「没有」。 ----

    const val EXTRA_PLATFORM = "platform"

    const val EXTRA_GOODS_NAME = "goodsName"

    /** 到站 / 入站时间（毫秒），0 表示没有。 */
    const val EXTRA_ARRIVAL_AT = "arrivalAt"

    /** 运单动态（宿主 lastLogisticDetail）。 */
    const val EXTRA_LOGISTICS_DETAIL = "logisticsDetail"

    /** 驿站营业时间（宿主 packageStation.officeTime）。 */
    const val EXTRA_STATION_HOURS = "stationHours"

    /** 驿站坐标（WGS84 度）。putExtra 收不了 null，「没有」用 NaN 表示；0 当有效值会让「最近的驿站」算错。 */
    const val EXTRA_STATION_LAT = "stationLat"
    const val EXTRA_STATION_LNG = "stationLng"

    // ---- 轨迹接口（queryalltrace）补出来的字段 ----

    /** 驿站完整地址（轨迹末条的 address，只在到站件上有），与驿站名 station 分开。 */
    const val EXTRA_STATION_ADDRESS = "stationAddress"

    const val EXTRA_GOODS_IMAGE = "goodsImage"

    /** 全轨迹，ExpressTraceCodec 编码后的字符串；不拆多个 extra，一个编码入口一个解码入口。 */
    const val EXTRA_TRACE = "trace"

    // ---- 只有通知侧给得出的字段 ----

    /** 收件手机号尾号（「手机尾号1234」）。 */
    const val EXTRA_PHONE_TAIL = "phoneTail"

    /** 包裹尾号（「取尾号1234包裹」），与 [EXTRA_PHONE_TAIL] 是两件事：这是把通知认领到包裹上的运单号尾段。 */
    const val EXTRA_PARCEL_TAIL = "parcelTail"

    /** 原取件码 —— 尾号匹配换码后换下来的那个，尾号只有 3-6 位，原码要留给用户核对。 */
    const val EXTRA_PREVIOUS_PICKUP_CODE = "previousPickupCode"

    /** 记录来源，取值为 [io.github.YGHFv.ReaPressExtend.core.ExpressOrigin] 的名字。 */
    const val EXTRA_ORIGIN = "origin"

    /**
     * 原通知的点击跳转（contentIntent，Parcelable）。接收侧必须用 runCatching 包着读：富化那条路没有它，
     * 读失败只能让「跳转」这一个能力消失，不能连整条记录丢掉。不进存储：PendingIntent 没有可落盘的
     * 表示，只放内存（NotificationIntentCache），跨重启看 [EXTRA_NOTIFICATION_INTENT_URI]。
     */
    const val EXTRA_NOTIFICATION_INTENT = "notificationIntent"

    /**
     * 原通知跳转的可落盘快照：内部 Intent 的 toUri(URI_INTENT_SCHEME) 字符串。PendingIntent 本体不能落盘
     * 但它装的 Intent 可以；有损（Parcelable / FLAG_GRANT 会丢），接收侧内存令牌优先、快照兜底。
     */
    const val EXTRA_NOTIFICATION_INTENT_URI = "notificationIntentUri"

    /**
     * 令牌在 system_server 侧的缓存键（一个 UUID）。只是句柄：拿到不代表令牌还在，取回要按「可能空手」
     * 处理；也正因如此可以随便落盘 —— 记录里存的就是它。
     */
    const val EXTRA_INTENT_TOKEN = "intentToken"

    /** 模块侧审计记录的 Entry.id，请求带出去、应答原样带回；system_server 只搬运不解释。 */
    const val EXTRA_INTENT_ENTRY_ID = "intentEntryId"

    /** 被拦截通知的分类（NotificationCategory 枚举名）。用名字不用序号：枚举插新值会让旧记录错位。 */
    const val EXTRA_CATEGORY = "interceptedCategory"

    /** [ACTION_TRACE_REQUEST] 的载荷：要拉全轨迹的运单号。 */
    const val EXTRA_TRACE_TRACKING = "traceTracking"

    /** [ACTION_COOKIE_SYNC] 的载荷：拼好的 cookie 请求头。 */
    const val EXTRA_COOKIE = "traceCookie"

    /** [EXTRA_COOKIE] 的随行：宿主 WebView 的真实 UA（让请求画像与登录态一致）。 */
    const val EXTRA_COOKIE_UA = "traceCookieUa"

    /** 登录态取不到时的人话原因。见到它即视为回执，据此区分「宿主没有登录态」与「广播没送到」；键可以加，语义不能改。 */
    const val EXTRA_COOKIE_ERROR = "traceCookieError"

    // ---- 身份码（[ACTION_IDENTITY_SYNC]）----

    /** 身份码本体，等于一次取件凭据：两端都只进内存，不落盘不写日志，唯一出口是弹窗上那串数字。 */
    const val EXTRA_IDENTITY_CODE = "identityCodeValue"

    /** 身份码有效期（epoch ms），0 = 宿主没给，一律当作可用。 */
    const val EXTRA_IDENTITY_EXPIRE_AT = "identityExpireAt"

    /** 是否离线码（宿主 IdentityBean.isOffLine），只影响界面小字。 */
    const val EXTRA_IDENTITY_OFFLINE = "identityOffline"

    /** 来源 host / cache，只用于日志。 */
    const val EXTRA_IDENTITY_PROVENANCE = "identityProvenance"

    /** 取不到原因。见到它即视为回执，语义与 [EXTRA_COOKIE_ERROR] 相同。 */
    const val EXTRA_IDENTITY_ERROR = "identityError"

    /** 索取通道状态播报，与码 / 错误互斥：只表示「通道已立好」，是唤醒销有没有叫醒宿主的唯一可观测判据。 */
    const val EXTRA_IDENTITY_BRIDGE_STATUS = "identityBridgeStatus"

    /** 自查结论（查到几行 / 失败几次），不带运单号与包裹内容；包裹数据走 [ACTION_ENRICH]。 */
    const val EXTRA_HOST_QUERY_REPORT = "hostQueryReport"

    /** 探针摊开的一行（含运单号等诊断数据），与 [EXTRA_HOST_QUERY_REPORT] 只差接收侧处置。 */
    const val EXTRA_HOST_PROBE = "hostProbeText"

    /** 这一记唤醒的用途（只进日志），与 HostWakePin.wake 的 reason 同串透传到回执。 */
    const val EXTRA_WAKE_REASON = "wakeReason"

    /** [ACTION_WAKE_REPORT] 的载荷：system_server 的结论，只描述动作本身，不带用户数据。 */
    const val EXTRA_WAKE_REPORT = "wakeReport"

    /** [ACTION_TRACE_REQUEST] 的发送方凭证（signature 级权限，模块 APK 声明 + 自持）。字符串契约，两侧原样一致。 */
    const val PERMISSION_TRACE_REQUEST = "io.github.YGHFv.ReaPressExtend.permission.TRACE_REQUEST"

    const val HOST_PACKAGE = "com.cainiao.wireless"

    /** 拼多多包名：不是广播寻址目标（PDD 侧没有反向 receiver），只是来源标记。 */
    const val PDD_PACKAGE = "com.xunmeng.pinduoduo"

    /** 淘宝包名：登录态备用来源（菜鸟没绑淘宝账号时只有淘宝 App 里有 .taobao.com 域 cookie）；需在 LSPosed 勾选作用域。 */
    const val TAOBAO_PACKAGE = "com.taobao.taobao"

    /** 索要登录态时两边都发（哪边有凭据事先不知道），后到覆盖先到，重复投递无害。 */
    val CREDENTIAL_PACKAGES = listOf(HOST_PACKAGE, TAOBAO_PACKAGE)

    /** 模块自身来源标记：防「拦截 → 重发 → 又被拦截」无限循环（三重防护的第一重）。 */
    const val EXTRA_MODULE_ORIGIN = "io.github.YGHFv.ReaPressExtend.extra.MODULE_ORIGIN"
}
