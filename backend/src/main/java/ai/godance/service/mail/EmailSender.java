package ai.godance.service.mail;

import java.util.List;

/**
 * Outbound transactional email. Implementations MUST swallow every
 * failure — log + audit {@code mail.send.failed} — and never throw into
 * the calling business flow: a failed mail must not fail a signup or a
 * chat request.
 */
public interface EmailSender {

    /**
     * Send one email. {@code to} carries one recipient for user-facing
     * mail and every admin for the pricing digest (one email per run, not
     * one per admin).
     */
    void send(List<String> to, String subject, String textBody, String htmlBody);
}
