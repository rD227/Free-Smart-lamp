package org.openlamp.local

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import com.google.android.material.R as MaterialR

data class LampScreenCallbacks(
    val onPower: (Boolean) -> Unit,
    val onPair: () -> Unit,
    val onLightingEdited: (Int, Int) -> Unit,
    val onApplyLighting: (Int, Int) -> Unit,
    val onTimerSelected: (Int) -> Unit,
    val onTimer: (Int) -> Unit,
    val onModeSelected: (Int) -> Unit,
    val onThemeChanged: (ThemeSettings) -> Unit,
)

/** The complete screen lives here. Replace this file without changing Bluetooth or encoding. */
class LampScreen(
    base: Context,
    initialState: LampState,
    private val appearance: ThemeSettings,
    private val callbacks: LampScreenCallbacks,
) {
    private val context = base
    val colors = LampColors(context, appearance)
    private var state = initialState
    private val handler = Handler(Looper.getMainLooper())
    private val controls = mutableListOf<View>()
    private val timerChips = mutableMapOf<Int, Chip>()
    private val warmthChips = mutableMapOf<Int, Chip>()
    private var progressAnimator: ValueAnimator? = null
    private var clockRunning = false
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var warmth: Slider
    private lateinit var brightness: Slider
    private lateinit var warmthValue: TextView
    private lateinit var brightnessValue: TextView
    private lateinit var timerSummary: TextView
    private lateinit var timerButton: MaterialButton
    private lateinit var customTimer: Chip
    private lateinit var modeButton: MaterialButton
    private val scroll = NestedScrollView(context).apply {
        id = R.id.page
        isFillViewport = true
        clipToPadding = true
        isVerticalScrollBarEnabled = false
        setBackgroundColor(colors.surface)
    }
    val root: View = scroll
    val scrollPosition: Int get() = scroll.scrollY
    private val page = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(32))
    }
    private val clock = object : Runnable {
        override fun run() {
            updateTimerSummary()
            if (clockRunning) handler.postDelayed(this, 1000)
        }
    }

    init {
        val width = min(context.resources.displayMetrics.widthPixels, dp(560))
        scroll.addView(page, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
            val available = right - left - view.paddingLeft - view.paddingRight
            val pageWidth = min(available, dp(560))
            if (pageWidth > 0 && page.layoutParams.width != pageWidth) {
                page.layoutParams = page.layoutParams.apply { this.width = pageWidth }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        buildHeader()
        buildPower()
        buildLighting()
        buildTimer()
        buildAppearance()
        buildPairing()
        page.addView(text(context.getString(R.string.control_note), 12, colors.secondaryText).apply {
            setPadding(dp(8), dp(8), dp(8), 0)
        })
        update(initialState)
        startClock()
    }

    private fun buildHeader() {
        val row = row()
        row.addView(ImageView(context).apply {
            setImageDrawable(BulbDrawable(colors.primary))
            imageTintList = ColorStateList.valueOf(colors.primary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(colors.primaryContainer, 18f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(column().apply {
            addView(text(context.getString(R.string.app_name), 26, colors.onSurface, true))
            addView(text(context.getString(R.string.subtitle), 13, colors.secondaryText))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        page.addView(row, fullWidth().apply { bottomMargin = dp(24) })
    }

    private fun buildPower() {
        val body = card(colors.primaryContainer)
        body.addView(text(context.getString(R.string.power_title), 24, colors.onPrimaryContainer, true))
        status = text(state.status, 14, colors.onPrimaryContainer).apply {
            id = R.id.status
            minHeight = dp(44)
            setPadding(0, dp(6), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(status, fullWidth())
        progress = LinearProgressIndicator(context).apply {
            id = R.id.send_progress
            max = 1000
            trackThickness = dp(4)
            trackCornerRadius = dp(2)
            waveAmplitude = dp(3)
            setWavelength(dp(28))
            waveSpeed = dp(32)
            setIndicatorColor(colors.primary)
            trackColor = colors.high
            indicatorTrackGapSize = dp(4)
            visibility = View.INVISIBLE
            contentDescription = context.getString(R.string.sending_progress)
        }
        body.addView(progress, fullWidth(dp(16)).apply { topMargin = dp(4); bottomMargin = dp(16) })
        val buttons = row()
        buttons.addView(button(context.getString(R.string.turn_on), R.id.turn_on) { callbacks.onPower(true) }, equalWidth())
        buttons.addView(button(context.getString(R.string.turn_off), R.id.turn_off, tonal = true) { callbacks.onPower(false) }, equalWidth().apply { marginStart = dp(12) })
        body.addView(buttons, fullWidth())
    }

    private fun buildLighting() {
        val body = card()
        val title = row()
        title.addView(text(context.getString(R.string.temperature_title), 20, colors.onSurface, true), weighted())
        warmthValue = text("", 14, colors.primary, true)
        title.addView(warmthValue)
        body.addView(title)
        body.addView(text(context.getString(R.string.temperature_help), 13, colors.secondaryText), fullWidth().apply { topMargin = dp(6) })
        val presets = chips()
        listOf(0 to R.string.cold_white, 50 to R.string.natural_white, 100 to R.string.warm_white).forEach { (value, label) ->
            val chip = chip(context.getString(label)).apply {
                setOnClickListener {
                    warmth.value = value.toFloat()
                    editLighting()
                    callbacks.onApplyLighting(value, brightness.value.roundToInt())
                }
            }
            warmthChips[value] = chip
            presets.addView(chip)
        }
        body.addView(presets, fullWidth().apply { topMargin = dp(12) })
        warmth = slider(0, 100, state.warmth, R.id.warmth).apply {
            contentDescription = context.getString(R.string.temperature_title)
            addOnChangeListener { _, _, _ -> updateLightingLabels() }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { }
                override fun onStopTrackingTouch(slider: Slider) { editLighting() }
            })
        }
        body.addView(warmth, fullWidth(dp(48)).apply { topMargin = dp(8) })
        val ends = row()
        ends.addView(text(context.getString(R.string.cold_white), 12, colors.secondaryText), weighted())
        ends.addView(text(context.getString(R.string.warm_white), 12, colors.secondaryText))
        body.addView(ends)
        val brightnessTitle = row()
        brightnessTitle.addView(text(context.getString(R.string.brightness_title), 16, colors.onSurface, true), weighted())
        brightnessValue = text("", 14, colors.primary, true)
        brightnessTitle.addView(brightnessValue)
        body.addView(brightnessTitle, fullWidth().apply { topMargin = dp(22) })
        brightness = slider(1, 100, state.brightness, R.id.brightness).apply {
            contentDescription = context.getString(R.string.brightness_title)
            addOnChangeListener { _, _, _ -> updateLightingLabels() }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { }
                override fun onStopTrackingTouch(slider: Slider) { editLighting() }
            })
        }
        body.addView(brightness, fullWidth(dp(48)))
        body.addView(button(context.getString(R.string.apply_lighting), R.id.apply_lighting, tonal = true) {
            callbacks.onApplyLighting(warmth.value.roundToInt(), brightness.value.roundToInt())
        }, fullWidth(dp(52)).apply { topMargin = dp(12) })
    }

    private fun buildTimer() {
        val body = card()
        body.addView(text(context.getString(R.string.timer_title), 20, colors.onSurface, true))
        timerSummary = text("", 14, colors.secondaryText).apply { id = R.id.timer_summary }
        body.addView(timerSummary, fullWidth().apply { topMargin = dp(6) })
        val choices = chips()
        for (minutes in listOf(15, 30, 60)) {
            val chip = chip(context.getString(R.string.minutes_short, minutes)).apply { setOnClickListener { callbacks.onTimerSelected(minutes) } }
            timerChips[minutes] = chip
            choices.addView(chip)
        }
        customTimer = chip(context.getString(R.string.custom_timer)).apply {
            id = R.id.custom_timer
            setOnClickListener { showCustomTimer() }
        }
        choices.addView(customTimer)
        body.addView(choices, fullWidth().apply { topMargin = dp(14) })
        val buttons = row()
        timerButton = button("", R.id.set_timer) { callbacks.onTimer(state.timerMinutes) }
        buttons.addView(timerButton, equalWidth())
        buttons.addView(button(context.getString(R.string.cancel_timer), R.id.cancel_timer, tonal = true) { callbacks.onTimer(0) }, equalWidth().apply { marginStart = dp(12) })
        body.addView(buttons, fullWidth().apply { topMargin = dp(14) })
        body.addView(text(context.getString(R.string.timer_help), 12, colors.secondaryText), fullWidth().apply { topMargin = dp(12) })
    }

    private fun buildAppearance() {
        val body = card()
        body.addView(text(context.getString(R.string.appearance_title), 20, colors.onSurface, true))
        val system = MaterialSwitch(context).apply {
            id = R.id.system_colors
            text = context.getString(R.string.system_colors)
            textSize = 15f
            setTextColor(colors.onSurface)
            minHeight = dp(56)
            isChecked = appearance.useSystemM3
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked -> callbacks.onThemeChanged(appearance.copy(useSystemM3 = checked)) }
        }
        controls.add(system)
        body.addView(system, fullWidth().apply { topMargin = dp(4) })
        val picker = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val wheel = ColorWheelView(context, appearance.seed, colors).apply {
            id = R.id.color_wheel
            isEnabled = !appearance.useSystemM3
            alpha = if (isEnabled) 1f else 0.35f
            onColorSelected = { color -> callbacks.onThemeChanged(appearance.copy(useSystemM3 = false, colorPack = ColorPack.CUSTOM, customColor = color)) }
        }
        picker.addView(column().apply {
            addView(wheel, fullWidth(dp(116)))
            addView(text(context.getString(R.string.theme_color), 12, colors.secondaryText).apply { gravity = Gravity.CENTER }, fullWidth())
        }, LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.add(wheel)
        val palette = chips()
        ColorPack.entries.filter { it != ColorPack.CUSTOM }.forEach { pack ->
            palette.addView(chip(pack.label).apply {
                chipIcon = DotDrawable(pack.seed)
                chipIconTint = null
                isChipIconVisible = true
                chipIconSize = dp(14).toFloat()
                isChecked = !appearance.useSystemM3 && appearance.colorPack == pack
                isEnabled = !appearance.useSystemM3
                setOnClickListener { callbacks.onThemeChanged(appearance.copy(useSystemM3 = false, colorPack = pack)) }
            })
        }
        picker.addView(palette, weighted().apply { marginStart = dp(16) })
        body.addView(picker, fullWidth().apply { topMargin = dp(6) })
    }

    private fun buildPairing() {
        val body = card()
        body.addView(text(context.getString(R.string.pair_title), 20, colors.onSurface, true))
        body.addView(text(context.getString(R.string.pair_help), 13, colors.secondaryText), fullWidth().apply { topMargin = dp(8) })
        body.addView(button(context.getString(R.string.pair_button), R.id.pair, tonal = true, onClick = callbacks.onPair), fullWidth(dp(52)).apply { topMargin = dp(14) })
        modeButton = button("", R.id.mode, outlined = true) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compatibility)
                .setSingleChoiceItems(R.array.compatibility_modes, state.mode) { dialog, selected -> callbacks.onModeSelected(selected); dialog.dismiss() }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        body.addView(modeButton, fullWidth(dp(48)).apply { topMargin = dp(10) })
    }

    fun update(next: LampState) {
        val wasBusy = state.busy
        state = next
        status.text = next.status
        controls.forEach { it.isEnabled = !next.busy }
        // Theme colors remain available only when the system-color switch is off.
        page.findViewById<View>(R.id.color_wheel)?.apply {
            isEnabled = !next.busy && !appearance.useSystemM3
            alpha = if (isEnabled) 1f else 0.35f
        }
        controls.filterIsInstance<Chip>().filter { it.chipIcon is DotDrawable }.forEach { it.isEnabled = !next.busy && !appearance.useSystemM3 }
        if (warmth.value.roundToInt() != next.warmth) warmth.value = next.warmth.toFloat()
        if (brightness.value.roundToInt() != next.brightness) brightness.value = next.brightness.toFloat()
        updateLightingLabels()
        timerChips.forEach { (minutes, chip) -> chip.isChecked = minutes == next.timerMinutes }
        customTimer.isChecked = next.timerMinutes !in timerChips
        customTimer.text = if (customTimer.isChecked) context.getString(R.string.minutes_short, next.timerMinutes) else context.getString(R.string.custom_timer)
        timerButton.text = context.getString(R.string.set_timer_minutes, next.timerMinutes)
        modeButton.text = context.resources.getStringArray(R.array.compatibility_modes)[next.mode]
        updateTimerSummary()
        if (next.busy && !wasBusy) {
            handler.removeCallbacksAndMessages(null)
            progress.visibility = View.VISIBLE
            progress.setProgressCompat(0, false)
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofInt(0, 970).apply {
                duration = next.sendDurationMillis
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { progress.setProgressCompat(it.animatedValue as Int, false) }
                start()
            }
            if (clockRunning) handler.post(clock)
        } else if (!next.busy && wasBusy) {
            progressAnimator?.cancel()
            progress.setProgressCompat(1000, true)
            handler.postDelayed({ if (!state.busy) progress.visibility = View.INVISIBLE }, 200)
        }
    }

    private fun editLighting() {
        callbacks.onLightingEdited(warmth.value.roundToInt(), brightness.value.roundToInt())
    }

    private fun updateLightingLabels() {
        if (!::warmth.isInitialized || !::brightness.isInitialized) return
        val value = warmth.value.roundToInt()
        warmthValue.text = context.getString(R.string.percent, value)
        brightnessValue.text = context.getString(R.string.percent, brightness.value.roundToInt())
        warmthChips.forEach { (preset, chip) -> chip.isChecked = preset == value }
    }

    private fun updateTimerSummary() {
        if (!::timerSummary.isInitialized) return
        val remaining = state.timerDeadline - System.currentTimeMillis()
        timerSummary.text = when {
            state.timerDeadline == 0L -> context.getString(R.string.timer_none)
            remaining <= 0 -> context.getString(R.string.timer_elapsed)
            else -> context.getString(R.string.timer_expected, (remaining + 59_999) / 60_000, DateFormat.getTimeFormat(context).format(Date(state.timerDeadline)))
        }
    }

    private fun showCustomTimer() {
        val builder = MaterialAlertDialogBuilder(context).setTitle(R.string.custom_timer)
        val dialogContext = builder.context
        val layout = TextInputLayout(dialogContext).apply {
            hint = context.getString(R.string.timer_minutes_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val input = TextInputEditText(layout.context).apply {
            id = R.id.timer_input
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setText(String.format(Locale.getDefault(), "%d", state.timerMinutes))
            selectAll()
        }
        layout.addView(input, fullWidth())
        val dialog = builder
            .setView(layout)
            .setPositiveButton(R.string.choose, null)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text?.toString()?.toIntOrNull()
                if (minutes == null || minutes !in 1..720) layout.error = context.getString(R.string.timer_range_error)
                else { callbacks.onTimerSelected(minutes); dialog.dismiss() }
            }
        }
        dialog.setOnDismissListener { update(state) }
        dialog.show()
    }

    fun restoreScroll(position: Int) { scroll.post { scroll.scrollTo(0, position) } }
    fun startClock() { if (!clockRunning) { clockRunning = true; handler.post(clock) } }
    fun stopClock() { clockRunning = false; handler.removeCallbacks(clock) }
    fun dispose() { stopClock(); handler.removeCallbacksAndMessages(null); progressAnimator?.cancel() }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun fullWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun equalWidth() = LinearLayout.LayoutParams(0, dp(54), 1f)
    private fun rounded(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = radius * context.resources.displayMetrics.density
    }

    private fun card(color: Int = colors.card): LinearLayout {
        val body = column().apply { setPadding(dp(20), dp(22), dp(20), dp(20)) }
        val card = MaterialCardView(context).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color)
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        page.addView(card, fullWidth().apply { bottomMargin = dp(16) })
        return body
    }

    private fun button(label: String, viewId: Int, tonal: Boolean = false, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        return MaterialButton(context).apply {
            id = viewId
            text = label
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(24)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(48)
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            val foreground = if (outlined || tonal) colors.primary else colors.onPrimary
            setTextColor(buttonTextColors(foreground))
            backgroundTintList = buttonBackgroundColors(if (outlined) colors.card else if (tonal) colors.high else colors.primary)
            if (outlined) { strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(colors.outline) }
            setOnClickListener { onClick() }
            controls.add(this)
        }
    }

    private fun buttonTextColors(enabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(withAlpha(colors.onSurface, 0.38f), enabled),
    )
    private fun buttonBackgroundColors(enabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(withAlpha(colors.onSurface, 0.12f), enabled),
    )
    private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((255 * alpha).roundToInt() shl 24)
    private fun chips() = ChipGroup(context).apply { chipSpacingHorizontal = dp(6); chipSpacingVertical = dp(2) }
    private fun chip(label: String) = Chip(context, null, MaterialR.attr.chipStyle).apply {
        text = label
        textSize = 13f
        isCheckable = true
        isSaveEnabled = false
        isCheckedIconVisible = false
        shapeAppearanceModel = shapeAppearanceModel.withCornerSize(dp(16).toFloat())
        chipMinHeight = dp(36).toFloat()
        setEnsureMinTouchTargetSize(true)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(colors.onPrimaryContainer, colors.secondaryText)))
        chipBackgroundColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(colors.primaryContainer, colors.high))
        chipStrokeWidth = 0f
        controls.add(this)
    }
    private fun slider(from: Int, to: Int, value: Int, viewId: Int) = Slider(context).apply {
        id = viewId
        isSaveEnabled = false
        valueFrom = from.toFloat()
        valueTo = to.toFloat()
        stepSize = 1f
        this.value = value.toFloat()
        trackHeight = dp(8)
        thumbRadius = dp(10)
        trackActiveTintList = ColorStateList.valueOf(colors.primary)
        trackInactiveTintList = ColorStateList.valueOf(colors.high)
        thumbTintList = ColorStateList.valueOf(colors.primary)
        haloTintList = ColorStateList.valueOf(colors.primary)
        tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
        setLabelFormatter { context.getString(R.string.percent, it.roundToInt()) }
        controls.add(this)
    }
}

