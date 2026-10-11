package org.openlamp.local

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper

/** Layout Editor host: reuse the real cards with idle data and no controller. */
class LampControlsPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val screen = LampScreen(
        base = ContextThemeWrapper(context, R.style.AppTheme),
        initialState = LampState(status = context.getString(R.string.ready)),
        appearance = ThemeSettings(),
        callbacks = LampScreenCallbacks(
            onPower = {},
            onPair = {},
            onLightingEdited = { _, _, _ -> },
            onApplyLighting = { _, _ -> },
            onLightingApplyModeSelected = {},
            onTimerSelected = {},
            onTimerCancelled = {},
            onTimer = {},
            onModeSelected = {},
            onThemeChanged = {},
        ),
        controlsOnly = true,
    )

    init {
        addView(screen.root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onDetachedFromWindow() {
        screen.dispose()
        super.onDetachedFromWindow()
    }
}
