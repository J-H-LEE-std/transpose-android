package com.pockettranspose.webview

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient

class PocketWebChromeClient(private val activity: Activity) : WebChromeClient() {
    private var fullscreenView: View? = null
    private var fullscreenCallback: CustomViewCallback? = null

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (com.pockettranspose.BuildConfig.DEBUG && consoleMessage.message().startsWith("[PocketTranspose]")) {
            // Only our structured diagnostics; no page logs, source IDs, or URLs.
            Log.d("PocketTransposeJS", consoleMessage.message())
        }
        return true
    }

    fun exitFullscreen(): Boolean {
        if (fullscreenView == null) return false
        onHideCustomView()
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        activity.window.addContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    override fun onHideCustomView() {
        val view = fullscreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }
}
