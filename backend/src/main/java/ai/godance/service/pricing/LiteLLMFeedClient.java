package ai.godance.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
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
import reactor.core.publisher.Mono;

/**
 * Pulls the BerriAI/litellm pricing JSON and emits {@link ObservedPrice}
 * records — particularly strong on day-0 new models the aggregators miss.
 *
 * <p>The feed is a flat top-level object keyed by model id; we ignore
 * {@code mode=embedding} entries (no chat/image/video pricing surface),
 * skip the {@code sample_spec} sentinel, and let unknown {@code mode}s
 * fall through silently. Image-mode rows carry {@code
 * output_cost_per_image} (USD per generated image); video-mode rows
 * carry {@code output_cost_per_second} (USD per second of output).
 *
 * <p>Numbers come as JSON numbers here (not strings as on OpenRouter)
 * but we still go through {@link BigDecimal#valueOf(double)} avoidance
 * — pulling the asText() and parsing — so subnormal doubles don't drift.
 */
public class LiteLLMFeedClient {

    private static final Logger log = LoggerFactory.getLogger(LiteLLMFeedClient.class);
    private static final BigDecimal PER_TOKEN_USD_TO_MICROCENTS_PER_1K = new BigDecimal("1000000000");
    private static final BigDecimal PER_UNIT_USD_TO_MICROCENTS         = new BigDecimal("1000000");

    private final WebClient client;
    private final String url;
    private final ObjectMapper json;

    public LiteLLMFeedClient(WebClient.Builder builder, PricingProperties props, ObjectMapper json) {
        this.client = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        this.url = props.getFeeds().getLitellmUrl();
        this.json = json;
    }

    public Mono<List<ObservedPrice>> fetch() {
        return client.get().uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorResume(e -> {
                    log.warn("LiteLLM feed fetch failed: {}", e.getMessage());
                    return Mono.just(List.of());
                });
    }

    public List<ObservedPrice> parse(String body) {
        Instant now = Instant.now();
        List<ObservedPrice> out = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            log.warn("LiteLLM feed parse failed: {}", e.getMessage());
            return List.of();
        }
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (key.startsWith("_") || "sample_spec".equals(key)) continue;
            JsonNode v = e.getValue();
            if (!v.isObject()) continue;
            String mode = v.path("mode").asText("chat");
            String sourceUrl = v.path("source").asText(null);
            String bare = stripResolutionPrefix(key);
            switch (mode) {
                case "chat", "completion", "responses" -> {
                    emitToken(out, key, bare, v, "input_cost_per_token",  PriceField.INPUT,  sourceUrl, now);
                    emitToken(out, key, bare, v, "output_cost_per_token", PriceField.OUTPUT, sourceUrl, now);
                }
                case "image_generation" ->
                    emitPerUnit(out, key, bare, Modality.IMAGE, v, "output_cost_per_image",
                            PriceField.PER_IMAGE, sourceUrl, now);
                case "video_generation" ->
                    emitPerUnit(out, key, bare, Modality.VIDEO, v, "output_cost_per_second",
                            PriceField.PER_SECOND, sourceUrl, now);
                default -> {
                    // embedding / audio_speech / audio_transcription / unknown — skip
                }
            }
        }
        return out;
    }

    /** LiteLLM keys image rows as {@code "1024-x-1024/seedream-5.0"}; strip the resolution prefix. */
    static String stripResolutionPrefix(String key) {
        if (!key.contains("/")) return key;
        int slash = key.indexOf('/');
        String prefix = key.substring(0, slash);
        if (prefix.matches("\\d+-x-\\d+(/\\d+-steps)?")) {
            return key.substring(slash + 1);
        }
        return key;
    }

    private void emitToken(List<ObservedPrice> out, String sourceId, String bare, JsonNode v,
                           String costKey, PriceField field, String sourceUrl, Instant observedAt) {
        JsonNode n = v.path(costKey);
        if (n.isMissingNode() || n.isNull()) return;
        String raw = n.asText();
        if ("0".equals(raw) || "0.0".equals(raw)) return;
        BigDecimal usd;
        try {
            usd = new BigDecimal(raw);
        } catch (NumberFormatException nfe) {
            out.add(new ObservedPrice(sourceId, bare, null, Modality.TEXT, field,
                    Long.MIN_VALUE, BigDecimal.ZERO, "litellm", sourceUrl, observedAt));
            return;
        }
        long mc = toMicrocents(usd.multiply(PER_TOKEN_USD_TO_MICROCENTS_PER_1K));
        out.add(new ObservedPrice(sourceId, bare, null, Modality.TEXT, field,
                mc, usd, "litellm", sourceUrl, observedAt));
    }

    private void emitPerUnit(List<ObservedPrice> out, String sourceId, String bare, Modality modality,
                             JsonNode v, String costKey, PriceField field,
                             String sourceUrl, Instant observedAt) {
        JsonNode n = v.path(costKey);
        if (n.isMissingNode() || n.isNull()) return;
        String raw = n.asText();
        if ("0".equals(raw) || "0.0".equals(raw)) return;
        BigDecimal usd;
        try {
            usd = new BigDecimal(raw);
        } catch (NumberFormatException nfe) {
            out.add(new ObservedPrice(sourceId, bare, null, modality, field,
                    Long.MIN_VALUE, BigDecimal.ZERO, "litellm", sourceUrl, observedAt));
            return;
        }
        long mc = toMicrocents(usd.multiply(PER_UNIT_USD_TO_MICROCENTS));
        out.add(new ObservedPrice(sourceId, bare, null, modality, field,
                mc, usd, "litellm", sourceUrl, observedAt));
    }

    private static long toMicrocents(BigDecimal microcents) {
        BigDecimal rounded = microcents.setScale(0, RoundingMode.HALF_UP);
        try {
            return rounded.longValueExact();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
