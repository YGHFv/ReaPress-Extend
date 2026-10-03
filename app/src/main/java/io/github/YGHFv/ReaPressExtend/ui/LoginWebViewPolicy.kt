package io.github.YGHFv.ReaPressExtend.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URI

internal object LoginWebViewPolicy {
    // Taobao's login/challenge pages require JS. No native JS bridge, local files or cleartext resources.
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        view.webViewClient = LoginWebViewClient()
    }

    // Keep HTTPS challenge redirects working without treating third-party pages as native commands.
    fun allowsNavigation(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}

internal class LoginWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !LoginWebViewPolicy.allowsNavigation(request.url.toString())

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        !LoginWebViewPolicy.allowsNavigation(url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        // Embedded data/blob resources are local page content, not file/content-provider access.
        if (!request.isForMainFrame && uri.scheme?.lowercase() in setOf("data", "blob", "about")) return null
        if (LoginWebViewPolicy.allowsNavigation(uri.toString())) return null
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
    }
}

internal class LoginWebViewHolder {
    var view: WebView? = null

    fun release(target: WebView? = view) {
        if (target == null || target !== view) return
        view = null
        runCatching { target.stopLoading() }
        runCatching { target.destroy() }
    }
}
