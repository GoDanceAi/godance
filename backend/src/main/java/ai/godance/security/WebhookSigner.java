package ai.godance.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>At rest.</b> The secret is kept only through {@link KeyVault}
 * (backend CLAUDE.md, hard constraint 2). This class encrypts it before it
 * is saved and decrypts it to sign or to hand it to its owner; nothing else
 * reads those columns. The context names the account and the slot,
 * {@code webhook:<user>:current} or {@code webhook:<user>:previous}, so a
 * value copied to another account, or from one slot to the other, does not
 * decrypt. Secrets saved in clear before V42 were encrypted by
 * {@link WebhookSigningSecretVaultMigration}, and the database refuses a
 * value in clear since.
 */
@Service
public class WebhookSigner {

    public static final String HEADER = "X-Godance-Signature";
    public static final Duration GRACE_WINDOW = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(WebhookSigner.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository users;
    private final KeyVault vault;

    public WebhookSigner(UserRepository users, KeyVault vault) {
        this.users = users;
        this.vault = vault;
    }

    /** Lazy-get-or-create the per-user signing secret. */
    @Transactional
    public String secretFor(UUID userId) {
        return currentOrCreate(load(userId));
    }

    /**
     * Rotate — current secret becomes "previous" (valid for {@link #GRACE_WINDOW}
     * in outbound signatures), and a new current secret is generated and returned.
     *
     * <p>A current secret that no longer decrypts (the vault key changed, or
     * the value came from another row) can't sign, so it is dropped instead
     * of kept for the grace window. Rotating is how such an account recovers.
     */
    @Transactional
    public String rotateSecret(UUID userId) {
        User u = load(userId);
        String oldCurrent = currentIfReadable(u);
        String fresh = generate();
        u.setWebhookSigningSecretPreviousEncrypted(
                oldCurrent == null ? null : seal(oldCurrent, previousContext(userId)));
        u.setWebhookSigningSecretRotatedAt(Instant.now());
        u.setWebhookSigningSecretEncrypted(seal(fresh, currentContext(userId)));
        users.save(u);
        return fresh;
    }

    /**
     * Rotate with no grace window: the old secret stops signing at once.
     * For a password reset, where whoever held the account before may
     * know it.
     */
    @Transactional
    public void rotateSecretWithoutGrace(UUID userId) {
        User u = load(userId);
        u.setWebhookSigningSecretEncrypted(seal(generate(), currentContext(userId)));
        u.setWebhookSigningSecretPreviousEncrypted(null);
        u.setWebhookSigningSecretRotatedAt(Instant.now());
        users.save(u);
    }

    /**
     * Snapshot of the secret material at the moment of signing — the
     * current value, plus optionally the previous one while within the
     * grace window. Caller passes to {@link #headerValueFromMaterial}.
     */
    @Transactional
    public SigningMaterial materialFor(UUID userId) {
        User u = load(userId);
        String current = currentOrCreate(u);
        String previous = null;
        Instant rotatedAt = u.getWebhookSigningSecretRotatedAt();
        if (u.getWebhookSigningSecretPreviousEncrypted() != null
                && rotatedAt != null
                && Instant.now().isBefore(rotatedAt.plus(GRACE_WINDOW))) {
            previous = open(u.getWebhookSigningSecretPreviousEncrypted(), previousContext(userId));
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

        /** Never the secrets, should this end up in a log line or an exception message. */
        @Override
        public String toString() {
            return "SigningMaterial[current=<redacted>, previous=" + (previous == null ? "none" : "<redacted>") + "]";
        }
    }

    /** KeyVault's context for an account's current secret. */
    static String currentContext(UUID userId) {
        return "webhook:" + userId + ":current";
    }

    /** KeyVault's context for the secret the last rotation replaced. */
    static String previousContext(UUID userId) {
        return "webhook:" + userId + ":previous";
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    /** The current secret; the account's first is made and saved here. */
    private String currentOrCreate(User u) {
        String stored = u.getWebhookSigningSecretEncrypted();
        if (stored != null) {
            return open(stored, currentContext(u.getId()));
        }
        String current = generate();
        u.setWebhookSigningSecretEncrypted(seal(current, currentContext(u.getId())));
        users.save(u);
        return current;
    }

    /** The current secret for a rotation to keep, or null if there is none or it does not decrypt. */
    private String currentIfReadable(User u) {
        String stored = u.getWebhookSigningSecretEncrypted();
        if (stored == null) {
            return null;
        }
        try {
            return open(stored, currentContext(u.getId()));
        } catch (IllegalStateException | IllegalArgumentException e) {  // the latter: not even Base64
            log.warn("Webhook signing secret of user {} does not decrypt; rotating it without a grace window",
                    u.getId());
            return null;
        }
    }

    private String seal(String secret, String context) {
        return vault.encrypt(secret.getBytes(StandardCharsets.UTF_8), context);
    }

    private String open(String stored, String context) {
        return new String(vault.decrypt(stored, context), StandardCharsets.UTF_8);
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
