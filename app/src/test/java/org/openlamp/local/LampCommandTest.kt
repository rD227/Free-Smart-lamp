package org.openlamp.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LampCommandTest {
    @Test
    fun temperatureKeepsChannelsInRangeAtBothEndsAndDimmedMiddle() {
        assertEquals(LampCommand(0x21, arg1 = 255), LampCommand.temperature(0))
        assertEquals(LampCommand(0x21, arg2 = 255), LampCommand.temperature(100))
        assertEquals(LampCommand(0x21, arg1 = 64, arg2 = 64), LampCommand.temperature(50, 50))
        assertThrows(IllegalArgumentException::class.java) { LampCommand.temperature(101) }
        assertThrows(IllegalArgumentException::class.java) { LampCommand.temperature(50, 0) }
    }

    @Test
    fun timerUsesMinutesAcrossTheByteBoundaryAndZeroCancels() {
        assertEquals(LampCommand(0x41), LampCommand.timer(0))
        assertEquals(LampCommand(0x41, arg0 = 1), LampCommand.timer(1))
        assertEquals(LampCommand(0x41, arg1 = 1), LampCommand.timer(256))
        assertEquals(LampCommand(0x41, arg0 = 255, arg1 = 255), LampCommand.timer(65535))
        assertThrows(IllegalArgumentException::class.java) { LampCommand.timer(-1) }
        assertThrows(IllegalArgumentException::class.java) { LampCommand.timer(65536) }
    }
}
