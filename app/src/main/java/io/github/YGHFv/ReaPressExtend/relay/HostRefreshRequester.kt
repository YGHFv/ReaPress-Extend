package io.github.YGHFv.ReaPressExtend.relay

import android.content.Context
import android.content.Intent
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog

/**
 * 请宿主把本地那张包裹表**现在**重查一遍（`ACTION_REFRESH_REQUEST`）。
 *
 * ## 与 [HostWakePin] 的分工（两个都要发，缺一个就有场景不工作）
 *
 * | 宿主进程状态 | [HostWakePin.wake] | 这里 |
 * |---|---|---|
 * | 不在 | **能拉起**（投递清单接收者会创建进程） | 没人接 —— 动态接收者只随进程存在 |
 * | 已存活 | 空操作 | **能叫它立刻查一次** |
 *
 * 只发唤醒销的破绽：小米上菜鸟常年以推送进程（`:channel`）的形式活着、主进程不重启，
 * 此时 `Application#onCreate` 不会再触发，宿主的冷启动自查也就**一次都不会再跑** ——
 * 用户打开模块看到的是「刷了跟没刷一样」。只发这一条则拉不起一个死进程。
 * 所以 `ExpressMainActivity#onResume` 两条都发。
 *
 * ## 为什么不需要回执也能用
 *
 * 发完即忘（广播是单向的）。有没有真的查到，看模块日志里随后有没有
 * `host self query: rows=N`（宿主自查的播报，见 [ExpressRelay.ACTION_HOST_QUERY_REPORT]）；
 * 数据有没有落进模块，再看 `enrichment received`。**这两行才是判据**，
 * 本方法只表示「请求发出去了」。
 *
 * ⚠️ 依赖宿主进程存活。与其它反向请求一样，用 `setPackage` 寻址到宿主包 ——
 * 接收器是**动态注册**的，只有宿主活着它才存在，所以这条广播在没有宿主时会被静默丢弃
 * （不报错、不回执）。调用方一律按「没有后续」处理，不要指望回执。
 */
object HostRefreshRequester {

    private const val LOG_TAG = "ReaPress"

    fun request(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ExpressRelay.ACTION_REFRESH_REQUEST)
                    .setPackage(ExpressRelay.HOST_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
            )
            ModuleAndroidLog.legacy(LOG_TAG, "host refresh request: 已请宿主重查本地包裹表")
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "host refresh request failed", it) }
    }
}
