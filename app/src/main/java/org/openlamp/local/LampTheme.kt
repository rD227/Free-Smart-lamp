package org.openlamp.local

import android.content.Context
import android.app.Activity
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

enum class ColorPack(val label: String, val seed: Int) {
    BLUE("蓝", 0xFF4285F4.toInt()),
    GREEN("绿", 0xFF34A853.toInt()),
    LAVENDER("紫", 0xFF8B72CE.toInt()),
    ROSE("玫瑰", 0xFFC76C8C.toInt()),
    AMBER("琥珀", 0xFFD89532.toInt()),
    CUSTOM("自选", 0xFF4285F4.toInt()),
}

/** Flip this boolean to choose system Material colors or the user's palette. */
data class ThemeSettings(
    val useSystemM3: Boolean = true,
    val colorPack: ColorPack = ColorPack.BLUE,
    val customColor: Int = ColorPack.BLUE.seed,
) {
    val seed: Int get() = if (colorPack == ColorPack.CUSTOM) customColor else colorPack.seed

    fun save(prefs: SharedPreferences) {
        prefs.edit().putBoolean("use_system_m3", useSystemM3).putString("color_pack", colorPack.name)
            .putInt("custom_color", customColor).apply()
    }

    fun applyTo(activity: Activity) {
        // Apply before Activity.onCreate so inflated dialogs share the same complete theme.
        when {
            useSystemM3 -> DynamicColors.applyToActivityIfAvailable(activity)
            Build.VERSION.SDK_INT >= 31 -> DynamicColors.applyToActivityIfAvailable(activity, DynamicColorsOptions.Builder().setContentBasedSource(seed).build())
        }
    }

    companion object {
        fun load(prefs: SharedPreferences) = ThemeSettings(
            useSystemM3 = prefs.getBoolean("use_system_m3", true),
            colorPack = ColorPack.entries.firstOrNull { it.name == prefs.getString("color_pack", null) } ?: ColorPack.BLUE,
            customColor = prefs.getInt("custom_color", ColorPack.BLUE.seed),
        )
    }
}

/** Material color roles also provide an accessible custom-color fallback before Android 12. */
class LampColors(private val context: Context, settings: ThemeSettings) {
    private fun color(attr: Int) = MaterialColors.getColor(context, attr, javaClass.simpleName)
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val roles = MaterialColors.getColorRoles(settings.seed, !dark)
    private val fromTheme = settings.useSystemM3 || DynamicColors.isDynamicColorAvailable()
    val primary = if (fromTheme) color(androidx.appcompat.R.attr.colorPrimary) else roles.accent
    val onPrimary = if (fromTheme) color(MaterialR.attr.colorOnPrimary) else roles.onAccent
    val primaryContainer = if (fromTheme) color(MaterialR.attr.colorPrimaryContainer) else roles.accentContainer
    val onPrimaryContainer = if (fromTheme) color(MaterialR.attr.colorOnPrimaryContainer) else roles.onAccentContainer
    val surface = color(MaterialR.attr.colorSurface)
    val card = if (fromTheme) color(MaterialR.attr.colorSurfaceContainerLow) else ColorUtils.blendARGB(surface, roles.accent, 0.03f)
    val high = if (fromTheme) color(MaterialR.attr.colorSurfaceContainerHigh) else ColorUtils.blendARGB(surface, roles.accent, 0.08f)
    val onSurface = color(MaterialR.attr.colorOnSurface)
    val secondaryText = color(MaterialR.attr.colorOnSurfaceVariant)
    val outline = color(MaterialR.attr.colorOutlineVariant)
}
