package com.pockettranspose

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pockettranspose.script.InjectedScriptProvider
import com.pockettranspose.webview.TransposeWebViewController

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var controller: TransposeWebViewController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        controller = TransposeWebViewController(this, webView, InjectedScriptProvider(this))
        controller.configure()
        setContentView(buildLayout())
        controller.loadDefault()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { navigateBack() }
        }
    }

    private fun navigateBack() {
        if (controller.exitFullscreen()) return
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
    }

    // API 33+ uses the framework OnBackInvokedDispatcher registered in onCreate.
    // This override is exclusively the fallback for API 26–32; no AndroidX Activity dependency.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() = navigateBack()

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) { webView.onResume(); controller.resume() }
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            controller.destroy()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun buildLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(LinearLayout(context).apply {
                val address = android.widget.EditText(context).apply {
                    hint = "https://…"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                }
                addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = "Go"
                    setOnClickListener {
                        if (!controller.loadUrl(address.text.toString())) address.error = "Enter an HTTPS URL"
                    }
                })
            })
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(android.widget.ScrollView(context).apply { addView(controlPanel()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.38f).toInt()))
        }
    }

    private fun controlPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 8, 16, 8)
        addView(labeledSeekBar("Gain", 0, 200, 100) { controller.setGain(it / 100f) })
        addView(labeledSeekBar("Speed", 25, 200, 100) { controller.setPlaybackRate(it / 100f) })
        addView(android.widget.Switch(context).apply {
            text = "Preserve pitch when changing speed"
            isChecked = true
            setOnCheckedChangeListener { _, checked -> controller.setPreservesPitch(checked) }
        })
        addView(TextView(context).apply { text = "Independent pitch shift: not implemented (PoC)" })
        addView(LinearLayout(context).apply {
            addView(Button(context).apply { text = "YouTube"; setOnClickListener { controller.loadDefault() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Test audio"; setOnClickListener { controller.loadTest() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Reload"; setOnClickListener { webView.reload() } }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Reinject"
                setOnClickListener { controller.reinject() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Status"
                setOnClickListener { controller.requestStatus() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun labeledSeekBar(label: String, min: Int, max: Int, initial: Int, onChanged: (Int) -> Unit): LinearLayout {
        val range = max - min
        val valueText = TextView(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val updateLabel = { value: Int -> valueText.text = "$label: $value" }
            updateLabel(initial)
            addView(valueText)
            addView(SeekBar(context).apply {
                this.max = range
                progress = initial - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        updateLabel(value)
                        if (fromUser) onChanged(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }
    }
}
