import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.openlamp.local.LampCodec;

/** Compare Java packet encoding with independent upstream-generated vectors. */
public final class CodecCheck {
    public static void main(String[] args) throws Exception {
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in));
        String line; int count=0;
        while ((line=input.readLine())!=null) {
            String[] fields=line.split(" ");
            byte[] packet=LampCodec.encode(Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),Integer.parseInt(fields[3]),Integer.parseInt(fields[4]));
            StringBuilder hex=new StringBuilder();
            for (byte value:packet) hex.append(String.format("%02x",value&255));
            if (packet.length!=26 || !hex.toString().equals(fields[5]))
                throw new AssertionError("Protocol mismatch at vector "+count+": "+hex);
            count++;
        }
        if (count<300) throw new AssertionError("Missing test vectors");
        try { LampCodec.encode(4,0x10,1,1,1); throw new AssertionError("Accepted invalid mode"); }
        catch (IllegalArgumentException expected) {}
        try { LampCodec.encode(1,0x10,0x100,1,1); throw new AssertionError("Accepted invalid ID"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("PASS: "+count+" packets match the upstream implementation; invalid input rejected");
    }
}
