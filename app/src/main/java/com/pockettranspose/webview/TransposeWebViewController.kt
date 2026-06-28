package com.pockettranspose.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pockettranspose.BuildConfig
import com.pockettranspose.script.InjectedScriptProvider

class TransposeWebViewController(
    private val activity: Activity,
    private val webView: WebView,
    private val scriptProvider: InjectedScriptProvider
) {
    private val script: String by lazy { scriptProvider.pocketTransposeHook() }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.webChromeClient = PocketWebChromeClient(activity)
        webView.webViewClient = PocketWebViewClient { injectFallback(it) }
        installDocumentStartScriptIfSupported()
    }

    fun loadDefault() = webView.loadUrl(DEFAULT_URL)

    fun reinject() = injectFallback(webView)

    fun setGain(value: Float) = evaluate("window.PocketTranspose && window.PocketTranspose.setGain($value);")

    fun setPlaybackRate(value: Float) = evaluate("window.PocketTranspose && window.PocketTranspose.setPlaybackRate($value);")

    fun setPitchSemitone(value: Float) = evaluate("window.PocketTranspose && window.PocketTranspose.setPitchSemitone($value);")

    fun requestStatus() = evaluate(
        "JSON.stringify(window.PocketTranspose ? window.PocketTranspose.getStatus() : {installed:false});"
    ) { Log.d("PocketTransposeJS", "status=$it") }

    private fun installDocumentStartScriptIfSupported() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            Log.d("PocketTransposeJS", "document-start injection registered")
        } else {
            Log.d("PocketTransposeJS", "document-start injection unavailable; using onPageFinished fallback")
        }
    }

    private fun injectFallback(target: WebView) {
        evaluate("(function(){ $script })();", null, target)
    }

    private fun evaluate(source: String, callback: ((String) -> Unit)? = null, target: WebView = webView) {
        target.post { target.evaluateJavascript(source, callback) }
    }

    companion object {
        private const val DEFAULT_URL = "https://m.youtube.com"
    }
}
