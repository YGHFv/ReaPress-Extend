package io.github.YGHFv.ReaPressExtend.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.YGHFv.ReaPressExtend.relay.TraceCookieCache
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ContextLifetimeTest {
    @Test
    fun hostStoresApplicationInsteadOfActivityAndPublishesItToListeners() = hostScope {
        val activity = mock(Activity::class.java)
        val app = mock(Application::class.java)
        `when`(activity.applicationContext).thenReturn(app)
        var received: Context? = null
        HostContextHolder.onReady { received = it }
        HostContextHolder.upgrade(activity)
        assertSame(app, HostContextHolder.acquire())
        assertSame(app, received)
    }

    @Test
    fun hostDoesNotRetainContextWithoutApplication() = hostScope {
        HostContextHolder.upgrade(mock(Activity::class.java))
        assertNull(field(HostContextHolder, "cached"))
    }

    @Test
    fun realApplicationCanBeRetainedBeforeApplicationContextIsReady() = hostScope {
        val app = mock(Application::class.java)
        HostContextHolder.upgrade(app)
        assertSame(app, HostContextHolder.acquire())
    }

    @Test
    fun lateHostListenerGetsApplicationExactlyOnce() = hostScope {
        val app = mock(Application::class.java)
        HostContextHolder.upgrade(app)
        val calls = mutableListOf<Context>()
        HostContextHolder.onReady { calls += it }
        HostContextHolder.upgrade(app)
        assertEquals(listOf(app), calls)
    }

    @Test
    fun unavailableApplicationDoesNotDiscardWaitingListeners() = hostScope {
        var calls = 0
        HostContextHolder.onReady { calls++ }
        HostContextHolder.upgrade(mock(Activity::class.java))
        assertEquals(0, calls)
        HostContextHolder.upgrade(mock(Application::class.java))
        assertEquals(1, calls)
    }

    @Test
    fun systemContextIsTheNmsContextNotAnActivityApplicationSubstitute() {
        ObjectStateScope().use { state ->
            state.set(SystemContextHolder, "cached", null)
            val context = mock(Context::class.java)
            SystemContextHolder.upgrade(NmsFixture(context))
            assertSame(context, SystemContextHolder.acquire())
            verify(context, never()).applicationContext
        }
    }

    @Test
    fun cookieAttachRetainsOnlyApplicationContext() = cookieScope { storage ->
        val activity = mock(Activity::class.java)
        `when`(activity.applicationContext).thenReturn(storage.context)
        TraceCookieCache.attach(activity)
        TraceCookieCache.put("synthetic-cookie")
        assertSame(storage.context, field(TraceCookieCache, "store"))
        verify(activity, never()).getSharedPreferences(anyString(), anyInt())
    }

    @Test
    fun cookieRestoreRetainsOnlyApplicationContext() = cookieScope { storage ->
        val activity = mock(Activity::class.java)
        `when`(activity.applicationContext).thenReturn(storage.context)
        TraceCookieCache.reloadAfterRestore(activity)
        assertSame(storage.context, field(TraceCookieCache, "store"))
        verify(activity, never()).getSharedPreferences(anyString(), anyInt())
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun hostScope(block: () -> Unit) {
        ObjectStateScope().use { state ->
            state.set(HostContextHolder, "cached", null)
            @Suppress("UNCHECKED_CAST")
            val listeners = field(HostContextHolder, "readyListeners") as MutableList<(Context) -> Unit>
            val previous = listeners.toList()
            listeners.clear()
            try { block() } finally { listeners.clear(); listeners.addAll(previous) }
        }
    }

    private fun cookieScope(block: (PreferencesContext) -> Unit) {
        ObjectStateScope().use { state ->
            state.set(TraceCookieCache, "cookie", null)
            state.set(TraceCookieCache, "store", null)
            state.set(TraceCookieCache, "restored", false)
            state.set(TraceCookieCache, "hostUa", null)
            state.set(TraceCookieCache, "syncedAt", 0L)
            state.set(CainiaoTraceApi, "preferredUa", null)
            block(PreferencesContext())
        }
    }

    private class NmsFixture(@JvmField val mContext: Context)
}
