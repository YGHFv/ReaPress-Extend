package io.github.YGHFv.ReaPressExtend.relay

import io.github.YGHFv.ReaPressExtend.backup.ExpressBackup
import io.github.YGHFv.ReaPressExtend.core.RelayCredential
import io.github.YGHFv.ReaPressExtend.testing.MemoryPreferences
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCredentialStoreTest {
    @Test
    fun `首次初始化落盘后重读及重复初始化不轮换凭据`() {
        val storage = PreferencesContext()
        assertNull(RelayCredentialStore.read(storage.context))
        val credential = RelayCredentialStore.ensure(storage.context)

        assertTrue(RelayCredential.isValid(credential))
        assertEquals(credential, RelayCredentialStore.read(storage.context))
        assertEquals(credential, RelayCredentialStore.ensure(storage.context))
        assertEquals(2, storage.preferences(RelayCredential.LOCAL_PREFS).commits)
    }

    @Test
    fun `并发初始化只生成并保存同一个凭据`() {
        val storage = PreferencesContext()
        val workers = Executors.newFixedThreadPool(4)
        val release = CountDownLatch(1)
        try {
            val results = (1..4).map {
                workers.submit<String?> {
                    check(release.await(5, TimeUnit.SECONDS))
                    RelayCredentialStore.ensure(storage.context)
                }
            }
            release.countDown()
            val credentials = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, credentials.toSet().size)
            assertTrue(RelayCredential.isValid(credentials.first()))
            assertEquals(4, storage.preferences(RelayCredential.LOCAL_PREFS).commits)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `框架重连重新提交本地权威值而不轮换凭据`() {
        val storage = PreferencesContext()
        val remote = MemoryPreferences()
        val credential = RelayCredentialStore.ensure(storage.context)
        assertTrue(RelayCredentialStore.publish(storage.context, remote))
        assertTrue(RelayCredentialStore.publish(storage.context, remote))

        assertEquals(credential, remote.getString(RelayCredential.KEY, null))
        assertEquals(2, remote.commits)
    }

    @Test
    fun `清除本地数据后的新凭据覆盖框架旧值而不从框架导入`() {
        val oldStorage = PreferencesContext()
        val newStorage = PreferencesContext()
        val remote = MemoryPreferences()
        RelayCredentialStore.publish(oldStorage.context, remote)
        val previous = remote.getString(RelayCredential.KEY, null)
        assertTrue(RelayCredentialStore.publish(newStorage.context, remote))
        val current = RelayCredentialStore.read(newStorage.context)

        assertTrue(RelayCredential.isValid(current))
        assertNotEquals(previous, current)
        assertEquals(current, remote.getString(RelayCredential.KEY, null))
    }

    @Test
    fun `本地损坏值重新生成而不接受任意长度密钥`() {
        val storage = PreferencesContext()
        storage.preferences(RelayCredential.LOCAL_PREFS).edit()
            .putString(RelayCredential.KEY, "invalid").commit()

        assertNull(RelayCredentialStore.read(storage.context))
        assertTrue(RelayCredential.isValid(RelayCredentialStore.ensure(storage.context)))
    }

    @Test
    fun `写入失败不会报告初始化或发布成功`() {
        val storage = PreferencesContext()
        storage.preferences(RelayCredential.LOCAL_PREFS).commitSucceeds = false
        assertNull(RelayCredentialStore.ensure(storage.context))

        val remote = MemoryPreferences().apply { commitSucceeds = false }
        assertFalse(RelayCredentialStore.publish(PreferencesContext().context, remote))
    }

    @Test
    fun `框架提交失败后即使内存已有相同值仍重试提交`() {
        val storage = PreferencesContext()
        val remote = MemoryPreferences().apply { commitSucceeds = false }
        assertFalse(RelayCredentialStore.publish(storage.context, remote))
        assertEquals(RelayCredentialStore.read(storage.context), remote.getString(RelayCredential.KEY, null))
        assertFalse(RelayCredentialStore.publish(storage.context, remote))
        assertEquals(2, remote.commits)

        remote.commitSucceeds = true
        assertTrue(RelayCredentialStore.publish(storage.context, remote))
        assertEquals(3, remote.commits)
    }

    @Test
    fun `本地落盘失败后不能仅凭内存中的凭据发布到框架`() {
        val storage = PreferencesContext()
        val local = storage.preferences(RelayCredential.LOCAL_PREFS)
        local.commitSucceeds = false
        val remote = MemoryPreferences()
        assertNull(RelayCredentialStore.ensure(storage.context))
        assertTrue(RelayCredential.isValid(RelayCredentialStore.read(storage.context)))
        assertFalse(RelayCredentialStore.publish(storage.context, remote))
        assertEquals(0, remote.commits)

        local.commitSucceeds = true
        assertTrue(RelayCredentialStore.publish(storage.context, remote))
        assertEquals(RelayCredentialStore.read(storage.context), remote.getString(RelayCredential.KEY, null))
    }

    @Test
    fun `认证凭据不属于导入导出的备份白名单`() {
        assertFalse(RelayCredential.LOCAL_PREFS in ExpressBackup.PREFS_NAMES)
        assertFalse(RelayCredential.REMOTE_GROUP in ExpressBackup.PREFS_NAMES)
    }
}
