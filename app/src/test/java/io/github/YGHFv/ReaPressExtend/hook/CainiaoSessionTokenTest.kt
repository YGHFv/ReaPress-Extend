package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`

class CainiaoSessionTokenTest {
    @Test
    fun `不同Cookie不能复用上个会话预热的Token`() = withAndroidTestRuntime {
        isolated {
            val cookies = mutableListOf<String>()
            val responses = ArrayDeque(listOf(
                "{\"c\":\"old-token;old-enc\"}", "{}",
                "{\"c\":\"new-token;new-enc\"}", "{}",
            ))
            withConnections(responses, cookies) {
                CainiaoTraceApi.callH5("synthetic", "1.0", "{}", "old-cookie", "https://synthetic.invalid/h5/")
                CainiaoTraceApi.callH5("synthetic", "1.0", "{}", "new-cookie", "https://synthetic.invalid/h5/")
                assertEquals(4, cookies.size)
            }
            assertEquals("old-cookie", cookies[0])
            assertEquals("new-cookie", cookies[2])
            assertTrue(cookies[3].contains("new-token"))
            assertTrue(responses.isEmpty())
        }
    }

    @Test
    fun `同一个Cookie可复用Token但显式失效后必须重新预热`() = withAndroidTestRuntime {
        isolated {
            val cookies = mutableListOf<String>()
            val responses = ArrayDeque(listOf(
                "{\"c\":\"first-token;enc\"}", "{}", "{}",
                "{\"c\":\"renewed-token;enc\"}", "{}",
            ))
            withConnections(responses, cookies) {
                repeat(2) { CainiaoTraceApi.callH5("synthetic", "1.0", "{}", "same-cookie", "https://synthetic.invalid/h5/") }
                assertEquals(3, cookies.size)
                CainiaoTraceApi.clearCachedTokens()
                CainiaoTraceApi.callH5("synthetic", "1.0", "{}", "same-cookie", "https://synthetic.invalid/h5/")
                assertEquals(5, cookies.size)
            }
            assertTrue(cookies.last().contains("renewed-token"))
            assertTrue(responses.isEmpty())
        }
    }

    private fun withConnections(responses: ArrayDeque<String>, cookies: MutableList<String>, block: () -> Unit) {
        mockConstruction(URL::class.java) { url, _ ->
            val connection = mock(HttpURLConnection::class.java) { invocation ->
                if (invocation.method.name == "setRequestProperty" && invocation.getArgument<String>(0) == "Cookie") {
                    cookies.add(invocation.getArgument(1))
                }
                org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
            `when`(connection.responseCode).thenReturn(200)
            `when`(connection.inputStream).thenAnswer { responses.removeFirst().byteInputStream() }
            `when`(connection.headerFields).thenReturn(emptyMap())
            `when`(url.openConnection()).thenReturn(connection)
        }.use { block() }
    }

    private fun isolated(block: () -> Unit) {
        ObjectStateScope().use { state ->
            state.set(CainiaoTraceApi, "lastError", null)
            state.set(CainiaoTraceApi, "preferredUa", null)
            CainiaoTraceApi.clearCachedTokens()
            try {
                block()
            } finally {
                CainiaoTraceApi.clearCachedTokens()
            }
        }
    }
}
