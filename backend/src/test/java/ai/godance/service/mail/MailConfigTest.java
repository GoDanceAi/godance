package ai.godance.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import ai.godance.service.AuditLogService;

/** Sender selection: dummy key (dev/CI) → logging stub; real key → Resend. */
class MailConfigTest {

    private final MailConfig config = new MailConfig();
    private final AuditLogService audit = mock(AuditLogService.class);

    @Test
    void emailSender_givenDummyApiKey_expectsLoggingStub() {
        MailProperties props = new MailProperties(
                MailProperties.DUMMY_API_KEY, "Godance <noreply@godance.ai>", "https://api.resend.com",
                "https://app.godance.test");

        assertThat(config.emailSender(props, audit, WebClient.builder()))
                .isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void emailSender_givenBlankApiKey_expectsLoggingStub() {
        MailProperties props = new MailProperties(
                "", "Godance <noreply@godance.ai>", "https://api.resend.com",
                "https://app.godance.test");

        assertThat(config.emailSender(props, audit, WebClient.builder()))
                .isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void emailSender_givenRealApiKey_expectsResendSender() {
        MailProperties props = new MailProperties(
                "re_live_something_real", "Godance <noreply@godance.ai>", "https://api.resend.com",
                "https://app.godance.test");

        assertThat(config.emailSender(props, audit, WebClient.builder()))
                .isInstanceOf(ResendEmailSender.class);
    }
}
