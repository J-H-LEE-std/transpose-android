package com.pockettranspose.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pockettranspose.BuildConfig
import com.pockettranspose.script.InjectedScriptProvider
import com.pockettranspose.script.InjectionMode
import org.json.JSONArray

/**
 * Owns WebView configuration, script injection and every Native -> JS call.
 *
 * Native -> JS goes exclusively through evaluateJavascript(); no addJavascriptInterface()
 * bridge is exposed to web content.
 */
class TransposeWebViewController(
    private val activity: Activity,
    private val webView: WebView,
    private val scriptProvider: InjectedScriptProvider,
    fullscreenContainer: ViewGroup,
    private val listener: Listener
) {
    interface Listener {
        fun onUrlChanged(url: String?)
        fun onFullscreenChanged(fullscreen: Boolean)
        fun onStatus(statusJson: String)
        fun onPlaybackStateChanged(playing: Boolean)
        fun onHiddenPause(source: String)
    }

    data class AudioSettings(
        val gain: Float = 1f,
        val playbackRate: Float = 1f,
        val pitchSemitone: Int = 0,
        val preservesPitch: Boolean = true
    )

    var mode: InjectionMode = InjectionMode.HOOK
        private set
    var settings = AudioSettings()
        private set
    var documentStartSupported = false
        private set

    private var documentStartHandler: ScriptHandler? = null
    private var defaultUserAgent: String? = null
    private var defaultUserAgentMetadata: UserAgentMetadata? = null

    var identifyAsChrome = false
        private set

    private val chromeClient = PocketWebChromeClient(
        fullscreenContainer,
        onFullscreenChanged = { listener.onFullscreenChanged(it) },
        onLoadComplete = { injectFallback(it) },
        onPlaybackStateChanged = { setPlaying(it) },
        onHiddenPause = { listener.onHiddenPause(it) }
    )

    /** Whether page media is playing, as reported by the hook script. */
    var isPlaying = false
        private set

    val isFullscreen: Boolean get() = chromeClient.isFullscreen

    @SuppressLint("SetJavaScriptEnabled")
    fun configure() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // PoC convenience: lets media and AudioContext start without a tap. Review before release.
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // Mixed content keeps the platform default (blocked); file access stays disabled.
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        if (BuildConfig.DEBUG) {
            // Debug only: exposes the WebView to chrome://inspect.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .build()
        webView.webChromeClient = chromeClient
        webView.webViewClient = PocketWebViewClient(
            assetLoader,
            // A full navigation tears down the page and its media.
            onPageStarted = { setPlaying(false) },
            onPageFinished = {
                injectFallback(it)
                pushSettings()
            },
            onUrlChanged = { listener.onUrlChanged(it) }
        )
        installDocumentStartScript()
    }

    fun loadUrl(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        webView.loadUrl(url)
    }

    fun loadYouTube() = webView.loadUrl(YOUTUBE_URL)

    fun loadTestPage() = webView.loadUrl(TEST_PAGE_URL)

    /**
     * Presents this WebView as mobile Chrome: drops the "; wv" / "Version/x" tokens
     * from the User-Agent and renames the "Android WebView" client-hint brand.
     * For sites that offer a feature to mobile browsers but not to WebViews, such as
     * YouTube Premium background playback. It does not change what an account is
     * entitled to. Off by default.
     */
    fun setIdentifyAsChrome(enabled: Boolean, reload: Boolean) {
        val settings = webView.settings
        if (defaultUserAgent == null) {
            defaultUserAgent = settings.userAgentString
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                defaultUserAgentMetadata = WebSettingsCompat.getUserAgentMetadata(settings)
            }
        }
        identifyAsChrome = enabled
        val baseAgent = defaultUserAgent.orEmpty()
        settings.userAgentString = if (enabled) chromeUserAgent(baseAgent) else baseAgent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            defaultUserAgentMetadata?.let { metadata ->
                WebSettingsCompat.setUserAgentMetadata(settings, if (enabled) chromeMetadata(metadata) else metadata)
            }
        }
        Log.d(LOG_TAG, "identify as Chrome: $enabled")
        if (reload) webView.reload()
    }

    private fun chromeUserAgent(agent: String): String =
        agent.replace("; wv)", ")").replace(Regex("Version/\\S+ "), "")

    private fun chromeMetadata(metadata: UserAgentMetadata): UserAgentMetadata {
        val brands = metadata.brandVersionList.map { brand ->
            if (brand.brand == "Android WebView") {
                UserAgentMetadata.BrandVersion.Builder(brand).setBrand("Google Chrome").build()
            } else {
                brand
            }
        }
        return UserAgentMetadata.Builder(metadata).setBrandVersionList(brands).build()
    }

    /** Switches between the probe and hook scripts; takes effect after a reload. */
    fun setMode(newMode: InjectionMode) {
        if (newMode == mode) return
        mode = newMode
        Log.d(LOG_TAG, "injection mode -> $newMode")
        installDocumentStartScript()
        webView.reload()
    }

    /** Injects the script if missing; if it is already installed, the script just rescans. */
    fun reinject() {
        injectFallback(webView)
        pushSettings()
    }

    fun onHostResumed() = evaluate("window.PocketTranspose && window.PocketTranspose.resume();")

    fun pauseMedia() = evaluate("window.PocketTranspose && window.PocketTranspose.pause && window.PocketTranspose.pause();")

    private fun setPlaying(playing: Boolean) {
        if (playing == isPlaying) return
        isPlaying = playing
        listener.onPlaybackStateChanged(playing)
    }

    fun exitFullscreen() = chromeClient.exitFullscreen()

    fun setGain(value: Float) {
        settings = settings.copy(gain = value)
        evaluate("window.PocketTranspose && window.PocketTranspose.setGain($value);")
    }

    fun setPlaybackRate(value: Float) {
        settings = settings.copy(playbackRate = value)
        evaluate("window.PocketTranspose && window.PocketTranspose.setPlaybackRate($value);")
    }

    fun setPitchSemitone(value: Int) {
        settings = settings.copy(pitchSemitone = value)
        evaluate("window.PocketTranspose && window.PocketTranspose.setPitchSemitone($value);")
    }

    fun setPreservesPitch(value: Boolean) {
        settings = settings.copy(preservesPitch = value)
        evaluate("window.PocketTranspose && window.PocketTranspose.setPreservesPitch($value);")
    }

    fun requestStatus() = evaluate(
        "JSON.stringify(window.PocketTranspose ? window.PocketTranspose.getStatus() : {installed:false});"
    ) { raw ->
        // evaluateJavascript returns the JS string JSON-encoded; unwrap it.
        val json = runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
        Log.d(LOG_TAG, "status=$json")
        listener.onStatus(json)
    }

    /** Re-applies the native slider values, e.g. after a full page load reset the JS state. */
    private fun pushSettings() {
        val s = settings
        evaluate(
            "window.PocketTranspose && window.PocketTranspose.applySettings({" +
                "gain:${s.gain},playbackRate:${s.playbackRate}," +
                "pitchSemitone:${s.pitchSemitone},preservesPitch:${s.preservesPitch}});"
        )
    }

    private fun installDocumentStartScript() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartSupported = true
            documentStartHandler?.remove()
            // "*" = every origin loaded in this app's WebView (never other apps/browsers).
            documentStartHandler = WebViewCompat.addDocumentStartJavaScript(webView, scriptProvider.script(mode), setOf("*"))
            Log.d(LOG_TAG, "document-start injection registered ($mode)")
        } else {
            documentStartSupported = false
            Log.d(LOG_TAG, "document-start injection unavailable; using onPageFinished fallback ($mode)")
        }
    }

    private fun injectFallback(target: WebView) {
        // The script guards against double installation, so this is safe even when the
        // document-start script already ran.
        evaluate(scriptProvider.script(mode), target)
    }

    private fun evaluate(source: String, target: WebView = webView, callback: ((String) -> Unit)? = null) {
        target.post { target.evaluateJavascript(source, callback) }
    }

    companion object {
        private const val LOG_TAG = PocketWebChromeClient.LOG_TAG
        const val YOUTUBE_URL = "https://m.youtube.com"
        const val TEST_PAGE_URL = "https://appassets.androidplatform.net/assets/test/media_test.html"
    }
}
