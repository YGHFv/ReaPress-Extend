# 项目结构与功能自检报告

初查日期：2026-09-29；复核日期：2026-10-02。检查基线：`8a2d901`，复核时业务源码、正式测试及构建配置均未变化。

第 1–8 节保留修复前的审计结论与基线代码定位，不代表当前修复状态；后续改动、测试结果及未完成事项见第 9 节。

最新修复复核日期：2026-10-03；第七批开发状态见第 9 节末尾。

## 1. 结论与检查边界

项目已经具备完整的 Android 应用与 Xposed 模块形态，解析、富化、分组等核心逻辑有较充分的单元测试，但不能据此判定所有功能正常。当前主要风险集中在跨进程信任边界、异步任务状态清理、并发持久化以及恢复后的运行状态同步。

- 原有 **512 项测试全部通过**，共 37 个测试类、27 个测试源码文件；失败、错误、跳过均为 0。
- **Debug、Release 构建通过**。Release 产物为未签名 APK，不能直接当作可安装的正式发行包。
- **Android Lint 未通过：18 个错误、70 个警告、1 个提示**。部分权限告警需要结合注入进程身份判断，不等于 18 个已复现的运行时故障。
- 额外执行了 **2 个离线问题探针**：一个调用实际轨迹拉取器验证失败后的状态，一个验证订单筛选表达式的求值行为。两者均观察到了下文描述的问题，探针通过不代表缺陷已修复。
- `adb devices` 没有发现设备。本次未验证真实 LSPosed 注入、系统通知投递、厂商焦点通知、登录后的服务端接口、开机恢复、位置/WiFi 与 UI 真机表现。
- 本次是梳理与自检，**没有修改业务源码、正式测试或构建配置**；仅新增本报告。临时探针与日志保留在 Git 忽略的 `log/` 目录，不进入正常构建。

## 2. 项目结构

项目为单一 Gradle `app` 模块，共 120 个生产 Kotlin 文件。配置为 JDK 17、AGP 9.2.1、Kotlin 2.4.20、Gradle 9.4.1；`minSdk=26`、`targetSdk=35`、`compileSdk=37`。

下表及问题定位中的源码路径均相对于 `app/src/main/java/io/github/YGHFv/ReaPressExtend/`。

| 目录 | 文件数 | 主要职责 |
| --- | ---: | --- |
| 根目录 | 1 | `ExpressApplication`：框架服务连接、备份调度初始化 |
| `core/` | 32 | 通知分类/解析、身份与运单模型、富化、分组、轨迹解析、备份格式与加密、时间/距离计算 |
| `config/` | 3 | 本地设置、框架 RemotePreferences 投影、配置快照与键 |
| `hook/` | 23 | Xposed 入口、system_server/菜鸟/淘宝/拼多多适配、宿主凭据、反射、轨迹请求与看门狗 |
| `xposed/`（含 `callbacks/`） | 2 | libxposed 兼容封装与回调适配 |
| `noroot/` | 3 | NotificationListenerService、通知使用权与实际连接状态探测 |
| `relay/` | 14 | 跨进程事件、模块侧拉取、凭据缓存、自动轮查、开机处理 |
| `notification/` | 11 | 记录存储、通知投递/去重/审计、驿站规则、原通知跳转与焦点通知 |
| `backup/` | 5 | 导入导出、SAF/应用目录存储、自动/定时备份及恢复前快照 |
| `ui/`（含 `liquid/`） | 23 | Compose 页面、交互、主题、身份码、定位与玻璃效果 |
| `logging/` | 3 | 日志策略、内存缓冲、本地日志文件 |

其他关键文件：

- `app/src/main/AndroidManifest.xml`：权限、Activity/Service/Receiver、包可见性。
- `app/src/main/resources/META-INF/xposed/`：现代 Xposed 入口与作用域，包含 `system` 和三个宿主。
- `app/src/test/`：主要为 JVM 纯逻辑/契约测试；没有现成的 `androidTest` 真机测试集。
- `.github/workflows/ci.yml`：单测、Debug/Release 构建、产物及预发布；目前不执行完整 Lint，也没有 `pull_request` 触发器。
- `docs/no-root-plan.md`：免 root 能力设计与边界。

## 3. 关键调用链与功能覆盖

```text
system_server 通知 hook ── 广播 ──> ExpressRelayReceiver ──┐
宿主包裹/轨迹/登录态 hook ─ 广播 ──> 富化/凭据缓存 ───────────┤
NotificationListenerService ────────────────────────────┤
淘宝自登录 → 订单发现 → 轨迹拉取 ──────────────────────────┤
                                                       ↓
                  ExpressRecordStore + 通知去重/投递/审计
                                                       ↓
                   ExpressChangeNotifier → Compose UI

本地设置 → ExpressSettings → 框架 RemotePreferences → 注入进程
SharedPreferences ↔ ExpressBackup ↔ 加密/明文文件、SAF、定时调度
```

| 功能域 | 已做检查 | 当前结论 |
| --- | --- | --- |
| 通知分类、运单号/取件码识别、快递公司、状态 | 核心代码、对应单测 | 现有样例通过，不代表覆盖所有真实通知格式 |
| 富化、身份匹配、分组、归档、取件状态 | 合并函数、序列化、UI/存储入口、对应单测 | 纯逻辑通过；并发写入存在问题 F04 |
| root 采集、宿主自查、看门狗 | 入口/作用域/反射/广播与失败处理审阅 | 契约测试通过；F03、F05 待修；宿主版本兼容需真机 |
| 免 root 采集与原通知撤销 | 监听服务、权限/连接状态、去重链路 | 静态检查发现 F10；系统回调需真机 |
| 淘宝登录、订单发现、轨迹刷新 | WebView Cookie、API、队列、节流/退避 | F01 已实际离线复现；F02 已验证表达式行为；未请求真实服务 |
| 自动轮查、夜间暂停、重启接续 | Service/开机入口、调度函数及 25 项调度测试 | 计算测试通过；恢复副作用和系统保活需验证 |
| 身份码、条码、驿站规则、位置/WiFi | 能力边界、身份模型、页面和定位门禁 | 身份/规则/距离相关单测通过；扫码与硬件未验收 |
| 普通通知/焦点通知、原通知跳转 | 投递/令牌/快照路径、协议测试 | 协议测试通过；ROM 展示、令牌跨重启需真机 |
| 备份、加密、恢复、自动备份 | 编解码/加密单测、落盘与调度链路 | 文件格式测试通过；F07、F08、F09 待修 |
| UI、主题、毛玻璃、隐藏最近任务 | 页面状态、资源、编译、Lint | 可编译；低版本 API 有缺口，未做视觉与设备兼容验收 |
| 日志与诊断 | 本地存储、忽略规则、上报入口 | 未发现 Cookie 明文直接打印路径；运单/取件码仍可进入本地诊断日志，分享前应脱敏 |

## 4. 按优先级列出的问题

等级：P1 为优先修复的数据完整性/安全/核心链路问题；P2 为明确功能或可靠性问题。除特别标明“探针复现”外，以下为代码路径确认，尚未在设备上复现。

### F01 · P1：轨迹请求返回空结果后永久占用进行中状态

- 定位：`hook/CainiaoTraceFetcher.kt:96`、`hook/CainiaoTraceFetcher.kt:108`。
- `fetch()` 返回 `null` 时使用 `return@execute`，直接退出工作任务，绕过后续 `inFlight.remove(tracking)` 和 `failedAt` 更新。
- 网络失败、Cookie 缺失、解析失败等正常失败结果，会让该单号在当前进程内一直被视为正在请求。即使 `recheck=true` 也会在 `inFlight.add()` 处被挡住。
- **离线实调用探针复现**：Cookie provider 返回 `null`，任务结束后单号仍在 `inFlight`，不在失败冷却表；第二次强制复查没有再次调用 provider。没有发起网络请求。
- 建议：用 `try/finally` 保证释放进行中状态；只在真正成功投递后登记成功；对空结果记录可重试失败。补空结果、异常、投递失败、取消的回归测试。

### F02 · P2：订单批次筛选会把未处理的订单标记为已处理

- 定位：`relay/CainiaoDirectFetcher.kt:56`、`relay/CainiaoDirectFetcher.kt:116`。
- `orders.filter { it.isInTransit && askedOrders.add(it.orderId) }.take(MAX_ORDERS)` 先遍历整张列表，再截取 5 个；第 6 个起也已经加入 `askedOrders`。
- 后续同步在同一进程内再也选不到这些尚未请求物流的订单。已选中但 SSR 请求失败、或者中途风控退出的订单，也没有从集合撤回或到期重试。
- **表达式探针验证**：6 个候选产生 5 个首批目标，但集合包含全部 6 个；下一批为空。此探针验证原表达式语义，不冒充真实订单接口集成测试。
- 建议：先选取有限目标，再登记实际请求；区分待处理/进行中/成功/失败冷却，避免把失败永久记成已处理。

