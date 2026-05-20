package ai.godance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.CoinbaseProperties;
import ai.godance.domain.User;

/**
 * Thin adapter over the Coinbase Commerce REST API. No official Java
 * SDK exists; the surface we need is small (one POST + one webhook
 * verification) so we drive it with Spring's {@code RestClient}.
 *
 * <p>Signature scheme differs from Stripe: the
 * {@code X-CC-Webhook-Signature} header is plain
 * {@code hex(hmac-sha256(secret, rawBody))} with no timestamp prefix.
 * Compared in constant time via {@link MessageDigest#isEqual}.
 */
@Service
public class CoinbaseService {

    public static final String META_USER_ID = "godance_user_id";
    public static final String EVENT_CONFIRMED = "charge:confirmed";

    private final CoinbaseProperties props;
    private final RestClient client;
    private final ObjectMapper json;

    public CoinbaseService(CoinbaseProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.client = RestClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader("X-CC-Api-Key", props.apiKey())
                .defaultHeader("X-CC-Version", props.apiVersion())
                .build();
    }

    /**
     * Create a hosted charge for a credit top-up. Returns the
     * {@code hosted_url} the user redirects to and the charge id we'll
     * see again in the webhook.
     */
    public CreatedCharge createCharge(User user, long amountCents) {
        String amountStr = BigDecimal.valueOf(amountCents)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();

        Map<String, Object> body = Map.of(
                "name", "Godance credit top-up",
                "description", "Top-up of $" + amountStr + " USD",
                "pricing_type", "fixed_price",
                "local_price", Map.of("amount", amountStr, "currency", "USD"),
                "metadata", Map.of(META_USER_ID, user.getId().toString()));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post()
                .uri("/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Coinbase /charges response missing 'data'");
        }
        String id = (String) data.get("id");
        String code = (String) data.get("code");
        String hostedUrl = (String) data.get("hosted_url");
        if (id == null || hostedUrl == null) {
            throw new IllegalStateException("Coinbase /charges response missing id or hosted_url");
        }
        return new CreatedCharge(id, code, hostedUrl);
    }

    /**
     * Verify {@code X-CC-Webhook-Signature} and parse the event payload.
     * Throws {@link InvalidSignatureException} on mismatch; controller
     * surfaces that as a 400 so Coinbase shows the failure in their
     * dashboard.
     */
    public CoinbaseEvent verifyAndParse(String payload, String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new InvalidSignatureException("missing X-CC-Webhook-Signature");
        }
        String expected = hmacSha256Hex(props.webhookSecret(), payload);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                sigHeader.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidSignatureException("signature mismatch");
        }
        try {
            JsonNode root = json.readTree(payload);
            String id = root.path("id").asText(null);
            JsonNode eventNode = root.path("event");
            // Coinbase delivers webhooks in two shapes depending on API version:
            //   {"event": {"id": ..., "type": ..., "data": ...}}    (newer)
            //   {"id": ..., "type": ..., "data": ...}               (legacy)
            JsonNode envelope = eventNode.isMissingNode() ? root : eventNode;
            String eventId = envelope.path("id").asText(id);
            String type = envelope.path("type").asText(null);
            JsonNode data = envelope.path("data");
            if (eventId == null || eventId.isBlank() || type == null) {
                throw new IllegalArgumentException("Coinbase event missing id or type");
            }
            return new CoinbaseEvent(eventId, type, data);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed Coinbase event JSON: " + e.getMessage(), e);
        }
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

    public record CreatedCharge(String id, String code, String hostedUrl) { }

    public record CoinbaseEvent(String id, String type, JsonNode data) {

        /** USD-cents amount from {@code data.pricing.local.amount}, or empty if not USD. */
        public long usdAmountCents() {
            JsonNode local = data.path("pricing").path("local");
            String currency = local.path("currency").asText("");
            if (!"USD".equalsIgnoreCase(currency)) {
                throw new IllegalArgumentException(
                        "Coinbase charge currency must be USD; got '" + currency + "'");
            }
            String amount = local.path("amount").asText("0");
            return new BigDecimal(amount)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        }

        public String userIdFromMetadata() {
            return data.path("metadata").path(META_USER_ID).asText(null);
        }

        public String chargeId() {
            return data.path("id").asText(null);
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) { super(message); }
    }
}
