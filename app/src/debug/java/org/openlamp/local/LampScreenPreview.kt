package org.openlamp.local

import android.content.Context
import android.content.res.Configuration
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView

@Preview(name = "灯光与色温 · 浅色", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "灯光与色温 · 深色", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LampControlsPreview() {
    AndroidView(
        factory = { context -> PreviewControlsView(context) },
        modifier = Modifier.fillMaxSize(),
        onRelease = { view -> view.dispose() },
    )
}

/** Host the actual M3 cards; fixed idle data and empty callbacks never send lamp commands. */
private class PreviewControlsView(context: Context) : FrameLayout(context) {
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
        addView(screen.root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // Fit before measuring, including when Preview changes its device width.
            screen.fitContentWidth(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                - screen.root.paddingLeft - screen.root.paddingRight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun dispose() = screen.dispose()
}