/** Theme-only hue picker; keyboard and accessibility actions offer the same control as touch. */
@SuppressLint("ViewConstructor") // Constructed only by LampScreen, never inflated from XML.
private class ColorWheelView(context: Context, seed: Int, private val colors: LampColors) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var hue = FloatArray(3).also { Color.colorToHSV(seed, it) }[0]
    private var ringGradient: SweepGradient? = null
    var onColorSelected: (Int) -> Unit = {}
    private val selectedColor: Int get() = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))

    init { isFocusable = true; isClickable = true; updateDescription() }
    private fun updateDescription() { contentDescription = context.getString(R.string.theme_hue, hue.roundToInt()) }
    private fun pick(x: Float, y: Float) {
        hue = ((Math.toDegrees(atan2((y - height / 2).toDouble(), (x - width / 2).toDouble())) + 360) % 360).toFloat()
        updateDescription()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ringGradient = SweepGradient(w / 2f, h / 2f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val y = height / 2f
        val radius = min(width, height) / 2f - 12 * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16 * density
        paint.shader = ringGradient
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = colors.primaryContainer
        canvas.drawCircle(x, y, radius - 19 * density, paint)
        paint.color = colors.primary
        canvas.drawCircle(x, y, 9 * density, paint)
        val angle = Math.toRadians(hue.toDouble())
        val px = x + radius * cos(angle).toFloat()
        val py = y + radius * sin(angle).toFloat()
        paint.color = Color.WHITE
        canvas.drawCircle(px, py, 10 * density, paint)
        paint.color = selectedColor
        canvas.drawCircle(px, py, 6.5f * density, paint)
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.color = colors.primary
            paint.strokeWidth = 2 * density
            canvas.drawCircle(x, y, min(width, height) / 2f - density, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(event.x - width / 2f, event.y - height / 2f)
                if (distance < min(width, height) / 2f - 32 * density) return false
                parent.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                pick(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> pick(event.x, event.y)
            MotionEvent.ACTION_UP -> { pick(event.x, event.y); isPressed = false; parent.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (isEnabled) onColorSelected(selectedColor)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            hue = (hue + (if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) 345 else 15)) % 360
            updateDescription(); invalidate(); onColorSelected(selectedColor)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION") // RangeInfo.obtain supports the app's Android 8 minimum.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 360f, hue)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return super.performAccessibilityAction(action, arguments)
        hue = when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.coerceIn(0f, 360f) ?: return false
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> (hue + 15) % 360
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> (hue + 345) % 360
            else -> return super.performAccessibilityAction(action, arguments)
        }
        updateDescription(); invalidate(); onColorSelected(selectedColor)
        return true
    }
}

private class DotDrawable(tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    override fun draw(canvas: Canvas) { canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), min(bounds.width(), bounds.height()) / 2f, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Required by Drawable on older Android versions") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

private class BulbDrawable(tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND }
    private val bulb = RectF(5f, 2f, 19f, 16f)
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawArc(bulb, 35f, -250f, false, paint)
        canvas.drawLine(6.3f, 13f, 9f, 17f, paint)
        canvas.drawLine(17.7f, 13f, 15f, 17f, paint)
        canvas.drawLine(9f, 17f, 15f, 17f, paint)
        canvas.drawLine(9f, 20f, 15f, 20f, paint)
        canvas.drawLine(10.5f, 22f, 13.5f, 22f, paint)
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Required by Drawable on older Android versions") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
