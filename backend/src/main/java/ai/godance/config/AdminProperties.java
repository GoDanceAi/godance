package ai.godance.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap for the first admin, and nothing else. Who may do what lives
 * in {@code users.role}; these addresses only matter when no active,
 * verified account holds {@link ai.godance.domain.UserRole#ADMIN}. See
 * {@link ai.godance.service.admin.AdminBootstrap}.
 *
 * <p>Filled from the {@code GODANCE_ADMIN_EMAILS} env var in prod
 * (comma-separated) through {@code godance.admin.emails}. Empty by
 * default — fail-closed, so a misconfigured prod environment grants
 * nobody anything.
 */
@ConfigurationProperties("godance.admin")
public class AdminProperties {

    private List<String> emails = List.of();

    public List<String> getEmails() { return emails; }

    public void setEmails(List<String> emails) {
        this.emails = emails == null ? List.of() : emails;
    }
}
