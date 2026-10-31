package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import ai.godance.config.KeyVaultProperties;

class KeyVaultTest {

    private static final byte[] SECRET = "a-totp-secret-20-byte".getBytes(StandardCharsets.UTF_8);

    private static KeyVault vault(String keyText) {
        byte[] key = keyText.getBytes(StandardCharsets.UTF_8);
        return new KeyVault(new KeyVaultProperties(Base64.getEncoder().encodeToString(key)));
    }

    private final KeyVault vault = vault("01234567890123456789012345678901");

    @Test
    void decrypt_givenWhatEncryptWrote_expectsTheSecret() {
        String stored = vault.encrypt(SECRET, "user-1");

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain(new String(SECRET, StandardCharsets.UTF_8));
        assertThat(vault.decrypt(stored, "user-1")).isEqualTo(SECRET);
    }

    @Test
    void encrypt_givenTheSameSecretTwice_expectsDifferentCiphertext() {
        assertThat(vault.encrypt(SECRET, "user-1")).isNotEqualTo(vault.encrypt(SECRET, "user-1"));
    }

    @Test
    void decrypt_givenAnotherContext_expectsFailure() {
        // A row's ciphertext copied onto another account must not work there.
        String stored = vault.encrypt(SECRET, "user-1");

        assertThatThrownBy(() -> vault.decrypt(stored, "user-2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_givenAnotherKey_expectsFailure() {
        String stored = vault.encrypt(SECRET, "user-1");
        KeyVault other = vault("abcdefghijklmnopqrstuvwxyz012345");

        assertThatThrownBy(() -> other.decrypt(stored, "user-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_givenChangedCiphertext_expectsFailure() {
        String stored = vault.encrypt(SECRET, "user-1");
        byte[] raw = Base64.getDecoder().decode(stored.substring("v1:".length()));
        raw[raw.length - 1] ^= 1;
        String changed = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> vault.decrypt(changed, "user-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_givenPlaintextOrTruncatedValue_expectsFailure() {
        assertThatThrownBy(() -> vault.decrypt("JBSWY3DPEHPK3PXP", "user-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.decrypt("v1:AAAA", "user-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void properties_givenMissingShortOrNonBase64Key_expectsIllegalState() {
        assertThatThrownBy(() -> new KeyVaultProperties(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyVaultProperties(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyVaultProperties(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new KeyVaultProperties("not base64!!")).isInstanceOf(IllegalStateException.class);
    }
}
