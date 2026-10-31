package ai.godance.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238) with the parameters every authenticator app supports:
 * HMAC-SHA-1, 30-second steps, six digits.
 */
public final class Totp {

    private static final int STEP_SECONDS = 30;
    private static final int MODULUS = 1_000_000;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() { }

    /** The time step holding {@code instant}. */
    public static long stepAt(Instant instant) {
        return instant.getEpochSecond() / STEP_SECONDS;
    }

    /** The six-digit code for a time step, left-padded with zeros. */
    public static String code(byte[] secret, long step) {
        byte[] hash = hmacSha1(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        // Dynamic truncation, RFC 4226 section 5.3.
        int offset = hash[hash.length - 1] & 0x0f;
        int value = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & 0x7fffffff;
        return String.format("%06d", value % MODULUS);
    }

    /** RFC 4648 Base32 without padding: the form an authenticator app takes a secret in. */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(BASE32.charAt((buffer >> bits) & 0x1f));
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable; JVM is broken", e);
        }
    }
}
