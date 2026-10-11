package org.openlamp.local

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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

// Wave motion is independent of the actual Bluetooth transmission duration.
private const val SEND_WAVE_SPEED_DP_PER_SECOND = 24
private const val SEND_WAVELENGTH_DP = 28
private const val SEND_WAVE_AMPLITUDE_DP = 3
// The wave follows the controller's send duration and fades when sending actually finishes.
private const val SEND_FEEDBACK_FADE_MILLIS = 80L
private const val LIGHTING_HALO_MAX_ALPHA = 0.5f

data class LampScreenCallbacks(
    val onPower: (Boolean) -> Unit,
    val onPair: () -> Unit,
    val onLightingEdited: (Int, Int, Boolean) -> Unit,
    val onApplyLighting: (Int, Int) -> Unit,
    val onLightingApplyModeSelected: (LightingApplyMode) -> Unit,
    val onTimerSelected: (Int) -> Unit,
    val onTimerCancelled: () -> Unit,
    val onTimer: (Int) -> Unit,
    val onModeSelected: (Int) -> Unit,
    val onThemeChanged: (ThemeSettings) -> Unit,
)

/** The complete screen lives here. Replace this file without changing Bluetooth or encoding. */
class LampScreen(
    private val base: Context,
    initialState: LampState,
    private val appearance: ThemeSettings,
    private val callbacks: LampScreenCallbacks,
    retainedScroll: NestedScrollView? = null,
    private val controlsOnly: Boolean = false,
) {
    private val context = appearance.createContext(base)
    val colors = LampColors(context, appearance)
    private var state = initialState
    private val handler = Handler(Looper.getMainLooper())
    private val controls = mutableListOf<View>()
    private val timerChips = mutableMapOf<Int, Chip>()
    private val warmthChips = mutableMapOf<Int, Chip>()
    private val lightingControls = mutableListOf<View>()
    private val trackingSliders = mutableSetOf<Slider>()
    private lateinit var lightingModes: MaterialButtonToggleGroup
    private lateinit var lightingModeHelp: TextView
    private var updatingLightingMode = false
    private var themeHint: Toast? = null
    private var progressAnimator: ValueAnimator? = null
    private var clockRunning = false
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var continuousWave: ContinuousWaveView
    private lateinit var applyLightingButton: MaterialButton
    private lateinit var warmth: Slider
    private lateinit var brightness: Slider
    private lateinit var warmthValue: TextView
    private lateinit var brightnessValue: TextView
    private lateinit var timerSummary: TextView
    private lateinit var timerButton: MaterialButton
    private lateinit var customTimer: Chip
    private lateinit var modeButton: MaterialButton
    private val scroll = (retainedScroll ?: NestedScrollView(base)).apply {
        id = R.id.page
        isFillViewport = true
        // Restore only on Activity recreation; palette changes retain this same scroll view.
        isSaveEnabled = false
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
    private val pageLayoutListener = View.OnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
        fitContentWidth(right - left - view.paddingLeft - view.paddingRight)
    }
    private val clock = object : Runnable {
        override fun run() {
            updateTimerSummary()
            if (clockRunning) handler.postDelayed(this, 1000)
        }
    }

    init {
        // Reuse the measured width when recoloring, avoiding an extra layout at screen width.
        val available = (scroll.width - scroll.paddingLeft - scroll.paddingRight).takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val width = min(available, dp(560))
        if (!controlsOnly) buildHeader()
        buildPower()
        buildLighting()
        if (!controlsOnly) {
            buildTimer()
            buildAppearance()
            buildPairing()
            page.addView(text(context.getString(R.string.control_note), 12, colors.secondaryText).apply {
                setPadding(dp(8), dp(8), dp(8), 0)
            })
        }
        update(initialState)
        // Attach the finished content with its current labels and selections already applied.
        // A theme change must never expose temporary text such as "custom" before "1 minute".
        scroll.removeAllViews()
        scroll.addView(page, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.addOnLayoutChangeListener(pageLayoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
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
            waveAmplitude = dp(SEND_WAVE_AMPLITUDE_DP)
            setWavelength(dp(SEND_WAVELENGTH_DP))
            waveSpeed = dp(SEND_WAVE_SPEED_DP_PER_SECOND)
            setIndicatorColor(colors.primary)
            trackColor = colors.high
            indicatorTrackGapSize = dp(4)
            visibility = View.INVISIBLE
            contentDescription = context.getString(R.string.sending_progress)
        }
        continuousWave = ContinuousWaveView(context, colors.primary).apply {
            id = R.id.live_send_wave
            visibility = View.INVISIBLE
            contentDescription = context.getString(R.string.sending_progress)
        }
        body.addView(FrameLayout(context).apply {
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(continuousWave, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, fullWidth(dp(16)).apply { topMargin = dp(4); bottomMargin = dp(16) })
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
                    callbacks.onApplyLighting(value, brightness.value.roundToInt())
                }
            }
            lightingControls.add(chip)
            warmthChips[value] = chip
            presets.addView(chip)
        }
        body.addView(presets, fullWidth().apply { topMargin = dp(12) })
        warmth = slider(0, 100, state.warmth, R.id.warmth).apply {
            contentDescription = context.getString(R.string.temperature_title)
            connectLightingSlider(this)
        }
        lightingControls.add(warmth)
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
            connectLightingSlider(this)
        }
        lightingControls.add(brightness)
        body.addView(brightness, fullWidth(dp(48)))
        lightingModes = AnimatedLightingModes(context, colors).apply {
            id = R.id.lighting_apply_mode
            isSingleSelection = true
            isSelectionRequired = true
            isSaveEnabled = false
            contentDescription = context.getString(R.string.lighting_apply_mode)
        }
        applyLightingButton = button(context.getString(R.string.apply_lighting), R.id.apply_lighting, tonal = true) {
            callbacks.onApplyLighting(warmth.value.roundToInt(), brightness.value.roundToInt())
        }.also { lightingControls.add(it) }
        listOf(LightingApplyMode.WHILE_SLIDING, LightingApplyMode.ON_RELEASE, LightingApplyMode.MANUAL).forEach { mode ->
            val segment = MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                id = lightingModeId(mode)
                text = context.getString(lightingModeLabel(mode))
                textSize = 13f
                isAllCaps = false
                isSaveEnabled = false
                cornerRadius = dp(24)
                insetTop = 0
                insetBottom = 0
                minimumWidth = 0
                minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                strokeWidth = 0
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(withAlpha(colors.onSurface, 0.38f), colors.onPrimaryContainer, colors.secondaryText),
                ))
            }
            controls.add(segment)
            lightingControls.add(segment)
            lightingModes.addView(segment, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        lightingModes.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingLightingMode) {
                LightingApplyMode.entries.firstOrNull { lightingModeId(it) == id }?.let(callbacks.onLightingApplyModeSelected)
            }
        }
        body.addView(lightingModes, fullWidth().apply { topMargin = dp(16) })
        body.addView(applyLightingButton, fullWidth(dp(52)).apply { topMargin = dp(12) })
        lightingModeHelp = text("", 12, colors.secondaryText)
        body.addView(lightingModeHelp, fullWidth().apply { topMargin = dp(10) })
    }

    private fun connectLightingSlider(slider: Slider) {
        slider.addOnChangeListener { source, _, fromUser ->
            updateLightingLabels()
            // Keyboard and accessibility changes have no touch-release callback.
            if (fromUser) editLighting(gestureEnded = source !in trackingSliders)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                trackingSliders.add(slider)
                if (state.lightingApplyMode == LightingApplyMode.WHILE_SLIDING) showFullWave()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                trackingSliders.remove(slider)
                editLighting(gestureEnded = true)
            }
        })
    }

    private fun lightingModeId(mode: LightingApplyMode) = when (mode) {
        LightingApplyMode.ON_RELEASE -> R.id.apply_on_release
        LightingApplyMode.WHILE_SLIDING -> R.id.apply_while_sliding
        LightingApplyMode.MANUAL -> R.id.apply_manually
    }

    private fun lightingModeLabel(mode: LightingApplyMode) = when (mode) {
        LightingApplyMode.ON_RELEASE -> R.string.apply_on_release
        LightingApplyMode.WHILE_SLIDING -> R.string.apply_while_sliding
        LightingApplyMode.MANUAL -> R.string.apply_manually
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
        customTimer = chip(customTimerLabel(state.timerMinutes)).apply {
            id = R.id.custom_timer
            isChecked = state.timerMinutes !in timerChips
            setOnClickListener { showCustomTimer() }
        }
        choices.addView(customTimer)
        body.addView(choices, fullWidth().apply { topMargin = dp(14) })
        val buttons = row()
        timerButton = button(context.getString(R.string.set_timer_minutes, state.timerMinutes), R.id.set_timer) { callbacks.onTimer(state.timerMinutes) }
        buttons.addView(timerButton, equalWidth())
        buttons.addView(button(context.getString(R.string.cancel_timer), R.id.cancel_timer, tonal = true, onClick = callbacks.onTimerCancelled), equalWidth().apply { marginStart = dp(12) })
        body.addView(buttons, fullWidth().apply { topMargin = dp(14) })
        body.addView(text(context.getString(R.string.timer_help), 12, colors.secondaryText), fullWidth().apply { topMargin = dp(12) })
    }

    private fun customTimerLabel(minutes: Int) = if (minutes in timerChips) {
        context.getString(R.string.custom_timer)
    } else context.getString(R.string.minutes_short, minutes)

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
        val wheel = ColorRingView(context, appearance.wheelColor, RingKind.HUE, colors).apply {
            id = R.id.color_wheel
            isEnabled = !appearance.useSystemM3
            alpha = if (isEnabled) 1f else 0.35f
            blockedBySystemColors = appearance.useSystemM3
            onBlockedInteraction = ::showSystemColorHint
        }
        val whiteRing = ColorRingView(context, appearance.wheelColor, RingKind.WHITENESS, colors).apply {
            id = R.id.white_ring
            isEnabled = !appearance.useSystemM3
            alpha = if (isEnabled) 1f else 0.35f
            blockedBySystemColors = appearance.useSystemM3
            onBlockedInteraction = ::showSystemColorHint
        }
        val colorLabel = text(context.getString(R.string.theme_whiteness_value, (appearance.wheelColor.whiteness * 100).roundToInt()), 12, colors.secondaryText).apply {
            id = R.id.theme_whiteness_value
            gravity = Gravity.CENTER
        }
        val preview: (ThemeColor) -> Unit = { selection ->
            wheel.selection = selection
            whiteRing.selection = selection
            colorLabel.text = context.getString(R.string.theme_whiteness_value, (selection.whiteness * 100).roundToInt())
        }
        val commit: (ThemeColor) -> Unit = { callbacks.onThemeChanged(appearance.withCustomColor(it)) }
        wheel.onPreview = preview
        whiteRing.onPreview = preview
        wheel.onColorSelected = commit
        whiteRing.onColorSelected = commit
        val rings = FrameLayout(context).apply {
            addView(whiteRing, FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER))
            addView(wheel, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        }
        picker.addView(column().apply {
            addView(rings, fullWidth(dp(180)))
            addView(colorLabel, fullWidth())
        }, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.add(wheel)
        controls.add(whiteRing)
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
        picker.addView(palette, weighted().apply { marginStart = dp(12) })
        body.addView(picker, fullWidth().apply { topMargin = dp(6) })
        body.addView(text(context.getString(R.string.theme_rings_help), 12, colors.secondaryText), fullWidth().apply { topMargin = dp(12) })
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

    private fun showSystemColorHint() {
        themeHint?.cancel()
        themeHint = Toast.makeText(context, R.string.system_colors_ring_hint, Toast.LENGTH_SHORT).also { it.show() }
    }

    fun update(next: LampState) {
        val wasBusy = state.busy
        val previousTransmissionId = state.transmissionId
        val wasContinuous = continuousWave.visibility == View.VISIBLE
        state = next
        status.text = next.status
        controls.forEach { it.isEnabled = !next.busy }
        lightingControls.forEach { it.isEnabled = !next.busy || next.sendingLighting }
        applyLightingButton.visibility = if (next.lightingApplyMode == LightingApplyMode.MANUAL) View.VISIBLE else View.GONE
        updatingLightingMode = true
        lightingModes.check(lightingModeId(next.lightingApplyMode))
        updatingLightingMode = false
        lightingModeHelp.setText(when (next.lightingApplyMode) {
            LightingApplyMode.ON_RELEASE -> R.string.apply_on_release_help
            LightingApplyMode.WHILE_SLIDING -> R.string.apply_while_sliding_help
            LightingApplyMode.MANUAL -> R.string.apply_manually_help
        })
        // Theme colors remain available only when the system-color switch is off.
        listOf(R.id.color_wheel, R.id.white_ring).forEach { id ->
            page.findViewById<View>(id)?.apply {
                isEnabled = !next.busy && !appearance.useSystemM3
                alpha = if (isEnabled) 1f else 0.35f
            }
        }
        controls.filterIsInstance<Chip>().filter { it.chipIcon is DotDrawable }.forEach { it.isEnabled = !next.busy && !appearance.useSystemM3 }
        if (warmth.value.roundToInt() != next.warmth) warmth.value = next.warmth.toFloat()
        if (brightness.value.roundToInt() != next.brightness) brightness.value = next.brightness.toFloat()
        updateLightingLabels()
        if (!controlsOnly) {
            timerChips.forEach { (minutes, chip) -> chip.isChecked = minutes == next.timerMinutes }
            customTimer.isChecked = next.timerMinutes !in timerChips
            val timerLabel = customTimerLabel(next.timerMinutes)
            if (customTimer.text.toString() != timerLabel) customTimer.text = timerLabel
            timerButton.text = context.getString(R.string.set_timer_minutes, next.timerMinutes)
            modeButton.text = context.resources.getStringArray(R.array.compatibility_modes)[next.mode]
            updateTimerSummary()
        }
        val showContinuous = next.lightingApplyMode == LightingApplyMode.WHILE_SLIDING &&
            (trackingSliders.isNotEmpty() || next.busy && next.sendingLighting)
        continuousWave.visibility = if (showContinuous) View.VISIBLE else View.INVISIBLE
        continuousWave.setRunning(showContinuous)
        if (showContinuous) {
            showFullWave()
        } else if (next.busy && (!wasBusy || next.transmissionId != previousTransmissionId || wasContinuous)) {
            showSendFeedback(next.sendDurationMillis)
        } else if (!next.busy && wasBusy && !wasContinuous) {
            progressAnimator?.cancel()
            progress.setProgressCompat(1000, false)
            fadeSendFeedback()
        }
    }

    private fun showSendFeedback(sendDurationMillis: Long) {
        progressAnimator?.cancel()
        progress.animate().cancel()
        progress.alpha = 1f
        progress.visibility = View.VISIBLE
        progress.setProgressCompat(0, false)
        progressAnimator = ValueAnimator.ofInt(0, 970).apply {
            duration = sendDurationMillis.coerceAtLeast(1L)
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { progress.setProgressCompat(it.animatedValue as Int, false) }
            // Bluetooth startup adds some overhead. Complete the wave only when busy clears,
            // keeping its disappearance in sync with the controls becoming available again.
            start()
        }
    }

    private fun fadeSendFeedback() {
        if (progress.visibility != View.VISIBLE) return
        progress.animate().cancel()
        progress.animate().alpha(0f).setDuration(SEND_FEEDBACK_FADE_MILLIS)
            .withEndAction { progress.visibility = View.INVISIBLE }.start()
    }

    private fun showFullWave() {
        progressAnimator?.cancel()
        progressAnimator = null
        progress.animate().cancel()
        progress.visibility = View.INVISIBLE
        continuousWave.visibility = View.VISIBLE
        continuousWave.setRunning(true)
    }

    private fun editLighting(gestureEnded: Boolean) {
        callbacks.onLightingEdited(warmth.value.roundToInt(), brightness.value.roundToInt(), gestureEnded)
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

    /** Recolor inside the attached scroll view, without Activity/window lifecycle or focus resets. */
    fun withAppearance(next: ThemeSettings): LampScreen {
        val position = scrollPosition
        val focusedId = scroll.findFocus()?.id ?: View.NO_ID
        val descendantFocusability = scroll.descendantFocusability
        // Removing a focused child otherwise lets Android focus the first control at the top.
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        scroll.requestFocus()
        dispose()
        return LampScreen(base, state, next, callbacks, scroll, controlsOnly).also {
            scroll.doOnPreDraw {
                scroll.descendantFocusability = descendantFocusability
                if (focusedId != View.NO_ID && focusedId != scroll.id) {
                    scroll.findViewById<View>(focusedId)?.requestFocus()
                }
                scroll.scrollTo(0, position)
            }
        }
    }

    fun restoreScroll(position: Int) { scroll.doOnPreDraw { scroll.scrollTo(0, position) } }
    fun startClock() { if (!controlsOnly && !clockRunning) { clockRunning = true; handler.post(clock) } }
    fun stopClock() { clockRunning = false; handler.removeCallbacks(clock) }
    fun dispose() {
        stopClock()
        handler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        progress.animate().cancel()
        continuousWave.setRunning(false)
        themeHint?.cancel()
        scroll.removeOnLayoutChangeListener(pageLayoutListener)
    }

    internal fun fitContentWidth(available: Int) {
        val pageWidth = min(available, dp(560))
        if (pageWidth > 0 && page.layoutParams.width != pageWidth) {
            page.layoutParams = page.layoutParams.apply { width = pageWidth }
        }
    }

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
    private fun slider(from: Int, to: Int, value: Int, viewId: Int) = LightingSlider(context).apply {
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
        tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
        setLabelFormatter { context.getString(R.string.percent, it.roundToInt()) }
        controls.add(this)
    }
}

/** Keep Material's slider behavior; paint the drag halo above both track segments. */
@SuppressLint("ViewConstructor")
private class LightingSlider(context: Context) : Slider(context) {
    private val density = resources.displayMetrics.density
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var haloOpacity = 0f
    private var haloAnimator: ValueAnimator? = null
    private var dragging = false

    init {
        haloRadius = 0
        haloTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        addOnSliderTouchListener(object : OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { dragging = true; animateHalo(LIGHTING_HALO_MAX_ALPHA) }
            override fun onStopTrackingTouch(slider: Slider) { dragging = false; animateHalo(0f) }
        })
    }

    private fun animateHalo(target: Float) {
        haloAnimator?.cancel()
        haloAnimator = ValueAnimator.ofFloat(haloOpacity, target).apply {
            duration = 120L
            addUpdateListener {
                haloOpacity = (it.animatedValue as Float).coerceIn(0f, LIGHTING_HALO_MAX_ALPHA)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEnabled || haloOpacity <= 0f) return
        var fraction = (value - valueFrom) / (valueTo - valueFrom)
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction
        val x = trackSidePadding + trackWidth * fraction
        val y = height / 2f
        val tint = thumbTintList.getColorForState(drawableState, thumbTintList.defaultColor)
        haloPaint.color = tint
        haloPaint.alpha = (haloOpacity * 255).roundToInt()
        canvas.drawCircle(x, y, 22 * density, haloPaint)
        // Keep the solid thumb clear while the translucent halo overlays the inactive track.
        haloPaint.alpha = 255
        canvas.drawCircle(x, y, thumbRadius.toFloat(), haloPaint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!dragging) animateHalo(if (gainFocus && !isInTouchMode) LIGHTING_HALO_MAX_ALPHA else 0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { dragging = false; animateHalo(0f) }
        return handled
    }

    override fun onDetachedFromWindow() {
        haloAnimator?.cancel()
        haloOpacity = 0f
        super.onDetachedFromWindow()
    }
}

/** A full-length wave for live sending; packet changes never reset its phase or length. */
@SuppressLint("ViewConstructor")
private class ContinuousWaveView(context: Context, tint: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun setRunning(running: Boolean) {
        if (!running) { animator?.cancel(); animator = null; return }
        if (animator != null || SEND_WAVE_SPEED_DP_PER_SECOND <= 0) return
        animator = ValueAnimator.ofFloat(0f, SEND_WAVELENGTH_DP * density).apply {
            duration = 1000L * SEND_WAVELENGTH_DP / SEND_WAVE_SPEED_DP_PER_SECOND
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth / 2f
        val wavelength = SEND_WAVELENGTH_DP * density
        val amplitude = SEND_WAVE_AMPLITUDE_DP * density
        path.rewind()
        var x = inset
        while (x <= width - inset) {
            val y = height / 2f + amplitude * sin((x - phase) * 2 * Math.PI / wavelength).toFloat()
            if (x == inset) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2 * density
        }
        canvas.drawPath(path, paint)
    }

    override fun onDetachedFromWindow() { setRunning(false); super.onDetachedFromWindow() }
}

/** Material's single-selection semantics with a sliding pill inspired by WireFstm's bottom bar. */
@SuppressLint("ViewConstructor")
private class AnimatedLightingModes(context: Context, private val colors: LampColors) : MaterialButtonToggleGroup(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private var selectedId = View.NO_ID
    private var indicatorLeft = 0f
    private var indicatorWidth = 0f
    private var previousWidth = 0
    private val pulses = mutableMapOf<Int, Pair<SpringAnimation, SpringAnimation>>()
    private val position = object : FloatPropertyCompat<AnimatedLightingModes>("indicatorLeft") {
        override fun getValue(view: AnimatedLightingModes) = view.indicatorLeft
        override fun setValue(view: AnimatedLightingModes, value: Float) { view.indicatorLeft = value; view.invalidate() }
    }
    private val slide = SpringAnimation(this, position).apply {
        spring = SpringForce().setDampingRatio(0.5f).setStiffness(SpringForce.STIFFNESS_LOW)
    }

    init {
        setWillNotDraw(false)
        addOnButtonCheckedListener { _, id, checked ->
            if (checked && id != selectedId) {
                selectedId = id
                val button = findViewById<MaterialButton>(id)
                if (isLaidOut && button.width > 0) {
                    val target = button.left + 3 * density
                    indicatorWidth = button.width - 6 * density
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        slide.animateToFinalPosition(target)
                        val pulse = pulses.getOrPut(id) {
                            SpringAnimation(button, DynamicAnimation.SCALE_X).apply {
                                spring = SpringForce(1f).setDampingRatio(0.5f).setStiffness(SpringForce.STIFFNESS_LOW)
                            } to SpringAnimation(button, DynamicAnimation.SCALE_Y).apply {
                                spring = SpringForce(1f).setDampingRatio(0.5f).setStiffness(SpringForce.STIFFNESS_LOW)
                            }
                        }
                        pulse.first.cancel(); pulse.second.cancel()
                        button.scaleX = 1.08f; button.scaleY = 1.04f
                        pulse.first.start(); pulse.second.start()
                    } else { slide.cancel(); indicatorLeft = target; invalidate() }
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (previousWidth != width) {
            previousWidth = width
            findViewById<MaterialButton>(selectedId)?.let {
                slide.cancel()
                indicatorLeft = it.left + 3 * density
                indicatorWidth = it.width - 6 * density
                invalidate()
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.rewind()
        clip.addRoundRect(bounds, 24 * density, 24 * density, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = colors.card
        canvas.drawRect(bounds, paint)
        if (indicatorWidth > 0) {
            bounds.set(indicatorLeft, 3 * density, indicatorLeft + indicatorWidth, height - 3 * density)
            paint.color = colors.primaryContainer
            paint.alpha = if (findViewById<View>(selectedId)?.isEnabled == true) 255 else 96
            canvas.drawRoundRect(bounds, 21 * density, 21 * density, paint)
            paint.alpha = 255
        }
        super.dispatchDraw(canvas)
        paint.style = Paint.Style.STROKE
        paint.color = colors.outline
        paint.strokeWidth = density
        bounds.set(density / 2, density / 2, width - density / 2, height - density / 2)
        canvas.drawRoundRect(bounds, 24 * density, 24 * density, paint)
        canvas.restoreToCount(saved)
    }

    override fun onDetachedFromWindow() {
        slide.cancel()
        pulses.values.forEach { it.first.cancel(); it.second.cancel() }
        super.onDetachedFromWindow()
    }
}

private enum class RingKind { HUE, WHITENESS }

/** Separate accessible range controls share a color preview and commit only on release. */
@SuppressLint("ViewConstructor") // Constructed only by LampScreen, never inflated from XML.
private class ColorRingView(
    context: Context,
    initial: ThemeColor,
    private val kind: RingKind,
    private val colors: LampColors,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val arcBounds = RectF()
    private var gradient: SweepGradient? = null
    private var gestureStart = initial
    var onPreview: (ThemeColor) -> Unit = {}
    var onColorSelected: (ThemeColor) -> Unit = {}
    var blockedBySystemColors = false
    var onBlockedInteraction: () -> Unit = {}
    var selection = initial
        set(value) {
            val hueChanged = field.hue != value.hue || field.baseValue != value.baseValue
            field = value
            if (hueChanged && kind == RingKind.WHITENESS) updateGradient()
            updateDescription()
            invalidate()
        }
    private val maximum get() = if (kind == RingKind.HUE) 360f else 100f
    private val value get() = if (kind == RingKind.HUE) selection.hue else selection.whiteness * 100f
    private val radius get() = min(width, height) / 2f - 12 * density

    init { isFocusable = true; isClickable = true; updateDescription() }

    private fun updateDescription() {
        contentDescription = context.getString(
            if (kind == RingKind.HUE) R.string.theme_hue else R.string.theme_whiteness,
            value.roundToInt(),
        )
    }

    private fun updateGradient() {
        val x = width / 2f
        val y = height / 2f
        gradient = if (kind == RingKind.HUE) {
            SweepGradient(x, y, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        } else {
            SweepGradient(x, y, intArrayOf(selection.copy(whiteness = 0f).argb, Color.WHITE, Color.WHITE), floatArrayOf(0f, 300f / 360f, 1f)).apply {
                setLocalMatrix(Matrix().apply { setRotate(120f, x, y) })
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updateGradient() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val y = height / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16 * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.shader = gradient
        if (kind == RingKind.HUE) canvas.drawCircle(x, y, radius, paint)
        else {
            paint.strokeCap = Paint.Cap.BUTT
            arcBounds.set(x - radius, y - radius, x + radius, y + radius)
            canvas.drawArc(arcBounds, 120f, 300f, false, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = selection.copy(whiteness = 0f).argb
            canvas.drawCircle(x - radius * 0.5f, y + radius * 0.8660254f, 8 * density, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x + radius * 0.5f, y + radius * 0.8660254f, 8 * density, paint)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        if (kind == RingKind.HUE) {
            paint.color = colors.high
            canvas.drawCircle(x, y, radius - 18 * density, paint)
            paint.color = selection.argb
            canvas.drawCircle(x, y, radius - 23 * density, paint)
        }
        val angle = Math.toRadians(if (kind == RingKind.HUE) selection.hue.toDouble() else 120.0 + selection.whiteness * 300.0)
        val px = x + radius * cos(angle).toFloat()
        val py = y + radius * sin(angle).toFloat()
        // A dark outline keeps both handles visible against pale colors and white.
        paint.color = colors.outline
        canvas.drawCircle(px, py, 11 * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(px, py, 9 * density, paint)
        paint.color = if (kind == RingKind.HUE) selection.copy(whiteness = 0f).argb else selection.argb
        canvas.drawCircle(px, py, 6 * density, paint)
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.color = colors.primary
            paint.strokeWidth = 2 * density
            canvas.drawCircle(x, y, min(width, height) / 2f - density, paint)
        }
    }

    private fun angle(x: Float, y: Float) = ((Math.toDegrees(atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())) + 360) % 360).toFloat()

    private fun pick(x: Float, y: Float) {
        val hue = angle(x, y)
        val next = if (kind == RingKind.HUE) selection.copy(hue = hue) else {
            val offset = (hue - 120f + 360f) % 360f
            val clamped = when {
                offset <= 300f -> offset
                offset < 330f -> 300f
                else -> 0f
            }
            selection.copy(whiteness = clamped / 300f)
        }
        selection = next
        onPreview(next)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled && !blockedBySystemColors) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(event.x - width / 2f, event.y - height / 2f)
                if (distance < radius - 13 * density || distance > radius + 12 * density) return false
                if (kind == RingKind.WHITENESS) {
                    val offset = (angle(event.x, event.y) - 120f + 360f) % 360f
                    // Include the rounded end caps so 0% and 100% are easy to touch.
                    if (offset > 315f && offset < 345f) return false
                }
                gestureStart = selection
                parent.requestDisallowInterceptTouchEvent(true)
                if (blockedBySystemColors) onBlockedInteraction()
                else { isPressed = true; pick(event.x, event.y) }
            }
            MotionEvent.ACTION_MOVE -> if (!blockedBySystemColors) pick(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (!blockedBySystemColors) { pick(event.x, event.y); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!blockedBySystemColors) { selection = gestureStart; onPreview(gestureStart) }
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (blockedBySystemColors) onBlockedInteraction()
        else if (isEnabled) onColorSelected(selection)
        return true
    }

    private fun setValue(newValue: Float) {
        selection = if (kind == RingKind.HUE) selection.copy(hue = (newValue % 360f + 360f) % 360f)
        else selection.copy(whiteness = newValue.coerceIn(0f, 100f) / 100f)
        onPreview(selection)
        onColorSelected(selection)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) -1 else 1
            setValue(value + direction * if (kind == RingKind.HUE) 15f else 5f)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION") // RangeInfo.obtain supports the app's Android 8 minimum.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, maximum, value)
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return super.performAccessibilityAction(action, arguments)
        val step = if (kind == RingKind.HUE) 15f else 5f
        val target = when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.coerceIn(0f, maximum) ?: return false
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> value + step
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> value - step
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (!target.isFinite()) return false
        setValue(target)
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
