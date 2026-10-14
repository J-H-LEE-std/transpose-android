package com.pockettranspose

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pockettranspose.playback.PlaybackService
import com.pockettranspose.script.InjectedScriptProvider
import com.pockettranspose.script.InjectionMode
import com.pockettranspose.ui.ControlSheet
import com.pockettranspose.webview.TransposeWebViewController
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), TransposeWebViewController.Listener, ControlSheet.Actions {
    private lateinit var webView: WebView
    private lateinit var controller: TransposeWebViewController
    private lateinit var root: FrameLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var handleSpacer: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var sheet: ControlSheet
    private var notificationPermissionAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        controller = TransposeWebViewController(
            this, webView, InjectedScriptProvider(this), fullscreenContainer, this
        )
        controller.configure()
        controller.setIdentifyAsChrome(prefs().getBoolean(PREF_IDENTIFY_AS_CHROME, false), reload = false)
        PlaybackService.pauseMediaHandler = { controller.pauseMedia() }
        layOutEdgeToEdge()
        setContentView(buildLayout())
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) controller.loadYouTube()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            controller.isFullscreen -> controller.exitFullscreen()
            sheet.isExpanded -> sheet.collapse()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Background -> foreground: give the AudioContext a chance to resume.
        controller.onHostResumed()
    }

    override fun onPause() {
        // While media plays, keep the WebView running so playback can continue in the
        // background (the playback foreground service keeps the process alive). The
        // page itself still decides whether it keeps playing when hidden.
        if (!controller.isPlaying) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        PlaybackService.pauseMediaHandler = null
        PlaybackService.stop(this)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // --- TransposeWebViewController.Listener ---

    override fun onUrlChanged(url: String?) {
        if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
    }

    override fun onFullscreenChanged(fullscreen: Boolean) {
        val visibility = if (fullscreen) View.GONE else View.VISIBLE
        contentLayout.visibility = visibility
        sheet.visibility = visibility
        setSystemBarsHidden(fullscreen)
        root.requestApplyInsets()
    }

    override fun onPlaybackStateChanged(playing: Boolean) {
        if (playing) {
            requestNotificationPermissionOnce()
            PlaybackService.onPlaying(this, playbackSummary())
        } else {
            PlaybackService.onPaused()
        }
    }

    private fun playbackSummary(): String {
        val s = controller.settings
        return "Transpose %+d · %.2fx · gain %.2f".format(Locale.US, s.pitchSemitone, s.playbackRate, s.gain)
    }

    // Android 13+: the playback notification needs POST_NOTIFICATIONS. Playback works
    // without it; the notification just stays hidden. Ask once, on first playback.
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionAsked) return
        notificationPermissionAsked = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onStatus(statusJson: String) {
        sheet.showStatus(runCatching { JSONObject(statusJson).toString(2) }.getOrDefault(statusJson))
    }

    // --- ControlSheet.Actions ---

    override fun onPitch(semitone: Int) = controller.setPitchSemitone(semitone)
    override fun onSpeed(rate: Float) = controller.setPlaybackRate(rate)
    override fun onGain(gain: Float) = controller.setGain(gain)
    override fun onPreservesPitch(enabled: Boolean) = controller.setPreservesPitch(enabled)
    override fun onProbeOnly(enabled: Boolean) =
        controller.setMode(if (enabled) InjectionMode.PROBE else InjectionMode.HOOK)
    override fun onIdentifyAsChrome(enabled: Boolean) {
        prefs().edit().putBoolean(PREF_IDENTIFY_AS_CHROME, enabled).apply()
        controller.setIdentifyAsChrome(enabled, reload = true)
    }

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun onYouTube() = controller.loadYouTube()
    override fun onTestPage() = controller.loadTestPage()
    override fun onReinject() = controller.reinject()
    override fun onStatus() = controller.requestStatus()

    // --- System bars / insets ---
    //
    // targetSdk 35 makes Android 15+ draw the app edge-to-edge (behind the status and
    // navigation bars). Do the same on every version, then pad the root view by the
    // system-bar/cutout/IME insets so nothing is drawn under the bars.

    @Suppress("DEPRECATION")
    private fun layOutEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun systemInsets(insets: WindowInsets): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            insets.getInsets(types).let { Rect(it.left, it.top, it.right, it.bottom) }
        } else {
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    /** Only video fullscreen hides the bars (swipe from the edge shows them briefly). */
    @Suppress("DEPRECATION")
    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insetsController = window.insetsController ?: return
            if (hidden) {
                insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsets.Type.systemBars())
            } else {
                insetsController.show(WindowInsets.Type.systemBars())
            }
        } else {
            val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (hidden) {
                layoutFlags or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                layoutFlags
            }
        }
    }

    // --- Layout (programmatic, no extra UI dependencies) ---

    private fun buildLayout(): View {
        sheet = ControlSheet(this, this, controller.identifyAsChrome)
        handleSpacer = View(this)
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // Reserves the height of the collapsed sheet's handle so it never covers the page.
            addView(handleSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        }
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = sheet.handleHeight
            if (handleSpacer.layoutParams.height != height) {
                handleSpacer.layoutParams = handleSpacer.layoutParams.apply { this.height = height }
            }
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(ControlSheet.SURFACE)
            addView(contentLayout, matchParent())
            addView(sheet, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            ))
            addView(fullscreenContainer, matchParent())
            setOnApplyWindowInsetsListener { view, insets ->
                val padding = if (controller.isFullscreen) Rect() else systemInsets(insets)
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
        }
        return root
    }

    private fun urlBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(4), dp(2))
        urlInput = EditText(context).apply {
            hint = "URL"
            isSingleLine = true
            textSize = 14f
            setTextColor(ControlSheet.TEXT_PRIMARY)
            setHintTextColor(ControlSheet.TEXT_SECONDARY)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openTypedUrl()
                    true
                } else {
                    false
                }
            }
        }
        addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "Go"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ControlSheet.ACCENT)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { openTypedUrl() }
        })
    }

    private fun openTypedUrl() {
        controller.loadUrl(urlInput.text.toString())
        urlInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val PREF_IDENTIFY_AS_CHROME = "identify_as_chrome"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
