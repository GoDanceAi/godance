package ai.godance.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The dev sender must keep the historical {@code [email-stub]} log
 * contract: the first line of the text body — which MailService sets to
 * the exact message the old inline stubs logged — comes out verbatim
 * behind the same prefix.
 */
class LoggingEmailSenderTest {

    private final LoggingEmailSender sender = new LoggingEmailSender();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void send_givenTextBody_expectsFirstLineLoggedVerbatimBehindStubPrefix() {
        sender.send(List.of("a@godance.test"), "Verify your Godance email",
                "Verification URL for a@godance.test: /v1/auth/verify?token=tok-123\n\nHi Mail Tester,",
                "<p>html</p>");

        List<String> infoLines = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(infoLines).containsExactly(
                "[email-stub] Verification URL for a@godance.test: /v1/auth/verify?token=tok-123");
    }
}
