package ai.godance.service.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import ai.godance.service.AuditLogService;

/**
 * Selects the {@link EmailSender} implementation at startup: the dev/CI
 * dummy key keeps the {@code [email-stub]} logging sender (no HTTP call
 * is ever attempted); any real key activates the Resend sender.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public EmailSender emailSender(MailProperties props, AuditLogService audit,
                                   WebClient.Builder builder) {
        if (props.isStubKey()) {
            log.info("Mail: RESEND_API_KEY is the dev dummy — [email-stub] logging sender active");
            return new LoggingEmailSender();
        }
        log.info("Mail: Resend sender active (from={}, api-base={})", props.from(), props.apiBase());
        return new ResendEmailSender(builder, props, audit);
    }
}
