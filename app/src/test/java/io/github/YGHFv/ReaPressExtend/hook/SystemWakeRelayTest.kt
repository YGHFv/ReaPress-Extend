package io.github.YGHFv.ReaPressExtend.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Process
import android.os.UserHandle
import android.util.Log
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogSink
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.withAndroidSdk
import io.github.YGHFv.ReaPressExtend.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SystemWakeRelayTest {
    @Test
    fun nonSystemProcessCannotRegisterEvenIfFlagWasPreviouslySet() = fixture(uid = 10_123) { f ->
        assertFalse(SystemWakeRelay.ensureRegistered(f.context))
        ObjectStateScope().use { state ->
            state.set(SystemWakeRelay, "registered", true)
            assertFalse(SystemWakeRelay.ensureRegistered(f.context))
        }
        verifyNoInteractions(f.context)
    }

    @Test
    fun missingContextDoesNotRegister() = fixture { _ ->
        assertFalse(SystemWakeRelay.ensureRegistered(null))
    }

    @Test
    fun registrationOnAndroid35RequiresSignaturePermissionAndIsIdempotent() = fixture { f ->
        f.register()
        assertTrue(SystemWakeRelay.ensureRegistered(f.context))
        verify(f.context, times(1)).registerReceiver(
            same(f.receiver), any(IntentFilter::class.java), eq(ExpressRelay.PERMISSION_TRACE_REQUEST), isNull(), eq(Context.RECEIVER_EXPORTED),
        )
        verify(f.filter!!).addAction(ExpressRelay.ACTION_WAKE_REQUEST)
    }

    @Test
    fun registrationOnAndroid26AlsoRequiresSignaturePermission() = fixture(sdk = 26) { f ->
        f.register()
        verify(f.context).registerReceiver(same(f.receiver), any(IntentFilter::class.java), eq(ExpressRelay.PERMISSION_TRACE_REQUEST), isNull())
    }

    @Test
    fun failedRegistrationCanRetry() = fixture { f ->
        f.failRegistration = true
        assertFalse(SystemWakeRelay.ensureRegistered(f.context))
        f.failRegistration = false
        f.register()
    }

    @Test
    fun wrongActionDoesNotSendOrClearIdentity() = fixture { f ->
        f.register()
        mockStatic(Binder::class.java).use { binder ->
            f.receiver!!.onReceive(f.context, f.request("synthetic.unknown"))
            binder.verifyNoInteractions()
            assertTrue(f.pins.isEmpty())
        }
    }

    @Test
    fun pinIsFixedAndSentOnlyBetweenClearAndRestore() = fixture { f ->
        f.register()
        mockStatic(Binder::class.java).use { binder ->
            binder.`when`<Long> { Binder.clearCallingIdentity() }.thenAnswer { f.events += "clear"; 73L }
            binder.`when`<Unit> { Binder.restoreCallingIdentity(73L) }.thenAnswer { f.events += "restore"; null }
            val request = f.request()
            f.receiver!!.onReceive(f.context, request)
            assertEquals(listOf("clear", "send", "restore"), f.events)
            val pin = f.pins.single()
            assertNotSame(request, pin)
            assertEquals("com.cainiao.wireless.notification_dismiss", pin.action)
            assertEquals("com.cainiao.wireless", pin.component!!.packageName)
            assertEquals("com.cainiao.wireless.components.agoo.NotificationDismissReceiver", pin.component!!.className)
            verify(pin).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            verify(pin, never()).putExtra(anyString(), anyString())
            verify(f.context, never()).sendBroadcast(any(Intent::class.java))
        }
    }

    @Test
    fun sendFailureStillRestoresIdentityWithoutPlainBroadcastFallback() = fixture { f ->
        f.register()
        f.failSend = true
        mockStatic(Binder::class.java).use { binder ->
            binder.`when`<Long> { Binder.clearCallingIdentity() }.thenReturn(91L)
            f.receiver!!.onReceive(f.context, f.request())
            binder.verify { Binder.restoreCallingIdentity(91L) }
            verify(f.context, never()).sendBroadcast(any(Intent::class.java))
            assertEquals(1, f.pins.size)
        }
    }

    @Test
    fun sendAndLoggingFailuresDoNotEscapeReceiver() = fixture { f ->
        f.register()
        f.failSend = true
        f.failLogging = true
        mockStatic(Binder::class.java).use { binder ->
            binder.`when`<Long> { Binder.clearCallingIdentity() }.thenReturn(92L)
            f.receiver!!.onReceive(f.context, f.request())
            binder.verify { Binder.restoreCallingIdentity(92L) }
        }
    }

    @Test
    fun cachedSystemContextIsPreferredToCallbackWrapper() = fixture { f ->
        f.register()
        ObjectStateScope().use { state ->
            val system = mock(Context::class.java)
            state.set(SystemContextHolder, "cached", system)
            mockStatic(Binder::class.java).use {
                f.receiver!!.onReceive(f.context, f.request())
                verify(system).sendBroadcastAsUser(any(Intent::class.java), same(f.user))
                assertTrue(f.pins.isEmpty())
            }
        }
    }

    private fun fixture(uid: Int = Process.SYSTEM_UID, sdk: Int = 35, block: (Fixture) -> Unit) = mockStatic(Log::class.java).use {
        withAndroidSdk(sdk) {
            ObjectStateScope().use { state ->
                state.set(SystemWakeRelay, "registered", false)
                state.set(SystemContextHolder, "cached", null)
                val f = Fixture()
                withBridge(f) {
                    mockStatic(Process::class.java).use { process ->
                        process.`when`<Int> { Process.myUid() }.thenReturn(uid)
                        process.`when`<UserHandle> { Process.myUserHandle() }.thenReturn(f.user)
                        mockConstruction(IntentFilter::class.java).use {
                            mockConstruction(ComponentName::class.java) { component, construction ->
                                `when`(component.packageName).thenReturn(construction.arguments()[0] as String)
                                `when`(component.className).thenReturn(construction.arguments()[1] as String)
                            }.use {
                                mockConstruction(Intent::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { intent, construction ->
                                    `when`(intent.action).thenReturn(construction.arguments().firstOrNull() as? String)
                                    doAnswer {
                                        val component = it.getArgument<ComponentName>(0)
                                        `when`(intent.component).thenReturn(component)
                                        intent
                                    }.`when`(intent).setComponent(any(ComponentName::class.java))
                                }.use { block(f) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withBridge(f: Fixture, block: () -> Unit) {
        val framework = XposedBridge::class.java.getDeclaredField("frameworkRef").apply { isAccessible = true }
            .get(XposedBridge) as AtomicReference<XposedInterface?>
        val sink = XposedBridge::class.java.getDeclaredField("logSinkRef").apply { isAccessible = true }
            .get(XposedBridge) as AtomicReference<ModuleLogSink?>
        val previousFramework = framework.getAndSet(null)
        val previousSink = sink.getAndSet(object : ModuleLogSink {
            override fun log(priority: Int, tag: String, text: String, throwable: Throwable?) {
                if (f.failLogging) error("synthetic logging failure")
            }
        })
        try {
            block()
        } finally {
            framework.set(previousFramework)
            sink.set(previousSink)
        }
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val user = mock(UserHandle::class.java)
        var receiver: BroadcastReceiver? = null
        var filter: IntentFilter? = null
        val pins = mutableListOf<Intent>()
        val events = mutableListOf<String>()
        var failRegistration = false
        var failSend = false
        var failLogging = false

        init {
            doAnswer { captureReceiver(it.getArgument(0), it.getArgument(1)) }.`when`(context).registerReceiver(
                any(BroadcastReceiver::class.java), any(IntentFilter::class.java), anyString(), isNull(), anyInt(),
            )
            doAnswer { captureReceiver(it.getArgument(0), it.getArgument(1)) }.`when`(context).registerReceiver(
                any(BroadcastReceiver::class.java), any(IntentFilter::class.java), anyString(), isNull(),
            )
            doAnswer {
                pins += it.getArgument<Intent>(0)
                events += "send"
                if (failSend) throw SecurityException("synthetic send failure")
                null
            }.`when`(context).sendBroadcastAsUser(any(Intent::class.java), same(user))
        }

        private fun captureReceiver(value: BroadcastReceiver, valueFilter: IntentFilter): Intent? {
            if (failRegistration) throw SecurityException("synthetic registration failure")
            receiver = value
            filter = valueFilter
            return null
        }

        fun register() { assertTrue(SystemWakeRelay.ensureRegistered(context)); assertNotNull(receiver) }

        fun request(action: String = ExpressRelay.ACTION_WAKE_REQUEST): Intent = mock(Intent::class.java).also {
            `when`(it.action).thenReturn(action)
            `when`(it.getStringExtra(ExpressRelay.EXTRA_WAKE_REASON)).thenReturn("synthetic")
        }
    }
}
