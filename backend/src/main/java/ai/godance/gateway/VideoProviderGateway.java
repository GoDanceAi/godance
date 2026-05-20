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
 * Video counterpart of {@link ProviderGateway} / {@link ImageProviderGateway}.
 * Same allowlist enforcement. Independent registry from chat/image because
 * the provider set differs (e.g. Sora has video, no chat; BFL has image,
 * no video).
 */
@Component
public class VideoProviderGateway {

    private final Map<Provider, VideoProviderAdapter> adapters;
    private final Region region;

    public VideoProviderGateway(List<VideoProviderAdapter> discovered,
                                @Value("${godance.region:local}") String regionName) {
        this.region = parseRegion(regionName);
        this.adapters = new EnumMap<>(Provider.class);
        for (VideoProviderAdapter a : discovered) {
            VideoProviderAdapter prior = this.adapters.put(a.type(), a);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate VideoProviderAdapter for " + a.type()
                                + ": " + prior.getClass() + " vs " + a.getClass());
            }
        }
    }

    public VideoProviderAdapter dispatch(ModelInfo model) {
        Provider provider = model.provider();
        if (region == Region.ALICLOUD && !AlicloudProviderAllowlist.isAllowed(provider)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "provider " + provider + " not available in this region");
        }
        VideoProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "no video adapter registered for provider " + provider);
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
