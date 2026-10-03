package io.github.YGHFv.ReaPressExtend.config

import android.content.SharedPreferences
import io.github.YGHFv.ReaPressExtend.core.WatchdogStateMachine
import io.github.YGHFv.ReaPressExtend.testing.MemoryPreferences
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncTest {
    @Test
    fun `离线修改在首次绑定框架时完整补同步`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences()
            val expected = ExpressSettingsSnapshot(sourceCainiao = false, autoWatch = true, watchGapMinutes = 10)
            assertTrue(ExpressSettings.write(storage.context, expected))

            ExpressSettings.attachService(storage.context, FakeService(remote))

            assertEquals(expected, ExpressSettingsKeys.readFrom(remote))
        }
    }

    @Test
    fun `复位请求已消费后普通设置同步不应再次激活`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))
            assertTrue(ExpressSettings.requestHookReset(storage.context))
            val request = ExpressSettingsKeys.hookResetRequest(remote)
            val started = WatchdogStateMachine.beforeInstall(WatchdogStateMachine.State(disabled = true), request)
            assertTrue(started.decision is WatchdogStateMachine.Decision.Install)

            ExpressSettings.syncToFrameworkNow(storage.context)

            assertEquals(request, ExpressSettingsKeys.hookResetRequest(remote))
            val retried = WatchdogStateMachine.beforeInstall(started.state, ExpressSettingsKeys.hookResetRequest(remote))
            assertEquals(1, retried.state.failures)
            val tripped = WatchdogStateMachine.beforeInstall(retried.state, ExpressSettingsKeys.hookResetRequest(remote))
            assertTrue(tripped.decision is WatchdogStateMachine.Decision.Refuse)
        }
    }

    @Test
    fun `服务死亡后的离线修改会在新连接补同步`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val first = FakeService(MemoryPreferences())
            ExpressSettings.attachService(storage.context, first)
            ExpressSettings.detachService(first)
            assertFalse(ExpressSettings.isServiceAvailable())
            assertTrue(ExpressSettings.write(storage.context, ExpressSettingsSnapshot(sourceSms = false)))
            val renewed = MemoryPreferences()

            ExpressSettings.attachService(storage.context, FakeService(renewed))

            assertTrue(ExpressSettings.isServiceAvailable())
            assertFalse(ExpressSettingsKeys.readFrom(renewed).sourceSms)
        }
    }

    @Test
    fun `旧服务迟到的死亡回调不会清除已经连接的新服务`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val old = FakeService(MemoryPreferences())
            ExpressSettings.attachService(storage.context, old)
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))

            ExpressSettings.detachService(old)
            assertTrue(ExpressSettings.update(storage.context) { it.copy(sourceTaobao = false) })

            assertTrue(ExpressSettings.isServiceAvailable())
            assertFalse(ExpressSettingsKeys.readFrom(remote).sourceTaobao)
        }
    }

    @Test
    fun `本地写盘失败不报告成功或投影未保存的设置`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))
            val before = remote.all
            storage.preferences(ExpressSettingsKeys.LOCAL_PREFS).commitSucceeds = false

            assertFalse(ExpressSettings.write(storage.context, ExpressSettingsSnapshot(sourceCainiao = false)))

            assertEquals(before, remote.all)
        }
    }

    @Test
    fun `远端提交失败不丢本地配置且重连不能仅凭内存相同跳过重试`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences().apply { commitSucceeds = false }
            val service = FakeService(remote)
            ExpressSettings.attachService(storage.context, service)
            assertTrue(ExpressSettings.write(storage.context, ExpressSettingsSnapshot(sourceCainiao = false)))
            assertFalse(ExpressSettings.syncToFrameworkNow(storage.context))
            val failedCommits = remote.commits
            assertFalse(ExpressSettings.read(storage.context).sourceCainiao)

            remote.commitSucceeds = true
            ExpressSettings.attachService(storage.context, service)

            assertEquals(failedCommits + 1, remote.commits)
            assertFalse(ExpressSettingsKeys.readFrom(remote).sourceCainiao)
        }
    }

    @Test
    fun `离线复位请求在绑定时发布同一个持久化ID`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            assertTrue(ExpressSettings.requestHookReset(storage.context))
            val request = ExpressSettingsKeys.hookResetRequest(ExpressSettingsKeys.localPrefs(storage.context))
            val remote = MemoryPreferences()

            ExpressSettings.attachService(storage.context, FakeService(remote))

            assertNotNull(request)
            assertEquals(request, ExpressSettingsKeys.hookResetRequest(remote))
            assertFalse(remote.getBoolean(ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED, true))
        }
    }

    @Test
    fun `重新点击复位生成新ID但一般设置保存不改变它`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))
            assertTrue(ExpressSettings.requestHookReset(storage.context))
            val first = ExpressSettingsKeys.hookResetRequest(remote)
            assertTrue(ExpressSettings.update(storage.context) { it.copy(sourceSms = false) })
            assertEquals(first, ExpressSettingsKeys.hookResetRequest(remote))

            assertTrue(ExpressSettings.requestHookReset(storage.context))

            assertNotEquals(first, ExpressSettingsKeys.hookResetRequest(remote))
        }
    }

    @Test
    fun `旧版布尔复位迁移为固定ID而不在每次连接轮换`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val local = ExpressSettingsKeys.localPrefs(storage.context)
            local.edit().putBoolean(ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED, true).commit()
            val remote = MemoryPreferences()
            val service = FakeService(remote)
            ExpressSettings.attachService(storage.context, service)
            val request = ExpressSettingsKeys.hookResetRequest(remote)

            ExpressSettings.attachService(storage.context, service)

            assertNotNull(request)
            assertEquals("legacy-force-enable", request)
            assertEquals(request, ExpressSettingsKeys.hookResetRequest(remote))
            assertFalse(local.getBoolean(ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED, true))
        }
    }

    @Test
    fun `复位请求写盘失败不得发布且之后成功保存仍沿用同一个ID`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))
            val local = storage.preferences(ExpressSettingsKeys.LOCAL_PREFS)
            local.commitSucceeds = false
            assertFalse(ExpressSettings.requestHookReset(storage.context))
            val request = ExpressSettingsKeys.hookResetRequest(local)
            assertFalse(ExpressSettings.syncToFrameworkNow(storage.context))
            assertNull(ExpressSettingsKeys.hookResetRequest(remote))

            local.commitSucceeds = true
            assertTrue(ExpressSettings.syncToFrameworkNow(storage.context))

            assertEquals(request, ExpressSettingsKeys.hookResetRequest(remote))
        }
    }

    @Test
    fun `投影覆盖完整设置但不清理无关框架字段`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val expected = ExpressSettingsSnapshot(
                sourceCainiao = false, sourcePinduoduo = false, sourceTaobao = false, sourceSms = false,
                mode = ExpressSettingsKeys.MODE_INTERCEPT,
                extraKeywords = setOf("合成关键词"), excludeKeywords = setOf("忽略"), confidenceThreshold = 50,
                interceptedCategories = setOf(io.github.YGHFv.ReaPressExtend.core.NotificationCategory.TRANSIT),
                traceFetchMode = ExpressSettingsKeys.MODE_TRACE_AUTO,
                archiveMode = ExpressSettingsKeys.MODE_ARCHIVE_ON_SIGN,
                autoWatch = true, watchScope = ExpressSettingsKeys.MODE_WATCH_UNFINISHED,
                watchQuiet = false, watchQuietStart = 21, watchQuietEnd = 7,
                watchGapMinutes = 5, watchCycleMinutes = 60, watchNotification = false, noRootListener = true,
            )
            assertTrue(ExpressSettings.write(storage.context, expected))
            val remote = MemoryPreferences()
            remote.edit().putString("unrelated", "preserved").commit()

            ExpressSettings.attachService(storage.context, FakeService(remote))

            assertEquals(expected, ExpressSettingsKeys.readFrom(remote))
            assertEquals("preserved", remote.getString("unrelated", null))
        }
    }

    @Test
    fun `框架服务方法失败不会破坏本地设置或抛出异常`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            ExpressSettings.attachService(storage.context, Any())

            assertTrue(ExpressSettings.update(storage.context) { it.copy(sourceCainiao = false) })
            assertFalse(ExpressSettings.syncToFrameworkNow(storage.context))
            assertFalse(ExpressSettings.read(storage.context).sourceCainiao)
        }
    }

    @Test
    fun `系统先消费旧标志后模块再迁移不能产生第二次复位`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            val storage = PreferencesContext()
            val remote = MemoryPreferences()
            remote.edit().putBoolean(ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED, true).commit()
            storage.preferences(ExpressSettingsKeys.LOCAL_PREFS).edit()
                .putBoolean(ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED, true).commit()
            val reset = WatchdogStateMachine.beforeInstall(WatchdogStateMachine.State(disabled = true), ExpressSettingsKeys.hookResetRequest(remote))

            ExpressSettings.attachService(storage.context, FakeService(remote))

            val next = WatchdogStateMachine.beforeInstall(reset.state, ExpressSettingsKeys.hookResetRequest(remote))
            assertEquals(2, next.state.attempt)
            assertEquals(1, next.state.failures)
            assertEquals(reset.state.consumedResetId, next.state.consumedResetId)
        }
    }

    class FakeService(private val preferences: SharedPreferences) {
        fun getRemotePreferences(group: String): SharedPreferences {
            check(group == ExpressSettingsKeys.GROUP)
            return preferences
        }
    }
}
