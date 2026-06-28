package com.pockettranspose.webview

import android.webkit.WebView
import android.webkit.WebViewClient

class PocketWebViewClient(private val reinject: (WebView) -> Unit) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        reinject(view)
    }
}
