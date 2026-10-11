package org.openlamp.local

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.color.DynamicColors

/** Wires the replaceable screen to the Bluetooth controller and Android lifecycle. */
class MainActivity : AppCompatActivity() {
    private lateinit var controller: LampController
    private lateinit var screen: LampScreen
    private lateinit var appearance: ThemeSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        appearance = ThemeSettings.load(getSharedPreferences("appearance", MODE_PRIVATE))
        // Keep the Activity's full base theme stable; custom palettes belong to the screen.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller = LampController(applicationContext, getSharedPreferences("controller", MODE_PRIVATE))
        renderScreen()
        screen.restoreScroll(savedInstanceState?.getInt("scroll_position") ?: 0)
        controller.onStateChanged = { screen.update(it) }
        requestBluetoothPermissions()
    }

    private fun renderScreen() {
        screen = LampScreen(this, controller.state, appearance, LampScreenCallbacks(
            onPower = { on -> send(if (on) LampCommand.ON else LampCommand.OFF, getString(if (on) R.string.turn_on else R.string.turn_off)) },
            onPair = { send(LampCommand.PAIR, getString(R.string.pair_action)) },
            onLightingEdited = { warmth, brightness, gestureEnded ->
                controller.editLighting(warmth, brightness, gestureEnded)
                requestBluetoothPermissions()
            },
            onApplyLighting = { warmth, brightness ->
                controller.applyLighting(warmth, brightness)
                requestBluetoothPermissions()
            },
            onLightingApplyModeSelected = controller::selectLightingApplyMode,
            onTimerSelected = controller::selectTimer,
            onTimerCancelled = { send(LampCommand.timer(0), getString(R.string.cancel_timer)) },
            onTimer = { minutes ->
                send(LampCommand.timer(minutes), if (minutes == 0) getString(R.string.cancel_timer) else getString(R.string.timer_action, minutes))
            },
            onModeSelected = controller::selectMode,
            onThemeChanged = { next ->
                if (next != appearance) {
                    appearance = next
                    next.save(getSharedPreferences("appearance", MODE_PRIVATE))
                    screen = screen.withAppearance(next)
                    updateWindowColors()
                }
            },
        ))
        setContentView(screen.root)
        updateWindowColors()
    }

    @Suppress("DEPRECATION")
    private fun updateWindowColors() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = screen.colors.surface
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, screen.root).apply {
            val light = ColorUtils.calculateLuminance(screen.colors.surface) > 0.5
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun send(command: LampCommand, action: String) {
        controller.send(command, action)
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31 && !controller.hasBluetoothPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) screen.startClock()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("scroll_position", screen.scrollPosition)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (::controller.isInitialized) controller.cancel()
        if (::screen.isInitialized) screen.stopClock()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.cancel()
        if (::screen.isInitialized) screen.dispose()
        super.onDestroy()
    }
}
