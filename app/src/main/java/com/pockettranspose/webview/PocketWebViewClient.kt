package com.pockettranspose.webview

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class PocketWebViewClient(
    private val assetLoader: WebViewAssetLoader,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: (WebView) -> Unit,
    private val onUrlChanged: (String?) -> Unit
) : WebViewClient() {
    // Serves app assets (the HTML5 test page) from https://appassets.androidplatform.net,
    // which is a secure context, so AudioWorklet is available there too.
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        assetLoader.shouldInterceptRequest(request.url)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished(view)
    }

    // Also fires for SPA (History API) navigations, e.g. YouTube video changes.
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        onUrlChanged(url)
    }
}