### F03 · P1：导出广播入口没有验证发送方，能被伪造数据与登录态

- 定位：`app/src/main/AndroidManifest.xml:117`、`relay/ExpressRelayReceiver.kt:44`、`relay/ExpressRelayReceiver.kt:165`。
- Receiver 导出且没有入口权限/消息认证；接收器直接按 action 处理数据、Cookie、身份码与看门狗上报。
- 显式组件名与所谓“私有 action”不是身份认证。普通应用可以构造相同显式广播，尝试覆盖 Cookie 缓存、污染包裹/审计数据、伪造诊断结果并触发模块后续处理。未证实能够读取原 Cookie，不应把此问题夸大成已验证的凭据窃取。
- Manifest 中“最多只能让模块多发一条快递通知”的注释已经不符合当前接收能力。
- 正向入口与 `hook/HostReceiverRegistrar.kt` 的反向入口不对称，后者已有 signature 权限保护。
- 建议：拆分不同信任来源，采用可信 IPC 身份校验或框架分发密钥的消息认证，并加字段/长度限制。不能简单给整个 Receiver 加模块 signature 权限：不同签名的合法宿主也会被挡住。需要专门的未授权发送方回归测试。

### F04 · P1：记录存储的读改写不具备原子性，会丢并发更新

- 定位：`notification/ExpressRecordStore.kt:68`、`notification/ExpressRecordStore.kt:272`、`notification/ExpressRecordStore.kt:377`、`notification/ExpressRecordStore.kt:403`。
- `upsert/enrich/setPickedUp` 分别执行 `load → 修改整张列表 → save`，没有统一锁或串行写入队列。SharedPreferences 自身线程安全不等于多步事务原子。
- 通知/广播主线程与 `CainiaoTraceFetcher` 工作线程经 `ModuleTraceFetcher.deliverLocally` 写入的是同一份记录。两者都读到旧列表时，后保存者可覆盖先保存者的新包裹、轨迹或取件标记。
- 现有 Store 测试主要验证纯合并与序列化，不能排除 Android 存储层的并发覆盖。
- 建议：由单一 Repository 串行化全部修改，或为完整读改写事务加同一把锁；恢复/清空也应纳入同一写入协调。用可控屏障测试两个并发更新都保留。

### F05 · P2：看门狗熔断后，“复位并重新启用”仍会被旧失败次数拒绝

- 定位：`hook/Watchdog.kt:50`、`hook/Watchdog.kt:60`；调用方 `hook/SystemServerHook.kt:81`。
- `forceEnabled` 只绕过 `state.disabled` 的第一道检查，没有重置 `attempt/ok/failures`。
- 真实熔断状态本来就满足 `attempt > ok` 且失败次数超限；复位后继续计算旧失败次数，又返回 `Decision.Refuse`，与 UI 承诺“重启后重新尝试”不一致。
- 建议：将显式人工复位设计为新的受监控尝试，清除旧熔断计数，同时保留新一轮失败后的保护。提取状态机并测试连续失败、正常存活、人工复位和复位后再次失败。

### F06 · P2：框架重新连接后没有补同步本地设置

- 定位：`ExpressApplication.kt:47`、`config/ExpressSettings.kt:63`。
- 框架不在线时设置保存在本地；`onServiceBind()` 只调用 `attachService()`，后者仅赋值，没有重新投影本地设置。
- 先修改设置、后获得框架连接时，UI 显示新值，而注入进程继续使用旧配置，直到下一次设置写入或显式同步。
- 建议：服务成功绑定后同步完整本地权威配置；覆盖离线修改、初次绑定、服务死亡后重连以及一次性复位标志的测试。

### F07 · P2：恢复备份只恢复磁盘数据，未恢复对应运行状态

- 定位：`backup/ExpressBackup.kt:147`、`relay/TraceCookieCache.kt:46`、`ui/ExpressMainActivity.kt:676`。
- 恢复写回 SharedPreferences 后仅通知界面刷新；`reloadAfterRestore` 重读 UI 状态，但没有刷新已经 attach 的 Cookie 缓存、重投影框架设置或启动恢复后应开启的自动轮查。
- `TraceCookieCache.attach()` 在 `restored=true` 时直接返回；恢复了另一份登录态后，当前进程仍可能使用原 Cookie。
- 关闭轮查可被正在运行的循环自行发现，但从关闭恢复成开启，不会单靠重读 UI 创建服务。框架设置即使重启模块仍受到 F06 影响。
- 建议：恢复后统一协调缓存失效、框架同步、服务/调度与 UI 刷新；恢复期间与正常写入互斥；明确部分失败的回滚策略。测试当前进程内恢复，而不只测试文件往返。

### F08 · P2：备份闹钟过早结束广播生命周期

- 定位：`backup/BackupAlarmReceiver.kt:33`、`backup/BackupAlarmReceiver.kt:37`、`backup/BackupScheduler.kt:154`。
- `goAsync()` 创建的后台线程调用 `maybeRunDue()`，但后者只是把实际备份再次提交到 executor，然后立即返回。接收器随即 `finish()`，实际文件写入可能还没开始。
- 冷启动且没有其他活跃组件时，系统在广播完成后可回收进程；当前保活边界没有覆盖备份写盘，存在备份被中断的风险。
- 建议：把 `PendingResult.finish()` 绑定实际备份的完成/失败；为长时间 SAF I/O 使用合适的持久后台任务机制。不要靠增加一个普通线程来假定进程不会被杀。

### F09 · P2：备份失败后，下次到期时间仍可能停留在过去

- 定位：`backup/BackupScheduler.kt:191`、`backup/BackupScheduler.kt:202`。
- 失败路径保留旧 `lastBackupAt`，而 `remember()` 使用 `lastAt + intervalMs` 计算下一次时间。
- 如果以前成功过、这次已经到期又失败，算出的值仍已过期；随后每次 `maybeRunDue()` 都会再次排队，违背“成功与否都向前推”的注释。
- 建议：保留最后成功时间用于展示，但用本次尝试时间计算下次重试；对过去成功/本次失败/首次失败分别测试。
- 相关可靠性提醒：`running` 在单线程 executor 内设置与清除，无法合并已经排进队列的重复任务；应在入队前做原子占位或在执行时重新检查到期条件。

### F10 · P2：通知投递失败仍占用去重名额，重复事件可能撤掉原通知

- 定位：`notification/ExpressDeliveryLedger.kt:29`、`noroot/ExpressNotificationListener.kt:128`。
- 去重表的 `claim()` 发生在实际投递前，只记录“尝试过”，没有成功确认或失败释放。
- 通知权限关闭导致第一次 `post()` 返回 false 时，免 root 路径保留原通知；但 60 秒去重窗口内相同事件再次到来，`firstDelivery=false`，拦截模式分支直接撤掉原通知，不能保证已有替代通知。
- 撤原通知分支：`noroot/ExpressNotificationListener.kt:137`；投递失败入口为 `notification/ExpressNotificationPoster.kt:55`。
- 建议：去重区分“进行中”和“已成功投递”；失败释放名额；只在已知替代通知成功时撤原通知。补权限拒绝、渠道关闭、投递异常和双链路乱序的集成测试。
- root 链路另有已提示用户的设计限制：广播交出不等于替代通知显示成功。工作模式提示不能取代最终投递确认，后续应设计明确的失败放行策略。

## 5. Lint 结果解读

| 分类 | 数量 | 解读与建议 |
| --- | ---: | --- |
| `MissingPermission`：`sendBroadcastAsUser` | 12 | 分布于宿主/system_server 广播路径。先区分实际 Context、发送 UID 与目标用户；同用户宿主路径优先考虑 `sendBroadcast`，系统路径对能力做局部说明/抑制。不要直接申请普通 APK 拿不到的系统权限。 |
| `MissingPermission`：位置/WiFi | 4 | 已有辅助权限检查和 `runCatching`，Lint 未识别所有包装；需验证权限拒绝/撤销路径，再决定拆分权限分支或局部注解，不能直接当作四个必崩点。 |
| `NewApi` | 1 | `ui/ExpressMainActivity.kt:237` 访问 API 29 的 `TaskInfo.taskId`，而最低 API 26。外层 `runCatching` 避免异常扩散，但 26–28 的隐藏最近任务功能会失效；需要兼容分支。 |
| `PropertyEscape` | 1 | 被 Git 忽略的本机 `local.properties:1` 驱动器冒号转义告警；不是已提交源码缺陷。 |

