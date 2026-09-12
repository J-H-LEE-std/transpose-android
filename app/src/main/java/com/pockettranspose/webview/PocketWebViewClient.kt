package com.pockettranspose.webview

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class PocketWebViewClient(private val finished: (WebView) -> Unit) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) { finished(view) }
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.scheme != "https"
}
