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
 * Image-side counterpart of {@link ProviderGateway}. Same allowlist
 * enforcement against {@link AlicloudProviderAllowlist} — independent
 * of chat-side wiring because not every chat provider does image gen
 * (and vice versa).
 */
@Component
public class ImageProviderGateway {

    private final Map<Provider, ImageProviderAdapter> adapters;
    private final Region region;

    public ImageProviderGateway(List<ImageProviderAdapter> discovered,
                                @Value("${godance.region:local}") String regionName) {
        this.region = parseRegion(regionName);
        this.adapters = new EnumMap<>(Provider.class);
        for (ImageProviderAdapter a : discovered) {
            ImageProviderAdapter prior = this.adapters.put(a.type(), a);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate ImageProviderAdapter for " + a.type()
                                + ": " + prior.getClass() + " vs " + a.getClass());
            }
        }
    }

    public ImageProviderAdapter dispatch(ModelInfo model) {
        Provider provider = model.provider();
        if (region == Region.ALICLOUD && !AlicloudProviderAllowlist.isAllowed(provider)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "provider " + provider + " not available in this region");
        }
        ImageProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "no image adapter registered for provider " + provider);
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
