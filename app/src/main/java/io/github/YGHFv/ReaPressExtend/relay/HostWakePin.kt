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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 「叫醒菜鸟」—— 它的进程不在时把它拉起来，好让我们的 hook 有机会装上。
 *
 * ## 为什么需要它
 *
 * 身份码只能由菜鸟进程里的 hook 用宿主自己的会话取（见 [IdentityCodeFetcher] 的说明），
 * 包裹表自查也在宿主进程里跑。而模块和菜鸟之间的那条索取广播是**动态注册**的 ——
 * 菜鸟进程不在，那条广播就没人接，被系统直接丢掉（不报错、不回执）。
 * 用户此时看到的就是「连不上菜鸟」「刷了跟没刷一样」。
 *
 * ## 为什么是「广播到清单接收者」而不是别的
 *
 * 一个 Android 进程的启动有几条路，各自的代价不同：
 *
 * - **广播投递给 `exported="true"` 的清单接收者**：投递本身就要把目标进程拉起来，
 *   这是 AOSP 的既有行为，不需要任何权限。选中的这条最干净（见下）。
 * - **绑服务（bindService）**：同样能拉起进程，还能拿到「成功 / 失败」的返回值，但会真的
 *   创建那个 Service 实例 —— 菜鸟对外导出的服务全是 ACCS / 推送 / 广告 / 下载这些重家伙，
 *   叫醒的代价是把它们一起点着。
 * - **访问导出的 ContentProvider**：菜鸟的 provider **全部 `exported="false"`**
 *   （2026-09-26 逐个核过清单），这条路根本不存在。
 * - **起 Activity**：会跳到菜鸟界面，不能叫「静默」。
 *
 * ## 为什么挑这个接收者
 *
 * `com.cainiao.wireless.components.agoo.NotificationDismissReceiver`（菜鸟 8.11.923，vc 475）：
 *
 * - 清单里 `exported="true"`、**没有任何权限门槛**，我们能直接投；
 * - `onReceive` 的**全部逻辑**是：读 action、读 `StringExtra("id")`，
 *   只有 `action 匹配 且 id 非空` 才去真正撤消息 —— 我们不带任何 extra，
 *   于是 `TextUtils.isEmpty(id)` 直接短路，**一行副作用都不产生**；
 * - 整段包在 `try/catch (Exception)` 里，写日志了事，不会把菜鸟弄崩。
 *
 * ⚠️ 也就是说：**我们只借它的「进程会被拉起来」这一个副作用，不借它的功能。**
 * 这一点下次换接收者时必须重新核一遍 —— 清单里那 18 个可外部触达的接收者，
 * 大多数带真副作用（`AgooBusinessReceiver` 会解析推送 JSON、`UpdateAppReceiver` 会去查更新、
 * widget 那几个会刷小组件）。
 *
 * ## 备选（这招没用时再上）
 *
 * `com.taobao.accs.ServiceReceiver` + `com.taobao.accs.intent.action.START_FROM_AGOO`：
 * 那是 ACCS 自己「被推送唤醒起点」的正门，唤醒成功率更高，代价是会把 ACCS 通道一起拉起来，
 * 而且它的实现是丢到线程池里跑的（`BaseReceiver#onReceive` → `ReceiverImpl` + `execute`），
 * 我们伪造的 intent 若让它抛异常，未捕获异常会**带走整个进程**。所以先不用它。
 *
 * ## 两条投递路径：直投 + 借 system_server 的身份代发（2026-09-27 起两条都发）
 *
 * 上面那条「广播能给 `exported` 的清单接收者拉起进程」是 AOSP 行为，但 **ROM 可以按发送方
 * 把它拦掉**。2026-09-27 在 HyperOS 上把三条路都试了一遍：
 *
 * | 发送方 | 结果 |
 * |---|---|
 * | 模块自己 `sendBroadcast`（直投） | **被拦**：`am kill` 后打开模块，12 秒后宿主进程数仍是 0 |
 * | 交给 AlarmManager，system_server 投递 | **也被拦**：`dumpsys alarm` 里那记闹钟确实触发了（`*walarm*:com.cainiao.wireless.notification_dismiss`），宿主照样没起来 |
 * | system uid 投同一条 intent | **能过**：8 秒内宿主进程数 3 |
 *
 * 闹钟那条为什么也不行：PendingIntent 的广播在 AMS 眼里**仍算它的创建者发的**
 * （AOSP `PendingIntentRecord.sendInner` 把创建者的 uid 当发送方传下去），所以它和直投
 * 是同一个身份，换投递者没有意义。**要换的是发起方**——
 * 于是补上第二条：把这条 intent 请 system_server 替我们发（[ACTION_WAKE_REQUEST] →
 * `SystemWakeRelay`），发送方是 system uid，ROM 那套「第三方应用想拉起别人」的判据够不着它。
 *
 * 两条**都发**，分工如下：
 *
 * | ROM | 直投 | 系统代发 |
 * |---|---|---|
 * | 不限（AOSP / 多数 ROM） | **立刻生效** | 也生效，多一次广播投递 |
 * | 拦第三方关联启动（HyperOS / MIUI） | 被丢掉 | **唯一能过的** |
 * | 框架不支持 system_server hook / 看门狗熔断 | **唯一能过的** | 没人接（system_server 里没有我们的代码） |
 *
 * 与 [HostRefreshRequester] 的分工不变：那一条只在宿主**已存活**时有用，本文件解决「进程不在」。
 *
 * ## 已知会失败的情形
 *
 * - 菜鸟被**强行停止**过：处于 stopped 状态的应用默认收不到任何外部广播。这里加了
 *   `FLAG_INCLUDE_STOPPED_PACKAGES` 试着把它捞回来（小米上「最近任务划掉」有把应用置成
 *   stopped 的历史行为，而这正是本功能最可能的用法），但**捞不回来也认** ——
 *   那时界面会说「没能叫醒菜鸟」。
 * - 系统代发那条要求 system_server 里那份代码**装上了**且**把接收器注册上了**。
 *   注册需要 system_server 的 Context（`SystemContextHolder`），开机后要等它可用，
 *   所以这一步是带重试的。注册成不成，看模块日志里有没有
 *   `host wake bridge ready`（一次开机一行，见 [ACTION_WAKE_REPORT]）。
 * - **有没有叫醒，不看本方法的返回值**，看后续那两行之一：
 *   `identity bridge ready`（宿主进程里的桥把索取通道立好时会播报，
 *   见 `IdentityCodeFetcher` 的取舍 5）或 `host self query: rows=`（宿主自查并回执了，
 *   见 [ACTION_HOST_QUERY_REPORT]）。
 *
 * ## 调用方：两个，都带一记
 *
 * 1. **取身份码**（[IdentityCodeFetcher]）：放进重发循环里，每次重发都补一记。
 *    宿主活着时它是一记空操作，所以**多发几次没有代价**；而第一记有可能落在「系统正拦 /
 *    广播被丢」的时刻，重发是唯一能补救的手段。
 * 2. **打开模块刷新快递信息**（`ExpressMainActivity#refreshHostOnResume`）：用户打开模块时
 *    叫醒宿主。⚠️ 那里**同时**还发一条 [HostRefreshRequester] 的请求 —— 因为本方法只在
 *    宿主**进程不在**时有意义，而小米上菜鸟常年以推送进程活着，那时它什么也不做。
 *    两条是互补的，不是冗余的（分工表见 [ACTION_REFRESH_REQUEST]）。
 *
 * 两处的日志都带 [reason]，所以从一行日志就能分清这一记是干什么用的 ——
 * 也只有这样，「叫醒了但没数据」和「压根没叫醒」才分得开。
 */