70 项警告中包括 `UseKtx` 27 项、`ApplySharedPref` 9 项、`StaticFieldLeak` 6 项，以及资源、图标、旧 SDK 判断、反射等。

需要区别处理：恢复数据使用 `commit()` 有同步落盘语义，不能批量替换成 `apply()`；应用级 Context 缓存不一定泄漏 Activity；宿主 hook 本来依赖反射，不能简单删除所有反射警告。优先处理有真实功能影响的项，再收敛剩余告警。

## 6. 代码结构评价与建议

### 已有优点

- 核心解析、格式化、身份和合并逻辑多数可独立测试；已有 512 项测试，而不是完全依赖真机试错。
- 设置键、广播字段、备份格式、作用域有集中定义或契约测试；旧数据兼容较受重视。
- `compileOnly` libxposed API 与反射宿主类型避免将宿主依赖直接打入模块。
- 核心 hook 有保护性异常处理、看门狗、通知递归防护；免 root 能力限制在 README/UI 中有说明。
- 备份恢复前写快照、加密采用 PBKDF2 + AES-GCM，备份格式与执行分离。

### 建议调整的边界

1. **拆解 UI 状态与业务编排**：`ExpressMainActivity.kt` 1730 行，混合导航、刷新、设置、恢复、诊断。优先抽出页面状态持有者和应用级操作协调，不必一次重写所有 Compose 页面。
2. **建立唯一的记录写入入口**：`ExpressRecordStore.kt` 924 行，同时承担存储、合并、修复、驿站/分组等职责。先解决事务边界，再按纯合并、编码、Repository 拆分。
3. **把共享请求引擎移出 hook 层**：`relay`/自动轮查直接依赖 `hook.CainiaoTraceApi`、`CainiaoTraceFetcher`、`TaobaoOrderApi`。这些实际上是双模式共享能力，适合 `data/network`；宿主反射适配留在 `hook`，通过接口提供 Cookie/宿主服务。
4. **统一异步状态机**：成功、进行中、失败冷却、风控退避散落在多个单例集合中，已经造成 F01/F02。用明确状态与可注入的时钟/执行器，比继续堆集合和布尔字段更容易验证。
5. **恢复是应用级操作，不是文件替换**：统一处理配置、缓存、后台服务、UI 以及并发写入；不要让各页面自行补副作用。
6. **补上集成层测试**：当前纯函数测试不能验证 BroadcastReceiver 身份、SharedPreferences 并发、前台服务与 `goAsync()` 生命周期。为这些边界引入可测试接口，再补少量 Robolectric/设备测试。
7. **修正文档与真实依赖的差异**：README 称 `core` 不读取系统时钟，但 `core/ExpressRecordRepair.kt:43` 有默认时钟参数；“hook 只做宿主适配”的目录语义也已被共享 HTTP 引擎打破。应改注释或抽离实现，而不是只写一个理想结构图。

其他加固候选：备份导入应限制总大小和 KDF 工作量（`core/BackupCrypto.kt:119` 只检查 iterations 为正数）；明文备份包含 Cookie，应明确风险；本地诊断日志的日期格式化器 `SimpleDateFormat` 需要关注跨线程调用；可注入时钟及全局请求限速器有助于风控边界测试。这些没有进行恶意大输入或真实账号压力测试。

## 7. 实际执行与证据

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
adb devices
```

- 最后一轮正常构建重新执行原有测试，排除了临时探针对最终 512 项测试统计的影响。
- 2026-10-02 再次执行上述四任务组合：512 项原有测试通过、两个 APK 构建任务完成，组合命令因 `:app:lintDebug` 的 18 个错误而失败；警告与提示仍为 70 和 1。再次查询 ADB 仍无设备，因此没有将构建成功当作真机功能验收。
- 单测报告：`app/build/reports/tests/testDebugUnitTest/index.html`；JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`。
- Lint 报告：`app/build/reports/lint-results-debug.html`、`app/build/reports/lint-results-debug.xml`。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Release APK：`app/build/outputs/apk/release/app-release-unsigned.apk`。
- 本地执行日志：`log/self-check-gradle.log`、`log/self-check-release.log`、`log/self-check-final-build.log`。
- 最新复核日志：`log/self-check-2026-10-02.log`。
- 探针结果：`log/self-check-probes.xml`、`log/self-check-probes.log`；源码 `log/audit-probes/AuditProbeTest.kt`；临时 Gradle 初始化脚本 `log/self-check.init.gradle`。
- 探针运行时曾临时加入标准测试源码目录，并通过初始化脚本给 Android 桩启用默认返回、仅在测试配置加入 libxposed API；运行后已移回 `log/`，生产构建配置未改动。要复跑需再次临时加入测试源，不能直接运行初始化脚本就认为探针会自动加载。
- 未发生需要等待的服务限速，也没有为检查调用真实淘宝/菜鸟/拼多多账号接口。

## 8. 建议修复顺序与真机验收清单

建议按以下批次处理，不要把本次自检报告误读成已完成修复：

1. F01/F02：请求状态与批次逻辑，先补可离线运行的失败路径回归测试。
2. F03/F04：接收入口认证和存储事务，覆盖未授权消息、并发与恢复互斥。
3. F05/F06/F07：看门狗复位、框架重连、备份恢复副作用。
4. F08/F09/F10：备份生命周期/重试与通知成功确认。
5. 处理真实 Lint 缺陷，将完整 Lint 与 PR 检查纳入 CI；保留有依据的局部抑制，不用全局 baseline 掩盖问题。

设备验收至少覆盖：

- [ ] Android 26–28、33+ 和目标 HyperOS/MIUI 版本：启动、最近任务隐藏、通知授权、主题/玻璃效果。
- [ ] root 单独、免 root 单独、双链路同时启用：同一通知只提醒一次，不漏记，不错误撤原通知。
- [ ] 通知权限/渠道关闭后恢复；监听断连/重连；冷启动、切后台、进程回收。
- [ ] 菜鸟/淘宝/拼多多当前宿主版本：注入、包裹发现、轨迹、商品信息、身份码及原通知跳转。
- [ ] 超过 5 个在途订单、失败重试、登录态过期/切换、风控退避；不得用高频请求“测试”真实账号。
- [ ] 连续两次看门狗失败后人工复位，以及复位后再次失败仍受保护。
- [ ] 前台与后台并发更新取件状态/轨迹，所有修改均保留。
- [ ] 当前进程内恢复另一份设置/Cookie；自动轮查开关与框架侧实际行为同步。
- [ ] 加密备份、错误密码、损坏文件、SAF 权限失效、低存储、闹钟冷启动后完成写盘。
- [ ] 定位权限拒绝/撤销、WiFi 不可用、离线、身份码过期、不同屏幕/字体大小。

完成这些运行期检查之前，只能确认“构建和现有单测通过，审计发现的问题与未验证范围已列清”，不能承诺“所有功能已验收通过”。

## 9. 修复进度（2026-10-02）

### 第一批：F01 / F02

状态：已实现并通过本机回归测试；未进行真实账号或设备验收。F03–F10、现有 Lint 问题均未在本批次修复。

| 问题 | 改动 | 验证 |
| --- | --- | --- |
| F01 请求失败后永久占位 | `core/FetchRequestLedger.kt` 统一原子占位与完成状态；`core/FetchRequestQueue.kt` 在 `finally` 内清理空结果、异常和取消；只有投递回调正常返回才登记成功 | 请求状态 8 项、队列 13 项测试，覆盖并发占位、冷却边界、复查失败、空结果、投递异常、执行器拒绝、风控与中断 |
| F02 未处理订单被记为完成 | `core/OrderDiscovery.kt` 先选取批次，再为实际查询的订单占位；完成状态由轨迹请求回执接回；失败订单进入冷却，未执行订单不占位 | 订单发现 16 项测试，覆盖超过 5 单、重复订单、空物流页、异常、风控中断、取消、异步回执及与真实请求队列类的离线联测 |

补充约束：

- 保留原有 2.5 秒请求间隔、10 分钟失败冷却和风控退避，不通过缩短限速来实现重试。
- 排队期间或等待间隔期间触发风控，尚未请求的任务释放占位；直接订单查询也在执行队列内检查退避状态。
- 优先查询从未尝试的订单，再按上次失败时间处理重试，避免失败订单反复占满前 5 个名额。
- `CainiaoTraceFetcher.requestFetch` 的 `onComplete(true)` 表示投递回调正常返回或已有成功记录，**不是**广播最终送达/数据写盘确认。宿主广播最终送达、存储失败分类及并发持久化仍需后续批次处理。
- 成功/失败/进行中更新使用同一个锁；不存在先释放占位、后登记结果的竞态窗口。
- 本批次新增 3 个不依赖 Android 的逻辑类和 3 个测试类，修改两个实际拉取入口；未改变依赖、正式测试配置或 Xposed 作用域。

