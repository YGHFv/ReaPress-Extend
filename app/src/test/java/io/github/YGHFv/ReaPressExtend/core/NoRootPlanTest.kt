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

package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免 root 方案的纯逻辑单测：来源判定、通知使用权串解析、能力清单、去重窗口。
 *
 * 这几处都是「界面照它说话」的地方 —— 判定错了用户看到的就是假的授权状态，
 * 所以每个分支都钉住。
 */
class NoRootPlanTest {

    private val pkg = "io.github.YGHFv.ReaPressExtend"

    // ---------------------------------------------------------------- 工作模式

    @Test
    fun `框架在线时主来源是宿主 hook`() {
        val status = NoRootPlan.resolveSourceMode(frameworkAvailable = true, listenerCollecting = false)
        assertEquals(NoRootPlan.SourceMode.HOST_HOOK, status.mode)
        assertFalse(status.supplementActive)
        assertTrue(status.detail.contains("宿主 hook"))
    }

    @Test
    fun `框架与采集同时在线时把补充说出来`() {
        val status = NoRootPlan.resolveSourceMode(frameworkAvailable = true, listenerCollecting = true)
        assertEquals(NoRootPlan.SourceMode.HOST_HOOK, status.mode)
        assertTrue(status.supplementActive)
        assertTrue(status.detail.contains("免 root 采集同时开着"))
    }

    @Test
    fun `只有采集时主来源是免 root 并提示身份码不可用`() {
        val status = NoRootPlan.resolveSourceMode(frameworkAvailable = false, listenerCollecting = true)
        assertEquals(NoRootPlan.SourceMode.NO_ROOT, status.mode)
        assertFalse(status.supplementActive)
        assertTrue(status.detail.contains("身份码"))
    }

    @Test
    fun `两条都不通时给出两条补法`() {
        val status = NoRootPlan.resolveSourceMode(frameworkAvailable = false, listenerCollecting = false)
        assertEquals(NoRootPlan.SourceMode.NONE, status.mode)
        assertTrue(status.detail.contains("LSPosed"))
        assertTrue(status.detail.contains("通知使用权"))
    }

    /**
     * 采集「真的在跑」要三个条件同时成立。任何一个缺失都必须判 false ——
     * 界面是按这个布尔值渲染「免 root 采集」这个主来源的，宽一格就会显示成
     * 「正在免 root 采集」而实际上一条通知都没在读。
     */
    @Test
    fun `采集生效需要开关_授权_模式三者同时成立`() {
        assertTrue(
            NoRootPlan.isListenerCollecting(listenerEnabled = true, accessGranted = true, modeOff = false),
        )
        assertFalse(
            NoRootPlan.isListenerCollecting(listenerEnabled = false, accessGranted = true, modeOff = false),
        )
        assertFalse(
            NoRootPlan.isListenerCollecting(listenerEnabled = true, accessGranted = false, modeOff = false),
        )
        // 工作模式「关闭」= 模块什么都不做，免 root 这条腿同样不该动。
        assertFalse(
            NoRootPlan.isListenerCollecting(listenerEnabled = true, accessGranted = true, modeOff = true),
        )
    }

    // ---------------------------------------------------------------- 通知使用权串

    @Test
    fun `通知使用权串为空视为未授予`() {
        assertFalse(NoRootPlan.isListenerEnabled(null, pkg))
        assertFalse(NoRootPlan.isListenerEnabled("", pkg))
        assertFalse(NoRootPlan.isListenerEnabled("   ", pkg))
        assertFalse(NoRootPlan.isListenerEnabled("a/b:c/d", ""))
    }

    @Test
    fun `通知使用权串按包名精确匹配`() {
        assertTrue(NoRootPlan.isListenerEnabled("$pkg/$pkg.noroot.ExpressNotificationListener", pkg))
        // 多条目里命中一个就够。
        assertTrue(NoRootPlan.isListenerEnabled("com.other.app/.Svc:$pkg/.Svc", pkg))
        // 别的应用不能算。
        assertFalse(NoRootPlan.isListenerEnabled("com.other.app/com.other.app.Svc", pkg))
    }

    /**
     * 这条是本类最容易写错的地方：本包名是别的包名的**前缀**，用 `contains` 判会得到
     * 恒真的结果 —— 未授权也说「已授予」。所以必须按 `/` 切出包名再全等比较。
     */
    @Test
    fun `同前缀包名不能算作已授予`() {
        assertFalse(NoRootPlan.isListenerEnabled("${pkg}Extra/.Svc", pkg))
        assertFalse(NoRootPlan.isListenerEnabled("$pkg.other/.Svc", pkg))
        // 恰好是「包名 + 斜杠」才是真的命中。
        assertTrue(NoRootPlan.isListenerEnabled("$pkg/.Svc", pkg))
    }

    @Test
    fun `条目里带空白或缺斜杠时按最保守的方式处理`() {
        assertTrue(NoRootPlan.isListenerEnabled(" $pkg/.Svc ", pkg))
        // 缺 `/`（格式变了）用整串比：就是本包名才算。
        assertTrue(NoRootPlan.isListenerEnabled(pkg, pkg))
        assertFalse(NoRootPlan.isListenerEnabled("$pkg.Svc", pkg))
    }

    // ---------------------------------------------------------------- 能力清单

