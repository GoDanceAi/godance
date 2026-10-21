package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import ai.godance.domain.User;
import ai.godance.repository.UserRepository;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner(null);  // secret-format only; no DB needed

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
    void graceWindow_is24Hours() {
        // Pinned so external docs / runbooks can reference the constant.
        assertThat(WebhookSigner.GRACE_WINDOW.toHours()).isEqualTo(24);
    }

    // ===== Password reset: rotation with no grace window ==============

    @Test
    void rotateSecretWithoutGrace_givenSecretInGraceWindow_replacesItAndSignsWithNewOnly() {
        UserRepository users = mock(UserRepository.class);
        WebhookSigner withDb = new WebhookSigner(users);
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setWebhookSigningSecret("whsec_known_to_previous_holder");
        u.setWebhookSigningSecretPrevious("whsec_older");
        u.setWebhookSigningSecretRotatedAt(Instant.now().minusSeconds(60));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        withDb.rotateSecretWithoutGrace(u.getId());

        assertThat(u.getWebhookSigningSecret())
                .startsWith("whsec_")
                .isNotEqualTo("whsec_known_to_previous_holder");
        assertThat(u.getWebhookSigningSecretPrevious()).isNull();
        WebhookSigner.SigningMaterial material = withDb.materialFor(u.getId());
        assertThat(material.current()).isEqualTo(u.getWebhookSigningSecret());
        assertThat(material.hasPrevious()).isFalse();
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
