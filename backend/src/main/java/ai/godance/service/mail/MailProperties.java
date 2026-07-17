package ai.godance.service.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.mail.*}. Mirrors {@link
 * ai.godance.config.NowPaymentsProperties} — required in prod, dev gets a
 * clearly-marked dummy default, {@code apiBase} can be pointed at WireMock
 * in tests.
 *
 * <p>The dummy {@code apiKey} default doubles as the sender selector: while
 * the key is {@link #DUMMY_API_KEY} (dev/CI), {@link MailConfig} wires the
 * {@link LoggingEmailSender} so no real HTTP call is ever attempted.
 */
@ConfigurationProperties(prefix = "godance.mail")
public record MailProperties(
        String apiKey,
        String from,
        String apiBase
) {
    /** Dev/CI placeholder — selects the {@code [email-stub]} logging sender. */
    public static final String DUMMY_API_KEY = "re_dev_dummy_key_do_not_use_for_real_calls";

    public MailProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) {
            throw new IllegalStateException("godance.mail.api-key must be configured");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("godance.mail.from must be set");
        }
        if (apiBase == null || apiBase.isBlank()) {
            throw new IllegalStateException("godance.mail.api-base must be set");
        }
    }

    /** True when no real Resend key is configured (dev/CI). */
    public boolean isStubKey() {
        return apiKey.isBlank() || DUMMY_API_KEY.equals(apiKey);
    }
}