    @Test
    fun `能力清单五项都在且缺失项给出补法`() {
        val items = NoRootPlan.checklist(
            frameworkAvailable = false,
            listenerAccessGranted = false,
            notifyPermissionGranted = false,
            taobaoAgeMs = null,
        )
        assertEquals(NoRootPlan.Capability.Id.entries.size, items.size)
        items.forEach { assertFalse("${it.title} 应当是缺失的", it.ok) }
        // 每一项都必须是「能照着做」的一句话，不是空话。
        items.forEach { assertTrue("${it.title} 说明太短", it.detail.length > 10) }
    }

    @Test
    fun `淘宝登录态在时把年龄说出来`() {
        val items = NoRootPlan.checklist(
            frameworkAvailable = true,
            listenerAccessGranted = true,
            notifyPermissionGranted = true,
            taobaoAgeMs = 3 * 3_600_000L,
        )
        assertTrue(items.all { it.ok })
        val login = items.first { it.id == NoRootPlan.Capability.Id.TAOBAO_LOGIN }
        assertTrue(login.detail.contains("3 小时前"))
    }

    @Test
    fun `身份码只在框架在线时可用且缺失原因写明不可用`() {
        val offline = NoRootPlan.checklist(
            frameworkAvailable = false,
            listenerAccessGranted = true,
            notifyPermissionGranted = true,
            taobaoAgeMs = 1_000L,
        ).first { it.id == NoRootPlan.Capability.Id.IDENTITY_CODE }
        assertFalse(offline.ok)
        assertTrue(offline.detail.contains("不可用"))
        assertTrue(offline.detail.contains("APP 通道"))

        val online = NoRootPlan.checklist(
            frameworkAvailable = true,
            listenerAccessGranted = false,
            notifyPermissionGranted = false,
            taobaoAgeMs = null,
        ).first { it.id == NoRootPlan.Capability.Id.IDENTITY_CODE }
        assertTrue(online.ok)
    }

    @Test
    fun `年龄分级`() {
        assertEquals("刚刚", NoRootPlan.describeAge(0L))
        assertEquals("刚刚", NoRootPlan.describeAge(59_999L))
        assertEquals("1 分钟前", NoRootPlan.describeAge(60_000L))
        assertEquals("59 分钟前", NoRootPlan.describeAge(59 * 60_000L))
        assertEquals("1 小时前", NoRootPlan.describeAge(3_600_000L))
        assertEquals("23 小时前", NoRootPlan.describeAge(23 * 3_600_000L))
        assertEquals("1 天前", NoRootPlan.describeAge(24 * 3_600_000L))
        assertEquals("30 天前", NoRootPlan.describeAge(30 * 24 * 3_600_000L))
        // 时钟被改过：不编造「负 X 分钟前」。
        assertEquals("刚刚", NoRootPlan.describeAge(-5_000L))
    }

    // ---------------------------------------------------------------- 来源摘要

    /** 名字映射由调用方注入（core 不认识界面层的表），这里用一份最小替身。 */
    private fun nameOf(pkg: String): String = when (pkg) {
        "com.cainiao.wireless" -> "菜鸟"
        "com.xunmeng.pinduoduo" -> "拼多多"
        "com.taobao.taobao" -> "淘宝"
        ExpressClassifier.SMS_PACKAGE -> "短信"
        else -> pkg
    }

    @Test
    fun `来源摘要跟着开关走`() {
        assertEquals("菜鸟、拼多多、淘宝、短信",
            NoRootPlan.sourceSummary(ExpressRule.DEFAULT_SOURCES, handleSms = true, nameOf = ::nameOf))
        // 用户在「来源」里关掉拼多多，摘要必须跟着少一个 —— 这一页说的就是实际会处理的那些包。
        assertEquals("菜鸟、淘宝、短信",
            NoRootPlan.sourceSummary(
                ExpressRule.DEFAULT_SOURCES - "com.xunmeng.pinduoduo",
                handleSms = true,
                nameOf = ::nameOf,
            ))
        assertEquals("菜鸟",
            NoRootPlan.sourceSummary(listOf("com.cainiao.wireless"), handleSms = false, nameOf = ::nameOf))
    }

    /**
     * 全关时要给的是「去哪儿开」，不是一句「无」—— 用户在这页看到空白的正确反应是
     * 「设置是不是坏了」。
     */
    @Test
    fun `来源全关时指出去哪儿打开`() {
        val text = NoRootPlan.sourceSummary(emptyList(), handleSms = false, nameOf = ::nameOf)
        assertTrue(text.contains("全部来源都已关闭"))
        assertTrue(text.contains("来源"))
    }

    /** 空串与重复项都不该在摘要里露出来（规则是拼出来的，重复并非不可能）。 */
    @Test
    fun `来源摘要去重且忽略空串`() {
        val text = NoRootPlan.sourceSummary(
            listOf("com.cainiao.wireless", "com.cainiao.wireless", "  ", "com.taobao.taobao"),
            handleSms = false,
            nameOf = ::nameOf,
        )
        assertEquals("菜鸟、淘宝", text)
    }

    // ---------------------------------------------------------------- 采集服务在线状态

    /**
     * 这一行要能区分「权限给了」与「链路真的在动」—— 后者只有服务自己知道。
     * 未收到回调时不能写成「未授权」（那是另一件事，会把人往错的方向引），要写成
     * 「等一条通知」+「一直不动怎么办」。
     */
    @Test
    fun `采集服务在线状态两种说法都给出下一步`() {
        val online = NoRootPlan.listenerServiceLabel(seenCallback = true)
        assertTrue(online.contains("在线"))
        assertFalse(online.contains("授权"))

        val waiting = NoRootPlan.listenerServiceLabel(seenCallback = false)
        assertTrue(waiting.contains("尚未收到系统回调"))
        assertTrue(waiting.contains("重启"))
    }
}
