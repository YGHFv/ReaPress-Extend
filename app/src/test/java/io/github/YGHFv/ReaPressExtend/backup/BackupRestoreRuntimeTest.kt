package io.github.YGHFv.ReaPressExtend.backup

import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.config.SettingsSyncTest.FakeService
import io.github.YGHFv.ReaPressExtend.core.BackupBundle
import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.relay.AutoWatchService
import io.github.YGHFv.ReaPressExtend.relay.ModuleTraceFetcher
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieStore
import io.github.YGHFv.ReaPressExtend.testing.MemoryPreferences
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BackupRestoreRuntimeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `已绑定缓存的进程恢复登录态后立即使用新Cookie和UA`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(TraceCookieCache, "cookie", null)
            state.set(TraceCookieCache, "hostUa", null)
            state.set(TraceCookieCache, "syncedAt", 0L)
            state.set(TraceCookieCache, "store", null)
            state.set(TraceCookieCache, "restored", false)
            state.set(CainiaoTraceApi, "preferredUa", null)
            val storage = PreferencesContext(temporary.newFolder())
            TraceCookieCache.attach(storage.context)
            TraceCookieCache.put("synthetic-old-cookie", "synthetic-old-ua")
            val now = System.currentTimeMillis()
            val backup = BackupBundle.encode(BackupBundle.Payload(
                version = BackupBundle.FORMAT_VERSION,
                exportedAt = now,
                appVersion = "test",
                prefs = mapOf(TraceCookieStore.PREFS to mapOf(
                    "cookie" to "synthetic-new-cookie", "ua" to "synthetic-new-ua", "at" to now,
                )),
            ))

            val outcome = ExpressBackup.restore(storage.context, backup, now)

            assertTrue(outcome.message, outcome.ok)
            assertEquals("synthetic-new-cookie", TraceCookieCache.get())
            assertEquals("synthetic-new-ua", TraceCookieCache.hostUa)
            assertEquals("synthetic-new-ua", CainiaoTraceApi.preferredUa)
        }
    }

    @Test
    fun `恢复空登录态清空旧Cookie及引擎UA`() = withAndroidTestRuntime {
        isolated { storage ->
            TraceCookieCache.attach(storage.context)
            TraceCookieCache.put("synthetic-old-cookie", "synthetic-old-ua")

            val outcome = restore(storage, mapOf(TraceCookieStore.PREFS to emptyMap()))

            assertTrue(outcome.ok)
            assertNull(TraceCookieCache.get())
            assertNull(TraceCookieCache.hostUa)
            assertNull(CainiaoTraceApi.preferredUa)
            assertNull(TraceCookieCache.ageMs())
        }
    }

    @Test
    fun `恢复无UA或已过期登录态不沿用原有UA`() = withAndroidTestRuntime {
        isolated { storage ->
            TraceCookieCache.attach(storage.context)
            TraceCookieCache.put("synthetic-old-cookie", "synthetic-old-ua")
            val now = System.currentTimeMillis()
            assertTrue(restore(storage, mapOf(TraceCookieStore.PREFS to mapOf("cookie" to "new", "at" to now))).ok)
            assertEquals("new", TraceCookieCache.get())
            assertNull(TraceCookieCache.hostUa)

            assertTrue(restore(storage, mapOf(TraceCookieStore.PREFS to mapOf("cookie" to "expired", "at" to 1L))).ok)

            assertNull(TraceCookieCache.get())
            assertNull(CainiaoTraceApi.preferredUa)
        }
    }

    @Test
    fun `恢复开启轮查立即请求重启服务并同步框架配置`() = withAndroidTestRuntime {
        isolated { storage ->
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))
            val expected = ExpressSettingsSnapshot(autoWatch = true, sourceCainiao = false, watchGapMinutes = 10)
            val values = settingsOf(expected)

            val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to values))

            assertTrue(outcome.message, outcome.ok)
            assertTrue(outcome.runtimeWarnings.isEmpty())
            assertEquals(expected, ExpressSettings.read(storage.context))
            assertEquals(expected, ExpressSettingsKeys.readFrom(remote))
            val intent = ArgumentCaptor.forClass(Intent::class.java)
            verify(storage.context).startForegroundService(intent.capture())
            verify(intent.value).setAction(AutoWatchService.ACTION_RELOAD)
            verify(storage.context, never()).stopService(any(Intent::class.java))
        }
    }

    @Test
    fun `恢复关闭轮查立即停止服务`() = withAndroidTestRuntime {
        isolated { storage ->
            assertTrue(ExpressSettings.write(storage.context, ExpressSettingsSnapshot(autoWatch = true)))

            val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to settingsOf(ExpressSettingsSnapshot())))

            assertTrue(outcome.ok)
            verify(storage.context).stopService(any(Intent::class.java))
            verify(storage.context, never()).startForegroundService(any(Intent::class.java))
        }
    }

    @Test
    fun `前台服务被系统拒绝时保留恢复数据并明确返回运行态警告`() = withAndroidTestRuntime {
        isolated { storage ->
            doThrow(SecurityException("synthetic denied"))
                .`when`(storage.context).startForegroundService(any(Intent::class.java))

            val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to settingsOf(ExpressSettingsSnapshot(autoWatch = true))))

            assertTrue(outcome.ok)
            assertTrue(outcome.dataChanged)
            assertTrue(ExpressSettings.read(storage.context).autoWatch)
            assertTrue(outcome.runtimeWarnings.any { it.contains("自动轮查") })
            verify(storage.context).sendBroadcast(any(Intent::class.java))
        }
    }

    @Test
    fun `框架离线恢复保留本地配置并在后来绑定时补同步`() = withAndroidTestRuntime {
        isolated { storage ->
            val expected = ExpressSettingsSnapshot(sourceCainiao = false, watchCycleMinutes = 120)
            val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to settingsOf(expected)))
            assertTrue(outcome.ok)
            assertTrue(outcome.runtimeWarnings.isEmpty())
            val remote = MemoryPreferences()

            ExpressSettings.attachService(storage.context, FakeService(remote))

            assertEquals(expected, ExpressSettingsKeys.readFrom(remote))
        }
    }

    @Test
    fun `框架提交失败不冒充全部运行态已同步`() = withAndroidTestRuntime {
        isolated { storage ->
            val remote = MemoryPreferences()
            ExpressSettings.attachService(storage.context, FakeService(remote))
            remote.commitSucceeds = false

            val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to settingsOf(ExpressSettingsSnapshot(sourceSms = false))))

            assertTrue(outcome.ok)
            assertFalse(ExpressSettings.read(storage.context).sourceSms)
            assertTrue(outcome.runtimeWarnings.any { it.contains("框架配置") })
        }
    }

    @Test
    fun `写入失败会回滚已经写入的存储且不启动服务`() = withAndroidTestRuntime {
        isolated { storage ->
            val local = storage.preferences(ExpressSettingsKeys.LOCAL_PREFS)
            val cookies = storage.preferences(TraceCookieStore.PREFS)
            assertTrue(ExpressSettings.write(storage.context, ExpressSettingsSnapshot(sourceSms = false)))
            TraceCookieCache.attach(storage.context)
            TraceCookieCache.put("synthetic-old-cookie", "synthetic-old-ua")
            val beforeSettings = local.all
            val beforeCookie = cookies.all
            var writes = 0
            cookies.beforeWrite = { cookies.commitSucceeds = ++writes != 1 }

            val outcome = restore(storage, linkedMapOf(
                ExpressSettingsKeys.LOCAL_PREFS to settingsOf(ExpressSettingsSnapshot(autoWatch = true)),
                TraceCookieStore.PREFS to mapOf("cookie" to "synthetic-new-cookie", "at" to System.currentTimeMillis()),
            ))

            assertFalse(outcome.ok)
            assertFalse(outcome.dataChanged)
            assertTrue(outcome.message.contains("已回滚"))
            assertEquals(beforeSettings, local.all)
            assertEquals(beforeCookie, cookies.all)
            assertEquals("synthetic-old-cookie", TraceCookieCache.get())
            verify(storage.context, never()).startForegroundService(any(Intent::class.java))
            verify(storage.context, never()).stopService(any(Intent::class.java))
            verify(storage.context, never()).sendBroadcast(any(Intent::class.java))
        }
    }

    @Test
    fun `回滚仍失败时返回明确风险并让UI刷新当前实际数据`() = withAndroidTestRuntime {
        isolated { storage ->
            storage.preferences(TraceCookieStore.PREFS).commitSucceeds = false

            val outcome = restore(storage, mapOf(TraceCookieStore.PREFS to mapOf("cookie" to "new", "at" to System.currentTimeMillis())))

            assertFalse(outcome.ok)
            assertTrue(outcome.dataChanged)
            assertTrue(outcome.message.contains("未能确认回滚"))
            assertTrue(outcome.snapshotName != null)
            verify(storage.context).sendBroadcast(any(Intent::class.java))
        }
    }

    @Test
    fun `导入导出排除复位标志且恢复保留本机请求ID`() = withAndroidTestRuntime {
        isolated { storage ->
            assertTrue(ExpressSettings.requestHookReset(storage.context))
            val local = storage.preferences(ExpressSettingsKeys.LOCAL_PREFS)
            val request = ExpressSettingsKeys.hookResetRequest(local)
            val exported = BackupBundle.decode(ExpressBackup.export(storage.context, 1_000L))
            assertFalse(exported.prefs.getValue(ExpressSettingsKeys.LOCAL_PREFS).keys.any { it in ExpressSettingsKeys.HOOK_RESET_KEYS })

            val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to mapOf(
                ExpressSettingsKeys.KEY_HOOK_FORCE_ENABLED to true,
                ExpressSettingsKeys.KEY_HOOK_RESET_REQUEST_ID to "forged-backup-reset",
                ExpressSettingsKeys.KEY_SOURCE_SMS to false,
            )))

            assertTrue(outcome.ok)
            assertEquals(request, ExpressSettingsKeys.hookResetRequest(local))
            val snapshot = BackupBundle.decode(java.io.File(storage.context.filesDir, requireNotNull(outcome.snapshotName)).readText())
            assertFalse(snapshot.prefs.getValue(ExpressSettingsKeys.LOCAL_PREFS).keys.any { it in ExpressSettingsKeys.HOOK_RESET_KEYS })
            assertFalse(ExpressSettings.read(storage.context).sourceSms)
        }
    }

    @Test
    fun `恢复旧备份不能缩短已有风控退避且可采纳更长退避`() = withAndroidTestRuntime {
        isolated { storage ->
            val current = System.currentTimeMillis() + 600_000L
            CainiaoTraceApi.restoreRisk(current)
            val prefs = storage.preferences(ModuleTraceFetcher.PREFS)
            prefs.edit().putLong(ModuleTraceFetcher.KEY_RISK_UNTIL, current).commit()
            assertTrue(restore(storage, mapOf(ModuleTraceFetcher.PREFS to mapOf(ModuleTraceFetcher.KEY_RISK_UNTIL to 0L))).ok)
            assertEquals(current, CainiaoTraceApi.riskBlockedUntil)
            assertEquals(current, prefs.getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0L))

            val later = current + 600_000L
            assertTrue(restore(storage, mapOf(ModuleTraceFetcher.PREFS to mapOf(ModuleTraceFetcher.KEY_RISK_UNTIL to later))).ok)

            assertEquals(later, CainiaoTraceApi.riskBlockedUntil)
            assertEquals(later, prefs.getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0L))
        }
    }

    @Test
    fun `仅恢复未知存储不触发缓存服务或UI副作用`() = withAndroidTestRuntime {
        isolated { storage ->
            val outcome = restore(storage, mapOf("unknown" to mapOf("value" to "synthetic")))
            assertTrue(outcome.ok)
            assertFalse(outcome.dataChanged)
            verify(storage.context, never()).startForegroundService(any(Intent::class.java))
            verify(storage.context, never()).stopService(any(Intent::class.java))
            verify(storage.context, never()).sendBroadcast(any(Intent::class.java))
        }
    }

    @Test
    fun `风控状态无法保存时按需拉取安全停止且不发网络或宿主请求`() = withAndroidTestRuntime {
        isolated { storage ->
            TraceCookieCache.attach(storage.context)
            TraceCookieCache.put("synthetic-cookie", "synthetic-ua")
            storage.preferences(ModuleTraceFetcher.PREFS).commitSucceeds = false

            assertTrue(ModuleTraceFetcher.onDemand(storage.context, "TEST-RISK-FAILURE"))

            verify(storage.context, never()).sendBroadcast(any(Intent::class.java))
        }
    }

    @Test
    fun `恢复更严格退避后其他存储失败回滚也不能降低保护`() = withAndroidTestRuntime {
        isolated { storage ->
            val until = System.currentTimeMillis() + 600_000L
            val cookies = storage.preferences(TraceCookieStore.PREFS)
            var writes = 0
            cookies.beforeWrite = { cookies.commitSucceeds = ++writes != 1 }

            val outcome = restore(storage, linkedMapOf(
                ModuleTraceFetcher.PREFS to mapOf(ModuleTraceFetcher.KEY_RISK_UNTIL to until),
                TraceCookieStore.PREFS to mapOf("cookie" to "new", "at" to System.currentTimeMillis()),
            ))

            assertFalse(outcome.ok)
            assertEquals(until, CainiaoTraceApi.riskBlockedUntil)
            assertEquals(until, storage.preferences(ModuleTraceFetcher.PREFS).getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0L))
        }
    }

    @Test
    fun `恢复较长退避后新的风险回执不能缩短剩余保护`() = withAndroidTestRuntime {
        isolated { storage ->
            val until = System.currentTimeMillis() + 3_600_000L
            assertTrue(restore(storage, mapOf(ModuleTraceFetcher.PREFS to mapOf(ModuleTraceFetcher.KEY_RISK_UNTIL to until))).ok)
            val markRisk = CainiaoTraceApi::class.java.getDeclaredMethod("markRiskBlocked").apply { isAccessible = true }

            markRisk.invoke(CainiaoTraceApi)

            assertEquals(until, CainiaoTraceApi.riskBlockedUntil)
            assertEquals(until, storage.preferences(ModuleTraceFetcher.PREFS).getLong(ModuleTraceFetcher.KEY_RISK_UNTIL, 0L))
        }
    }

    @Test
    fun `恢复后按本机备份配置重排闹钟而不导入目录密码或立即请求网络`() = withAndroidTestRuntime {
        isolated { storage ->
            val config = BackupConfig(intervalMs = 3_600_000L, retention = 5, encrypt = true, password = "synthetic-password")
            BackupSettings.save(storage.context, config)
            val manager = mock(AlarmManager::class.java)
            `when`(storage.context.getSystemService(AlarmManager::class.java)).thenReturn(manager)
            val pending = mock(PendingIntent::class.java)
            mockStatic(PendingIntent::class.java).use { intents ->
                intents.`when`<PendingIntent> {
                    PendingIntent.getBroadcast(eq(storage.context), anyInt(), any(Intent::class.java), anyInt())
                }.thenReturn(pending)

                val outcome = restore(storage, mapOf(ExpressSettingsKeys.LOCAL_PREFS to settingsOf(ExpressSettingsSnapshot())))

                assertTrue(outcome.ok)
                assertEquals(config, BackupSettings.load(storage.context))
                verify(manager).setInexactRepeating(eq(AlarmManager.RTC_WAKEUP), anyLong(), eq(config.intervalMs), eq(pending))
                assertTrue(outcome.runtimeWarnings.isEmpty())
            }
        }
    }

    private fun isolated(block: (PreferencesContext) -> Unit) {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            state.set(TraceCookieCache, "cookie", null)
            state.set(TraceCookieCache, "hostUa", null)
            state.set(TraceCookieCache, "syncedAt", 0L)
            state.set(TraceCookieCache, "store", null)
            state.set(TraceCookieCache, "restored", false)
            state.set(CainiaoTraceApi, "preferredUa", null)
            state.set(CainiaoTraceApi, "riskBlockedUntil", 0L)
            state.set(CainiaoTraceApi, "backoffLevel", 0)
            state.set(CainiaoTraceApi, "onRiskMarked", null)
            state.set(ModuleTraceFetcher, "riskRestored", false)
            block(PreferencesContext(temporary.newFolder()))
        }
    }

    private fun restore(storage: PreferencesContext, preferences: Map<String, Map<String, Any?>>): RestoreOutcome {
        val now = System.currentTimeMillis()
        val backup = BackupBundle.encode(BackupBundle.Payload(
            version = BackupBundle.FORMAT_VERSION, exportedAt = now, appVersion = "test", prefs = preferences,
        ))
        return ExpressBackup.restore(storage.context, backup, now)
    }

    private fun settingsOf(snapshot: ExpressSettingsSnapshot): Map<String, Any?> = MemoryPreferences().run {
        check(ExpressSettingsKeys.writeTo(this, snapshot))
        all
    }
}
