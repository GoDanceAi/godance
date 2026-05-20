package ai.godance.gateway;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Routes by {@link ModelInfo#provider()} to the registered
 * {@link ProviderAdapter} <b>after</b> enforcing
 * {@link AlicloudProviderAllowlist}. The allowlist check is the
 * code-level enforcement of CLAUDE.md hard constraint #1 / HANDOFF
 * Hard Stop #3 — "regional routing is code, not config".
 *
 * <p>Lookup is on the current region from {@code godance.region} via
 * {@link Region} (case-insensitive parse, defaults to {@link Region#LOCAL}).
 * The unit test for the denial path (e.g. OpenAI dispatch from
 * {@code REGION=alicloud} → 503) is HANDOFF-required.
 */
@Component
public class ProviderGateway {

    private final Map<Provider, ProviderAdapter> adapters;
    private final Region region;

    public ProviderGateway(List<ProviderAdapter> discovered,
                           @Value("${godance.region:local}") String regionName) {
        this.region = parseRegion(regionName);
        this.adapters = new EnumMap<>(Provider.class);
        for (ProviderAdapter a : discovered) {
            ProviderAdapter prior = this.adapters.put(a.type(), a);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate ProviderAdapter for " + a.type()
                                + ": " + prior.getClass() + " vs " + a.getClass());
            }
        }
    }

    /**
     * Resolve the adapter for {@code model}. Throws
     * {@link ResponseStatusException} 503 if the current region's
     * allowlist forbids this provider, or 502 if no adapter is
     * registered for it.
     */
    public ProviderAdapter dispatch(ModelInfo model) {
        Provider provider = model.provider();
        if (region == Region.ALICLOUD && !AlicloudProviderAllowlist.isAllowed(provider)) {
            // Fail closed. Message intentionally vague — clients shouldn't
            // learn which providers are forbidden vs. simply unavailable.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "provider " + provider + " not available in this region");
        }
        ProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "no adapter registered for provider " + provider);
        }
        return adapter;
    }

    public Region region() {
        return region;
    }

    private static Region parseRegion(String name) {
        if (name == null || name.isBlank()) return Region.LOCAL;
        try {
            return Region.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("godance.region must be one of "
                    + java.util.Arrays.toString(Region.values()) + "; got '" + name + "'", e);
        }
    }
}
