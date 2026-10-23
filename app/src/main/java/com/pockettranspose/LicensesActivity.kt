package com.pockettranspose

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pockettranspose.ui.ControlSheet

/**
 * Open source licenses: PocketTranspose's own BSD 2-Clause license and the bundled
 * third-party components (Apache-2.0). The texts are app assets generated at build
 * time from LICENSE, app/licenses/Apache-2.0.txt and the checkThirdPartyLicenses
 * report (see the licenseAssets task in app/build.gradle.kts).
 */
class LicensesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layOutEdgeToEdge()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            addView(heading("Open source licenses"))
            addView(body("PocketTranspose is free software. You may use, copy, modify and redistribute it under the BSD 2-Clause License below."))
            addView(heading("PocketTranspose"))
            addView(licenseText(asset("LICENSE.txt")))
            addView(heading("Third-party components"))
            addView(body("These components are included in the app under their own licenses:"))
            addView(body(thirdPartyList()))
            addView(heading("Apache License 2.0"))
            addView(licenseText(asset("Apache-2.0.txt")))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ControlSheet.SURFACE)
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = systemBars(insets)
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    private fun asset(name: String): String =
        runCatching { assets.open("licenses/$name").bufferedReader().use { it.readText() } }
            .getOrDefault("($name is missing from this build.)")

    /** "group:name:version — License" lines from the license report's Markdown table. */
    private fun thirdPartyList(): String =
        asset("third-party-licenses.md").lineSequence()
            .filter { it.startsWith("| ") && !it.startsWith("| Module") && !it.startsWith("| ---") }
            .map { row -> row.trim('|').split(" | ").map { it.trim() } }
            .filter { it.size >= 2 }
            .joinToString("\n") { "• ${it[0]}: ${it[1]}" }
            .ifEmpty { "(No third-party component list in this build.)" }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ControlSheet.TEXT_PRIMARY)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ControlSheet.TEXT_SECONDARY)
        setPadding(0, 0, 0, dp(8))
    }

    private fun licenseText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(ControlSheet.TEXT_SECONDARY)
        setTextIsSelectable(true)
    }

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
    private fun systemBars(insets: WindowInsets): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                .let { Rect(it.left, it.top, it.right, it.bottom) }
        } else {
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
