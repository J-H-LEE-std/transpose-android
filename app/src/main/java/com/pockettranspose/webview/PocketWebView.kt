package com.pockettranspose.webview

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView that can keep its window "visible" to the Chromium engine while the app
 * is in the background, because Android WebView pauses hidden <video> on its own
 * (mobile Chrome does not). This is only ever done together with the hook's
 * setAppHidden(true), which tells the page the truth (document.hidden = true), so
 * the site still decides whether to keep playing; see TransposeWebViewController.
 */
class PocketWebView(context: Context) : WebView(context) {
    private var pendingVisibility: Int? = null

    /** While true, window-hidden events are held back from the engine. */
    var holdWindowVisible = false
        set(value) {
            field = value
            // Releasing delivers the hidden state that was held back.
            if (!value) pendingVisibility?.let {
                pendingVisibility = null
                super.onWindowVisibilityChanged(it)
            }
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (holdWindowVisible && visibility != View.VISIBLE) {
            pendingVisibility = visibility
            return
        }
        pendingVisibility = null
        super.onWindowVisibilityChanged(visibility)
    }
}
