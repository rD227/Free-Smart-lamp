package org.openlamp.local

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** LampSmart Pro encoding adapted from NicoIIT/ha-ble-adv (MIT); see NOTICE.md. */
object LampCodec {
    private val box = intArrayOf(
        0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
        0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
        0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
        0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
        0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
        0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
        0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
        0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
    )

    /** AD type 0x03 payload. Modes: 1 = V1, 2 = signed V3, 3 = unsigned V2. */
    fun encode(mode: Int, command: Int, id: Int, sequence: Int, seed: Int): ByteArray {
        require(mode in 1..3 && command in setOf(0x10, 0x11, 0x28)) { "Unsupported mode or command" }
        require(id and 0xFF000F00.toInt() == 0 && sequence in 0..255 && seed in 1..0xFFF5) {
            "Invalid controller ID, sequence or seed"
        }
        return if (mode == 1) encodeV1(command, id, sequence, seed)
        else encodeV2(command, id, sequence, seed, signed = mode == 2)
    }

    private fun encodeV1(command: Int, id: Int, sequence: Int, seed: Int): ByteArray {
        val pairing = command == 0x28
        val plain = ByteArray(14)
        plain[0] = command.toByte()
        plain[1] = id.toByte()
        plain[2] = ((id shr 8) and 0xF0).toByte() // Group index is zero.
        if (pairing) {
            plain[3] = id.toByte()
            plain[4] = ((id shr 8) and 0xF0).toByte()
            plain[5] = 0x81.toByte()
        }
        plain[6] = sequence.toByte()
        plain[8] = (seed xor (id shr 16)).toByte()
        plain[9] = seed.toByte()
        putBE(plain, 10, seed)
        putBE(plain, 12, crc(plain, 12, seed xor 0xFFFF))
        val body = plain.copyOf(16)
        putBE(body, 14, crc(plain, plain.size, 0xA5BE))
        val prefix = byteArrayOf(0xAA.toByte(), 0x98.toByte(), 0x43, 0xAF.toByte(), 0x0B, 0x46, 0x46, 0x46)
        return ByteArrayOutputStream().apply {
            write(0x77)
            write(0xF8)
            write(whiten(reverse(prefix), 0x6F))
            write(whiten(reverse(body), 0x0C))
        }.toByteArray()
    }

    // This single-block AES operation is required by the lamp's existing wire protocol.
    @Suppress("GetInstance")
    private fun encodeV2(command: Int, id: Int, sequence: Int, seed: Int, signed: Boolean): ByteArray {
        val plain = ByteArray(22)
        plain[0] = if (signed) 0x30 else 0x10
        plain[1] = 0x80.toByte()
        plain[3] = sequence.toByte()
        plain[5] = 1 // LampSmart device type 0x0100, little endian.
        for (i in 0..3) plain[6 + i] = (id shr (8 * i)).toByte()
        plain[11] = command.toByte()
        putLE(plain, 20, seed)
        if (signed) {
            val key = byteArrayOf(
                seed.toByte(), (seed shr 8).toByte(), sequence.toByte(), 0x0D, 0xBF.toByte(), 0xE6.toByte(),
                0x42, 0x68, 0x41, 0x99.toByte(), 0x2D, 0x0F, 0xB0.toByte(), 0x54, 0xBB.toByte(), 0x16,
            )
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val signature = cipher.doFinal(plain.copyOfRange(1, 17))
            val value = (signature[0].toInt() and 255) or ((signature[1].toInt() and 255) shl 8)
            putLE(plain, 17, if (value == 0) 0xFFFF else value)
        }
        val encrypted = plain.copyOf(24)
        val seed8 = seed and 255
        for (i in 2 until 20) {
            encrypted[i] = (plain[i].toInt() xor seed8 xor box[(seed8 + i - 2 + 9) and 0x1F]).toByte()
        }
        putLE(encrypted, 22, crc(encrypted, 22, seed xor 0xFFFF))
        return byteArrayOf(0xF0.toByte(), 0x08) + encrypted
    }

    private fun crc(buffer: ByteArray, size: Int, seed: Int): Int {
        var result = seed and 0xFFFF
        for (i in 0 until size) {
            result = result xor ((buffer[i].toInt() and 255) shl 8)
            repeat(8) {
                result = ((result shl 1) xor (if (result and 0x8000 != 0) 0x1021 else 0)) and 0xFFFF
            }
        }
        return result
    }

    private fun reverse(data: ByteArray) = ByteArray(data.size) {
        (Integer.reverse(data[it].toInt() and 255) ushr 24).toByte()
    }

    private fun whiten(data: ByteArray, seed: Int): ByteArray {
        var register = seed
        return ByteArray(data.size) { i ->
            var mask = 0
            for (bit in 0..7) {
                register = register shl 1
                if (register and 0x80 != 0) {
                    register = register xor 0x11
                    mask = mask or (1 shl bit)
                }
                register = register and 0x7F
            }
            (data[i].toInt() xor mask).toByte()
        }
    }

    private fun putBE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun putLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
    }
}