实际验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*FetchRequestLedgerTest' --tests '*FetchRequestQueueTest' --tests '*OrderDiscoveryTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

- 定向测试最初 35 项通过，补充批次公平性的 2 项后，最终全量 **549 项测试通过**（原有 512 + 新增 37，失败/错误/跳过均为 0）。
- Debug / Release APK 构建成功；Release 仍未签名。
- 完整组合命令因原有 Lint 问题返回失败：18 个错误、70 个警告、1 个提示；本批修改的拉取器与新增逻辑未出现在 Lint 问题定位中。
- 执行日志：`log/fix-batch1-targeted.log`、`log/fix-batch1-validation.log`；当前 JUnit 结果位于 `app/build/test-results/testDebugUnitTest/`。
- 早期 `log/audit-probes/` 为修复前的临时复现证据，其中反射旧私有字段的探针不再适用于新实现；现在应使用正式回归测试，不再修改 Android 桩配置。
- ADB 仍无设备；所有新增测试使用假时钟、可控执行器及合成数据，没有访问真实服务，也没有发生需暂停等待的服务限速。

第一批结束时的下一批目标为 F03/F04；其后续实现与验证如下。

### 第二批：F03 / F04

状态：已完成代码修复与本机回归；未完成框架/宿主/真机端到端验收。F05–F10 及其余 Lint 问题仍待后续批次处理。

| 问题 | 改动 | 验证 |
| --- | --- | --- |
| F03 导出广播缺少发送方认证 | 安装级随机凭据由模块私有存储生成并经框架发布；所有系统/宿主回传集中到 `AuthenticatedRelaySender`；接收器先认证再调度，十类 action 使用字段、类型与长度白名单 | 凭据 3 项、存储/发布 9 项、载荷策略 9 项、真实接收入口 10 项、真实发送路径 13 项、源码契约 3 项，共新增 47 项安全回归 |
| F04 记录读改写并发覆盖 | `ExpressRecordStore` 的六个读写入口共享可重入锁；导出快照与恢复前快照/写回也加入该事务边界；恢复后的变更通知在释放锁后发送 | 12 项实际 Store/Backup 调用测试，覆盖并发新增、富化、取件、清空、读取、调和、异常释放、导出、恢复与排队写入、快照失败及凭据备份隔离 |

实现与安全边界：

- 使用 `SecureRandom` 生成 32 字节（256 位）随机凭据，严格检查编码与长度，比较时使用 `MessageDigest.isEqual`。本地文件 `reapress_relay_auth` 与框架组 `relay_auth_v1` 不进入备份白名单；恢复不能替换当前安装凭据。
- 本地 `commit()` 成功后才允许发布；每次服务绑定都重新提交框架值。失败的本地/RemotePreferences 提交可能已经改变内存，不能仅凭“内存值相同”宣布初始化或重连成功；用模拟该语义的测试验证再次提交。
- 所有入站广播都强制显式指定模块组件及包名，附带启动停止态应用的标志。普通宿主使用 `sendBroadcast`；仅 UID 1000 分支使用 `sendBroadcastAsUser`，并在 `finally` 恢复清除过的 Binder 身份。对应权限抑制只放在这一受限方法，没有全局关闭 Lint。
- 接收器在业务存储/处理前拒绝缺失、错误、旧安装凭据，认证通过后移除凭据再交给业务。错误字段类型、超长载荷、未知 action/字段、畸形 Bundle 与伪装的 PendingIntent 标记均拒绝。
- 共享凭据证明发送方持有受信注入通道能力，**不区分各宿主 UID，不是 HMAC，也不提供防重放**；root、框架和已受信宿主被控制不在本次防护范围。未宣称完成真实恶意 APK 攻击测试。
- 凭据不可用或发送异常时不退回未认证广播。系统原通知保持放行；分类拦截同样要先提交认证回传。失败日志不记录 Intent、凭据或可能携带秘密的原始异常；框架日志出口同时失败也不会向宿主抛出异常。
- 联动修正富化发送结果：失败不再被包裹采集去重表记为成功，按需轨迹回调将未提交转换为失败；Cookie 同步只有提交成功才进入 30 分钟节流。以上只确认“成功提交广播”，仍未引入接收/落盘确认协议。
- 记录锁覆盖完整读取、合并与保存，不仅锁单次 SharedPreferences 调用。并发测试使用屏障安排交错，并确认等待线程阻塞在 **ExpressRecordStore 同一个监视器**，避免把 Mockito 内部锁误认作业务互斥。
- 所有包裹记录 IO 仍由模块进程执行，锁不是跨进程文件锁；原有 `apply`/`commit` 语义未改变。本次不把全部设置/诊断存储升级成跨文件事务，也未实现恢复的部分失败回滚或运行态同步（F07 仍未完成）。恢复前快照失败时取消覆盖且不发送恢复变更通知。

