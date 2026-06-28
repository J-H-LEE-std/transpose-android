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
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun buildLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controlPanel(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun controlPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 8, 16, 8)
        addView(labeledSeekBar("Gain", 0, 200, 100) { controller.setGain(it / 100f) })
        addView(labeledSeekBar("Speed", 25, 200, 100) { controller.setPlaybackRate(it / 100f) })
        addView(labeledSeekBar("Pitch placeholder", -12, 12, 0) { controller.setPitchSemitone(it.toFloat()) })
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
