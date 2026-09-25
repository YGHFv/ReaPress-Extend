package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge

/**
 * 按包名把宿主进程分发给对应的 hook 安装器。
 *
 * 为什么需要这一层：libxposed 的 scope 是「进程级」的 —— 一个 App 可能有多个进程
 * （`:push`、`:channel`、`:tools` 等），每个进程都会走一遍 `onPackageReady`。同一份 hook 在多个
 * 进程里各装一次是正常的（各进程的类加载器互相独立），但同一个进程不能重复装。
 *
 * ## 去重键是「包名 + 进程名」，不是包名
 *
 * [PackageReadyParam] 只给包名，而菜鸟有主进程、`:channel`、`:normal_render_proc0`、`:tools`
 * 好几个 —— **按包名去重会让后到的进程全部被跳过**，而真正会查询包裹的未必是第一个到的。
 * 所以键必须带上进程名，让每个进程各装一次（各进程 ClassLoader 独立，本来就该各装一份）。
 *
 * 进程名走 `/proc/self/cmdline` 而不是 `ActivityThread.currentProcessName()`：后者要等
 * Java 层初始化完才有值，而 `onPackageReady` 来得比那更早 —— 旧日志里打出来的 `process=?`
 * 就是这么来的，等于去重键退化成包名。`/proc` 是内核对进程的权威记录，任何时刻可读。
 *
 * **2026-09-26 实测更正一条旧假设**：当时把「`hook alive` 不出现」归因于「可能分错了进程」，
 * 真机日志证明不是 —— 主进程 `com.cainiao.wireless` 上 hook `installed=true`，一切正常，
 * 只是那个挂点（响应实体 `MtopResponse#getData()`）**一次都没被调用过**。
 * 所以：`hook alive` 不出现 **不等于**装错了进程，更常见的原因是**挂点选错了**。
 *
 * 菜鸟这一轮连着踩了**六次**（详情页响应实体 → MTOP 响应 JSON → fastjson 转换入口 →
 * `DataUtil#injectDataToObject` → ORM 实现类运行时发现 → 卡启动），
 * 每次的实测依据都记在 [CainiaoPackageHook] 的类注释里。两条方法论：
 * - 前几次是「按数据形态猜」或「按语义猜」；奏效的是**先把出口找出来**
 *   （挂未混淆的调用方），而不是去猜实现类。
 * - **类加载路径（`ClassLoader#loadClass` / `BaseDexClassLoader#findClass`）上不要挂任何东西**：
 *   它是冷启动最热的路径，且在里面做反射有触发递归类加载的风险。绕开混淆名的正确方式是
 *   找未混淆的调用方，不是运行时发现。
 *
 * 这里用 [XposedBridge.logAlways] 而不是 `log`：这些行每进程只打一次，且是排查
 * 「模块到底有没有被注入」的第一手证据，不能被「简洁日志」开关吞掉。
 */
object ExpressHookDispatcher {

    /** 已知的快递来源包名。与 [io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys] 的白名单保持一致。 */
    private const val CAINIAO = "com.cainiao.wireless"
    private const val PINDUODUO = "com.xunmeng.pinduoduo"
    private const val TAOBAO = "com.taobao.taobao"

    /** 已处理过的「包名 + 进程名」。为什么带进程名见类注释。 */
    private val installed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun onPackageReady(packageName: String, classLoader: ClassLoader) {
        val process = currentProcessName()
        if (!installed.add("$packageName|$process")) {
            XposedBridge.logAlways("skip duplicate package: $packageName process=$process")
            return
        }
        XposedBridge.logAlways(
            "dispatch package: $packageName process=$process loader=${classLoader.javaClass.name}",
        )
        when (packageName) {
            CAINIAO -> {
                // hook 安装失败不能让宿主进程受影响，更不能冒泡到框架的包加载流程上。
                val ok = runCatching { CainiaoPackageHook.install(classLoader) }.getOrElse { error ->
                    XposedBridge.logError("$packageName: cainiao package hook install threw", error)
                    false
                }
                XposedBridge.logAlways("$packageName: cainiao package hook installed=$ok")
            }
            // 拼多多 / 淘宝的富化还没做。这里刻意只记一行日志、不做任何反射动作 ——
            // 否则日志看起来像"已经装上了"，真机验证时会白跑一轮。
            PINDUODUO, TAOBAO ->
                XposedBridge.logAlways("$packageName: enrichment hook not implemented yet")
            else ->
                XposedBridge.logAlways("$packageName: no hook registered, ignoring")
        }
    }

    /**
     * 当前进程名。
     *
     * 它有两个用途，都不能出错：日志里区分「hook 装在哪个进程上」，
     * 以及上面那个去重键 —— 所以必须走 `/proc/self/cmdline`。
     * `ActivityThread.currentProcessName()` 在 `onPackageReady` 这个时间点还没准备好，
     * 旧日志里的 `process=?` 就是证据（那意味着去重键退化成包名，后到的进程全被跳过）。
     */
    private fun currentProcessName(): String =
        runCatching { java.io.File(PROC_CMDLINE).readText() }
            .getOrNull()
            .orEmpty()
            .substringBefore('\u0000')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "?"

    private const val PROC_CMDLINE = "/proc/self/cmdline"
}
