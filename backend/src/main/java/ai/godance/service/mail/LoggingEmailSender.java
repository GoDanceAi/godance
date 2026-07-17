package ai.godance.service.mail;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev/CI sender, active while {@code godance.mail.api-key} is the dummy
 * default. Preserves the historical {@code [email-stub]} log contract:
 * every template in {@link MailService} puts the exact line the old
 * inline stubs logged as the <em>first line</em> of its text body, and
 * this sender logs that line verbatim behind the same prefix — so local
 * dev grep/output is unchanged.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(List<String> to, String subject, String textBody, String htmlBody) {
        String stubLine = textBody == null ? "" : textBody.lines().findFirst().orElse("");
        log.info("[email-stub] {}", stubLine);
        log.debug("[email-stub] to={} subject=\"{}\"\n{}", to, subject, textBody);
    }
}
