package ai.godance.gateway;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import ai.godance.config.AnthropicProperties;
import ai.godance.config.OpenAIProperties;
import ai.godance.domain.Provider;

/**
 * Checks a caller's own provider key with the cheapest call the provider
 * offers for it, listing its models (round 14 brief 03). Only providers
 * whose documentation confirms such a call are here:
 *
 * <ul>
 *   <li>OpenAI: {@code GET /v1/models} with {@code Authorization: Bearer}
 *       (OpenAI API reference, "List models").</li>
 *   <li>Anthropic: {@code GET /v1/models} with {@code x-api-key} and
 *       {@code anthropic-version}, the header the Anthropic adapter sends
 *       (Claude API overview: {@code x-api-key} "still supported").</li>
 * </ul>
 *
 * <p>A check sends the key and reads only the status: the provider's
 * answer is never logged or kept, whatever it says. The caller has already
 * passed the region check; nothing here makes one.
 */
@Component
public class ProviderKeyCheck {

    /** What the provider said about the key. */
    public enum Verdict {
        /** The provider answered 2xx. */
        VALID,
        /** The provider answered 401 or 403: it does not accept the key. */
        REJECTED,
        /** No answer either way: a timeout, a 5xx, a 429. The key is not stored. */
        UNKNOWN
    }

    private final Map<Provider, Check> checks = new EnumMap<>(Provider.class);

    /** One provider's check: where to send it, and how the key goes. */
    private record Check(WebClient client, String path, String header, String prefix, Duration timeout) { }

    public ProviderKeyCheck(OpenAIProperties openAi, AnthropicProperties anthropic) {
        checks.put(Provider.OPENAI, new Check(WebClient.builder().baseUrl(openAi.apiBase()).build(),
                "/v1/models", HttpHeaders.AUTHORIZATION, "Bearer ", openAi.timeout()));
        checks.put(Provider.ANTHROPIC, new Check(WebClient.builder().baseUrl(anthropic.apiBase())
                .defaultHeader("anthropic-version", anthropic.apiVersion()).build(),
                "/v1/models?limit=1", "x-api-key", "", anthropic.timeout()));
    }

    /** The providers a key can be checked for: the only ones bring-your-own-key may list. */
    public Set<Provider> checkable() {
        return checks.keySet();
    }

    /**
     * Sends {@code key} to {@code provider}'s model list and says what came
     * back. Blocks for at most {@code timeout}.
     */
    public Verdict check(Provider provider, String key, Duration timeout) {
        Check check = checks.get(provider);
        if (check == null) throw new IllegalArgumentException("no key check for " + provider);
        try {
            check.client().get()
                    .uri(check.path())
                    .header(check.header(), check.prefix() + key)
                    .retrieve()
                    .toBodilessEntity()
                    .block(min(timeout, check.timeout()));
            return Verdict.VALID;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            return status == 401 || status == 403 ? Verdict.REJECTED : Verdict.UNKNOWN;
        } catch (RuntimeException e) {
            return Verdict.UNKNOWN;
        }
    }

    private static Duration min(Duration a, Duration b) {
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }
}
