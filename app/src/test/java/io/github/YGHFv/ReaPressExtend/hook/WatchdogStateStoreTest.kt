package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.core.WatchdogStateMachine
import io.github.YGHFv.ReaPressExtend.testing.MemoryPreferences
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchdogStateStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `重建存储对象后已消费复位ID仍阻止反复绕过保护`() {
        val file = File(temporary.newFolder(), "watchdog.properties")
        val old = WatchdogStateMachine.State(attempt = 2, failures = 2, disabled = true)
        assertTrue(WatchdogStateStore(listOf(file)).write(old))
        assertTrue(Watchdog.beforeInstall("reset-1", WatchdogStateStore(listOf(file))) is WatchdogStateMachine.Decision.Install)
        assertEquals("reset-1", WatchdogStateStore(listOf(file)).read().consumedResetId)
        assertTrue(Watchdog.beforeInstall("reset-1", WatchdogStateStore(listOf(file))) is WatchdogStateMachine.Decision.Install)
        assertTrue(Watchdog.beforeInstall("reset-1", WatchdogStateStore(listOf(file))) is WatchdogStateMachine.Decision.Refuse)
    }

    @Test
    fun `宿主只读RemotePreferences不需要也不允许edit`() {
        val remote = MemoryPreferences()
        assertTrue(ExpressSettingsKeys.requestHookForceEnable(remote, true))
        val readOnly = object : SharedPreferences by remote {
            override fun edit(): SharedPreferences.Editor = error("hooked remote preferences are read-only")
        }
        val request = ExpressSettingsKeys.hookResetRequest(readOnly)
        val store = WatchdogStateStore(listOf(File(temporary.newFolder(), "watchdog.properties")))
        assertTrue(Watchdog.beforeInstall(request, store) is WatchdogStateMachine.Decision.Install)
        assertTrue(Watchdog.beforeInstall(request, store) is WatchdogStateMachine.Decision.Install)
        assertTrue(Watchdog.beforeInstall(request, store) is WatchdogStateMachine.Decision.Refuse)
        assertEquals(request, ExpressSettingsKeys.hookResetRequest(readOnly))
    }

    @Test
    fun `首次主路径不可写可用备用位置且后续沿用它`() {
        val unavailable = File(temporary.newFile(), "watchdog.properties")
        val fallback = File(temporary.newFolder(), "fallback.properties")
        val store = WatchdogStateStore(listOf(unavailable, fallback))
        val state = WatchdogStateMachine.State(attempt = 1, consumedResetId = "reset-1")
        assertTrue(store.write(state))
        assertEquals(state, WatchdogStateStore(listOf(unavailable, fallback)).read())
        assertFalse(unavailable.exists())
        assertEquals(listOf(fallback.name), fallback.parentFile?.list()?.toList())
    }

    @Test
    fun `保护状态无法落盘时拒绝安装而不是继续无保护运行`() {
        val file = File(temporary.newFile(), "watchdog.properties")
        val store = WatchdogStateStore(listOf(file))
        assertTrue(Watchdog.beforeInstall("reset-1", store) is WatchdogStateMachine.Decision.Refuse)
        assertFalse(file.exists())
    }

    @Test
    fun `已有旧格式状态可读且复位后更新为新格式`() {
        val file = temporary.newFile()
        file.writeText("attempt=2\nok=0\nfailures=2\ndisabled=true\nreason=old\n")
        val store = WatchdogStateStore(listOf(file))
        assertTrue(Watchdog.beforeInstall(null, store) is WatchdogStateMachine.Decision.Refuse)
        assertTrue(Watchdog.beforeInstall("reset-new", store) is WatchdogStateMachine.Decision.Install)
        assertEquals("reset-new", store.read().consumedResetId)
    }

    @Test
    fun `读取状态出错时不会覆盖原文件或继续安装`() {
        val file = temporary.newFile()
        val invalid = "reason=\\uZZZZ"
        file.writeText(invalid)
        val store = WatchdogStateStore(listOf(file))
        assertTrue(Watchdog.beforeInstall("reset-new", store) is WatchdogStateMachine.Decision.Refuse)
        assertEquals(invalid, file.readText())
    }

    @Test
    fun `真实存活入口持久化成功后下一次尝试不计失败`() = withAndroidTestRuntime {
        val file = File(temporary.newFolder(), "watchdog.properties")
        val store = WatchdogStateStore(listOf(file))
        assertTrue(Watchdog.beforeInstall("reset-1", store) is WatchdogStateMachine.Decision.Install)

        assertTrue(Watchdog.markBootSurvived(store))

        assertEquals(1, WatchdogStateStore(listOf(file)).read().ok)
        assertTrue(Watchdog.beforeInstall("reset-1", store) is WatchdogStateMachine.Decision.Install)
        assertEquals(0, store.read().failures)
        assertEquals("reset-1", store.read().consumedResetId)
    }

    @Test
    fun `截断或无效计数的文件不能当成初次启动清除保护`() {
        for (contents in listOf("", "attempt=2\n", "attempt=-1\nok=0\nfailures=0\ndisabled=false")) {
            val file = temporary.newFile()
            file.writeText(contents)
            assertTrue(Watchdog.beforeInstall("reset-new", WatchdogStateStore(listOf(file))) is WatchdogStateMachine.Decision.Refuse)
            assertEquals(contents, file.readText())
        }
    }
}