测试与构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*RelayCredentialTest' --tests '*RelayCredentialStoreTest' --tests '*RelayPayloadPolicyTest' --tests '*RelayIngressTest' --tests '*AuthenticatedRelaySenderTest' --tests '*RelayContractTest' --tests '*ExpressRecordConcurrencyTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath --console=plain
```

- 最终定向测试 **62 项通过**：本批新增 59 项，加上通配符匹配到的原有 `ExpressRelayContractTest` 3 项。日志：`log/fix-batch2-targeted.log`。
- 最终全量 **608 项通过，47 个测试类，失败/错误/跳过均为 0**（第一批结束时 549 + 本批 59）。日志：`log/fix-batch2-validation.log`。
- Debug / Release 构建成功，产物仍为 `app/build/outputs/apk/debug/app-debug.apk` 与 `app/build/outputs/apk/release/app-release-unsigned.apk`；Release 未签名。
- Lint **仍未通过：7 个错误、73 个警告、1 个提示**。错误较基线减少 11 个，来自集中发送出口后移除宿主跨用户发送调用；剩余错误为定位权限检查 4 项、`SystemWakeRelay` 跨用户发送 1 项、旧系统 `TaskInfo.taskId` 1 项、本地 `local.properties` 转义 1 项。新增凭据存储沿用原生同步提交，有 2 项 `UseKtx` 风格建议；未为消除建议改成异步提交或设置 Lint baseline。
- 新增依赖仅为 `testImplementation`：Mockito 5.18.0 与 libxposed API 102.0.0，用于局部 Android 桩替代和真实发送器测试；未启用全局 `returnDefaultValues`。生产 API 保持 `compileOnly`。
- `debugRuntimeClasspath` 不含 libxposed API、Mockito、JUnit；进一步扫描 Debug/Release APK 所有 DEX 的**类定义表**，均未打包这些测试/框架 API 类（仅引用类型不算误打包）。依赖日志：`log/fix-batch2-runtime-dependencies.log`。
- `git diff --check` 通过。ADB 仍未连接设备；测试只使用合成数据、内存偏好存储和模拟框架，没有调用真实账号接口，也未出现需暂停等待的服务限速。

升级与设备待验收：

1. 安装新 APK、确认 LSPosed 作用域含系统框架及目标宿主；在框架连接正常时打开模块，让本次安装凭据先落盘并发布。
2. 重启设备更新 `system_server` hook，并重新打开宿主；仅重启模块或宿主不足以更新旧系统侧代码。清除模块数据后需重新发布新凭据，旧凭据/旧无认证发送器不会被兼容放行。
3. 真机验证系统与宿主十类回传、冷启动、服务重连、升级前后混用、清除数据后旧凭据拒绝、缺少凭据时保留原通知；结合第 8 节原有设备清单完成运行期验收。

第二批结束时的下一批目标为 F05/F06/F07；其后续实现与验证如下。

### 第三批：F05 / F06 / F07

状态：已完成实现与本机回归，未进行设备/真实账号验收。按用户要求，前两批已提交并推送 GitHub `origin/main`，提交为 `c88a023216affd9905792a85f080c911d67f146e`；本批为推送后的继续修复。

| 问题 | 改动 | 验证 |
| --- | --- | --- |
| F05 人工复位仍被旧失败计数拒绝 | 提取 `WatchdogStateMachine`；新复位开启受监控的新尝试，旧失败清零；系统侧 `WatchdogStateStore` 持久化已消费请求 ID，重复配置同步不能再次复位 | 状态机 9 项、真实文件存储/入口 8 项，覆盖连续失败、正常存活、新旧请求、重启后去重、写盘失败、旧格式、损坏文件与计数溢出 |
| F06 框架重连不补同步 | 绑定时完整重投影本地设置，校验本地/远端同步提交结果；旧服务的迟到死亡回调不清除新连接；局部更新与恢复同锁 | 13 项设置同步测试，覆盖离线修改、首绑、死亡重连、完整字段、提交失败重试、复位迁移与不重复消费 |
| F07 恢复后运行态仍旧 | `BackupRestoreRuntime` 重载 Cookie/UA/风控、投影配置、请求轮查重启、重排备份闹钟并通知 UI；写回失败回滚；相关偏好存储加入统一事务锁 | 恢复运行态 17 项、恢复并发 7 项、Cookie 缓存 4 项、真实会话 Token 路径 2 项、轮查重载 1 项 |

复现与关键边界：

- 实现前 3 个正式回归用例均失败：离线配置首次绑定未同步、复位标志消费后又被普通同步激活、已绑定进程恢复 Cookie 后仍读旧值。证据：`log/fix-batch3-reproduction.log`。
- 核对所用 libxposed API 102.0.0 源码，`getRemotePreferences` 明确说明 **hooked apps 中只读**。旧逻辑在系统进程调用 `edit()` 消费标志既不符合契约，也不能解决本地布尔值重发；新实现仅在系统自己的看门狗文件记录消费。源码归档：<https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar>。
- 每次人工点击生成新的 UUID，本地落盘后与普通设置一起提交；重连不轮换。旧布尔请求统一映射到稳定的迁移 ID，系统先消费、模块后迁移也不会获得第二次复位。复位键不进入导出/恢复前快照，导入保留本机请求，不能用旧备份重新解除保护。
- 看门狗状态先写临时文件并同步，再替换同目录目标；已有状态位置后续保持一致，防止读旧主文件、写新备用文件的不一致。无法读取或持久化保护状态时拒绝本次系统 hook，保留原通知；损坏/截断文件不会被当作全新状态。存活窗口仍为 90 秒，连续两次失败仍熔断。
- 记录、设置、Cookie、驿站规则、通知审计、UI 偏好和轮查状态的关键读写与恢复共用模块进程内事务锁；测试直接确认第二线程等待同一个监视器。恢复前快照失败则不写；提交失败会回滚所有已尝试存储，包括“返回 false 但内存已变”的失败项。
- 回滚成功不启动/停止服务或发恢复变更通知；无法确认回滚时如实报告，UI 重新读取当前状态。缓存与框架同步使用回滚后的实际值。更严格的风控退避是保护性例外，不因恢复失败而缩短。
- 恢复新 Cookie 清除旧 UA/预热 Token；Token 缓存同时按登录态匹配，不能把旧会话的预热凭据直接套到新 Cookie 上。网络测试全部替换 URL/HttpURLConnection，只执行合成响应，不访问远端。
- 轮查重载取消旧任务，并用协程互斥保证同一服务没有重叠循环；检查当前协程取消状态，而非仅检查父 scope。系统拒绝前台服务时保留已恢复数据，返回运行态警告；不会高频重试。备份目录授权、密码、定时间隔沿用本机配置。
- 风控恢复只取更晚时间；后续风险回执同样不能把较长退避缩短。风控持久化失败时请求入口安全停止，不借宿主广播绕过该状态。
- 这不是跨文件崩溃安全事务：断电/进程被杀发生在多文件写回中间仍依赖恢复前快照；已经发出的网络请求不会被回滚，迟到回执仍可能作为后续写入。锁等待、SAF、服务启动、真实开机保护仍需真机测试。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SettingsSyncTest' --tests '*WatchdogStateMachineTest' --tests '*WatchdogStateStoreTest' --tests '*BackupRestoreRuntimeTest' --tests '*RestoreConcurrencyTest' --tests '*TraceCookieCacheTest' --tests '*CainiaoSessionTokenTest' --tests '*AutoWatchReloadTest' --tests '*ExpressRecordConcurrencyTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

- 定向 **73 项通过**（本批新增 61 + 原有记录并发 12）；日志：`log/fix-batch3-targeted.log`。
- 全量 **669 项通过，55 个测试类，失败/错误/跳过均为 0**（第二批 608 + 本批 61）；Debug / Release 构建成功，Release 仍未签名。
- Lint 仍未通过：**7 个错误、60 个警告、1 个提示**。7 个错误与第二批相同；未添加全局抑制或 baseline。组合日志：`log/fix-batch3-validation.log`。
- 未新增生产或测试依赖。`git diff --check` 通过；ADB 无设备，所有新增测试使用临时目录/合成数据，未访问真实账号与平台接口。

后续继续 F08/F09/F10：备份任务生命周期、失败重试调度与通知投递成功确认。尚不能宣称“所有功能已修复并验收”。

### 第四批：F08 / F09 / F10

状态：已完成本批实现与本机回归，未进行设备/真实账号验收；第三、四批改动均未新增提交或推送。

接续基线：第三批全量 669 项通过，Debug / Release 构建成功；Lint 仍有 7 个错误，不能把组合命令的失败解释为单测或构建失败。第三批改动保留在工作区，本批不回退或重新提交已有改动。

先复现再修复：工作区已有 `BackupRetryTest` 与 `NotificationFailureTest` 两个正式回归用例；前次执行日志 `log/fix-batch4-reproduction.log` 记录 **2 项执行、2 项失败**，不计入第三批的 669 项通过数。

- F09：调用真实 `BackupScheduler.backupNow`，过去成功、本次因未设置加密密码而失败，最后成功时刻保留，但下次到期仍在过去。
- F10：调用真实免 root 监听入口，同一原通知连续到达两次，替代通知未成功投递，第二次仍调用撤销原通知。这里未配置 NotificationManager，复现的是不可投递路径，不冒充设备通知权限测试。
- 本次重跑仍为 **2 项失败**，分别是下次重试必须晚于当前尝试的断言失败、以及监听器不应撤原通知的 Mockito 验证失败。证据：`log/fix-batch4-reproduction-rerun.log`。修复后原始两用例转绿。

实现与回归范围：

| 问题 | 改动 | 验证 |
| --- | --- | --- |
| F08 广播生命周期未覆盖实际写盘 | 闹钟仅提交平台 JobScheduler，非导出广播与需 `BIND_JOB_SERVICE` 的 JobService 配合；只有实际工作结束才 `jobFinished`，无需新依赖 | 真实 Receiver/JobService 入口 10 项，覆盖提交去重、首次排期、成功/失败完成、排队取消、关闭重查、读存储异常与执行器拒绝 |
| F09 失败重试仍停在过去及重复排队 | 保存独立 `lastAttemptAt`，先同步落盘冷却再做备份；结果按完成时刻加间隔，保留最后成功时间；入队前原子占位覆盖手动/自动/定时；闹钟对齐新到期时间 | BackupRetry 12 项、任务闸门 3 项，覆盖原始复现、首次失败、实际写文件失败/成功、自动十分钟冷却、提交失败、排期溢出、闹钟锚点与设置竞态 |
| F10 未成功也占用去重并撤原通知 | 两入口共用成功确认账本；进行中不代表成功，失败/异常释放；投递前检查权限、应用开关、渠道/渠道组；重复事件撤销前查询同一事件替代通知是否仍活跃 | 监听/真实发送器/认证 Relay 17 项、账本 8 项，加原有键语义 4 项；覆盖 SDK 26/35 分支、权限拒绝后恢复、渠道关闭、异常、双链路顺序与进行中重入 |

关键边界：

- 定时备份不再依赖 `goAsync` 里嵌套普通 executor 保活。系统拒绝安排 Job 会记错并由后续闹钟/打开模块再尝试；不回退到无生命周期保障的广播线程。调度仍不精确，受 ROM/Doze/系统配额影响，未宣称能强行保活或准点完成。
- `onStopJob` 后排队但未开始的工作不执行，迟到完成不调用已停止任务的 `jobFinished`；已经进入的同步文件/SAF IO 不强制中断，进程终止仍可能留下不完整文件。持久化尝试时刻用于避免立即重复，**尚未实现备份文件原子替换、SAF 部分文件清理或崩溃后完整性恢复**。有变更去抖仍是尽力而为的进程内任务。
- 备份配置自己的锁不覆盖耗时文件 IO；结果只合并运行字段，不能把旧快照里的目录、密码、保留数或开关重新覆盖回去。UI 保存配置保留最新尝试/成功状态，同步提交失败提示错误；`commit=false` 仍可能改变内存，本次不声称磁盘事务回滚。
- 结果保存后重排闹钟，避免备份完成略晚于原闹钟锚点，导致下次闹钟早于 `nextDueAt` 而再多等一整轮；普通重排沿用已存到期时间，不随每次打开页面向后漂移。
- 通知成功账本时间窗从实际 `post` 成功返回计起，为 60 秒、最多 64 个成功键；进行中不受成功缓存淘汰影响。正文使用完整键而非 Java 32 位哈希，避免不同文本同哈希被误判成同一事件。
- 重复事件不再仅凭历史成功撤销原通知：还须查询当前活跃的相同通知 ID、无 tag、同事件标记。通知已清除、ID 被其他事件覆盖、权限撤销或查询异常，均保留原通知。初次 `notify()` 无异常仍只是系统接受投递，不是 ROM 展示回执；查询与撤销也不是跨系统原子操作。
- root 的 system_server 仍在广播交出后决定是否拦截，**未增加模块到系统侧的端到端确认协议**；分类“直接吞掉”属于用户明确选择的另一分支，本批不改变其语义。以上修复不能等同所有 root/ROM 场景均不会丢提醒。
- Android 测试用局部 Mockito 替代 Binder/通知/调度 API，真实调用业务入口；权限/渠道用合成返回值，SDK 版本仅在 JVM 桩中临时调整并恢复。没有真实账号调用，不把这些测试当作设备通知展示或系统进程保活验收。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BackupRetryTest' --tests '*BackupTaskGateTest' --tests '*BackupJobServiceTest' --tests '*DeliveryLedgerTest' --tests '*NotificationFailureTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

- 最终定向 **54 项通过**（本批新增 50 + 原有账本键语义 4）；日志：`log/fix-batch4-targeted.log`，分类计数：`log/fix-batch4-targeted-count.txt`。
- 最终全量 **719 项通过，60 个测试类，失败/错误/跳过均为 0**（第三批 669 + 本批 50）。逐类统计：`log/fix-batch4-full-test-count.csv`，汇总：`log/fix-batch4-full-test-summary.txt`。
- Debug / Release 构建成功；产物为 `app/build/outputs/apk/debug/app-debug.apk` 与 `app/build/outputs/apk/release/app-release-unsigned.apk`，Release 仍未签名。
- Lint **仍未通过：7 个错误、60 个警告、1 个提示**，与第三批相同；错误仍是定位权限 4、跨用户发送 1、旧系统 API 1、本机 properties 转义 1。未新增全局抑制、baseline 或依赖。组合命令日志：`log/fix-batch4-validation.log`。
- `git diff --check` 通过；`adb devices` 仍无设备。下一阶段可收敛这 7 项 Lint 错误，并在设备上验证冷启动/Doze/Job 停止、失效 SAF 授权、通知权限/渠道开关与双链路；备份原子写入和 root 端到端确认仍是明确的后续可靠性工作。

### 第五批：Lint 错误初步处理

范围：处理第四批留下的 7 项 Lint 错误，不扩大到清理全部 60 项警告；保留第三、四批工作区改动，不新增提交或推送。

| 原错误 | 处理 | 回归 |
| --- | --- | --- |
| 定位/WiFi 权限检查 4 项 | 缓存位置读取集中到 `LocationAccess`，保留粗/精确权限差异，敏感调用显式处理 `SecurityException`；精确采集与 WiFi 读取重查权限，撤销时返回缺权限；当前位置超时/协程取消时取消平台请求 | `LocationPermissionTest` 16 项，覆盖未授权、仅粗定位、读取时撤销、采集中撤销、缺 provider、SDK 26 缓存路径、SDK 35 新定位/超时/取消/迟到回调及 WiFi 失败与过滤 |
| 系统代发跨用户权限 1 项 | 注册与接收限制 system UID，接收仅认固定 action；固定唤醒 Intent 在清除 Binder 身份后发给进程所属用户，`finally` 恢复身份；说明性 `MissingPermission` 抑制只放在这一受限私有方法 | `SystemWakeRelayTest` 10 项，覆盖普通 UID 拒绝、SDK 26/35 注册参数、signature 权限、幂等/重试、错误 action、固定目标、身份顺序、发送/日志同时失败与系统 Context 优先；原有认证发送器 13 项一并重跑 |
| 低版本 `TaskInfo.taskId` 1 项 | 提取 `RecentTaskCompat`，API 29+ 使用 `taskId`，26–28 使用旧 `id`；单个任务信息失效不阻断其他任务匹配，只有单任务才允许兜底，避免误隐藏其他卡片 | `RecentTaskCompatTest` 7 项，覆盖 SDK 26/28/29/35、任务消失、缺信息、空列表及多任务歧义 |
| 本机 properties 转义 1 项 | 将 SDK 路径写为 `C\:/Users/...`；只修改 Git 忽略的 `local.properties`，不把本机路径加入仓库 | Gradle 正常定位 SDK，Lint 不再报 `PropertyEscape` |

关键边界：

- 定位告警原本已有外层检查与通用异常捕获，本批不是声称发现或修复了 4 个必崩点；主要明确调用边界、补回归，并在取消时释放定位请求。驿站采集仍要求精确定位，身份码附近排序仍接受粗定位，不扩大授权或主动联网。
- 系统 UID 检查限定执行身份，不代替发送者认证。发送者限制仍由 `HostReceiverRegistrar` 的模块 signature 权限承担，测试验证注册参数；没有在设备上模拟恶意发送者，也没有给普通 APK 申请系统跨用户权限。目标仍是 system_server 所属用户，本批不扩展工作资料/多用户路由。
- Android 版本和权限/Binder/定位结果使用 JVM 桩与 Mockito 模拟；未宣称在 Android 8–9 真机运行过任务卡片功能，平台回调/权限对话框、WiFi 硬件、ROM 唤醒效果仍需设备验证。未新增生产/测试依赖，未加全局 Lint 抑制或 baseline。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*RecentTaskCompatTest' --tests '*LocationPermissionTest' --tests '*SystemWakeRelayTest' --tests '*AuthenticatedRelaySenderTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

- 定向 **46 项通过**（新增 33 + 原有认证发送器 13）。日志：`log/fix-batch5-targeted.log`；计数：`log/fix-batch5-targeted-count.txt`。
- 全量 **752 项通过，63 个测试类，失败/错误/跳过均为 0**（第四批 719 + 本批 33）。逐类统计：`log/fix-batch5-full-test-count.csv`；汇总：`log/fix-batch5-full-test-summary.txt`。
- **Debug / Release / Lint 均通过**，组合命令结果为 `BUILD SUCCESSFUL`；日志：`log/fix-batch5-validation.log`。APK 路径不变，Release 仍未签名。
- 最终 Lint 为 **0 个错误、62 个警告、1 个提示**；7 个错误已消除。第一轮仅生产改动检查为 60 个警告；加入测试后，`SystemWakeRelayTest` 的 SDK 26 旧版 `registerReceiver` 验证/桩配置新增 2 项 `UnspecifiedRegisterReceiverFlag`，Lint 无法检查 Mockito 模拟过滤器，未为压低数字扩大抑制。报告：`app/build/reports/lint-results-debug.html`。
- `git diff --check` 通过，ADB 仍无设备。本批完成本机初步处理，不等于硬件、系统注入、多用户与 ROM 行为已验收。

### 第六批：警告核查与低风险清理（2026-10-03）

接续基线：第五批 752 项测试通过，Lint 为 0 错误、62 警告、1 提示。本批按实际风险处理，不以所有警告归零为目标；未升级 Gradle、依赖或 targetSdk，未改动已有图标像素/比例，也未提交或推送。

已完成的核查与修复：

| 类别 | 改动与边界 | 验证 |
| --- | --- | --- |
| 时间格式与并发 | 4 处静态 `SimpleDateFormat` 改为共享不可变 `DateTimeFormatter` 的 `LocalTimeFormatter`；每次调用重读 Locale/时区，避免设置变更后仍使用旧值，也消除日志工作线程和 UI 共享可变格式器的问题 | 4 项测试：语言/数字样式变化、时区变化、8 线程 6000 次格式化、实际日志/审计入口 |
| Context 生命周期 | `HostContextHolder` 发布前归一化为 application Context；无法取得时不缓存 Activity，也不丢待执行监听器。`TraceCookieCache` 已只存 `applicationContext`；`SystemContextHolder` 仅来自 NMS 的系统 Context/ActivityThread 系统入口，不能机械替换成普通应用 Context | 8 项真实入口测试，覆盖宿主缓存/回调、Application 启动期、缺应用 Context、Cookie attach/restore 与 NMS Context 来源；6 项静态引用警告保留，未全局抑制 |
| WebView 安全与释放 | 新增 `LoginWebViewPolicy`：保留登录所需 JS/DOM storage；禁止 file/content 访问、明文混合内容与非 HTTPS 导航，不启动原生 scheme；启用 Safe Browsing，证书错误取消，不新增 JS bridge；AndroidView 释放与弹窗退出均销毁视图，并防迟到释放误伤新视图 | 12 组 URL 策略、7 项实际配置/回调/释放测试。JS 告警只在已审查配置方法局部说明性抑制，不宣称消除第三方页面风险 |
| 系统备份/迁移 | 保留 `allowBackup=false`，增加旧版 `fullBackupContent` 与 Android 12+ `dataExtractionRules`，云备份和设备迁移分别排除全部 9 类域，包括设备保护存储；不影响模块自己的手动导出/恢复 | 3 项 XML 契约测试；AAPT2 检查实际 Debug APK 的合并 Manifest 及编译后排除规则均已打包 |
| 低风险清理 | 清理通知/轮查的 API 26 以下分支，FUSED_PROVIDER 按 API 31 门禁；整数 Compose 状态改为 `mutableIntStateOf`；移除重复 Activity label、未引用主题；消除中文拆行拼接提示；图标 XML 移至无冗余限定目录，像素保持不变 | 原有通知 SDK 26/35、位置 SDK 26/35 回归；Debug/Release 资源编译与 Lint |
| KTX 风格 | 仅转换 3 处 URI/Drawable 与 7 处原本使用 `apply()` 的写入；备份、凭据、恢复等同步提交与返回值检查维持原样。修正旧注释：`apply()` 同样立即更新内存，区别是磁盘提交时机 | 3 项实际存储入口测试确认仍调用 `apply()` 而非 `commit()`；原有 12 项记录并发和 7 项恢复并发回归通过 |

保留的警告（最终 39 项）：

| 数量 | 类型 | 处置依据 |
| --- | --- | --- |
| 14 | `UseKtx` | 剩余均是同步提交/编辑器路径的风格建议；KTX `edit(commit=true)` 不返回磁盘成功值，尤其不能替代凭据发布和恢复的提交检查。本批统一保留，避免风格清理改变持久化语义 |
| 6 | `StaticFieldLeak` | 已核查 3 个进程级 Context 持有器并补测试；保留检测提示，不能把“不持有 Activity”扩大成整个应用绝无泄漏 |
| 2 | `ApplySharedPref` | 保留通知清空/驿站规则的同步落盘边界；不为了告警数改成异步，提交失败处理也不在本批重构 |
| 3 | `PrivateApi` | 宿主/系统 Context 反射兜底及小米能力探测依赖平台内部接口；已有失败降级，仍需 ROM/系统版本实测 |
| 1 | `ExportedReceiver` | 合法宿主需要导出入口，接收前已有框架凭据认证和载荷检查；不改成仅允许模块签名而挡住宿主，也不删除认证 |
| 2 | `UnspecifiedRegisterReceiverFlag` | Android 26 旧 API 的测试验证/桩配置，Lint 无法识别模拟过滤器；生产 API 33+ 分支已有导出标志与 signature 权限 |
| 5 + 1 | 图标形状、缺单色图标 | 现有图标是用户提供的成品栅格，自适应背景故意铺满；单色版需要独立形状设计与视觉验收，不能将全透明前景直接当单色图标 |
| 1 | `ObsoleteSdkInt` | 唯一残留是本机旧空 `mipmap-anydpi-v26` 目录；XML 已迁往 `mipmap-anydpi`，Git 不跟踪空目录。尝试移除空目录被执行环境策略拒绝，未绕过，也未通过抑制隐藏该提示 |
| 1 + 2 + 1 | Gradle、依赖、targetSdk 版本建议 | 需独立升级与兼容验证，不混入此次可靠性清理 |

安全与验收说明：

- Android 官方文档明确指出部分 Android 12+ 厂商设备即使 `allowBackup=false` 仍可能允许 D2D 迁移，因此分别写出云/设备迁移规则；只承诺标准 Android 规则配置，不保证厂商私有工具、root 复制也遵循。参考：<https://developer.android.com/identity/data/autobackup>；本地归档 `log/android-autobackup-reference.html`。
- WebView 仍允许任意正常 HTTPS 跨域页面，以免直接切断淘宝跨域安全验证；没有域名白名单或第三方页面可信证明。HTTP、外部应用 scheme、文件选择等受限流程可能影响部分登录验证，真实账号/验证码链路未验收；不通过放开文件访问或证书错误来兜底。
- 登录视图 `onRelease` 销毁，整个弹窗退出再兜底；重建前释放旧视图。CookieManager 的登录数据不因销毁 WebView 自动删除，本次未清除用户登录态。
- 首次 Lint 在新增文件分析时触发 Kotlin FIR/ExperimentalDetector 内部异常，日志保留在 `log/fix-batch6-initial.log`；后续完整重跑通过，未按工具建议关闭 UnsafeOptIn 检查或增设全局 baseline。
- 最后检查时 ADB 已发现一台 API 37 设备。本次仅查询连接与 SDK，没有安装覆盖、触碰登录态、主动迁移或做 UI/ROM 验收；不能沿用上一批“无设备”的结论，也不能把连接成功写成验收通过。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LocalTimeFormatterTest' --tests '*LoginWebViewPolicyTest' --tests '*LoginNavigationTest' --tests '*PlatformBackupPolicyTest' --tests '*ContextLifetimeTest' --tests '*AsyncPreferenceSemanticsTest' --tests '*LocationPermissionTest' --tests '*NotificationFailureTest' --tests '*ExpressRecordConcurrencyTest' --tests '*RestoreConcurrencyTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

- 最终定向 **89 项通过**（新增 37 + 原有 52）。日志：`log/fix-batch6-targeted.log`；分类计数：`log/fix-batch6-targeted-count.txt`。
- 最终全量 **789 项通过，69 个测试类，失败/错误/跳过均为 0**（第五批 752 + 本批 37）。逐类统计：`log/fix-batch6-full-test-count.csv`；汇总：`log/fix-batch6-full-test-summary.txt`。
- **Debug / Release / Lint 均通过**，组合日志：`log/fix-batch6-validation.log`。最终 Lint **0 错误、39 警告、0 提示**，比上一批少 23 项警告并消除唯一提示；分类统计：`log/fix-batch6-lint-count.csv`。
- APK 路径不变，Release 仍未签名；包内规则检查日志：`log/fix-batch6-packaged-manifest.txt`、`log/fix-batch6-packaged-backup-rules.txt`。`git diff --check` 通过，无新增依赖和全局抑制。

### 安装与提交记录（2026-10-03）

- 按用户要求，将第六批已验证的 `app/build/outputs/apk/debug/app-debug.apk` 安装到当前连接的 API 37 设备；`apksigner verify` 通过，使用 Android Debug 证书。
- 使用 `adb install -r` 覆盖安装，返回 `Success`；包版本仍为 `0.1.0` / versionCode 1，最后更新时间变为 `2026-10-03 19:50:33`，首次安装时间保持 `2026-09-26 04:58:53`。未卸载、未清数据、未自动重启，也未将安装成功写成所有功能真机验收通过。
- 已安装 APK 的 SHA-256：`24628A7742C1ABBBD1C993A162F84DBA0A86D103173D2D1E3C437DA4D4E13734`。本机安装日志：`log/install-batch6.log`；安装前后包信息和哈希也只保留在 Git 忽略的 `log/`。
- 本次提交汇集第三至第六批源码、资源、正式测试、README 与审计报告；各批“未提交/推送”的表述是当时状态。APK、签名材料、本机 `local.properties` 和日志不进入源码仓库。
- 更新系统与宿主 hook 仍需重启设备，并在框架连接正常时打开模块；安装未主动操作登录态、触发恢复/迁移或做完整 UI 验收。

### 第七批：菜鸟取件码后台联网同步（待真机验收）

接续基线：提交 `f70e9dbf2f81ca3dd856d9b0d3181a831c372705`，此前 789 项测试通过。本批按用户要求使用提供的 MT MCP 分析菜鸟 APK，再实现受限适配；未重新安装、提交或推送，未主动请求真实账号接口。

#### MT 分析依据

- MT MCP 0.2.0，复用工作区 `mgiapprk`，只读分析 `cainiao-8.11.923.apk`，包名 `com.cainiao.wireless`、versionCode 475，与 ADB 查询安装版本相符。分析调用为 APK search/read/xref/outline，不修改或重打包菜鸟，不读取用户数据库、Cookie 或真实取件码；工具响应与 Smali 仅保留在被 Git 忽略的 `log/mt-*`。
- `homepage.presenter.c#att()` → `components.init.a#afe()` → `cdss.c#b(Topic[])` → `core.facade.b#Xh()`，这是首页及 `JsHybridDoradoModule#refreshDoradoTopic` 的真实联网路径，而旧模块仅调用 `HybridDoradoApi#query`。
- `cdss.core.f#sendData` 在 `cdss.d.bNe=false` 时明确返回“Send data on background is not support”；因此直接反射首页 refresh 方法并不能保证后台有效。本实现不改该全局前台标志，不调用 `enterForeground`，不启动 Activity。
- 包裹主题由 `CDSSConstantHelper#Fo/Fp` 返回 `package_list_v4` / `1.3`；实际请求采用当前 TopicModel 中的版本与本地游标，不硬写服务端游标。`protocol.a#g(List,true)` 构造 sequence 请求，`f(List,true)` 构造 data 请求，`dorado...mtop.c#vZ` 做宿主新版协议转换。
- `MtopCainiaoNewDoradoClientRequestServiceRequestRequest` 对应 API `mtop.cainiao.pcs.app.user.package.sync` / `1.0`，需要宿主会话。通过 `CNMtopBusinessUtils.obtainCNMtopBusiness` 保留宿主 MTOP 实例、签名与 WUA，独立动态代理接收结果；不用 H5 仿签，也不替换 `DataSyncFinishListener`（该注册表每个 topic 只有一个槽位）。
- `MtopBusiness` 默认 `showLoginUI=true`，新请求显式设为 false；`setNeedAuth(..., false)` 反而会把 `needAuth` 置 true，因此不调用它，而是对 `isNeedAuth()!=false` 的实例直接拒绝。当前任务不弹授权页、不触发用户交互式登录；登录过期返回失败。
- 返回格式由 `SequenceResponse` / `DataResponse` 和 `DownwardSync` 核对：`content.response_type` 为 1/2，`response_content` 包含 `user_id/data`；数据页经宿主 `DownwardSync#bQ` 原有处理器写入，再检查 TopicModel 本地游标已更新。源码证据：`log/mt-home-refresh.smali`、`log/mt-send-data.smali`、`log/mt-new-request.smali`、`log/mt-mtop-util.smali`、`log/mt-downward.smali`、`log/mt-data-response.smali`。

