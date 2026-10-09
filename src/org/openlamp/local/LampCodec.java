package org.openlamp.local;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** LampSmart Pro encoding, adapted from NicoIIT/ha-ble-adv (MIT).
 * No vendor APK code or native libraries are included. */
public final class LampCodec {
    private LampCodec() {}

    private static final int[] BOX = {
        0xB7,0xFD,0x93,0x26,0x36,0x3F,0xF7,0xCC,0x34,0xA5,0xE5,0xF1,0x71,0xD8,0x31,0x15,
        0x04,0xC7,0x23,0xC3,0x18,0x96,0x05,0x9A,0x07,0x12,0x80,0xE2,0xEB,0x27,0xB2,0x75,
        0xD0,0xEF,0xAA,0xFB,0x43,0x4D,0x33,0x85,0x45,0xF9,0x02,0x7F,0x50,0x3C,0x9F,0xA8,
        0x51,0xA3,0x40,0x8F,0x92,0x9D,0x38,0xF5,0xBC,0xB6,0xDA,0x21,0x10,0xFF,0xF3,0xD2,
        0xE0,0x32,0x3A,0x0A,0x49,0x06,0x24,0x5C,0xC2,0xD3,0xAC,0x62,0x91,0x95,0xE4,0x79,
        0xE7,0xC8,0x37,0x6D,0x8D,0xD5,0x4E,0xA9,0x6C,0x56,0xF4,0xEA,0x65,0x7A,0xAE,0x08,
        0xE1,0xF8,0x98,0x11,0x69,0xD9,0x8E,0x94,0x9B,0x1E,0x87,0xE9,0xCE,0x55,0x28,0xDF,
        0x8C,0xA1,0x89,0x0D,0xBF,0xE6,0x42,0x68,0x41,0x99,0x2D,0x0F,0xB0,0x54,0xBB,0x16
    };

    /** Returns the value of an AD type 0x03 (16-bit service UUID list).
     * mode 1 = v1; mode 2 = v3 signed; mode 3 = v2 unsigned. */
    public static byte[] encode(int mode, int command, int id, int sequence, int seed)
            throws GeneralSecurityException {
        if (mode < 1 || mode > 3 || (command != 0x10 && command != 0x11 && command != 0x28))
            throw new IllegalArgumentException("Unsupported mode or command");
        if ((id & 0xFF000F00) != 0 || sequence < 0 || sequence > 255 || seed < 1 || seed > 0xFFF5)
            throw new IllegalArgumentException("Invalid controller ID, sequence or seed");
        return mode == 1 ? encodeV1(command, id, sequence, seed)
                         : encodeV2(command, id, sequence, seed, mode == 2);
    }

    private static byte[] encodeV1(int cmd, int id, int seq, int seed) {
        boolean pair = cmd == 0x28;
        byte[] plain = new byte[14];
        plain[0] = (byte)cmd;
        plain[1] = (byte)id;
        plain[2] = (byte)((id >> 8) & 0xF0); // group index is zero
        plain[3] = pair ? (byte)id : 0;
        plain[4] = pair ? (byte)((id >> 8) & 0xF0) : 0;
        plain[5] = pair ? (byte)0x81 : 0;
        plain[6] = (byte)seq;
        plain[8] = (byte)(seed ^ (id >> 16));
        plain[9] = (byte)seed;
        putBE(plain, 10, seed);
        putBE(plain, 12, crc(plain, 0, 12, seed ^ 0xFFFF));
        byte[] body = new byte[16];
        System.arraycopy(plain, 0, body, 0, plain.length);
        putBE(body, 14, crc(plain, 0, plain.length, 0xA5BE));
        byte[] prefix = {(byte)0xAA,(byte)0x98,0x43,(byte)0xAF,0x0B,0x46,0x46,0x46};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x77); out.write(0xF8);
        for (byte b : whiten(reverse(prefix), 0x6F)) out.write(b & 255);
        for (byte b : whiten(reverse(body), 0x0C)) out.write(b & 255);
        return out.toByteArray();
    }

    private static byte[] encodeV2(int cmd, int id, int seq, int seed, boolean signed)
            throws GeneralSecurityException {
        byte[] plain = new byte[22];
        plain[0] = signed ? (byte)0x30 : (byte)0x10;
        plain[1] = (byte)0x80;
        plain[3] = (byte)seq;
        plain[5] = 1; // LampSmart device type 0x0100, little endian
        for (int i = 0; i < 4; i++) plain[6+i] = (byte)(id >> (8*i));
        plain[11] = (byte)cmd;
        putLE(plain, 20, seed);
        if (signed) {
            byte[] key = {(byte)seed,(byte)(seed>>8),(byte)seq,0x0D,(byte)0xBF,(byte)0xE6,
                0x42,0x68,0x41,(byte)0x99,0x2D,0x0F,(byte)0xB0,0x54,(byte)0xBB,0x16};
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] input = new byte[16];
            System.arraycopy(plain, 1, input, 0, 16);
            byte[] sign = cipher.doFinal(input);
            int value = (sign[0]&255) | ((sign[1]&255)<<8);
            putLE(plain, 17, value == 0 ? 0xFFFF : value);
        }
        byte[] encrypted = new byte[24];
        System.arraycopy(plain, 0, encrypted, 0, plain.length);
        int seed8 = seed & 255;
        for (int i = 2; i < 20; i++)
            encrypted[i] = (byte)(plain[i] ^ seed8 ^ BOX[(seed8+i-2+9)&0x1F]);
        putLE(encrypted, 22, crc(encrypted, 0, 22, seed ^ 0xFFFF));
        byte[] out = new byte[26];
        out[0] = (byte)0xF0; out[1] = 0x08;
        System.arraycopy(encrypted, 0, out, 2, 24);
        return out;
    }

    private static int crc(byte[] buf, int start, int size, int seed) {
        int result = seed & 0xFFFF;
        for (int i = start; i < start+size; i++) {
            result ^= (buf[i]&255)<<8;
            for (int j=0; j<8; j++) result = ((result<<1) ^ ((result&0x8000)!=0 ? 0x1021 : 0)) & 0xFFFF;
        }
        return result;
    }
    private static byte[] reverse(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i=0; i<data.length; i++) out[i] = (byte)(Integer.reverse(data[i]&255) >>> 24);
        return out;
    }
    private static byte[] whiten(byte[] data, int seed) {
        byte[] out = new byte[data.length]; int r=seed;
        for (int i=0; i<data.length; i++) {
            int b=0;
            for (int j=0; j<8; j++) {
                r<<=1;
                if ((r&0x80)!=0) { r^=0x11; b|=1<<j; }
                r&=0x7F;
            }
            out[i]=(byte)(data[i]^b);
        }
        return out;
    }
    private static void putBE(byte[] data, int offset, int value) {
        data[offset]=(byte)(value>>8); data[offset+1]=(byte)value;
    }
    private static void putLE(byte[] data, int offset, int value) {
        data[offset]=(byte)value; data[offset+1]=(byte)(value>>8);
    }
}
