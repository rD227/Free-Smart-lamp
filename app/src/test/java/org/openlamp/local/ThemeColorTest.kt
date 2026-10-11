package org.openlamp.local

import org.junit.Assert.*
import org.junit.Test

class ThemeColorTest {
    @Test fun whiteMixProducesPinkAndKeepsTheHueAtTheWhiteEnd() {
        val red = ThemeColor(0f, 0f)
        assertEquals(0xFFFF0000.toInt(), red.argb)
        assertEquals(0xFFFF8080.toInt(), red.copy(whiteness = 0.5f).argb)
        val blue = ThemeColor(240f, 0f, 0.8f)
        val white = blue.copy(whiteness = 1f)
        assertEquals(0xFFFFFFFF.toInt(), white.argb)
        assertEquals(blue, ThemeColor.fromArgb(white.argb, white.hue, white.baseValue).copy(whiteness = 0f))
    }

    @Test fun loadingExistingColorsPreservesTheirExactRgbValues() {
        // Preset colors and old custom colors must not change when the picker is opened.
        listOf(0xFF4285F4, 0xFF34A853, 0xFF8B72CE, 0xFFC76C8C, 0xFFD89532,
            0xFF000000, 0xFF808080, 0xFFFFFFFF, 0xFF123456).forEach {
            assertEquals(it.toInt(), ThemeColor.fromArgb(it.toInt()).argb)
        }
    }
}
