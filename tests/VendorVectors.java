import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;

/** Diagnostic only: call the installed codec without transmitting Bluetooth data. */
public final class VendorVectors {
    public static void main(String[] args) throws Exception {
        DexClassLoader loader = new DexClassLoader(args[0], args[1], args[1], VendorVectors.class.getClassLoader());
        Class<?> codec = loader.loadClass("com.alllink.encodelib.ToolV3");
        Method pair = codec.getMethod("blevbBinding", int.class, long.class, int.class, boolean.class);
        Method power = codec.getMethod("blevbLampOnOff", int.class, long.class, int.class, boolean.class);
        int sequence = 0x10; // Initial tx_count in libencodeV3.so 1.4.1.
        for (int id : new int[]{0, 1, 0x123056, 0xFEF0FE}) {
            for (int command : new int[]{0x28, 0x10, 0x11}) {
                for (int repeat = 0; repeat < 23; repeat++) {
                    byte[] raw = (byte[])(command == 0x28 ? pair : power).invoke(null, 0x0100, (long)id, 0, command != 0x11);
                    int seed = (raw[27] & 255) | ((raw[28] & 255) << 8);
                    StringBuilder hex = new StringBuilder();
                    for (int i = 5; i < raw.length; i++) hex.append(String.format("%02x", raw[i] & 255));
                    System.out.println("4 " + command + " " + id + " " + (sequence++ & 255) + " " + seed + " " + hex);
                }
            }
        }
    }
}
