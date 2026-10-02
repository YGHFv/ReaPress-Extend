package io.github.YGHFv.ReaPressExtend.relay

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import io.github.YGHFv.ReaPressExtend.core.RelayCredential
import io.github.YGHFv.ReaPressExtend.notification.ExpressRecordStore
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class RelayIngressTest {
    @Test
    fun `无凭据的实际接收器在访问业务存储前退出`() {
        val storage = PreferencesContext()
        val intent = intentFor(ExpressRelay.ACTION_COOKIE_SYNC, null, mapOf(ExpressRelay.EXTRA_COOKIE to "forged"))

        ExpressRelayReceiver().onReceive(storage.context, intent)

        assertEquals(setOf(RelayCredential.LOCAL_PREFS), storage.names())
        verify(intent, never()).extras
    }

    @Test
    fun `错误凭据不能覆盖已存在的登录态或写入包裹`() {
        val storage = PreferencesContext()
        RelayCredentialStore.ensure(storage.context)
        val cookies = storage.preferences(TraceCookieStore.PREFS)
        cookies.edit().putString("sentinel", "unchanged").commit()
        val originalRecords = storage.preferences(ExpressRecordStore.PREFS).all
        val intent = intentFor(ExpressRelay.ACTION_COOKIE_SYNC, RelayCredential.generate(),
            mapOf(ExpressRelay.EXTRA_COOKIE to "forged"))

        ExpressRelayReceiver().onReceive(storage.context, intent)

        assertEquals("unchanged", cookies.getString("sentinel", null))
        assertEquals(originalRecords, storage.preferences(ExpressRecordStore.PREFS).all)
        verify(intent, never()).extras
    }

    @Test
    fun `合法凭据通过后从交给业务的消息移除`() {
        val storage = PreferencesContext()
        val credential = RelayCredentialStore.ensure(storage.context)
        val intent = intentFor(ExpressRelay.ACTION_COOKIE_SYNC, credential,
            mapOf(ExpressRelay.EXTRA_COOKIE to "synthetic-cookie"))

        assertTrue(RelayIngress.accept(storage.context, intent))
        verify(intent).removeExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL)
    }

    @Test
    fun `凭据正确但字段类型错误仍在实际接收器入口退出`() {
        val storage = PreferencesContext()
        val credential = RelayCredentialStore.ensure(storage.context)
        val intent = intentFor(ExpressRelay.ACTION_COOKIE_SYNC, credential,
            mapOf(ExpressRelay.EXTRA_COOKIE to 1234))

        ExpressRelayReceiver().onReceive(storage.context, intent)

        assertEquals(setOf(RelayCredential.LOCAL_PREFS), storage.names())
        verify(intent, never()).removeExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL)
    }

    @Test
    fun `畸形或不能反序列化的消息不会把异常传播到广播线程`() {
        val storage = PreferencesContext()
        RelayCredentialStore.ensure(storage.context)
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL))
            .thenThrow(IllegalArgumentException("synthetic malformed parcel"))

        assertFalse(RelayIngress.accept(storage.context, intent))
        ExpressRelayReceiver().onReceive(storage.context, intent)
    }

    @Test
    fun `合法凭据配合异常Bundle同样安全拒绝`() {
        val storage = PreferencesContext()
        val credential = RelayCredentialStore.ensure(storage.context)
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL)).thenReturn(credential)
        `when`(intent.extras).thenThrow(IllegalArgumentException("synthetic bundle failure"))

        assertFalse(RelayIngress.accept(storage.context, intent))
        ExpressRelayReceiver().onReceive(storage.context, intent)
    }

    @Test
    fun `真实PendingIntent类型仅能出现在指定回传字段`() {
        val storage = PreferencesContext()
        val credential = RelayCredentialStore.ensure(storage.context)
        val token = mock(PendingIntent::class.java)
        val good = intentFor(ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED, credential,
            mapOf(ExpressRelay.EXTRA_INTENT_ENTRY_ID to "entry", ExpressRelay.EXTRA_NOTIFICATION_INTENT to token))
        val bad = intentFor(ExpressRelay.ACTION_COOKIE_SYNC, credential,
            mapOf(ExpressRelay.EXTRA_COOKIE to "synthetic", ExpressRelay.EXTRA_NOTIFICATION_INTENT to token))

        assertTrue(RelayIngress.accept(storage.context, good))
        assertFalse(RelayIngress.accept(storage.context, bad))
    }

    @Test
    fun `不能用序列化的策略标记伪装成真实PendingIntent`() {
        val storage = PreferencesContext()
        val credential = RelayCredentialStore.ensure(storage.context)
        val intent = intentFor(ExpressRelay.ACTION_INTENT_TOKEN_ARRIVED, credential,
            mapOf(ExpressRelay.EXTRA_INTENT_ENTRY_ID to "entry",
                ExpressRelay.EXTRA_NOTIFICATION_INTENT to RelayPayloadPolicy.OpaqueValue.PENDING_INTENT))

        assertFalse(RelayIngress.accept(storage.context, intent))
    }

    @Test
    fun `缺少凭据时不会读取畸形或超大Bundle`() {
        val storage = PreferencesContext()
        val intent = mock(Intent::class.java)
        `when`(intent.extras).thenThrow(AssertionError("untrusted payload must not be read"))

        assertFalse(RelayIngress.accept(storage.context, intent))
        verify(intent, never()).extras
    }

    @Test
    fun `清除模块数据后旧安装凭据不再有效`() {
        val previous = RelayCredentialStore.ensure(PreferencesContext().context)
        val current = PreferencesContext()
        RelayCredentialStore.ensure(current.context)
        val intent = intentFor(ExpressRelay.ACTION_COOKIE_SYNC, previous,
            mapOf(ExpressRelay.EXTRA_COOKIE to "synthetic-cookie"))

        assertFalse(RelayIngress.accept(current.context, intent))
    }

    private fun intentFor(action: String, credential: String?, fields: Map<String, Any?>): Intent {
        val intent = mock(Intent::class.java)
        val extras = mock(Bundle::class.java)
        val values = fields + (ExpressRelay.EXTRA_RELAY_CREDENTIAL to credential)
        `when`(intent.action).thenReturn(action)
        `when`(intent.getStringExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL)).thenReturn(credential)
        `when`(intent.extras).thenReturn(extras)
        `when`(extras.size()).thenReturn(values.size)
        `when`(extras.keySet()).thenReturn(values.keys)
        values.forEach { (name, value) ->
            @Suppress("DEPRECATION")
            `when`(extras.get(name)).thenReturn(value)
        }
        return intent
    }
}
