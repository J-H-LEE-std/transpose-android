package com.pockettranspose.webview

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.pockettranspose.BuildConfig

class PocketWebChromeClient(
    private val fullscreenContainer: ViewGroup,
    private val onFullscreenChanged: (Boolean) -> Unit,
    private val onLoadComplete: (WebView) -> Unit
) : WebChromeClient() {
    private var fullscreenView: View? = null
    private var fullscreenCallback: CustomViewCallback? = null

    val isFullscreen: Boolean get() = fullscreenView != null

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val message = consoleMessage.message()
        // Our own logs are always forwarded. Page logs (which may contain URLs or other
        // page data) are forwarded only in debug builds. sourceId (a URL) is never logged.
        if (message.startsWith(JS_LOG_PREFIX) || BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "$message (line ${consoleMessage.lineNumber()})")
        }
        return true
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        if (newProgress == 100) onLoadComplete(view)
    }

    // System bars are hidden/restored by the activity via onFullscreenChanged.
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fullscreenContainer.visibility = View.VISIBLE
        onFullscreenChanged(true)
    }

    override fun onHideCustomView() {
        hideFullscreenView()
    }

    /** Called by the activity (e.g. back button) to leave fullscreen from the native side. */
    fun exitFullscreen() {
        val callback = fullscreenCallback
        hideFullscreenView()
        callback?.onCustomViewHidden()
    }

    private fun hideFullscreenView() {
        val view = fullscreenView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        fullscreenView = null
        fullscreenCallback = null
        onFullscreenChanged(false)
    }

    companion object {
        const val LOG_TAG = "PocketTransposeJS"
        private const val JS_LOG_PREFIX = "[PocketTranspose]"
    }
}
