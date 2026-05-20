package ai.godance.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.User;
import ai.godance.repository.UserRepository;

/**
 * Signs outbound webhook deliveries with HMAC-SHA256.
 *
 * <p>Header format mirrors Stripe:
 * {@code X-Godance-Signature: t=<unix-seconds>,v1=<hex>[,v1=<hex>...]}
 * where each {@code v1=<hex>} is {@code HMAC-SHA256(secret, "<t>.<rawPayload>")}.
 *
 * <p><b>Grace window.</b> When a user rotates the secret via
 * {@link #rotateSecret(UUID)}, the prior value is retained for
 * {@link #GRACE_WINDOW}. Outbound webhooks signed during that window
 * carry TWO {@code v1=...} entries — one with the current secret, one
 * with the previous. Receivers walk all v1 entries and accept on ANY
 * match, so deployments rolling out the new secret to multiple
 * environments don't drop webhooks mid-rollout.
 *
 * <p>Per-user secret is 32 bytes of {@link SecureRandom}, base64
 * (URL-safe, no padding), {@code whsec_} prefix.
 */
@Service
public class WebhookSigner {

    public static final String HEADER = "X-Godance-Signature";
    public static final Duration GRACE_WINDOW = Duration.ofHours(24);

    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository users;

    public WebhookSigner(UserRepository users) {
        this.users = users;
    }

    /** Lazy-get-or-create the per-user signing secret. */
    @Transactional
    public String secretFor(UUID userId) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        if (u.getWebhookSigningSecret() == null || u.getWebhookSigningSecret().isBlank()) {
            u.setWebhookSigningSecret(generate());
            users.save(u);
        }
        return u.getWebhookSigningSecret();
    }

    /**
     * Rotate — current secret becomes "previous" (valid for {@link #GRACE_WINDOW}
     * in outbound signatures), and a new current secret is generated and returned.
     */
    @Transactional
    public String rotateSecret(UUID userId) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        String oldCurrent = u.getWebhookSigningSecret();
        u.setWebhookSigningSecretPrevious(oldCurrent);
        u.setWebhookSigningSecretRotatedAt(Instant.now());
        u.setWebhookSigningSecret(generate());
        users.save(u);
        return u.getWebhookSigningSecret();
    }

    /**
     * Snapshot of the secret material at the moment of signing — the
     * current value, plus optionally the previous one while within the
     * grace window. Caller passes to {@link #headerValueFromMaterial}.
     */
    @Transactional
    public SigningMaterial materialFor(UUID userId) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        String current = u.getWebhookSigningSecret();
        if (current == null || current.isBlank()) {
            current = generate();
            u.setWebhookSigningSecret(current);
            users.save(u);
        }
        String previous = null;
        Instant rotatedAt = u.getWebhookSigningSecretRotatedAt();
        if (u.getWebhookSigningSecretPrevious() != null
                && rotatedAt != null
                && Instant.now().isBefore(rotatedAt.plus(GRACE_WINDOW))) {
            previous = u.getWebhookSigningSecretPrevious();
        }
        return new SigningMaterial(current, previous);
    }

    /** Single-secret signing — used by simple callers + the WebhookSignerTest unit case. */
    public String headerValue(String secret, String rawPayload) {
        long t = Instant.now().getEpochSecond();
        return "t=" + t + ",v1=" + hmacSha256Hex(secret, t + "." + rawPayload);
    }

    /**
     * Build the header from {@link SigningMaterial}. Emits 1 or 2
     * {@code v1=...} entries depending on grace-window state.
     */
    public String headerValueFromMaterial(SigningMaterial material, String rawPayload) {
        long t = Instant.now().getEpochSecond();
        String signed = t + "." + rawPayload;
        StringBuilder sb = new StringBuilder("t=").append(t);
        sb.append(",v1=").append(hmacSha256Hex(material.current(), signed));
        if (material.previous() != null) {
            sb.append(",v1=").append(hmacSha256Hex(material.previous(), signed));
        }
        return sb.toString();
    }

    /** Result of {@link #materialFor} — current secret plus optional within-grace previous. */
    public record SigningMaterial(String current, String previous) {
        public boolean hasPrevious() { return previous != null; }
    }

    private static String generate() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable; JVM broken", e);
        }
    }
}
