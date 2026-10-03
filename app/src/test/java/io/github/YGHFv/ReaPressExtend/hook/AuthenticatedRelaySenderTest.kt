package io.github.YGHFv.ReaPressExtend.hook

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.core.RelayCredential
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogSink
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.testing.MemoryPreferences
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.withSettings
import org.mockito.Mockito.`when`

class AuthenticatedRelaySenderTest {
    @Test
    fun `宿主发送强制指定模块组件并附加框架凭据`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                mockStatic(Binder::class.java).use { binder ->
                    process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                    val intent = intentFor()

                    assertTrue(AuthenticatedRelaySender.send(fixture.context, intent))

                    verify(intent).setClassName(ExpressRelay.MODULE_PACKAGE, ExpressRelay.RECEIVER_CLASS)
                    verify(intent).setPackage(ExpressRelay.MODULE_PACKAGE)
                    verify(intent).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    verify(intent).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, fixture.credential)
                    verify(fixture.context).sendBroadcast(intent)
                    process.verify({ Process.myUserHandle() }, never())
                    binder.verifyNoInteractions()
                }
            }
        }
    }

    @Test
    fun `系统进程只在清除调用身份后按当前用户发送并恢复身份`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                mockStatic(Binder::class.java).use { binder ->
                    val user = mock(UserHandle::class.java)
                    val intent = intentFor()
                    val events = mutableListOf<String>()
                    process.`when`<Int> { Process.myUid() }.thenReturn(Process.SYSTEM_UID)
                    process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(user)
                    binder.`when`<Long> { Binder.clearCallingIdentity() }.thenAnswer {
                        events.add("cleared")
                        73L
                    }
                    doAnswer {
                        events.add("submitted")
                        null
                    }.`when`(fixture.context).sendBroadcastAsUser(intent, user)
                    binder.`when`<Unit> { Binder.restoreCallingIdentity(73L) }.thenAnswer {
                        events.add("restored")
                        null
                    }

                    assertTrue(AuthenticatedRelaySender.send(fixture.context, intent))

                    assertEquals(listOf("cleared", "submitted", "restored"), events)
                    verify(fixture.context, never()).sendBroadcast(intent)
                    binder.verify { Binder.restoreCallingIdentity(73L) }
                }
            }
        }
    }

    @Test
    fun `系统发送异常仍恢复调用身份且不把凭据带入日志`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                mockStatic(Binder::class.java).use { binder ->
                    val user = mock(UserHandle::class.java)
                    val intent = intentFor()
                    process.`when`<Int> { Process.myUid() }.thenReturn(Process.SYSTEM_UID)
                    process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(user)
                    binder.`when`<Long> { Binder.clearCallingIdentity() }.thenReturn(91L)
                    doThrow(SecurityException("sensitive payload ${fixture.credential}"))
                        .`when`(fixture.context).sendBroadcastAsUser(intent, user)

                    assertFalse(AuthenticatedRelaySender.send(fixture.context, intent))

                    binder.verify { Binder.restoreCallingIdentity(91L) }
                    verify(fixture.context, never()).sendBroadcast(intent)
                    fixture.assertRedactedFailure()
                }
            }
        }
    }

    @Test
    fun `框架未连接时拒绝发送且不读取载荷`() {
        Fixture(connected = false).use { fixture ->
            val intent = intentFor()

            assertFalse(AuthenticatedRelaySender.send(fixture.context, intent))

            verifyNoInteractions(fixture.context)
            verify(intent, never()).extras
        }
    }

    @Test
    fun `框架缺失或损坏的凭据不会降级成未认证广播`() {
        Fixture().use { fixture ->
            for (credential in listOf(null, "", "invalid", "a".repeat(63))) {
                fixture.remote.edit().putString(RelayCredential.KEY, credential).commit()
                val intent = intentFor()

                assertFalse(AuthenticatedRelaySender.send(fixture.context, intent))

                verify(intent, never()).extras
                verify(intent, never()).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, fixture.credential)
            }
            verifyNoInteractions(fixture.context)
        }
    }

    @Test
    fun `发送前拒绝未知动作和错误字段类型`() {
        Fixture().use { fixture ->
            val unknown = intentFor(action = "synthetic.unknown")
            val malformed = intentFor(fields = mapOf(ExpressRelay.EXTRA_COOKIE to 1234))
            for (intent in listOf(unknown, malformed)) {
                assertFalse(AuthenticatedRelaySender.send(fixture.context, intent))
                verify(intent, never()).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, fixture.credential)
            }
            verifyNoInteractions(fixture.context)
        }
    }

    @Test
    fun `框架读取异常不向宿主传播也不记录异常携带的秘密`() {
        Fixture().use { fixture ->
            `when`(fixture.framework.getRemotePreferences(RelayCredential.REMOTE_GROUP))
                .thenThrow(IllegalStateException("sensitive payload ${fixture.credential}"))
            val intent = intentFor()

            assertFalse(AuthenticatedRelaySender.send(fixture.context, intent))

            verifyNoInteractions(fixture.context)
            verify(intent, never()).extras
            fixture.assertRedactedFailure()
        }
    }

    @Test
    fun `框架日志同时故障仍安全返回发送失败`() {
        Fixture().use { fixture ->
            fixture.remote.edit().remove(RelayCredential.KEY).commit()
            fixture.failLogging = true

            assertFalse(AuthenticatedRelaySender.send(fixture.context, intentFor()))

            verifyNoInteractions(fixture.context)
        }
    }

    @Test
    fun `每次发送重新读取框架凭据而不缓存旧安装值`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                val original = intentFor()
                val renewed = intentFor()

                assertTrue(AuthenticatedRelaySender.send(fixture.context, original))
                val replacement = RelayCredential.generate()
                fixture.remote.edit().putString(RelayCredential.KEY, replacement).commit()
                assertTrue(AuthenticatedRelaySender.send(fixture.context, renewed))

                verify(original).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, fixture.credential)
                verify(renewed).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, replacement)
                verify(fixture.framework, times(2)).getRemotePreferences(RelayCredential.REMOTE_GROUP)
            }
        }
    }

    @Test
    fun `宿主发送失败返回false且日志不含载荷或原始异常`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                val intent = intentFor()
                doThrow(IllegalStateException("sensitive payload ${fixture.credential}"))
                    .`when`(fixture.context).sendBroadcast(intent)

                assertFalse(AuthenticatedRelaySender.send(fixture.context, intent))

                fixture.assertRedactedFailure()
            }
        }
    }

    @Test
    fun `真实富化入口保留失败回执且生成的完整载荷通过校验`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                withConstructedIntents { constructed ->
                    val record = ExpressRecord(
                        sourcePackage = "com.cainiao.wireless",
                        rawText = "synthetic parcel",
                        trackingNumber = "TEST12345678",
                        timestamp = 1_790_000_000_000L,
                    )
                    fixture.remote.edit().remove(RelayCredential.KEY).commit()
                    assertFalse(ExpressRelaySender.sendEnrichment(record, fixture.context))
                    verifyNoInteractions(fixture.context)

                    fixture.remote.edit().putString(RelayCredential.KEY, fixture.credential).commit()
                    assertTrue(ExpressRelaySender.sendEnrichment(record, fixture.context))

                    assertEquals(2, constructed.size)
                    verify(fixture.context, never()).sendBroadcast(constructed.first())
                    verify(fixture.context).sendBroadcast(constructed.last())
                    verify(constructed.last()).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, fixture.credential)
                }
            }
        }
    }

    @Test
    fun packageSnapshotFlagIsAuthenticatedEvenWhenPickupCodeIsMissing() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                withConstructedIntents { constructed ->
                    val record = ExpressRecord(sourcePackage = ExpressRelay.HOST_PACKAGE, rawText = "synthetic", trackingNumber = "TEST12345678")
                    assertTrue(ExpressRelaySender.sendEnrichment(record, fixture.context, packageSnapshot = true))
                    verify(constructed.single()).putExtra(ExpressRelay.EXTRA_PACKAGE_SNAPSHOT, true)
                    verify(constructed.single()).putExtra(ExpressRelay.EXTRA_RELAY_CREDENTIAL, fixture.credential)
                    verify(fixture.context).sendBroadcast(constructed.single())
                }
            }
        }
    }

    @Test
    fun `包裹采集鉴权失败撤回去重占位而成功发送后保留去重`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                withField(HostContextHolder, "cached", fixture.context) {
                    withField(CainiaoPackageHook, "mainProcess", false) {
                        withField(CainiaoPackageHook, "traceReceiverRegistered", true) {
                            withField(ExpressRelaySender, "lastCookieSyncAt", System.currentTimeMillis()) {
                                val record = ExpressRecord(
                                    sourcePackage = "com.cainiao.wireless",
                                    rawText = "synthetic retryable parcel",
                                    trackingNumber = "TESTRELAYRETRY",
                                    timestamp = 1_790_000_000_000L,
                                )
                                val method = CainiaoPackageHook::class.java.getDeclaredMethod("deliver", ExpressRecord::class.java)
                                    .apply { isAccessible = true }
                                @Suppress("UNCHECKED_CAST")
                                val delivered = CainiaoPackageHook::class.java.getDeclaredField("delivered")
                                    .apply { isAccessible = true }.get(CainiaoPackageHook) as MutableSet<String>
                                val original = delivered.toSet()
                                try {
                                    withConstructedIntents { constructed ->
                                        fixture.remote.edit().remove(RelayCredential.KEY).commit()
                                        assertEquals(false, method.invoke(CainiaoPackageHook, record))
                                        assertEquals(original, delivered.toSet())
                                        verifyNoInteractions(fixture.context)

                                        fixture.remote.edit().putString(RelayCredential.KEY, fixture.credential).commit()
                                        assertEquals(true, method.invoke(CainiaoPackageHook, record))
                                        assertEquals(original.size + 1, delivered.size)
                                        assertEquals(false, method.invoke(CainiaoPackageHook, record))
                                        assertEquals(2, constructed.size)
                                        verify(fixture.context, never()).sendBroadcast(constructed.first())
                                        verify(fixture.context).sendBroadcast(constructed.last())
                                    }
                                } finally {
                                    delivered.clear()
                                    delivered.addAll(original)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `登录态鉴权失败不进入同步冷却而成功提交后节流`() {
        Fixture().use { fixture ->
            mockStatic(Process::class.java).use { process ->
                process.`when`<Int> { Process.myUid() }.thenReturn(10_123)
                withField(HostCredentialSource, "cached", "synthetic-cookie") {
                    withField(HostCredentialSource, "cachedAt", System.currentTimeMillis()) {
                        withField(ExpressRelaySender, "lastCookieSyncAt", 0L) {
                            withConstructedIntents { constructed ->
                                fixture.remote.edit().remove(RelayCredential.KEY).commit()
                                ExpressRelaySender.sendCookieSync(fixture.context)
                                verifyNoInteractions(fixture.context)

                                fixture.remote.edit().putString(RelayCredential.KEY, fixture.credential).commit()
                                ExpressRelaySender.sendCookieSync(fixture.context)
                                ExpressRelaySender.sendCookieSync(fixture.context)

                                assertEquals(2, constructed.size)
                                verify(fixture.context, never()).sendBroadcast(constructed.first())
                                verify(fixture.context).sendBroadcast(constructed.last())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun intentFor(
        action: String = ExpressRelay.ACTION_COOKIE_SYNC,
        fields: Map<String, Any?> = mapOf(ExpressRelay.EXTRA_COOKIE to "synthetic-cookie"),
    ): Intent = mock(Intent::class.java, RETURNS_SELF).apply {
        val bundle = bundleFor(fields)
        `when`(this.action).thenReturn(action)
        `when`(extras).thenReturn(bundle)
    }

    private fun bundleFor(fields: Map<String, Any?>): Bundle = mock(Bundle::class.java).apply {
        `when`(size()).thenAnswer { fields.size }
        `when`(keySet()).thenAnswer { fields.keys.toSet() }
        @Suppress("DEPRECATION")
        `when`(get(anyString())).thenAnswer { fields[it.getArgument<String>(0)] }
    }

    private fun withConstructedIntents(block: (List<Intent>) -> Unit) {
        val payloads = IdentityHashMap<Intent, MutableMap<String, Any?>>()
        val settings = withSettings().defaultAnswer { invocation ->
            if (invocation.method.name == "putExtra") {
                val intent = invocation.mock as Intent
                payloads.getOrPut(intent) { linkedMapOf() }[invocation.getArgument<String>(0)] =
                    invocation.getArgument<Any?>(1)
                intent
            } else {
                RETURNS_SELF.answer(invocation)
            }
        }
        mockConstruction(Intent::class.java, settings) { intent, construction ->
            val bundle = bundleFor(payloads.getOrPut(intent) { linkedMapOf() })
            `when`(intent.action).thenReturn(construction.arguments().firstOrNull() as? String)
            `when`(intent.extras).thenReturn(bundle)
        }.use { block(it.constructed()) }
    }

    private fun withField(owner: Any, name: String, value: Any?, block: () -> Unit) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val original = field.get(owner)
        try {
            field.set(owner, value)
            block()
        } finally {
            field.set(owner, original)
        }
    }

    private class Fixture(connected: Boolean = true) : AutoCloseable {
        val context: Context = mock(Context::class.java)
        val framework: XposedInterface = mock(XposedInterface::class.java)
        val remote = MemoryPreferences()
        val credential = RelayCredential.generate()
        var failLogging = false
        private val logs = mutableListOf<Pair<String, Throwable?>>()
        private val frameworkReference = bridgeReference<XposedInterface?>("frameworkRef")
        private val sinkReference = bridgeReference<ModuleLogSink?>("logSinkRef")
        private val previousFramework = frameworkReference.get()
        private val previousSink = sinkReference.get()

        init {
            remote.edit().putString(RelayCredential.KEY, credential).commit()
            `when`(framework.getRemotePreferences(RelayCredential.REMOTE_GROUP)).thenReturn(remote)
            frameworkReference.set(framework.takeIf { connected })
            sinkReference.set(object : ModuleLogSink {
                override fun log(priority: Int, tag: String, text: String, throwable: Throwable?) {
                    check(!failLogging) { "synthetic logging failure" }
                    logs.add(text to throwable)
                }
            })
        }

        fun assertRedactedFailure() {
            assertTrue(logs.isNotEmpty())
            logs.forEach { (text, throwable) ->
                assertFalse(text.contains(credential))
                assertFalse(text.contains("sensitive payload"))
                assertNull(throwable)
            }
        }

        override fun close() {
            frameworkReference.set(previousFramework)
            sinkReference.set(previousSink)
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            private fun <Value> bridgeReference(name: String): AtomicReference<Value> =
                XposedBridge::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(XposedBridge) as AtomicReference<Value>
        }
    }
}