#### 实现与保护

- `PackageSyncSession` 限制一次 sequence 查询、最多 3 页增量，检查账户、schema、游标推进和落库结果。请求前再次检查账户和退避；构造分离的 TopicModel，不手改宿主锁、初始化标志或真实游标；库/模型不就绪最多等待 8 秒，不强制初始化/重建。
- `CainiaoPackageSyncClient` 使用宿主 ABI 反射调用；开始网络请求调度到主线程，后台工作线程有 12 秒等待预算，超时取消，迟到主线程任务不再启动请求。只接收首次终态回调；风险/失败回调不盲重试，响应最大 4 Mi 字符；账户、主题或 schema 不符不应用。
- `CainiaoPackageSync` 在入队前原子占位，仅菜鸟主进程的受 signature 权限保护入口可触发；适配限制 8.11.923。模块/宿主分别持久化 5 分钟尝试冷却，失败 10 分钟，风险至少 1 小时；宿主完成 ID 缓存只重放结果，不重复联网。冷启动重复广播沿用同一个 ID（0/3/8 秒），模块 75 秒未收到匹配回执则显示超时。
- 模块退避包含已落盘轨迹风控；取件码风险回执反向更新轨迹风控，采用更晚时间，并与恢复共用记录事务锁。同步提交失败不假装落盘成功，保留进程内保护且显示提示；进程被杀后磁盘失败的保护仍不能保证，未宣称跨进程事务。
- 宿主完成后重新读取包裹表，最多 500 行；行结构畸形/超量拒绝宣布完整完成。用线程局部标志避免此次主动读取又被旧 query hook 重复发送；快照标志覆盖含码/无码行，接收端不再逐行启动轨迹请求。普通通知/轨迹路径不变。
- 确认的取件码附独立 `pickupCodeObservedAt`，以同步开始时刻作为保守新鲜度边界；完整运单号匹配才替换非空旧码，旧码记入 `previousPickupCode`。新鲜度落盘且兼容旧数据；旧快照、旧通知和旧尾号认领不能把码改回去，后到但确实更晚的通知可更新并推进新鲜度。`showAuthCode=false` 或空码不透出/不擦除已有记录。
- 新 action/extra 加入 Relay 字段白名单：同步回执必须带合法 UUID 和有限状态名，身份验证仍先于业务读取；状态回执只接受当前活动 ID，普通缓存回执不再冒充联网成功。

