package ai.godance.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway behavior knobs. Currently just per-model fallback chains;
 * future: per-region overrides, cache hints, dispatch retry counts.
 *
 * <p>The map keys are primary-model names; values are ordered lists of
 * fallback models tried after a {@code 5xx/429/timeout} from the
 * primary. Each fallback re-enters the allowlist check.
 */
@ConfigurationProperties(prefix = "godance.gateway")
public record GatewayProperties(
        Map<String, List<String>> fallbackChains
) {
    public GatewayProperties {
        fallbackChains = fallbackChains == null ? Map.of() : Map.copyOf(fallbackChains);
    }

    public List<String> fallbacksFor(String primaryModel) {
        return fallbackChains.getOrDefault(primaryModel, List.of());
    }
}
