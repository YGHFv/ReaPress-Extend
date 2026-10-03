package io.github.YGHFv.ReaPressExtend.ui

import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class LoginWebViewPolicyTest {
    @Test
    fun configureKeepsLoginFeaturesButDisablesLocalAccessAndMixedContent() {
        val view = mock(WebView::class.java)
        val settings = mock(WebSettings::class.java)
        `when`(view.settings).thenReturn(settings)
        LoginWebViewPolicy.configure(view)
        verify(settings).javaScriptEnabled = true
        verify(settings).domStorageEnabled = true
        verify(settings).allowFileAccess = false
        verify(settings).allowContentAccess = false
        verify(settings).mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        verify(settings).javaScriptCanOpenWindowsAutomatically = false
        verify(settings).setSupportMultipleWindows(false)
        verify(settings).safeBrowsingEnabled = true
        verify(view).webViewClient = any(LoginWebViewClient::class.java)
        verify(view, never()).addJavascriptInterface(any(), anyString())
    }

    @Test
    fun bothNavigationCallbacksEnforceSamePolicy() {
        val view = mock(WebView::class.java)
        val client = LoginWebViewClient()
        for ((url, allowed) in listOf("https://login.taobao.com/" to true, "http://login.taobao.com/" to false, "intent://launch" to false)) {
            @Suppress("DEPRECATION")
            val legacyBlocked = client.shouldOverrideUrlLoading(view, url)
            assertEquals(!allowed, legacyBlocked)
            assertEquals(!allowed, client.shouldOverrideUrlLoading(view, request(url)))
        }
    }

    @Test
    fun blockedSubresourceReceivesEmptyResponseWhileHttpsAndEmbeddedDataPass() {
        val client = LoginWebViewClient()
        val view = mock(WebView::class.java)
        mockConstruction(WebResourceResponse::class.java).use { responses ->
            assertNull(client.shouldInterceptRequest(view, request("https://cdn.example.test/captcha.js", false)))
            assertNull(client.shouldInterceptRequest(view, request("data:image/png;base64,AA", false)))
            assertNull(client.shouldInterceptRequest(view, request("blob:https://login.taobao.com/id", false)))
            assertNotNull(client.shouldInterceptRequest(view, request("http://cdn.example.test/captcha.js", false)))
            assertNotNull(client.shouldInterceptRequest(view, request("file:///data/local/token", false)))
            assertNotNull(client.shouldInterceptRequest(view, request("content://private/provider", false)))
            assertEquals(3, responses.constructed().size)
        }
    }

    @Test
    fun certificateErrorsAlwaysCancel() {
        val handler = mock(SslErrorHandler::class.java)
        LoginWebViewClient().onReceivedSslError(mock(WebView::class.java), handler, mock(SslError::class.java))
        verify(handler).cancel()
        verify(handler, never()).proceed()
    }

    @Test
    fun releaseDestroysOnceAndDropsActivityReference() {
        val view = mock(WebView::class.java)
        val holder = LoginWebViewHolder().apply { this.view = view }
        holder.release()
        holder.release(view)
        assertNull(holder.view)
        verify(view, times(1)).stopLoading()
        verify(view, times(1)).destroy()
    }

    @Test
    fun stoppingFailureStillDestroysAndClearsHolder() {
        val view = mock(WebView::class.java)
        doThrow(IllegalStateException("synthetic")).`when`(view).stopLoading()
        val holder = LoginWebViewHolder().apply { this.view = view }
        holder.release()
        verify(view).destroy()
        assertNull(holder.view)
    }

    @Test
    fun lateReleaseOfOldViewDoesNotDestroyNewView() {
        val old = mock(WebView::class.java)
        val current = mock(WebView::class.java)
        val holder = LoginWebViewHolder().apply { view = old }
        holder.release(old)
        holder.view = current
        holder.release(old)
        assertSame(current, holder.view)
        verifyNoInteractions(current)
    }

    private fun request(url: String, main: Boolean = true): WebResourceRequest {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(url)
        `when`(uri.scheme).thenReturn(url.substringBefore(':'))
        return mock(WebResourceRequest::class.java).also {
            `when`(it.url).thenReturn(uri)
            `when`(it.isForMainFrame).thenReturn(main)
        }
    }
}

@RunWith(Parameterized::class)
class LoginNavigationTest(private val url: String, private val allowed: Boolean) {
    @Test
    fun urlPolicy() { assertEquals(url, allowed, LoginWebViewPolicy.allowsNavigation(url)) }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("https://login.m.taobao.com/", true),
            arrayOf("https://verify.example.test/challenge?next=https%3A%2F%2Flogin.m.taobao.com", true),
            arrayOf("HTTPS://login.m.taobao.com/", true),
            arrayOf("http://login.m.taobao.com/", false),
            arrayOf("file:///data/user/0/private", false),
            arrayOf("content://private/cookie", false),
            arrayOf("javascript:alert(1)", false),
            arrayOf("intent://login#Intent;scheme=taobao;end", false),
            arrayOf("data:text/html,<script>test</script>", false),
            arrayOf("https://trusted.example@other.example/", false),
            arrayOf("https:///missing-host", false),
            arrayOf("not a URL", false),
        )
    }
}
