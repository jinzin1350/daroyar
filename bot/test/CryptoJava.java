// Java port of app/src/main/java/com/daroyar/app/Crypto.kt (same JCA calls),
// used to prove the bot and the Android app understand each other's payloads.
//   java CryptoJava.java topic <code>
//   java CryptoJava.java enc <code> <base64 text>  -> base64 payload
//   java CryptoJava.java dec <code> <base64 payload> -> base64 text
// (text travels as base64 so the shell locale cannot mangle Persian)
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoJava {
    static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    static String normalize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase().toCharArray()) if (ALPHA.indexOf(c) >= 0) b.append(c);
        return b.toString();
    }

    static byte[] sha(String label, String code) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(label.getBytes(StandardCharsets.UTF_8));
        md.update(normalize(code).getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    public static void main(String[] a) throws Exception {
        String code = a[1];
        SecretKeySpec key = new SecretKeySpec(sha("daroyar/key/v1/", code), "AES");
        switch (a[0]) {
            case "topic": {
                byte[] h = sha("daroyar/topic/v1/", code);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 12; i++) sb.append(Integer.toHexString((h[i] & 0xff) + 0x100).substring(1));
                System.out.print(sb);
                break;
            }
            case "enc": {
                byte[] iv = new byte[12];
                new SecureRandom().nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] ct = c.doFinal(Base64.getDecoder().decode(a[2]));
                byte[] out = new byte[1 + 12 + ct.length];
                out[0] = 1;
                System.arraycopy(iv, 0, out, 1, 12);
                System.arraycopy(ct, 0, out, 13, ct.length);
                System.out.print(Base64.getEncoder().encodeToString(out));
                break;
            }
            case "dec": {
                byte[] d = Base64.getDecoder().decode(a[2]);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, java.util.Arrays.copyOfRange(d, 1, 13)));
                System.out.print(Base64.getEncoder().encodeToString(c.doFinal(d, 13, d.length - 13)));
                break;
            }
        }
    }
}
