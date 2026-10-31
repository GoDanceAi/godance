package ai.godance.config;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.key-vault.*}. {@code key} is 32 random bytes
 * in Base64 ({@code openssl rand -base64 32}). The boot fails without a
 * usable key rather than run unable to read or write a secret.
 */
@ConfigurationProperties(prefix = "godance.key-vault")
public record KeyVaultProperties(String key) {

    private static final int KEY_BYTES = 32;

    public KeyVaultProperties {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("godance.key-vault.key must be set");
        }
        if (keyBytes(key).length != KEY_BYTES) {
            throw new IllegalStateException(
                    "godance.key-vault.key must be " + KEY_BYTES + " bytes (AES-256) in Base64");
        }
    }

    public byte[] keyBytes() {
        return keyBytes(key);
    }

    private static byte[] keyBytes(String key) {
        try {
            return Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("godance.key-vault.key must be Base64", e);
        }
    }
}
