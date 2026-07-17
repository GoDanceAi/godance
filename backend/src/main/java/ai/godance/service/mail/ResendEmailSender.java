package ai.godance.service.mail;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import ai.godance.domain.AuditOutcome;
import ai.godance.service.AuditLogService;

/**
 * Real sender — {@code POST {api-base}/emails} (Resend). The send is
 * synchronous with a hard timeout, mirroring the blocking-call pattern
 * of the pricing feed clients; call sites are JPA-transactional services
 * that already run off the event loop.
 *
 * <p>Failure containment: any non-2xx response, timeout or connect error
 * is logged + audited as {@code mail.send.failed} and swallowed. This
 * method never throws.
 */
public class ResendEmailSender implements EmailSender {

    public static final String EVT_MAIL_SEND_FAILED = "mail.send.failed";

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final MailProperties props;
    private final AuditLogService audit;

    public ResendEmailSender(WebClient.Builder builder, MailProperties props, AuditLogService audit) {
        this.client = builder
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
        this.props = props;
        this.audit = audit;
    }

    @Override
    public void send(List<String> to, String subject, String textBody, String htmlBody) {
        Map<String, Object> body = Map.of(
                "from", props.from(),
                "to", to,
                "subject", subject,
                "text", textBody,
                "html", htmlBody);
        try {
            ResponseEntity<String> resp = client.post().uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchangeToMono(r -> r.toEntity(String.class))
                    .block(SEND_TIMEOUT);
            if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
                fail(to, subject, resp == null
                        ? "empty response"
                        : "status=" + resp.getStatusCode().value() + " body=" + abbreviate(resp.getBody()));
            }
        } catch (Exception e) {
            fail(to, subject, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void fail(List<String> to, String subject, String reason) {
        log.error("mail send failed to={} subject=\"{}\": {}", to, subject, reason);
        try {
            Map<String, Object> detail = new HashMap<>();
            detail.put("to", String.join(",", to));
            detail.put("subject", subject);
            detail.put("reason", reason);
            audit.record(EVT_MAIL_SEND_FAILED, AuditOutcome.FAILURE, null, null, detail);
        } catch (Exception e) {
            // Even a failed audit write must not surface into the business flow.
            log.error("mail.send.failed audit write failed: {}", e.getMessage());
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
