# 免 root 方案（工程补充方案）设计

> 状态：已实施（v29）。设计日期 2026-09-27。
> 参考项目：[Halo0sama/ExpressAssistant](https://github.com/Halo0sama/ExpressAssistant)（MIT）——
> 「自己登录 → 拿 cookie → 直接向平台官方接口取快递信息」这条路。

## 一、定位：不是替代，是补充

模块原本只有一条腿：**寄生宿主**。它要求用户能装 Xposed 框架、能在作用域里勾上宿主 App。

免 root 方案补的是这条腿断掉时的三种情形：

| 情形 | root 方案 | 免 root 方案 |
|---|---|---|
| 没装 Xposed（或 LSPosed 未启用本模块） | 完全不可用 | **可用**（通知使用权 + 自登录） |
| 宿主不在作用域里（新装的应用、误删勾选） | 那个来源彻底断掉 | **可用**（通知使用权不挑应用） |
| 宿主进程被系统冻结 / 回收后叫不醒 | 数据停在最后一次刷新 | **可用**（模块自己发请求，不需要宿主活着） |

两者**同时可用时不冲突**：统一落进同一份记录存储、同一份广播契约，靠去重账本避免重复提醒
（见 §四）。

## 二、能力对照

| 能力 | root 方案（LSPosed） | 免 root 方案 |
|---|---|---|
| 读宿主通知原文 | system_server hook 拦截前读取 | **NotificationListenerService**（用户授予「通知使用权」） |
| 屏蔽某类通知 | 投递前拦下（用户完全看不到） | 通知出现后立刻 `cancelNotification(key)`（**可能有一帧闪烁**） |
| 发替换通知 | 模块进程发（同一条） | 模块进程发（同一条） |
| 单号 → 全轨迹 / 商品图 / 驿站地址 | 宿主 cookie + MTOP H5 | **模块自己登录淘宝的 cookie + MTOP H5**（同一条接口） |
| 记录页「打开原通知」 | 令牌（模块内存 + system_server 寄存，设备开机内有效） | 令牌（模块进程内存；进程重启后只剩「打开那个 App 主入口」） |
| 发现新包裹 | 宿主自查本地包裹表 | 淘宝订单列表 → SSR 物流页取运单号 |
| 拼多多件 | 读宿主 HTTP 缓存 + 挂解析出口 | **仅通知文案**（见 §三.2） |
| 身份码 | 借菜鸟自己的会话取 | ❌ **做不到**（那条接口只在 APP 通道） |
| 唤醒死进程 | 唤醒销 + system_server 代发 | 不需要（模块自己就是数据源） |

## 三、三条腿

### 1. 通知使用权（`noroot/ExpressNotificationListener.kt`）

`NotificationListenerService` 是 AOSP 公开 API，用户只需在系统设置里授予一次「通知使用权」，
**不需要 root**。它在模块进程里跑，所以拿到的东西能直接进现有链路：

```
onNotificationPosted(sbn)
  → 自家通知？(EXTRA_MODULE_ORIGIN) 直接跳过   // 递归防护第一重
  → NoRootPlan.isListenerCollecting(开关, 授权, 模式)  // 与界面读同一个函数
  → ExpressClassifier.isSourceAllowed()        // 来源白名单：设备上绝大多数通知在这里就返回
  → NotificationExtrasReader.read()            // 与 hook 侧同一个读法（只读已知键）
  → ExpressTextExtractor.extractFullText()     // 与 hook 侧同一份文本拼装
  → ExpressClassifier.classify()               // 与 hook 侧同一份判定与阈值
  → ExpressRecordStore.upsert()                // 与 hook 侧同一份落库
  → ExpressChangeNotifier.notify()             // 叫界面重读（写记录的地方必须自己发）
  → ExpressNotificationPoster.post()           // 与 hook 侧同一份通知形态（含焦点通知）
  → ModuleTraceFetcher.maybeAutoFetch()        // 采到新件顺手拉轨迹（免 root 没有宿主富化）
```

由此**首页、详情页、归档、驿站规则、通知记录页、表单文案全部零改动**。

判定顺序是刻意排的：这个回调会把**设备上每一条**通知都送进来，所以最便宜、能挡掉绝大多数的
三步（自家通知 / 开关 / 来源白名单）排在最前，读正文放最后。

`BuildConfig.OBSERVE_ONLY`（与 hook 侧同一个开关）为真时只判定并记一行日志 —— 不落库、不发通知、
不撤原通知，避免这条腿把观察结果搅浑。

「拦截并替换」在免 root 下退化为「先撤后发」：`cancelNotification(sbn.key)`。差异如实写进界面文案 ——
它比 hook 的投递前拦截多一帧闪烁，也可能在个别 ROM 上被拒（那时只记一条审计，原通知保留）。

递归防护同样保留两道：自家通知带 `EXTRA_MODULE_ORIGIN`（直接跳过）+ 来源白名单。

**跳转令牌这条路免 root 反而更直接**：监听服务拿到的是货真价实的 `PendingIntent`（宿主的），
直接交给 `NotificationIntentCache`（按审计记录 id 归口），详情页点「打开原通知」就是 `send()`
—— 不需要任何权限。只有「可落盘快照」那一份取不到（读令牌内部的 Intent 要 signature 级的
`GET_INTENT_SENDER_INTENT`，只有 system_server 清完调用身份才过得了），所以退成
「记下令牌是谁创建的」= 路②打开那个 App 的主入口。差别只在模块进程重启之后那一档。

### 2. 自登录 + cookie 直连（`ui/ExpressTaobaoLoginDialog` → `relay/TraceCookieCache` → `hook/CainiaoTraceApi`）

这就是参考项目的核心思路，模块里**早已有实现**（2026-09-26 写好、当时没有入口）：

1. 模块自己起一个 WebView，用户登录一次淘宝（`login.m.taobao.com`）；
2. 从**模块自己进程**的 `CookieManager` 里取 `.taobao.com` 的登录态（判据收紧到
   `sgcookie=` / `cookie2=`，未登录时的匿名 cookie 不算）；
3. 落地进 `TraceCookieCache`（内存 + 模块私有目录），与 hook 同步进来的那份**同一个落点**；
4. 之后所有请求都由模块进程发出：`mtop.taobao.order.queryboughtlistv2`（订单）→
   SSR 物流页（运单号）→ `mtop.taobao.logisticstracedetailservice.queryalltrace`（全轨迹）。

登录态是服务端签发的，伪造不出来，能做的只有「拿一份真的」；免 root 下这份由用户自己在模块里
登录产生，与宿主在不在、勾没勾作用域都无关。**它不会离开本机**：只写模块私有目录，不进日志、不上传。

### 3. 前台服务轮询（`relay/AutoWatchService`，已有）

轮询本来就有（默认关闭）。免 root 下它的地位变了：它从「补充」变成「唯一的自动更新来源」——
因为宿主可能永远不刷新。每轮开始时先跑一次**发现**（见 §四），再逐件拉轨迹。

## 四、发现与去重

### 发现引擎只有一份（`relay/CainiaoDirectFetcher`）

「用 cookie 问淘宝要订单、从 SSR 页抠运单号」这套逻辑原本是**兜底**用的（只在宿主不接话时启动）。
免 root 下它成为主路：`force = true` 的入口给界面上的「立即同步」与「登录成功后自动同步」，
自动路径保持原有节流。`force` 只把窗口从 10 分钟收到 30 秒（防连点），**风控退避与 cookie
两道闸门不跳** —— 那两道防的是「账号被处罚」和「空手发请求」，与「用户不想等」不是一回事。

`start()` 返回「有没有真的发起」，界面据此如实回话（没说发起就别让用户对着一个不动的数字等）。

```
TaobaoOrderApi.listOrders(cookie)            // 一次请求
  → 过滤 isInTransit                          // 已完成/关闭不问
  → take(MAX_ORDERS=5)，每个间隔 2.5s
  → TaobaoOrderApi.ssrParcel(cookie, orderId) // 一次请求/单
  → 拿到运单号 → CainiaoTraceFetcher.requestFetch()  // 闸门 + 串行 + 风控退避都在那
  → ExpressRecordStore.enrich()               // 配不上就新建
```

风控仍是硬约束（实测按请求频率收网、处罚是小时级余温）：全程共用 `CainiaoTraceApi` 的
指数退避，退避期内手动同步也会被挡下并如实告知。

### 双链路去重（`notification/ExpressDeliveryLedger.kt` + `core/NotifyDedupe.kt`）

两条链路可能在**同一时刻**处理同一条通知（hook 投递 + 监听服务采集），各发一条替换通知就是
重复提醒。账本放在模块进程内（两条链路都在这个进程），键 = `dedupeKey + 正文哈希`，
窗口 60 秒，先到者投递、后到者只落库。

进程在两次事件之间被杀会失效 —— 代价是**多一条通知**，比漏一条可接受，故不做持久化。
落库本身幂等（`upsert` 返回 `changed`），不会产生重复记录。

## 五、边界（必须如实说）

1. **身份码在免 root 下不可能**：那条接口只在 MTOP APP 通道可用，H5 通道预热就被拒
   （2026-09-26 两轮真机实证，见 `hook/CainiaoIdentityBridge`）。界面上写「当前方案不支持」，
   不静默回退、不假装。
2. **拼多多不做 cookie 方案**：它的接口签名 `anti_content` 在 native，cookie 本身不够；
   参考项目用的是「隐藏 WebView + 页面 JS 自己签名 + 钩 XHR」那条路（见
   `.workbuddy/memory/express-source-research.md`）。免 root 下拼多多件**只走通知文案**，
   缺失的字段靠宿主或后续版本补。
3. **工作模式是总闸**：`关闭` = 免 root 采集也不做任何事（与 hook 侧语义一致）。
   界面在「采集开着但模式是关闭」时给一句提示。
4. **通知使用权给了就能读通知**：这是系统给的能力，不是绕过访问控制；模块只处理白名单来源，
   分类判据与阈值和 hook 侧完全同一份。免 root 方案不增加任何新的数据出口。
5. **不放弃 root 方案**：两条腿各自独立可用，都在时互补（hook 覆盖面更全，免 root 不挑环境）。

## 六、落地清单

| 位置 | 内容 |
|---|---|
| `core/NoRootPlan.kt` | 工作模式判定、能力清单、通知使用权串解析（纯函数 + 单测） |
| `core/NotifyDedupe.kt` | 窗口去重（纯函数 + 单测） |
| `notification/ExpressDeliveryLedger.kt` | 进程级去重账本（两条链路共用） |
| `notification/ExpressChangeNotifier.kt` | 「写记录了就叫界面重读」的唯一出口（含富化那一路的节流） |
| `noroot/NoRootAccess.kt` | 免 root 能力探测（通知使用权 / 通知权限 / 登录态摘要） |
| `noroot/ExpressNotificationListener.kt` | 通知监听服务：采集 + 可选屏蔽 |
| `ui/NoRootPage.kt` | 「免 root 模式」二级页 |
| `ui/ExpressTaobaoLoginDialog.kt` | 淘宝登录弹窗（原实现接上入口） |
| `relay/CainiaoDirectFetcher.kt` | 加 `force` 入口与结果播报，成为免 root 发现引擎 |
| `config/ExpressSettingsKeys.kt` | `no_root_listener` 开关（走同一份 readFrom/writeTo） |
| `AndroidManifest.xml` | 监听服务声明 + BIND_NOTIFICATION_LISTENER_SERVICE |
