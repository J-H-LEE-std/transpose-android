package com.pockettranspose.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pockettranspose.BuildConfig
import com.pockettranspose.script.InjectedScriptProvider
import org.json.JSONTokener

class TransposeWebViewController(
    private val activity: Activity,
    private val webView: WebView,
    private val scriptProvider: InjectedScriptProvider
) {
    private val script: String by lazy { scriptProvider.pocketTransposeHook() }
    private val chrome = PocketWebChromeClient(activity)
    private var gain = 1f
    private var rate = 1f
    private var preservesPitch = true

    @SuppressLint("SetJavaScriptEnabled")
    fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.webChromeClient = chrome
        webView.webViewClient = PocketWebViewClient { reinject() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("https://*"))
        }
    }
    fun loadDefault() = webView.loadUrl("https://m.youtube.com")
    fun loadUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url.trim())
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        webView.loadUrl(uri.toString())
        return true
    }
    fun loadTest() = webView.loadDataWithBaseURL(
        "https://appassets.androidplatform.net/",
        activity.assets.open("test_media.html").bufferedReader().use { it.readText() },
        "text/html", "UTF-8", null
    )
    fun exitFullscreen() = chrome.exitFullscreen()
    fun destroy() { chrome.exitFullscreen() }
    fun reinject() = evaluate(script) { applySettings() }
    fun resume() = evaluate("window.PocketTranspose && window.PocketTranspose.resume();")
    fun setGain(value: Float) { gain = value.coerceIn(0f, 2f); applySettings() }
    fun setPlaybackRate(value: Float) { rate = value.coerceIn(0.25f, 2f); applySettings() }
    fun setPreservesPitch(value: Boolean) { preservesPitch = value; applySettings() }
    private fun applySettings() = evaluate("""
        if(window.PocketTranspose) {
          window.PocketTranspose.setGain($gain);
          window.PocketTranspose.setPlaybackRate($rate);
          window.PocketTranspose.setPreservesPitch($preservesPitch);
        }
    """.trimIndent())
    fun requestStatus() = evaluate(
        "JSON.stringify(window.PocketTranspose ? window.PocketTranspose.getStatus() : {installed:false});"
    ) { raw ->
        val message = runCatching { JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
        if (!activity.isFinishing && !activity.isDestroyed) AlertDialog.Builder(activity)
            .setTitle("Audio status").setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun evaluate(source: String, callback: ((String) -> Unit)? = null) {
        webView.evaluateJavascript(source, callback)
    }
}
