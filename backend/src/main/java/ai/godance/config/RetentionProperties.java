package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What an account may keep past the retention schedule
 * ({@link ai.godance.service.DataRetentionService}).
 *
 * @param savedLimit the most generations one account can save. A saved
 *                   generation's files are kept at no charge for as long
 *                   as the account exists, so the number is limited; 0
 *                   means nothing can be saved
 */
@ConfigurationProperties(prefix = "godance.retention")
public record RetentionProperties(
        @DefaultValue("500") int savedLimit
) {
    public RetentionProperties {
        if (savedLimit < 0) {
            throw new IllegalStateException("godance.retention.saved-limit must be >= 0");
        }
    }
}
