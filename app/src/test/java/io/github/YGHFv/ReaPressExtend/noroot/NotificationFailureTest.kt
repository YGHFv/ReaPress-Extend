package io.github.YGHFv.ReaPressExtend.noroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import io.github.YGHFv.ReaPressExtend.config.ExpressSettings
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsKeys
import io.github.YGHFv.ReaPressExtend.config.ExpressSettingsSnapshot
import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelay
import io.github.YGHFv.ReaPressExtend.relay.ExpressRelayReceiver
import io.github.YGHFv.ReaPressExtend.relay.RelayCredentialStore
import io.github.YGHFv.ReaPressExtend.notification.*
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import io.github.YGHFv.ReaPressExtend.testing.withAndroidSdk
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import java.util.concurrent.atomic.AtomicLong

class NotificationFailureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `重复事件在替代通知发送失败后不能撤掉原通知`() = withAndroidTestRuntime {
        ObjectStateScope().use { state ->
            state.set(ExpressSettings, "service", null)
            state.set(TraceCookieCache, "cookie", null)
            state.set(TraceCookieCache, "store", null)
            state.set(TraceCookieCache, "restored", false)
            state.set(ModuleLogBuffer, "logFile", null)
            val storage = PreferencesContext(temporary.newFolder())
            ExpressSettings.write(storage.context, ExpressSettingsSnapshot(mode = ExpressSettingsKeys.MODE_INTERCEPT, noRootListener = true))
            val listener = mock(ExpressNotificationListener::class.java, CALLS_REAL_METHODS)
            doReturn(storage.context).`when`(listener).applicationContext
            val extras = mock(Bundle::class.java)
            `when`(extras.getCharSequence("android.title")).thenReturn("菜鸟取件通知")
            `when`(extras.getCharSequence("android.text")).thenReturn("中通快递 773000000009999 已到菜鸟驿站，请凭取件码 1-2-345 取件")
            val notification = mock(Notification::class.java)
            notification.extras = extras
            val posted = mock(StatusBarNotification::class.java)
            `when`(posted.notification).thenReturn(notification)
            `when`(posted.packageName).thenReturn("com.cainiao.wireless")
            `when`(posted.postTime).thenReturn(System.currentTimeMillis())
            `when`(posted.key).thenReturn("synthetic-original")

            try {
                listener.onNotificationPosted(posted)
                listener.onNotificationPosted(posted)

                verify(listener, never()).cancelNotification("synthetic-original")
            } finally {
                val executor = ModuleLogBuffer::class.java.getDeclaredField("io").apply { isAccessible = true }
                    .get(ModuleLogBuffer) as ExecutorService
                executor.submit {}.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun permissionDeniedTwiceNeverCancelsAndCanRetryAfterGrant() = fixture { f ->
        `when`(f.storage.context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        repeat(2) { f.listener.onNotificationPosted(f.original) }
        verify(f.listener, never()).cancelNotification(anyString())
        verify(f.manager, never()).notify(anyInt(), any(Notification::class.java))
        assertEquals(2, ExpressNotificationLog.snapshot(f.storage.context).size)
        `when`(f.storage.context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        f.listener.onNotificationPosted(f.original)
        verify(f.manager).notify(anyInt(), any(Notification::class.java))
        verify(f.listener).cancelNotification(f.original.key)
    }

    @Test
    fun disabledApplicationNeverCancelsOriginal() = fixture { f ->
        `when`(f.manager.areNotificationsEnabled()).thenReturn(false)
        repeat(2) { f.listener.onNotificationPosted(f.original) }
        verify(f.listener, never()).cancelNotification(anyString())
        verify(f.manager, never()).notify(anyInt(), any(Notification::class.java))
        assertTrue(ExpressNotificationLog.snapshot(f.storage.context).all { !it.delivered })
    }

    @Test
    fun disabledChannelNeverCancelsOriginal() = fixture { f ->
        `when`(f.channel.importance).thenReturn(NotificationManager.IMPORTANCE_NONE)
        repeat(2) { f.listener.onNotificationPosted(f.original) }
        verify(f.listener, never()).cancelNotification(anyString())
        verify(f.manager, never()).notify(anyInt(), any(Notification::class.java))
        assertEquals(2, ExpressNotificationLog.snapshot(f.storage.context).size)
    }

    @Test
    fun missingChannelNeverCancelsOriginal() = fixture { f ->
        `when`(f.manager.getNotificationChannel(ExpressNotificationPoster.CHANNEL_ID)).thenReturn(null)
        repeat(2) { f.listener.onNotificationPosted(f.original) }
        verify(f.listener, never()).cancelNotification(anyString())
        verify(f.manager, never()).notify(anyInt(), any(Notification::class.java))
    }

    @Test
    fun android26ChecksChannelWithoutUsingChannelGroupApi() = fixture(sdk = 26) { f ->
        f.listener.onNotificationPosted(f.original)
        f.listener.onNotificationPosted(f.original)
        verify(f.manager, times(1)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener, times(2)).cancelNotification(f.original.key)
        verify(f.manager, never()).getNotificationChannelGroup(anyString())
    }

    @Test
    fun disabledChannelGroupNeverCancelsOriginal() = fixture { f ->
        val group = mock(NotificationChannelGroup::class.java)
        `when`(f.channel.group).thenReturn("group")
        `when`(f.manager.getNotificationChannelGroup("group")).thenReturn(group)
        `when`(group.isBlocked).thenReturn(true)
        repeat(2) { f.listener.onNotificationPosted(f.original) }
        verify(f.listener, never()).cancelNotification(anyString())
        verify(f.manager, never()).notify(anyInt(), any(Notification::class.java))
    }

    @Test
    fun notifyExceptionReleasesClaimForNextSuccessfulAttempt() = fixture { f ->
        f.throwOnPost = true
        f.listener.onNotificationPosted(f.original)
        verify(f.listener, never()).cancelNotification(anyString())
        f.throwOnPost = false
        f.listener.onNotificationPosted(f.original)
        verify(f.manager, times(2)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener).cancelNotification(f.original.key)
    }

    @Test
    fun duplicateAfterSuccessCancelsOnlyWhileReplacementStillExists() = fixture { f ->
        f.listener.onNotificationPosted(f.original)
        f.listener.onNotificationPosted(f.original)
        verify(f.manager, times(1)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener, times(2)).cancelNotification(f.original.key)
        f.active = emptyArray()
        f.listener.onNotificationPosted(f.original)
        verify(f.listener, times(2)).cancelNotification(f.original.key)
    }

    @Test
    fun duplicateCannotUseAnotherEventAtTheSameNotificationId() = fixture { f ->
        f.listener.onNotificationPosted(f.original)
        val notification = f.active.single().notification
        `when`(notification.extras.getString("reapress.delivery.event")).thenReturn("other-event")
        f.listener.onNotificationPosted(f.original)
        verify(f.listener, times(1)).cancelNotification(f.original.key)
    }

    @Test
    fun permissionsRevokedAfterSuccessRetainsDuplicateOriginal() = fixture { f ->
        f.listener.onNotificationPosted(f.original)
        `when`(f.manager.areNotificationsEnabled()).thenReturn(false)
        f.listener.onNotificationPosted(f.original)
        verify(f.listener, times(1)).cancelNotification(f.original.key)
    }

    @Test
    fun activeNotificationQueryFailureRetainsDuplicateOriginal() = fixture { f ->
        f.listener.onNotificationPosted(f.original)
        `when`(f.manager.activeNotifications).thenThrow(SecurityException("synthetic"))
        f.listener.onNotificationPosted(f.original)
        verify(f.listener, times(1)).cancelNotification(f.original.key)
    }

    @Test
    fun relayFailureThenListenerSuccessRetriesAndCancelsSafely() = fixture { f ->
        f.throwOnPost = true
        ExpressRelayReceiver().onReceive(f.storage.context, f.relay())
        f.throwOnPost = false
        f.listener.onNotificationPosted(f.original)
        verify(f.manager, times(2)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener).cancelNotification(f.original.key)
    }

    @Test
    fun relaySuccessThenListenerDedupesWithoutLosingOriginalSafety() = fixture { f ->
        ExpressRelayReceiver().onReceive(f.storage.context, f.relay())
        f.listener.onNotificationPosted(f.original)
        verify(f.manager, times(1)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener).cancelNotification(f.original.key)
    }

    @Test
    fun listenerFailureThenRelaySuccessMakesLaterDuplicateSafe() = fixture { f ->
        f.throwOnPost = true
        f.listener.onNotificationPosted(f.original)
        verify(f.listener, never()).cancelNotification(anyString())
        f.throwOnPost = false
        ExpressRelayReceiver().onReceive(f.storage.context, f.relay())
        f.listener.onNotificationPosted(f.original)
        verify(f.manager, times(2)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener).cancelNotification(f.original.key)
    }

    @Test
    fun passThroughModeDoesNotCancelEvenAfterSuccess() = fixture { f ->
        ExpressSettings.write(f.storage.context, ExpressSettingsSnapshot(mode = ExpressSettingsKeys.MODE_PASSTHROUGH, noRootListener = true))
        repeat(2) { f.listener.onNotificationPosted(f.original) }
        verify(f.manager, times(1)).notify(anyInt(), any(Notification::class.java))
        verify(f.listener, never()).cancelNotification(anyString())
    }

    @Test
    fun listenerDuringRelayPostDoesNotTreatInFlightAsSuccess() = fixture { f ->
        f.duringPost = { f.listener.onNotificationPosted(f.original) }
        ExpressRelayReceiver().onReceive(f.storage.context, f.relay())
        verify(f.listener, never()).cancelNotification(anyString())
        verify(f.manager, times(1)).notify(anyInt(), any(Notification::class.java))
        f.listener.onNotificationPosted(f.original)
        verify(f.listener).cancelNotification(f.original.key)
    }

    private fun fixture(sdk: Int = 35, block: (Fixture) -> Unit) = withAndroidTestRuntime {
        withAndroidSdk(sdk) {
            mockStatic(Uri::class.java).use {
                ObjectStateScope().use { state ->
                    state.set(ExpressSettings, "service", null)
                    state.set(TraceCookieCache, "cookie", null)
                    state.set(TraceCookieCache, "store", null)
                    state.set(TraceCookieCache, "restored", false)
                    state.set(ModuleLogBuffer, "logFile", null)
                    state.set(FocusNotificationCapability, "cached", FocusNotificationCapability.Snapshot(0, false, false))
                    val f = Fixture(PreferencesContext(temporary.newFolder()))
                    mockConstruction(NotificationChannel::class.java).use {
                        mockConstruction(Notification.BigTextStyle::class.java, withSettings().defaultAnswer(RETURNS_SELF)).use {
                            mockConstruction(Notification.Builder::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
                                val extras = mock(Bundle::class.java)
                                val values = mutableMapOf<String, String>()
                                doAnswer { values[it.getArgument(0)] = it.getArgument(1); null }
                                    .`when`(extras).putString(anyString(), anyString())
                                `when`(extras.getString(anyString())).thenAnswer { values[it.getArgument<String>(0)] }
                                `when`(builder.extras).thenReturn(extras)
                                val notification = mock(Notification::class.java)
                                notification.extras = extras
                                `when`(builder.build()).thenReturn(notification)
                            }.use {
                                try { block(f) } finally {
                                    val io = ModuleLogBuffer::class.java.getDeclaredField("io").apply { isAccessible = true }
                                        .get(ModuleLogBuffer) as ExecutorService
                                    io.submit {}.get(5, TimeUnit.SECONDS)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private class Fixture(val storage: PreferencesContext) {
        val manager = mock(NotificationManager::class.java)
        val channel = mock(NotificationChannel::class.java)
        val listener = mock(ExpressNotificationListener::class.java, CALLS_REAL_METHODS)
        val original = mock(StatusBarNotification::class.java)
        val tracking = (773000000800000L + sequence.incrementAndGet()).toString()
        val title = "菜鸟取件通知"
        val body = "中通快递 $tracking 已到菜鸟驿站，请凭取件码 1-2-345 取件"
        var active = emptyArray<StatusBarNotification>()
        var throwOnPost = false
        var duringPost: () -> Unit = {}

        init {
            ExpressSettings.write(storage.context, ExpressSettingsSnapshot(mode = ExpressSettingsKeys.MODE_INTERCEPT, noRootListener = true))
            `when`(storage.context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(manager)
            `when`(manager.areNotificationsEnabled()).thenReturn(true)
            `when`(manager.getNotificationChannel(ExpressNotificationPoster.CHANNEL_ID)).thenReturn(channel)
            `when`(channel.importance).thenReturn(NotificationManager.IMPORTANCE_DEFAULT)
            `when`(manager.activeNotifications).thenAnswer { active }
            doAnswer {
                duringPost()
                if (throwOnPost) throw SecurityException("synthetic denied")
                val posted = mock(StatusBarNotification::class.java)
                `when`(posted.id).thenReturn(it.getArgument(0))
                `when`(posted.notification).thenReturn(it.getArgument(1))
                active = arrayOf(posted)
                null
            }.`when`(manager).notify(anyInt(), any(Notification::class.java))
            doReturn(storage.context).`when`(listener).applicationContext
            doNothing().`when`(listener).cancelNotification(anyString())
            val extras = mock(Bundle::class.java)
            `when`(extras.getCharSequence("android.title")).thenReturn(title)
            `when`(extras.getCharSequence("android.text")).thenReturn(body)
            val notification = mock(Notification::class.java)
            notification.extras = extras
            `when`(original.notification).thenReturn(notification)
            `when`(original.packageName).thenReturn("com.cainiao.wireless")
            `when`(original.postTime).thenReturn(System.currentTimeMillis())
            `when`(original.key).thenReturn("original-$tracking")
        }

        fun relay(): Intent {
            val credential = RelayCredentialStore.ensure(storage.context)
            val values = mapOf(
                ExpressRelay.EXTRA_RELAY_CREDENTIAL to credential,
                ExpressRelay.EXTRA_SOURCE_PACKAGE to "com.cainiao.wireless",
                ExpressRelay.EXTRA_TITLE to title,
                ExpressRelay.EXTRA_TEXT to "$title\n$body",
                ExpressRelay.EXTRA_TRACKING to tracking,
            )
            val intent = mock(Intent::class.java)
            val extras = mock(Bundle::class.java)
            `when`(intent.action).thenReturn(ExpressRelay.ACTION_DELIVER)
            `when`(intent.extras).thenReturn(extras)
            `when`(extras.size()).thenReturn(values.size)
            `when`(extras.keySet()).thenReturn(values.keys)
            `when`(extras.get(anyString())).thenAnswer { values[it.getArgument<String>(0)] }
            `when`(intent.getStringExtra(anyString())).thenAnswer { values[it.getArgument<String>(0)] }
            return intent
        }

        companion object { val sequence = AtomicLong() }
    }
}
