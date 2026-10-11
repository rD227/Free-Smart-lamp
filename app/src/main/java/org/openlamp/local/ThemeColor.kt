package org.openlamp.local

import kotlin.math.roundToInt

/** A hue mixed with white. The underlying hue survives a completely white selection. */
data class ThemeColor(val hue: Float, val whiteness: Float, val baseValue: Float = 1f) {
    init {
        require(hue.isFinite() && hue in 0f..360f)
        require(whiteness.isFinite() && whiteness in 0f..1f)
        require(baseValue.isFinite() && baseValue in 0f..1f)
    }

    val argb: Int get() {
        val sector = (hue % 360f) / 60f
        val secondary = 1f - kotlin.math.abs(sector % 2f - 1f)
        val channels = when (sector.toInt()) {
            0 -> floatArrayOf(1f, secondary, 0f)
            1 -> floatArrayOf(secondary, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, secondary)
            3 -> floatArrayOf(0f, secondary, 1f)
            4 -> floatArrayOf(secondary, 0f, 1f)
            else -> floatArrayOf(1f, 0f, secondary)
        }.map { ((whiteness + (1f - whiteness) * baseValue * it) * 255).roundToInt().coerceIn(0, 255) }
        return 0xFF000000.toInt() or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    companion object {
        fun fromArgb(color: Int, fallbackHue: Float = 0f, fallbackValue: Float = 1f): ThemeColor {
            val r = (color ushr 16) and 255
            val g = (color ushr 8) and 255
            val b = color and 255
            val maximum = maxOf(r, g, b)
            val minimum = minOf(r, g, b)
            val delta = maximum - minimum
            val hue = if (delta == 0) fallbackHue else {
                val sector = when (maximum) {
                    r -> (g - b).toFloat() / delta
                    g -> (b - r).toFloat() / delta + 2f
                    else -> (r - g).toFloat() / delta + 4f
                }
                (sector * 60f + 360f) % 360f
            }
            return ThemeColor(
                hue = hue.coerceIn(0f, 360f),
                whiteness = minimum / 255f,
                baseValue = if (minimum == 255) fallbackValue.coerceIn(0f, 1f) else delta.toFloat() / (255 - minimum),
            )
        }
    }
}
