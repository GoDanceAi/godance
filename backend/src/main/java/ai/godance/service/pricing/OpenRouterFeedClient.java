package ai.godance.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.PricingProperties;
import ai.godance.domain.Modality;
import ai.godance.domain.PriceField;
import ai.godance.domain.Provider;
import reactor.core.publisher.Mono;

/**
 * Pulls the structured OpenRouter models feed and emits one {@link
 * ObservedPrice} per (model, priced field) pair.
 *
 * <p>OpenRouter ships USD-per-token pricing as <b>strings</b> (e.g.
 * {@code "0.000005"}) inside {@code pricing.{prompt,completion}}; this
 * parser must use {@link BigDecimal} on the raw string to preserve
 * exact value semantics — a {@code Double.parseDouble} round-trip would
 * lose a microcent on awkward values like {@code "0.0000045"}.
 *
 * <p>For our purposes OpenRouter is text-only: {@code pricing.image}
 * exists on multimodal models but represents the per-image-input-token
 * cost (not per generated image), so it's deliberately ignored here —
 * generated-image and generated-video cost is sourced from LiteLLM and
 * the LLM-fallback path instead.
 */
public class OpenRouterFeedClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterFeedClient.class);
    /** USD-per-token × 1000 (tokens per "per 1k" unit) × 1_000_000 (USD → microcents) = × 1_000_000_000. */
    private static final BigDecimal PER_TOKEN_USD_TO_MICROCENTS_PER_1K = new BigDecimal("1000000000");

    private final WebClient client;
    private final String url;
    private final ObjectMapper json;

    public OpenRouterFeedClient(WebClient.Builder builder, PricingProperties props, ObjectMapper json) {
        this.client = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
        this.url = props.getFeeds().getOpenrouterUrl();
        this.json = json;
    }

    /** Fetches the live feed. Tests inject a stub URL pointing at WireMock. */
    public Mono<List<ObservedPrice>> fetch() {
        return client.get().uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorResume(e -> {
                    log.warn("OpenRouter feed fetch failed: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /** Visible-for-testing: deterministic parse from a raw JSON string. */
    public List<ObservedPrice> parse(String body) {
        Instant now = Instant.now();
        List<ObservedPrice> out = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            log.warn("OpenRouter feed parse failed: {}", e.getMessage());
            return List.of();
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) return List.of();
        for (JsonNode entry : data) {
            String id = entry.path("id").asText(null);
            if (id == null || id.isBlank()) continue;
            JsonNode pricing = entry.path("pricing");
            if (pricing.isMissingNode()) continue;
            Provider provider = ModelAliasResolver.inferProvider(id).orElse(null);
            String bare = ModelAliasResolver.stripVendorPrefix(id);
            emitTokenField(out, id, bare, provider, pricing, "prompt",     PriceField.INPUT,  now);
            emitTokenField(out, id, bare, provider, pricing, "completion", PriceField.OUTPUT, now);
        }
        return out;
    }

    private void emitTokenField(List<ObservedPrice> out, String sourceId, String bare,
                                Provider provider, JsonNode pricing, String key,
                                PriceField field, Instant observedAt) {
        JsonNode v = pricing.path(key);
        if (v.isMissingNode() || v.isNull()) return;
        String raw = v.asText();
        if (raw == null || raw.isBlank() || "0".equals(raw)) return;  // skip free/missing
        BigDecimal usd;
        try {
            usd = new BigDecimal(raw);
        } catch (NumberFormatException nfe) {
            // NaN / non-finite — emit at microcents=Long.MIN_VALUE so the
            // sanity guard rejects it as ANOMALY rather than silently dropping.
            out.add(new ObservedPrice(sourceId, bare, provider, Modality.TEXT, field,
                    Long.MIN_VALUE, BigDecimal.ZERO, "openrouter", null, observedAt));
            return;
        }
        BigDecimal microcents = usd.multiply(PER_TOKEN_USD_TO_MICROCENTS_PER_1K)
                .setScale(0, RoundingMode.HALF_UP);
        long longValue;
        try {
            longValue = microcents.longValueExact();
        } catch (ArithmeticException overflow) {
            longValue = Long.MAX_VALUE;
        }
        out.add(new ObservedPrice(sourceId, bare, provider, Modality.TEXT, field,
                longValue, usd, "openrouter", null, observedAt));
    }

    /** Test helper: stub a Map to avoid spinning up WebClient. */
    public static List<ObservedPrice> parseFor(ObjectMapper mapper, Map<String, Object> body) {
        try {
            String s = mapper.writeValueAsString(body);
            return new OpenRouterFeedClient(WebClient.builder(),
                    new PricingProperties(), mapper).parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
