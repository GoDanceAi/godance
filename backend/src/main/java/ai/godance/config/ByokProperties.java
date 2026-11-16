package ai.godance.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import ai.godance.domain.Provider;

/**
 * Bring your own key (round 14 brief 03). A user stores a provider's API key
 * of their own; requests to that provider's models then go out on it, and
 * Godance charges only its fee. Ships off: with {@code providers} empty no
 * key can be saved and everything behaves as before.
 *
 * @param providers        the providers a key may be saved for. The operator
 *                         adds each one only after checking that the
 *                         provider's terms allow a customer's key to be used
 *                         through a third party. A provider here needs a key
 *                         check ({@code ProviderKeyCheck}); startup fails
 *                         for one that has none
 * @param feeBasisPoints   what a request on the user's key is charged: this
 *                         many basis points of the request's list price (the
 *                         catalog price before Godance's markup); 500 is 5%
 * @param checkTimeout     how long the provider may take to answer the key check
 */
@ConfigurationProperties(prefix = "godance.byok")
public record ByokProperties(
        @DefaultValue({}) List<Provider> providers,
        @DefaultValue("500") int feeBasisPoints,
        @DefaultValue("10s") Duration checkTimeout
) {
    public ByokProperties {
        providers = providers == null ? List.of() : List.copyOf(providers);
        if (feeBasisPoints < 0 || feeBasisPoints > 10_000) {
            throw new IllegalStateException("godance.byok.fee-basis-points must be between 0 and 10000; got "
                    + feeBasisPoints);
        }
        if (checkTimeout == null || checkTimeout.isZero() || checkTimeout.isNegative()) {
            throw new IllegalStateException("godance.byok.check-timeout must be positive; got " + checkTimeout);
        }
    }

    public boolean supports(Provider provider) {
        return providers.contains(provider);
    }
}
