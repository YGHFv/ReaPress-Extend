# 项目结构与功能自检报告

初查日期：2026-09-29；复核日期：2026-10-02。检查基线：`8a2d901`，复核时业务源码、正式测试及构建配置均未变化。

第 1–8 节保留修复前的审计结论与基线代码定位，不代表当前修复状态；后续改动、测试结果及未完成事项见第 9 节。

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

下一批优先 F05/F06/F07：看门狗人工复位状态机、框架重连后的完整配置投影、恢复后的缓存/服务/调度协调；之后处理 F08/F09/F10 与剩余 Lint 实质缺陷。当前不能表述为“所有功能已修复并验收”。
