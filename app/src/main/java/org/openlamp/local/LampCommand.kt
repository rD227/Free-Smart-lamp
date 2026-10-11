package org.openlamp.local

import kotlin.math.roundToInt

/** Protocol arguments, independent of Android or the screen that creates them. */
data class LampCommand(
    val code: Int,
    val param: Int = 0,
    val arg0: Int = 0,
    val arg1: Int = 0,
    val arg2: Int = 0,
) {
    init {
        require(code in setOf(0x10, 0x11, 0x21, 0x28, 0x41)) { "Unsupported command" }
        require(listOf(param, arg0, arg1, arg2).all { it in 0..255 }) { "Invalid command argument" }
    }

    companion object {
        val ON = LampCommand(0x10)
        val OFF = LampCommand(0x11)
        val PAIR = LampCommand(0x28)

        /** Relative warmth: 0 = cold white, 100 = warm white; no guessed Kelvin range. */
        fun temperature(warmth: Int, brightness: Int = 100): LampCommand {
            require(warmth in 0..100 && brightness in 1..100)
            val level = 255.0 * brightness / 100
            return LampCommand(
                0x21,
                arg1 = (level * (100 - warmth) / 100).roundToInt(),
                arg2 = (level * warmth / 100).roundToInt(),
            )
        }

        /** Lamp-side countdown in minutes. Zero cancels the current countdown. */
        fun timer(minutes: Int): LampCommand {
            require(minutes in 0..65535) { "Invalid timer duration" }
            return LampCommand(0x41, arg0 = minutes and 255, arg1 = minutes ushr 8)
        }
    }
}
