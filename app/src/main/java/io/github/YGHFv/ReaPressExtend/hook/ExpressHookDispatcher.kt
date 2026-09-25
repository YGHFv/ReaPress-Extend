package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 按包名把宿主进程分发给对应的 hook 安装器。
 *
 * 为什么需要这一层：libxposed 的 scope 是「进程级」的 —— 一个 App 可能有多个进程
 * （`:push`、`:channel`、`:tools` 等），每个进程都会走一遍 `onPackageReady`。同一份 hook 在多个
 * 进程里各装一次是正常的（各进程的类加载器互相独立），但**不能重复装同一个进程**，所以要按
 * 包名去重。
 *
 * 这里用 [XposedBridge.logAlways] 而不是 `log`：这些行每进程只打一次，且是排查
 * 「模块到底有没有被注入」的第一手证据，不能被「简洁日志」开关吞掉。
 */
object ExpressHookDispatcher {

    /** 已知的快递来源包名。与 [io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys] 的白名单保持一致。 */
    private const val CAINIAO = "com.cainiao.wireless"
    private const val PINDUODUO = "com.xunmeng.pinduoduo"
    private const val TAOBAO = "com.taobao.taobao"

    /** 已处理过的包名，防止同进程内重复安装。 */
    private val installed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun onPackageReady(packageName: String, classLoader: ClassLoader) {
        if (!installed.add(packageName)) {
            XposedBridge.logAlways("skip duplicate package: $packageName")
            return
        }
        XposedBridge.logAlways("dispatch package: $packageName loader=${classLoader.javaClass.name}")
        when (packageName) {
            // 阶段 5 才实现各家的富化 hook；这里先把分发骨架留出来，
            // 未实现的包只记一行日志，不做任何反射动作（避免日志看起来像"已经装上了"）。
            CAINIAO, PINDUODUO, TAOBAO ->
                XposedBridge.logAlways("$packageName: enrichment hook not implemented yet (stage 5)")
            else ->
                XposedBridge.logAlways("$packageName: no hook registered, ignoring")
        }
    }
}
