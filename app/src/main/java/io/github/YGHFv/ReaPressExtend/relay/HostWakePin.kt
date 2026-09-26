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
 * 身份码只能由菜鸟进程里的 hook 用宿主自己的会话取（见 [IdentityCodeFetcher] 的说明）。
 * 而模块和菜鸟之间的那条索取广播是**动态注册**的 —— 菜鸟进程不在，那条广播就没人接，
 * 被系统直接丢掉（不报错、不回执）。用户此时看到的就是「连不上菜鸟」。
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
 * ## 已知会失败的情形
 *
 * - 菜鸟被**强行停止**过：处于 stopped 状态的应用默认收不到任何外部广播。这里加了
 *   `FLAG_INCLUDE_STOPPED_PACKAGES` 试着把它捞回来（小米上「最近任务划掉」有把应用置成
 *   stopped 的历史行为，而这正是本功能最可能的用法），但**捞不回来也认** ——
 *   那时界面会说「没能叫醒菜鸟」。
 * - 系统的「关联启动」限制（小米上的默认行为）：第三方应用后台拉起别的应用可能被拦。
 *   拦住了在日志里表现为「销发出去了，但没有后续」—— 所以 [wake] 的日志只说「已发出」，
 *   不说「已叫醒」。**有没有叫醒，看的是后续那两行之一**：
 *   `identity bridge ready`（宿主进程里的桥把索取通道立好时会播报，
 *   见 `IdentityCodeFetcher` 的取舍 5）或 `host self query: rows=`（宿主自查并回执了，
 *   见 `ExpressRelay.ACTION_HOST_QUERY_REPORT`）。
 *
 * ## 调用方：两个，都带一记
 *
 * 1. **取身份码**（[IdentityCodeFetcher]）：放进重发循环里，每次重发都补一记。
 *    宿主活着时它是一记空操作，所以**多发几次没有代价**；而第一记有可能落在「系统正拦 /
 *    广播被丢」的时刻，重发是唯一能补救的手段。
 * 2. **打开模块刷新快递信息**（`ExpressMainActivity#refreshHostOnResume`）：用户打开模块时
 *    叫醒宿主。⚠️ 那里**同时**还发一条 [HostRefreshRequester] 的请求 —— 因为本方法只在
 *    宿主**进程不在**时有意义，而小米上菜鸟常年以推送进程活着，那时它什么也不做。
 *    两条是互补的，不是冗余的（分工表见 `ExpressRelay.ACTION_REFRESH_REQUEST`）。
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
     * 发一记唤醒广播。
     *
     * **发完即忘**：广播有没有被投递、进程有没有真的起来，从这里看不出来（`sendBroadcast`
     * 是单向的）。所以返回值只表示「这条广播有没有发出去」，不表示「菜鸟醒了」——
     * 真正的判据是随后的索取广播有没有拿到回执、或宿主有没有查出包裹来。
     *
     * 用显式组件（`setComponent`）而不是只写 action：接收者没有 intent-filter，
     * 只能靠组件名直投；顺带也避开了「隐式广播不给清单接收者投递」那套限制。
     *
     * `FLAG_INCLUDE_STOPPED_PACKAGES` 是 2026-09-26 加的：默认行为是**不给 stopped 状态的
     * 应用投递**，而「最近任务划掉」在某些 ROM 上正是把应用置成 stopped。
     *
     * @param reason 这一记是干什么用的（只进日志）。两个调用方各传各的，排查时一眼能分清
     *   「叫醒了但没数据」和「压根没叫醒」。
     */
    fun wake(context: Context, reason: String): Boolean = runCatching {
        context.sendBroadcast(
            Intent(PIN_ACTION)
                .setComponent(ComponentName(ExpressRelay.HOST_PACKAGE, PIN_CLASS))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
        )
        // 日志带 reason：实测里同一个进程一天会发好几次销，不带原因的话没法判断
        // 「这一记是哪条链路发的、它该有什么后续」。
        ModuleAndroidLog.legacy(LOG_TAG, "host wake（$reason）: 唤醒销已发出（$PIN_CLASS）")
        true
    }.getOrElse { error ->
        ModuleAndroidLog.error(LOG_TAG, "host wake failed", error)
        false
    }
}
