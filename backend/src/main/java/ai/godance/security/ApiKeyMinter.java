package ai.godance.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Mints plaintext API keys ({@code gd_live_<urlsafe-base64>}) and
 * computes the {@code SHA-256} hash that gets stored. The plaintext
 * is returned to the user exactly once at creation; afterwards only the
 * hash and the {@code key_prefix} (first 8 chars after {@code gd_live_})
 * are retained, per CLAUDE.md hard constraint #2.
 */
@Component
public class ApiKeyMinter {

    private static final String PREFIX = "gd_live_";
    private static final int RANDOM_BYTES = 32;   // 256 bits of entropy
    private static final int IDENTIFIER_CHARS = 8;

    private final SecureRandom rng = new SecureRandom();

    public Minted mint() {
        byte[] raw = new byte[RANDOM_BYTES];
        rng.nextBytes(raw);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String plaintext = PREFIX + body;
        String identifier = body.substring(0, IDENTIFIER_CHARS);
        return new Minted(plaintext, sha256Hex(plaintext), identifier);
    }

    /** Hash a presented key for lookup. Must mirror {@link #mint()} exactly. */
    public String hash(String plaintext) {
        return sha256Hex(plaintext);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable; JVM is broken", e);
        }
    }

    public record Minted(String plaintext, String hash, String prefix) { }
}
