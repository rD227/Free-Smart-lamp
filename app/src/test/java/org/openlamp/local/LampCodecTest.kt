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
