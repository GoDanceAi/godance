package ai.godance.routing;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who may write and use their own routing policies (round 4's Phase 3, round
 * 14 brief 04). Off by default: while off, {@code /v1/routing/policies}
 * answers 404 {@code routing_policies_disabled}, a policy's name is an unknown
 * model, and no batch may ask for fallbacks.
 *
 * @param enabled    whether anyone may
 * @param allowList  the email addresses that may, when on; empty means every account
 * @param maxPerUser the most policies one account may keep
 */
@ConfigurationProperties(prefix = "godance.routing.user-policies")
public record UserRoutingPolicyProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<String> allowList,
        @DefaultValue("20") int maxPerUser
) {
    public UserRoutingPolicyProperties {
        allowList = allowList == null ? List.of() : allowList.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (maxPerUser < 1) {
            throw new IllegalStateException(
                    "godance.routing.user-policies.max-per-user must be >= 1; got " + maxPerUser);
        }
    }

    /** Whether the account with this email may write and use policies. */
    public boolean allows(String email) {
        if (!enabled) return false;
        if (allowList.isEmpty()) return true;
        return email != null && allowList.contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
