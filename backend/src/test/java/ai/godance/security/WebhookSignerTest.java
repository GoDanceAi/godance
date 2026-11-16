package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import ai.godance.config.KeyVaultProperties;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;

class WebhookSignerTest {

    private static final KeyVault VAULT = new KeyVault(new KeyVaultProperties(Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8))));

    private final WebhookSigner signer = new WebhookSigner(null, VAULT);  // secret-format only; no DB needed

    @Test
    void headerValue_isStripeShape_t_v1() {
        String header = signer.headerValue("sample-secret", "{\"event\":\"x\"}");
        assertThat(header)
                .startsWith("t=")
                .contains(",v1=")
                .matches("^t=\\d+,v1=[0-9a-f]{64}$");
    }

    @Test
    void headerValue_signsTimestampDotPayload_verifiableByClient() throws Exception {
        String secret = "client-knows-this-secret";
        String body = "{\"jobId\":\"abc\"}";
        String header = signer.headerValue(secret, body);

        // Parse the header and recompute on the client side.
        String[] parts = header.split(",");
        long t = Long.parseLong(parts[0].substring(2));
        String v1 = parts[1].substring(3);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(expected.length * 2);
        for (byte b : expected) hex.append(String.format("%02x", b));

        assertThat(v1).isEqualTo(hex.toString());
    }

    @Test
    void headerValue_differentBody_producesDifferentSignature() {
        String s = "secret";
        String h1 = signer.headerValue(s, "payload-A");
        String h2 = signer.headerValue(s, "payload-B");
        // Compare the v1 portion only — timestamps may differ by a second in slow CI.
        assertThat(h1.split(",v1=")[1]).isNotEqualTo(h2.split(",v1=")[1]);
    }

    // ===== Dual-signature grace window (Session 10) ==================

    @Test
    void headerValueFromMaterial_currentOnly_emitsSingleV1() {
        WebhookSigner.SigningMaterial m = new WebhookSigner.SigningMaterial("current-only", null);
        String header = signer.headerValueFromMaterial(m, "{}");
        assertThat(countV1(header)).isEqualTo(1);
        assertThat(header).matches("^t=\\d+,v1=[0-9a-f]{64}$");
    }

    @Test
    void headerValueFromMaterial_withPrevious_emitsTwoV1Entries() {
        WebhookSigner.SigningMaterial m = new WebhookSigner.SigningMaterial("new-current", "old-previous");
        String body = "{}";
        String header = signer.headerValueFromMaterial(m, body);
        assertThat(countV1(header)).isEqualTo(2);
        assertThat(header).matches("^t=\\d+,v1=[0-9a-f]{64},v1=[0-9a-f]{64}$");

        // Both signatures must independently verify under their own secrets.
        String[] parts = header.split(",");
        long t = Long.parseLong(parts[0].substring(2));
        String v1Current  = parts[1].substring(3);
        String v1Previous = parts[2].substring(3);
        assertThat(v1Current).isEqualTo(hmac("new-current", t + "." + body));
        assertThat(v1Previous).isEqualTo(hmac("old-previous", t + "." + body));
    }

    @Test
    void signingMaterial_hasPrevious_isTrueOnlyWhenPreviousIsNonNull() {
        assertThat(new WebhookSigner.SigningMaterial("c", null).hasPrevious()).isFalse();
        assertThat(new WebhookSigner.SigningMaterial("c", "p").hasPrevious()).isTrue();
    }

    @Test
    void signingMaterial_toString_givenSecrets_expectsNeitherShown() {
        String shown = new WebhookSigner.SigningMaterial("whsec_current_value", "whsec_previous_value").toString();

        assertThat(shown).doesNotContain("whsec_current_value").doesNotContain("whsec_previous_value");
        assertThat(new WebhookSigner.SigningMaterial("whsec_current_value", null).toString())
                .doesNotContain("whsec_current_value");
    }

    @Test
    void graceWindow_is24Hours() {
        // Pinned so external docs / runbooks can reference the constant.
        assertThat(WebhookSigner.GRACE_WINDOW.toHours()).isEqualTo(24);
    }

    // ===== Kept through the key vault =================================

    @Test
    void secretFor_givenNoSecretYet_expectsWhsecReturnedAndOnlyItsCiphertextSaved() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user(null, null, null);
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        String secret = withDb.secretFor(u.getId());

        assertThat(secret).startsWith("whsec_").hasSize(49);
        String stored = u.getWebhookSigningSecretEncrypted();
        assertThat(stored).startsWith("v1:").doesNotContain(secret).doesNotContain(secret.substring(6));
        assertThat(open(stored, "webhook:" + u.getId() + ":current")).isEqualTo(secret);
        verify(users).save(u);

        // Read again: the same secret, decrypted, and nothing written.
        assertThat(withDb.secretFor(u.getId())).isEqualTo(secret);
        assertThat(u.getWebhookSigningSecretEncrypted()).isEqualTo(stored);
        verify(users, times(1)).save(any());
    }

    @Test
    void rotateSecret_givenSecret_expectsNewSecretAndOldOneKeptForTheGraceWindowUnderItsOwnContext() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user("whsec_before_rotation", null, null);
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        String fresh = withDb.rotateSecret(u.getId());

        assertThat(fresh).startsWith("whsec_").isNotEqualTo("whsec_before_rotation");
        String current = u.getWebhookSigningSecretEncrypted();
        String previous = u.getWebhookSigningSecretPreviousEncrypted();
        assertThat(current).startsWith("v1:").doesNotContain(fresh);
        assertThat(previous).startsWith("v1:").doesNotContain("whsec_before_rotation");
        assertThat(open(current, "webhook:" + u.getId() + ":current")).isEqualTo(fresh);
        assertThat(open(previous, "webhook:" + u.getId() + ":previous")).isEqualTo("whsec_before_rotation");
        // Bound to its slot: the old secret can't be put back as the current one.
        assertThatThrownBy(() -> VAULT.decrypt(previous, "webhook:" + u.getId() + ":current"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(u.getWebhookSigningSecretRotatedAt()).isBetween(Instant.now().minusSeconds(5), Instant.now());

        WebhookSigner.SigningMaterial material = withDb.materialFor(u.getId());
        assertThat(material.current()).isEqualTo(fresh);
        assertThat(material.previous()).isEqualTo("whsec_before_rotation");
        String header = withDb.headerValueFromMaterial(material, "{}");
        long t = Long.parseLong(header.split(",")[0].substring(2));
        assertThat(header).isEqualTo("t=" + t + ",v1=" + hmac(fresh, t + ".{}")
                + ",v1=" + hmac("whsec_before_rotation", t + ".{}"));
    }

    @Test
    void rotateSecret_givenNoSecretYet_expectsNewSecretAndNoPrevious() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user(null, null, null);
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        String fresh = withDb.rotateSecret(u.getId());

        assertThat(u.getWebhookSigningSecretPreviousEncrypted()).isNull();
        assertThat(withDb.materialFor(u.getId())).satisfies(m -> {
            assertThat(m.current()).isEqualTo(fresh);
            assertThat(m.hasPrevious()).isFalse();
        });
    }

    @Test
    void materialFor_givenPreviousPastTheGraceWindow_expectsCurrentOnly() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user("whsec_now", "whsec_retired", Instant.now().minus(WebhookSigner.GRACE_WINDOW).minusSeconds(1));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        WebhookSigner.SigningMaterial material = withDb.materialFor(u.getId());

        assertThat(material.current()).isEqualTo("whsec_now");
        assertThat(material.hasPrevious()).isFalse();
        verify(users, never()).save(any());
    }

    @Test
    void materialFor_givenPreviousWithinTheGraceWindow_expectsBoth() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user("whsec_now", "whsec_before", Instant.now().minus(Duration.ofHours(23)));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        WebhookSigner.SigningMaterial material = withDb.materialFor(u.getId());

        assertThat(material.current()).isEqualTo("whsec_now");
        assertThat(material.previous()).isEqualTo("whsec_before");
    }

    @Test
    void materialFor_givenCiphertextCopiedFromAnotherAccount_expectsRefusalWithoutTheSecret() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User other = user("whsec_of_the_other_account", null, null);
        User u = user(null, null, null);
        u.setWebhookSigningSecretEncrypted(other.getWebhookSigningSecretEncrypted());
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> withDb.materialFor(u.getId()))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("whsec_of_the_other_account"));
        assertThatThrownBy(() -> withDb.secretFor(u.getId())).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }

    @Test
    void materialFor_givenPreviousMovedIntoTheCurrentSlot_expectsRefusal() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user("whsec_now", "whsec_retired", Instant.now().minus(Duration.ofDays(3)));
        // Someone with the database puts the retired secret back as the current one.
        u.setWebhookSigningSecretEncrypted(u.getWebhookSigningSecretPreviousEncrypted());
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> withDb.materialFor(u.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotateSecret_givenCurrentThatDoesNotDecrypt_expectsNewSecretWithoutGrace() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user(null, null, null);
        u.setWebhookSigningSecretEncrypted(user("whsec_elsewhere", null, null).getWebhookSigningSecretEncrypted());
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        String fresh = withDb.rotateSecret(u.getId());

        assertThat(fresh).startsWith("whsec_");
        assertThat(u.getWebhookSigningSecretPreviousEncrypted()).isNull();
        WebhookSigner.SigningMaterial material = withDb.materialFor(u.getId());
        assertThat(material.current()).isEqualTo(fresh);
        assertThat(material.hasPrevious()).isFalse();
    }

    // ===== Password reset: rotation with no grace window ==============

    @Test
    void rotateSecretWithoutGrace_givenSecretInGraceWindow_replacesItAndSignsWithNewOnly() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users, VAULT);
        User u = user("whsec_known_to_previous_holder", "whsec_older", Instant.now().minusSeconds(60));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        withDb.rotateSecretWithoutGrace(u.getId());

        String stored = u.getWebhookSigningSecretEncrypted();
        assertThat(stored).startsWith("v1:");
        String current = open(stored, "webhook:" + u.getId() + ":current");
        assertThat(current)
                .startsWith("whsec_")
                .isNotEqualTo("whsec_known_to_previous_holder");
        assertThat(u.getWebhookSigningSecretPreviousEncrypted()).isNull();
        WebhookSigner.SigningMaterial material = withDb.materialFor(u.getId());
        assertThat(material.current()).isEqualTo(current);
        assertThat(material.hasPrevious()).isFalse();
    }

    /** An account whose secrets are stored as the signer stores them. */
    private static User user(String current, String previous, Instant rotatedAt) {
        User u = new User();
        u.setId(UUID.randomUUID());
        if (current != null) {
            u.setWebhookSigningSecretEncrypted(VAULT.encrypt(current.getBytes(StandardCharsets.UTF_8),
                    "webhook:" + u.getId() + ":current"));
        }
        if (previous != null) {
            u.setWebhookSigningSecretPreviousEncrypted(VAULT.encrypt(previous.getBytes(StandardCharsets.UTF_8),
                    "webhook:" + u.getId() + ":previous"));
        }
        u.setWebhookSigningSecretRotatedAt(rotatedAt);
        return u;
    }

    private static String open(String stored, String context) {
        return new String(VAULT.decrypt(stored, context), StandardCharsets.UTF_8);
    }

    private static int countV1(String header) {
        return header.split(",v1=", -1).length - 1;
    }

    private static String hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
