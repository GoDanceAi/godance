package ai.godance.service.mail;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.godance.config.AdminProperties;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.User;

/**
 * The transactional email templates (the four from brief 02, plus
 * topup-payment-failed), inline — subject + text + minimal HTML, no
 * template engine. Call sites inject this facade and never touch the
 * {@link EmailSender} directly.
 *
 * <p>Two invariants:
 * <ul>
 *   <li>Every text body's <b>first line</b> is exactly the message the
 *       old inline {@code [email-stub]} logs produced (a one-line summary
 *       for templates added since), so the {@link LoggingEmailSender}
 *       keeps dev output verbatim.</li>
 *   <li>No method here ever throws — mail failures must not propagate
 *       into signup, chat requests, auto-recharge, billing webhooks or
 *       the pricing cron.</li>
 * </ul>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final EmailSender sender;
    private final AdminProperties admin;

    public MailService(EmailSender sender, AdminProperties admin) {
        this.sender = sender;
        this.admin = admin;
    }

    /** verify-email — sent once per signup. */
    public void sendVerificationEmail(User user) {
        try {
            String stubLine = "Verification URL for " + user.getEmail()
                    + ": /verify-email?token=" + user.getEmailVerificationToken();
            String text = stubLine + "\n\n"
                    + "Hi " + user.getFullName() + ",\n\n"
                    + "Welcome to Godance. Please confirm this email address by opening the\n"
                    + "verification link above in the Godance app, signed in to this account.\n\n"
                    + "If you didn't create a Godance account, you can ignore this email.\n";
            String html = "<p>Hi " + escape(user.getFullName()) + ",</p>"
                    + "<p>Welcome to Godance. Please confirm this email address by opening this"
                    + " link in the Godance app, signed in to this account:</p>"
                    + "<p><code>/verify-email?token=" + escape(user.getEmailVerificationToken()) + "</code></p>"
                    + "<p>If you didn't create a Godance account, you can ignore this email.</p>";
            sender.send(List.of(user.getEmail()), "Verify your Godance email", text, html);
        } catch (Exception e) {
            log.error("verify-email build/send failed for {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * spend-warning-80 — fired at most once per period by
     * {@link ai.godance.service.SpendingLimitService}. {@code capSubject}
     * is {@code "user <id>"} or {@code "api-key <id>"}, matching the old
     * stub wording.
     */
    public void sendSpendWarning(String toEmail, String capSubject, String period,
                                 long spentCents, long capCents) {
        try {
            if (toEmail == null || toEmail.isBlank()) {
                log.debug("spend warning for {} skipped: no recipient email", capSubject);
                return;
            }
            String stubLine = "Spending warning for " + capSubject + ": " + period
                    + " usage at " + spentCents + " of " + capCents
                    + " cents (80% soft cap reached)";
            String text = stubLine + "\n\n"
                    + "You've spent " + dollars(spentCents) + " of your " + dollars(capCents) + " "
                    + period + " spending cap on Godance (" + capSubject + ").\n\n"
                    + "Requests keep working until the cap is fully used, then return\n"
                    + "402 Payment Required until the " + period + " window resets.\n";
            String html = "<p>You've spent <b>" + dollars(spentCents) + "</b> of your <b>"
                    + dollars(capCents) + "</b> " + escape(period) + " spending cap on Godance ("
                    + escape(capSubject) + ").</p>"
                    + "<p>Requests keep working until the cap is fully used, then return "
                    + "402 Payment Required until the " + escape(period) + " window resets.</p>";
            sender.send(List.of(toEmail),
                    "Godance: 80% of your " + period + " spending cap used", text, html);
        } catch (Exception e) {
            log.error("spend-warning build/send failed for {}: {}", capSubject, e.getMessage());
        }
    }

    /** autorecharge-failed — auto-recharge was disabled after a hard payment failure. */
    public void sendAutoRechargeFailed(User user, String reason) {
        try {
            String stubLine = "Auto-recharge failed for " + user.getEmail() + ": " + reason;
            String text = stubLine + "\n\n"
                    + "Your Godance auto-recharge payment did not go through, so\n"
                    + "auto-recharge has been switched off to avoid repeated charges.\n\n"
                    + "To turn it back on, run a manual top-up with an up-to-date card\n"
                    + "(save_card=true) and re-enable auto-recharge.\n";
            String html = "<p>Your Godance auto-recharge payment did not go through "
                    + "(<code>" + escape(reason) + "</code>), so auto-recharge has been "
                    + "switched off to avoid repeated charges.</p>"
                    + "<p>To turn it back on, run a manual top-up with an up-to-date card "
                    + "and re-enable auto-recharge.</p>";
            sender.send(List.of(user.getEmail()), "Godance: auto-recharge failed", text, html);
        } catch (Exception e) {
            log.error("autorecharge-failed build/send failed for {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * topup-payment-failed — a delayed-notification Stripe payment (ACH
     * Direct Debit, SEPA Debit) for a top-up failed after checkout, so the
     * credits the user was expecting never arrived.
     */
    public void sendTopUpPaymentFailed(User user, long amountCents) {
        try {
            String stubLine = "Top-up payment failed for " + user.getEmail() + ": "
                    + dollars(amountCents) + " not credited";
            String text = stubLine + "\n\n"
                    + "The bank payment for your " + dollars(amountCents) + " Godance top-up\n"
                    + "did not go through, so no credits were added to your balance.\n\n"
                    + "Your bank may have more detail on why. You can top up again with\n"
                    + "another payment method at any time.\n";
            String html = "<p>The bank payment for your <b>" + dollars(amountCents)
                    + "</b> Godance top-up did not go through, so no credits were added "
                    + "to your balance.</p>"
                    + "<p>Your bank may have more detail on why. You can top up again with "
                    + "another payment method at any time.</p>";
            sender.send(List.of(user.getEmail()), "Godance: top-up payment failed", text, html);
        } catch (Exception e) {
            log.error("topup-payment-failed build/send failed for {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * pricing-proposals-digest — ONE email per pricing-cron run listing
     * the pending proposals, to the {@code GODANCE_ADMIN_EMAILS}
     * allowlist. Skips silently when there is nothing pending or the
     * allowlist is empty (fail-closed, consistent with the admin API).
     */
    public void sendPricingProposalsDigest(List<ModelChangeProposal> pending) {
        try {
            if (pending == null || pending.isEmpty()) return;
            List<String> to = admin.getEmails().stream()
                    .filter(e -> e != null && !e.isBlank())
                    .toList();
            if (to.isEmpty()) {
                log.debug("pricing digest skipped: admin allowlist empty (fail-closed)");
                return;
            }
            String stubLine = "Pricing digest: " + pending.size()
                    + " pending proposal(s) awaiting review";
            StringBuilder text = new StringBuilder(stubLine).append("\n\n");
            StringBuilder rows = new StringBuilder();
            for (ModelChangeProposal p : pending) {
                String line = p.getModelName() + " [" + p.getType() + "] " + describeChange(p.getPayload());
                text.append("- ").append(line).append('\n');
                rows.append("<li>").append(escape(line)).append("</li>");
            }
            text.append("\nReview and approve via /v1/admin/pricing/proposals.\n");
            String html = "<p>" + pending.size() + " pending pricing proposal(s) awaiting review:</p>"
                    + "<ul>" + rows + "</ul>"
                    + "<p>Review and approve via <code>/v1/admin/pricing/proposals</code>.</p>";
            sender.send(to, "Godance pricing digest — " + pending.size() + " pending proposal(s)",
                    text.toString(), html);
        } catch (Exception e) {
            log.error("pricing-proposals-digest build/send failed: {}", e.getMessage());
        }
    }

    /** "field: old → new"; discovery payloads have no old_value. */
    private static String describeChange(Map<String, Object> payload) {
        if (payload == null) return "";
        Object field = payload.get("field");
        Object oldV = payload.get("old_value");
        Object newV = payload.get("new_value");
        return (field == null ? "?" : field) + ": "
                + (oldV == null ? "—" : oldV) + " → " + (newV == null ? "—" : newV);
    }

    /** Money is integer cents everywhere; format to a display string only here at the boundary. */
    private static String dollars(long cents) {
        return String.format("$%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /** Minimal HTML escaping for values interpolated into the inline templates. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