object HostWakePin {

    private const val LOG_TAG = "ReaPress"

    /** 清单里那个接收者（`exported="true"`、无权限门槛）。 */
    private const val PIN_CLASS = "com.cainiao.wireless.components.agoo.NotificationDismissReceiver"

    /** 它自己声明的 action。 */
    private const val PIN_ACTION = "com.cainiao.wireless.notification_dismiss"

    /**
     * 发一记唤醒广播 —— **直投 + 请 system_server 代发，两条都发**（分工表见类注释）。
     *
     * **发完即忘**：广播有没有被投递、进程有没有真的起来，从这里看不出来（`sendBroadcast`
     * 是单向的）。所以返回值只表示「这两条有没有发出去」，不表示「菜鸟醒了」——
     * 真正的判据是随后有没有 `identity bridge ready` 或 `host self query: rows=`。
     *
     * @param reason 这一记是干什么用的（只进日志，并随请求一起送进 system_server）。
     *   调用方各传各的，排查时一眼能分清「叫醒了但没数据」和「压根没叫醒」。
     */
    fun wake(context: Context, reason: String): Boolean {
        val direct = runCatching {
            context.sendBroadcast(pinIntent())
            ModuleAndroidLog.legacy(LOG_TAG, "host wake（$reason）: 唤醒销已直投（$PIN_CLASS）")
            true
        }.getOrElse { error ->
            ModuleAndroidLog.error(LOG_TAG, "host wake（$reason）: 直投失败", error)
            false
        }
        // 直投被 ROM 拦掉的场景（见类注释的真机实测）只能靠这一条，所以它**不依赖 direct 的结果**。
        val relayed = requestSystemRelay(context, reason)
        return direct || relayed
    }

