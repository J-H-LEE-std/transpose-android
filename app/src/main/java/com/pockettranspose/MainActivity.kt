package com.pockettranspose

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pockettranspose.script.InjectedScriptProvider
import com.pockettranspose.script.InjectionMode
import com.pockettranspose.webview.TransposeWebViewController
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), TransposeWebViewController.Listener {
    private lateinit var webView: WebView
    private lateinit var controller: TransposeWebViewController
    private lateinit var contentLayout: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var controlPanel: View
    private lateinit var statusText: TextView

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
        setContentView(buildLayout())
        if (savedInstanceState == null || !restoreWebView(savedInstanceState)) controller.loadYouTube()
    }

    private fun restoreWebView(state: Bundle): Boolean = webView.restoreState(state) != null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            controller.isFullscreen -> controller.exitFullscreen()
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
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // --- TransposeWebViewController.Listener ---

    override fun onUrlChanged(url: String?) {
        if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
    }

    override fun onFullscreenChanged(fullscreen: Boolean) {
        contentLayout.visibility = if (fullscreen) View.GONE else View.VISIBLE
    }

    override fun onStatus(statusJson: String) {
        statusText.text = runCatching { JSONObject(statusJson).toString(2) }.getOrDefault(statusJson)
    }

    // --- Layout (programmatic, no extra UI dependencies) ---

    private fun buildLayout(): View {
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar(), matchWidth())
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            controlPanel = buildControlPanel()
            addView(controlPanel, matchWidth())
        }
        return FrameLayout(this).apply {
            addView(contentLayout, matchParent())
            addView(fullscreenContainer, matchParent())
        }
    }

    private fun urlBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), 0, dp(4), 0)
        urlInput = EditText(context).apply {
            hint = "URL"
            isSingleLine = true
            textSize = 14f
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
        addView(smallButton("Go") { openTypedUrl() })
        addView(smallButton("Controls") {
            controlPanel.visibility = if (controlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
    }

    private fun openTypedUrl() {
        controller.loadUrl(urlInput.text.toString())
        urlInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun buildControlPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))

        addView(buttonRow(
            "YouTube" to { controller.loadYouTube() },
            "Test page" to { controller.loadTestPage() },
            "Reinject" to { controller.reinject() },
            "Status" to { controller.requestStatus() }
        ))

        addView(labeledSeekBar(0, 200, 100, { "Gain: %.2f".format(Locale.US, it / 100f) }) {
            controller.setGain(it / 100f)
        })
        addView(labeledSeekBar(25, 300, 100, { "Speed: %.2fx".format(Locale.US, it / 100f) }) {
            controller.setPlaybackRate(it / 100f)
        })
        addView(labeledSeekBar(-12, 12, 0, { "Pitch: %+d semitone(s) (experimental)".format(Locale.US, it) }) {
            controller.setPitchSemitone(it)
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(CheckBox(context).apply {
                text = "Preserve pitch on speed change"
                textSize = 12f
                isChecked = controller.settings.preservesPitch
                setOnCheckedChangeListener { _, checked -> controller.setPreservesPitch(checked) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(CheckBox(context).apply {
                text = "Probe only"
                textSize = 12f
                isChecked = controller.mode == InjectionMode.PROBE
                setOnCheckedChangeListener { _, checked ->
                    controller.setMode(if (checked) InjectionMode.PROBE else InjectionMode.HOOK)
                }
            })
        })

        statusText = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            maxLines = 6
            movementMethod = ScrollingMovementMethod()
            text = "Tap Status to read window.PocketTranspose.getStatus()"
        }
        addView(statusText, matchWidth())
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { (label, action) ->
            addView(smallButton(label, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun smallButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun labeledSeekBar(
        min: Int,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val valueText = TextView(this).apply { textSize = 12f; text = format(initial) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueText)
            addView(SeekBar(context).apply {
                this.max = max - min
                progress = initial - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        valueText.text = format(value)
                        if (fromUser) onChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
