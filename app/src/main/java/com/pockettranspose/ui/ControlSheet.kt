package com.pockettranspose.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bottom sheet holding the audio controls. Only the header ("handle") stays visible
 * when collapsed; swipe it up (or tap it) to open, swipe down / tap to close.
 *
 * The drag gesture lives on the handle only: swiping inside the WebView would fight
 * page scrolling, and swiping up from the screen edge is the system home gesture.
 */
@SuppressLint("ViewConstructor")
class ControlSheet(context: Context, private val actions: Actions) : LinearLayout(context) {
    interface Actions {
        fun onPitch(semitone: Int)
        fun onSpeed(rate: Float)
        fun onGain(gain: Float)
        fun onPreservesPitch(enabled: Boolean)
        fun onProbeOnly(enabled: Boolean)
        fun onYouTube()
        fun onTestPage()
        fun onReinject()
        fun onStatus()
    }

    private val header: LinearLayout
    private val body: MaxHeightScrollView
    private val arrow: TextView
    private val summary: TextView
    private lateinit var statusText: TextView
    private val rows = mutableListOf<StepperRow>()

    private var pitch = 0
    private var speed = 100
    private var gain = 100

    var isExpanded = false
        private set

    /** Height of the always-visible handle, so the page can reserve space for it. */
    val handleHeight: Int get() = header.height

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadii = floatArrayOf(dpF(16), dpF(16), dpF(16), dpF(16), 0f, 0f, 0f, 0f)
        }
        elevation = dpF(12)

        arrow = text("▲", 16f, TEXT_PRIMARY)
        summary = text("", 13f, TEXT_SECONDARY)
        header = buildHeader()
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        body = MaxHeightScrollView(context, maxHeightFraction = 0.6f).apply {
            isVerticalScrollBarEnabled = true
            addView(buildBody())
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Keep the collapsed position in sync whenever the body is (re)measured.
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (!isExpanded && bottom - top != oldBottom - oldTop) translationY = collapsedOffset()
        }
        updateSummary()
    }

    fun expand() = settle(true)

    fun collapse() = settle(false)

    fun showStatus(text: String) {
        statusText.text = text
    }

    // --- Header + drag gesture ---

    @SuppressLint("ClickableViewAccessibility")
    private fun buildHeader(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(8), dp(16), dp(10))
        addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(HANDLE)
                cornerRadius = dpF(2)
            }
        }, LayoutParams(dp(40), dp(4)))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(arrow)
            addView(text("  Transpose", 16f, TEXT_PRIMARY).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(summary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            summary.gravity = Gravity.END
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnTouchListener(DragListener())
        isClickable = true
        contentDescription = "Transpose controls. Swipe up or tap to open."
    }

    private inner class DragListener : OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
        private var downY = 0f
        private var startTranslation = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animate().cancel()
                    downY = event.rawY
                    startTranslation = translationY
                    dragging = false
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > touchSlop) dragging = true
                    if (dragging) translationY = (startTranslation + dy).coerceIn(0f, collapsedOffset())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    when {
                        !dragging && event.actionMasked == MotionEvent.ACTION_UP -> {
                            view.performClick()
                            settle(!isExpanded)
                        }
                        velocity < -flingVelocity -> settle(true)
                        velocity > flingVelocity -> settle(false)
                        else -> settle(translationY < collapsedOffset() / 2)
                    }
                }
            }
            return true
        }
    }

    private fun collapsedOffset(): Float = body.height.toFloat()

    private fun settle(expand: Boolean) {
        isExpanded = expand
        arrow.text = if (expand) "▼" else "▲"
        animate().translationY(if (expand) 0f else collapsedOffset()).setDuration(200).start()
    }

    // --- Body ---

    private fun buildBody(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), 0, dp(16), dp(12))

        rows += StepperRow("Pitch", -12, 12, 1, 0, { "%+d st".format(Locale.US, it) }) {
            pitch = it
            actions.onPitch(it)
        }
        rows += StepperRow("Speed", 25, 300, 5, 100, { "%.2fx".format(Locale.US, it / 100f) }) {
            speed = it
            actions.onSpeed(it / 100f)
        }
        rows += StepperRow("Gain", 0, 200, 5, 100, { "%.2f".format(Locale.US, it / 100f) }) {
            gain = it
            actions.onGain(it / 100f)
        }
        rows.forEach { addView(it.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(checkBox("Preserve pitch on speed change", true) { actions.onPreservesPitch(it) },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(checkBox("Probe only", false) { actions.onProbeOnly(it) })
        })

        addView(buttonRow("Reset" to { rows.forEach { it.reset() } }, "Status" to { actions.onStatus() }))
        addView(buttonRow(
            "YouTube" to { actions.onYouTube() },
            "Test page" to { actions.onTestPage() },
            "Reinject" to { actions.onReinject() }
        ))

        statusText = text("Tap Status to read window.PocketTranspose.getStatus()", 11f, TEXT_SECONDARY).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 8
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(8), 0, 0)
        }
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun updateSummary() {
        summary.text = "%+d st · %.2fx · gain %.2f".format(Locale.US, pitch, speed / 100f, gain / 100f)
    }

    /** One setting: label + value on top, [−] slider [+] below. Long-press the value to reset. */
    private inner class StepperRow(
        label: String,
        private val min: Int,
        max: Int,
        private val step: Int,
        private val default: Int,
        private val format: (Int) -> String,
        private val onChanged: (Int) -> Unit
    ) {
        private val valueText = text(format(default), 18f, ACCENT).apply { setTypeface(typeface, Typeface.BOLD) }
        private val seekBar = SeekBar(context).apply {
            this.max = max - min
            progress = default - min
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    valueText.text = format(value)
                    onChanged(value)
                    updateSummary()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        val view: LinearLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(label, 15f, TEXT_PRIMARY), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(valueText)
            })
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(roundButton("−") { nudge(-step) }, LayoutParams(dp(48), dp(48)))
                addView(seekBar, LayoutParams(0, dp(48), 1f))
                addView(roundButton("+") { nudge(step) }, LayoutParams(dp(48), dp(48)))
            })
            valueText.setOnLongClickListener {
                reset()
                true
            }
        }

        private fun nudge(delta: Int) {
            // Snap to the step grid so repeated taps land on round values.
            val current = seekBar.progress + min
            val snapped = ((current + delta).toFloat() / step).roundToInt() * step
            seekBar.progress = snapped - min
        }

        fun reset() {
            seekBar.progress = default - min
        }
    }

    // --- Small view helpers ---

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun roundButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(TEXT_PRIMARY)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BUTTON)
        }
        isClickable = true
        isFocusable = true
        contentDescription = if (label == "+") "Increase" else "Decrease"
        setOnClickListener { onClick() }
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(6), 0, 0)
        buttons.forEach { (label, action) ->
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(TEXT_PRIMARY)
                background = GradientDrawable().apply {
                    setColor(BUTTON)
                    cornerRadius = dpF(20)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }, LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    private fun checkBox(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) = CheckBox(context).apply {
        text = label
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
        buttonTintList = ColorStateList.valueOf(ACCENT)
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Int): Float = value * resources.displayMetrics.density

    /** ScrollView capped at a fraction of the screen height. */
    private class MaxHeightScrollView(context: Context, private val maxHeightFraction: Float) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(cap, size)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        val SURFACE = Color.rgb(0x20, 0x21, 0x24)
        val BUTTON = Color.rgb(0x3C, 0x40, 0x43)
        val HANDLE = Color.rgb(0x5F, 0x63, 0x68)
        val ACCENT = Color.rgb(0x8A, 0xB4, 0xF8)
        val TEXT_PRIMARY = Color.rgb(0xE8, 0xEA, 0xED)
        val TEXT_SECONDARY = Color.rgb(0xBD, 0xC1, 0xC6)
    }
}
