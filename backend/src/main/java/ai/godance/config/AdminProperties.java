package ai.godance.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-only access control. Single-tenant by design: there is no
 * {@code is_admin} column on {@code users}; instead, on every
 * {@code /v1/admin/**} request
 * {@link ai.godance.security.AdminAuthorizationFilter} loads the caller's
 * account and matches its current, verified email case-insensitively
 * against this allowlist. An allowlisted address grants nothing until
 * its account has verified it.
 *
 * <p>The allowlist lives in {@code application.yml} under
 * {@code godance.admin.emails} and is filled from the
 * {@code GODANCE_ADMIN_EMAILS} env var in prod (comma-separated). Empty
 * by default — fail-closed, so a misconfigured prod environment cannot
 * accidentally grant admin to every authenticated user.
 */
@ConfigurationProperties("godance.admin")
public class AdminProperties {

    private List<String> emails = List.of();

    public List<String> getEmails() { return emails; }

    public void setEmails(List<String> emails) {
        this.emails = emails == null ? List.of() : emails;
    }

    /** Case-insensitive membership check. */
    public boolean isAdmin(String subject) {
        if (subject == null || subject.isBlank() || emails.isEmpty()) return false;
        String needle = subject.toLowerCase(Locale.ROOT);
        Set<String> normalized = new HashSet<>(emails.size() * 2);
        for (String e : emails) {
            if (e != null) normalized.add(e.toLowerCase(Locale.ROOT));
        }
        return normalized.contains(needle);
    }
}
