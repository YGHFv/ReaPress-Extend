package io.github.YGHFv.ReaPressExtend.relay

import io.github.YGHFv.ReaPressExtend.hook.CainiaoTraceApi
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.PreferencesContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraceCookieCacheTest {
    @Test
    fun `同一登录态未提供UA时可保留配套UA`() = isolated { storage ->
        TraceCookieCache.attach(storage.context)
        TraceCookieCache.put("same-cookie", "same-ua")
        TraceCookieCache.put("same-cookie")
        assertEquals("same-ua", TraceCookieCache.hostUa)
        assertEquals("same-ua", TraceCookieStore.read(storage.context)?.ua)
    }

    @Test
    fun `更换登录态且无UA时不携带上个账号UA`() = isolated { storage ->
        TraceCookieCache.attach(storage.context)
        TraceCookieCache.put("old-cookie", "old-ua")
        TraceCookieCache.put("new-cookie")
        assertNull(TraceCookieCache.hostUa)
        assertNull(CainiaoTraceApi.preferredUa)
        assertNull(TraceCookieStore.read(storage.context)?.ua)
    }

    @Test
    fun `退出登录同时清空Cookie及UA的内存和落盘状态`() = isolated { storage ->
        TraceCookieCache.attach(storage.context)
        TraceCookieCache.put("cookie", "ua")
        TraceCookieCache.invalidate(storage.context)
        assertNull(TraceCookieCache.get())
        assertNull(TraceCookieCache.hostUa)
        assertNull(CainiaoTraceApi.preferredUa)
        assertNull(TraceCookieCache.ageMs())
        assertNull(TraceCookieStore.read(storage.context))
    }

    @Test
    fun `未变化的attach不会覆盖较新的内存值`() = isolated { storage ->
        TraceCookieCache.attach(storage.context)
        TraceCookieCache.put("new-cookie", "ua")
        TraceCookieCache.attach(storage.context)
        assertEquals("new-cookie", TraceCookieCache.get())
    }

    private fun isolated(block: (PreferencesContext) -> Unit) {
        ObjectStateScope().use { state ->
            state.set(TraceCookieCache, "cookie", null)
            state.set(TraceCookieCache, "hostUa", null)
            state.set(TraceCookieCache, "syncedAt", 0L)
            state.set(TraceCookieCache, "store", null)
            state.set(TraceCookieCache, "restored", false)
            state.set(CainiaoTraceApi, "preferredUa", null)
            block(PreferencesContext())
        }
    }
}
