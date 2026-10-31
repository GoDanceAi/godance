package ai.godance.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import ai.godance.config.KeyVaultProperties;

/**
 * Encrypts secrets the application must read back, for storing in the
 * database (backend CLAUDE.md, hard constraint 2). AES-256-GCM with a
 * fresh 96-bit IV each time, keyed by {@code KEY_VAULT_KEY}, which lives
 * in the environment and never in the database.
 *
 * <p>{@code context} names what the secret belongs to, for example a user
 * id. It is authenticated but not stored, so a ciphertext copied onto
 * another row fails to decrypt. A stored value is {@code v1:<Base64 of IV
 * then ciphertext and tag>}; the prefix leaves room to rotate the key.
 */
@Service
public class KeyVault {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKey key;

    public KeyVault(KeyVaultProperties props) {
        this.key = new SecretKeySpec(props.keyBytes(), "AES");
    }

    public String encrypt(byte[] plaintext, String context) {
        byte[] iv = new byte[IV_BYTES];
        RNG.nextBytes(iv);
        try {
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE, iv, context).doFinal(plaintext);
            byte[] stored = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt secret", e);
        }
    }

    /** Fails if the value was changed, or was encrypted with another key or context. */
    public byte[] decrypt(String stored, String context) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("not a key vault value");
        }
        byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        try {
            ByteBuffer in = ByteBuffer.wrap(raw);
            byte[] iv = new byte[IV_BYTES];
            in.get(iv);
            byte[] sealed = new byte[in.remaining()];
            in.get(sealed);
            return cipher(Cipher.DECRYPT_MODE, iv, context).doFinal(sealed);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("could not decrypt secret", e);
        }
    }

    private Cipher cipher(int mode, byte[] iv, String context) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