    /**
     * 请 system_server 替我们投那条「销」（[ExpressRelay.ACTION_WAKE_REQUEST]）。
     *
     * 用**隐式广播**（只写 action，不 `setPackage`）：system_server 不是一个包，没法用包名寻址；
     * 接收侧是它在自己的 Context 上动态注册的，靠 action + 权限闸（signature 级，
     * 只有本模块签得出）认人。别的应用发同一条 action 会被系统在权限检查那里丢掉。
     *
     * 发完即忘 —— 有没有真的代发出去，看模块日志里随后那行 `host wake: …`
     * （system_server 的回执，见 [ExpressRelay.ACTION_WAKE_REPORT]）。
     */
    private fun requestSystemRelay(context: Context, reason: String): Boolean = runCatching {
        context.sendBroadcast(
            Intent(ExpressRelay.ACTION_WAKE_REQUEST)
                .putExtra(ExpressRelay.EXTRA_WAKE_REASON, reason),
        )
        ModuleAndroidLog.legacy(LOG_TAG, "host wake（$reason）: 已请系统代发")
        true
    }.getOrElse { error ->
        ModuleAndroidLog.error(LOG_TAG, "host wake（$reason）: 请求系统代发失败", error)
        false
    }

    /**
     * 那条「销」的 intent —— **两条路径共用这一份构造**。
     *
     * 直投用它，system_server 侧（`SystemWakeRelay`）也用它：两边一旦各写一份，
     * 迟早出现「一条拉起的是这个接收者、另一条是另一个」这种极难查的偏差。
     *
     * 用显式组件（`setComponent`）而不是只写 action：接收者没有 intent-filter，
     * 只能靠组件名直投；顺带也避开了「隐式广播不给清单接收者投递」那套限制。
     *
     * `FLAG_INCLUDE_STOPPED_PACKAGES` 是 2026-09-26 加的：默认行为是**不给 stopped 状态的
     * 应用投递**，而「最近任务划掉」在某些 ROM 上正是把应用置成 stopped。
     */
    internal fun pinIntent(): Intent = Intent(PIN_ACTION)
        .setComponent(ComponentName(ExpressRelay.HOST_PACKAGE, PIN_CLASS))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
}