#### 明确未完成的验收

- **不是已经证实真机可用的发布结论**：本轮未访问实际用户包裹同步 API、未重新安装或重启菜鸟。MT 所见的 APK ABI 不能证明当前热修复、服务端风控、后台环境、分页上限及 ROM 行为都可用。需要下一步有限次数的设备验证。
- `synced` 的严格含义是“本次服务端目标游标已与宿主库一致，回传已提交”；Android 广播没有模块逐行落盘 ACK，不能保证页面已接齐全部记录。迟到数据即使状态已超时仍可能补入，模块下次查询可再次获得最新快照。
- `DownwardSync` 自身可能通知宿主监听器并调度后续工作；3 页限制约束本适配器发起的请求，不能声称限制了宿主所有后台网络。不会主动把 sequence 响应交给全局同步引擎，避免打开全主题自动同步。
- 本次只支持已登录且已有同步 schema 的菜鸟进程，不保证从未打开/初始化过菜鸟也能首次建立数据库。服务器未提供取件码时不生成；同一运单号多账号碰撞、宿主切换账户与下行应用之间的极短竞态、同步过程中宿主被杀等仍需真机观察。
- 冷却/状态文件 `reapress_package_sync` 不进入模块备份白名单。首次安装新模块后必须让菜鸟加载新 hook；未修改系统侧 hook，不需要为了本功能重启系统，但仅重开模块不足以更新已存活的菜鸟进程。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PackageSyncSessionTest' --tests '*PickupCodeRefreshTest' --tests '*CainiaoPackageSyncClientTest' --tests '*CainiaoPackageSyncGateTest' --tests '*HostRefreshRequesterTest' --tests '*PackageSyncContractTest' --tests '*RelayPayloadPolicyTest' --tests '*ExpressRelayContractTest' --tests '*ExpressRecordConcurrencyTest' --tests '*RelayIngressTest' --tests '*AuthenticatedRelaySenderTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
```

- 最终定向 **112 项通过**（本批新增 65 + 原有 47）；新增覆盖游标分页 8、取件码新鲜度与存储 14、真实反射调用/合成宿主 ABI 19、宿主入队/退避 8、模块请求/回执 12、源码边界契约 3、认证快照标志 1。日志：`log/fix-batch7-targeted.log`，计数：`log/fix-batch7-targeted-count.txt`。
- 最终全量 **854 项通过，75 个测试类，失败/错误/跳过均为 0**（第六批 789 + 本批 65）；统计：`log/fix-batch7-full-test-count.csv`、`log/fix-batch7-full-test-summary.txt`。
- **Debug / Release / Lint 均通过**，日志：`log/fix-batch7-validation.log`。Lint 为 **0 错误、43 警告、0 提示**；比第六批增加 4 项同步偏好提交的 `UseKtx` 建议，保留同步提交结果检查，不为告警数量改成异步或增加 baseline。
- APK 已构建至原路径，Release 仍未签名；本批尚未安装到设备，也未触发真实网络请求。`git diff --check` 通过；MT 原始 Smali、分析调用及测试日志不进入仓库，README 已注明“待真机验收”。

### 第七批安装与提交记录（2026-10-03）

- 按用户要求，将已通过 854 项单测和 Debug/Release/Lint 验证的 Debug APK 覆盖安装到当前设备。`apksigner verify` 通过，`adb install -r` 返回 `Success`；未卸载或清除数据。
- 包版本仍为 `0.1.0` / versionCode 1，最后更新时间为 `2026-10-03 21:46:03`，首次安装时间仍为 `2026-09-26 04:58:53`。APK SHA-256：`CC97E77F51D51227CE914F28DF251792D413229ADCB1B3AF8592DDC98831AF50`。
- 安装日志及前后包信息保留在 Git 忽略的 `log/install-batch7*.log`；APK、MT 原始分析和本机配置不纳入源码提交。此前“未安装/未提交”的文字保留为开发阶段状态。
- 本次未自动重启菜鸟或启动模块，未主动发起真实账号同步；安装不等于功能验收。设备上仍需让菜鸟主进程重新加载 hook，再从模块首页检查后台同步结果。
