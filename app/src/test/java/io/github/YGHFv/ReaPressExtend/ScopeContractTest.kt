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

package io.github.YGHFv.ReaPressExtend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 打包契约测试：**模块作用域**与**入口清单**。
 *
 * 这几样东西都不经过 Kotlin 代码 —— 它们是随 APK 一起打包的纯文本资源。写错了编译器
 * 一声不响，表现是「模块装上了但什么都不做」，排查方向还会被误导到 hook 选点上去。
 *
 * 2026-09-26 真机就踩过一次：新加的淘宝凭据渠道要挂淘宝进程，但 `scope.list` 里
 * 漏了一行 —— 用户在 LSPosed 的**作用域列表里根本找不到淘宝**，于是「渠道没生效」
 * 和「渠道写了没用」在现象上完全一样。
 *
 * 真正容易出错的点是**同一个事实写在两个文件里**：`scope.list` 是 API 102 实际生效的
 * 清单（`staticScope=true` 时以它为准），`res/values/arrays.xml` 是给 Manager 显示的。
 * 少写一处就会出现上面那种「列表里没有可勾项」。所以这里直接断言两者相等。
 */
class ScopeContractTest {

    @Test
    fun `作用域清单两处必须一致`() {
        // 不一致的后果不对称：scope.list 少一个 = 那个 App 永远不会被注入（静默失效），
        // arrays.xml 少一个 = Manager 里看不到可勾项。两种都不会报错，只能靠测试发现。
        assertEquals(
            "scope.list 与 arrays.xml 的 xposed_scope 必须完全相同",
            effectiveScope(),
            declaredScope(),
        )
    }

    @Test
    fun `作用域必须包含三条功能依赖`() {
        val scope = effectiveScope()
        // 菜鸟：包裹数据富化 + 第二条凭据来源；system：通知拦截的前提。
        assertTrue("缺 com.cainiao.wireless：富化与凭据采集都挂不上", "com.cainiao.wireless" in scope)
        assertTrue("缺 system：通知拦截挂不上", "system" in scope)
        // 淘宝：菜鸟没绑淘宝账号时，凭据只可能在淘宝 App 里（见 HostCredentialSource）。
        assertTrue("缺 com.taobao.taobao：凭据的第二条来源失效", "com.taobao.taobao" in scope)
        // 拼多多：富化还没做，作用域先占位 —— 这样将来加 hook 时用户不必再改一次作用域。
        assertTrue("缺 com.xunmeng.pinduoduo：作用域占位被删了", "com.xunmeng.pinduoduo" in scope)
    }

    @Test
    fun `scriptScope 必须为 true，否则 scope_list 不生效`() {
        // staticScope=false 时作用域改由用户在 Manager 里勾选，scope.list 会失去约束力。
        // 这份清单本身没错，但「为什么新加的包名没生效」会变得无法从仓库里看出来。
        val props = moduleProp()
        assertEquals("staticScope 必须是 true", "true", props["staticScope"])
        assertEquals("minApiVersion", "102", props["minApiVersion"])
    }

    @Test
    fun `入口清单里的类必须真的存在`() {
        // 类名写错 = 模块整个不加载，而且没有任何运行时错误能提示你 —— 名字是通过字符串
        // 传出去的，编译器看不见。
        //
        // ⚠️ 这里查的是 **classpath 上有没有这个 .class 资源**，不是 `Class.forName`。
        // 用 forName 会失败，而且是必然失败：入口类继承 `XposedModule`，而 libxposed 的
        // api 是 `compileOnly`（绝不能打进 APK，见 build.gradle.kts 那条注释），
        // 所以单测的 classpath 上根本没有那个超类 —— 抛的是 NoClassDefFoundError，
        // 看起来像「类名写错了」，实际上类名是对的。`getResource` 只查资源、不加载类，
        // 正好绕开这一点。
        val entries = entriesFile("java_init.list").readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("java_init.list 是空的，模块不会被加载", entries.isNotEmpty())
        val loader = requireNotNull(javaClass.classLoader) { "拿不到测试的 ClassLoader" }
        for (name in entries) {
            val resource = name.replace('.', '/') + ".class"
            assertTrue(
                "java_init.list 里的 $name 没编译进产物（找 $resource 失败）",
                loader.getResource(resource) != null,
            )
        }
    }

    // ------------------------------------------------------------ 读取

    /**
     * `scope.list`：每行一个包名。
     */
    private fun effectiveScope(): Set<String> =
        projectFile("src/main/resources/META-INF/xposed/scope.list").readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    /**
     * `arrays.xml` 里 `xposed_scope` 的条目。
     *
     * 用手写正则而不是引入 XML 解析：这个文件只有一种结构，而 Android 的单测 JVM
     * 上能拿到的解析器未必和打包期一致 —— 一条断言不该为此赌上依赖。
     */
    private fun declaredScope(): Set<String> {
        val xml = projectFile("src/main/res/values/arrays.xml").readText()
        val array = Regex(
            """<string-array\s+name="xposed_scope"\s*>(.*?)</string-array>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)
            ?: throw AssertionError("arrays.xml 里找不到 xposed_scope")
        return Regex("""<item>(.*?)</item>""").findAll(array)
            .map { it.groupValues[1].trim() }
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun moduleProp(): Map<String, String> =
        projectFile("src/main/resources/META-INF/xposed/module.prop").readLines()
            .map(String::trim)
            .filter { it.contains('=') && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    private fun entriesFile(name: String): File =
        projectFile("src/main/resources/META-INF/xposed/$name")

    /**
     * 定位仓库里的一个源文件。
     *
     * 探测两个候选而不是写死一个：Gradle 的 Test 任务默认工作目录是模块目录（`app/`），
     * 但换 IDE / 换运行方式会变。多探一次比让测试依赖运行环境稳定得多。
     */
    private fun projectFile(relative: String): File =
        listOf(relative, "app/$relative")
            .map(::File)
            .firstOrNull(File::exists)
            ?: throw AssertionError(
                "找不到 $relative（当前工作目录 ${File(".").absolutePath}）",
            )
}
