package org.openlamp.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LampCodecTest {
    @Test
    fun packetsMatchIndependentUpstreamVectors() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/upstream-vectors.txt"))
        val lines = stream.bufferedReader().use { it.readLines() }
        assertEquals(324, lines.size)
        lines.forEachIndexed { index, line ->
            val fields = line.split(' ')
            val expected = fields[5].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val actual = LampCodec.encode(
                fields[0].toInt(), fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), fields[4].toInt(),
            )
            assertArrayEquals("Upstream vector $index: $line", expected, actual)
        }
    }

    @Test
    fun packetsMatchGreenVendorAppIncludingSequenceWrap() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/jingyuan-v141-vectors.txt"))
        val lines = stream.bufferedReader().use { it.readLines() }
        assertEquals(276, lines.size)
        lines.forEachIndexed { index, line ->
            val fields = line.split(' ')
            val expected = fields[5].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val actual = LampCodec.encode(
                fields[0].toInt(), fields[1].toInt(), fields[2].toInt(), fields[3].toInt(), fields[4].toInt(),
            )
            assertArrayEquals("Green vendor app vector $index: $line", expected, actual)
        }
    }

    @Test
    fun temperatureAndTimerMatchGreenVendorApp() {
        val stream = checkNotNull(javaClass.getResourceAsStream("/jingyuan-controls-v141-vectors.txt"))
        val lines = stream.bufferedReader().use { it.readLines() }
        assertEquals(76, lines.size)
        lines.forEachIndexed { index, line ->
            val fields = line.split(' ')
            val expected = fields[9].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val command = LampCommand(fields[1].toInt(), fields[5].toInt(), fields[6].toInt(), fields[7].toInt(), fields[8].toInt())
            assertArrayEquals(
                "Vendor controls vector $index: $line", expected,
                LampCodec.encode(fields[0].toInt(), command, fields[2].toInt(), fields[3].toInt(), fields[4].toInt()),
            )
        }
    }

    @Test
    fun invalidInputsAreRejected() {
        listOf(
            intArrayOf(0, 0x10, 1, 1, 1), intArrayOf(5, 0x10, 1, 1, 1),
            intArrayOf(1, 0xFF, 1, 1, 1), intArrayOf(1, 0x10, 0x100, 1, 1),
            intArrayOf(1, 0x10, 0x1000000, 1, 1), intArrayOf(1, 0x10, 1, -1, 1),
            intArrayOf(1, 0x10, 1, 256, 1), intArrayOf(1, 0x10, 1, 1, 0),
            intArrayOf(1, 0x10, 1, 1, 0xFFF6),
        ).forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) {
                LampCodec.encode(fields[0], fields[1], fields[2], fields[3], fields[4])
            }
        }
    }
}
