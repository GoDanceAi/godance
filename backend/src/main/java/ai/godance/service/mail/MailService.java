package ai.godance.service.mail;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.service.ParentalNotice;

/**
 * The transactional email templates (the four from brief 02, plus
 * topup-payment-failed, password-reset and the two parental-consent
 * emails), inline — subject + text +
 * minimal HTML, no template engine. Call sites inject this facade and
 * never touch the {@link EmailSender} directly. Links point at the SPA
 * ({@link MailProperties#appBaseUrl()}).
 *
 * <p>Two invariants:
 * <ul>
 *   <li>Every text body's <b>first line</b> is exactly the message the
 *       old inline {@code [email-stub]} logs produced (a one-line summary
 *       for templates added since), so the {@link LoggingEmailSender}
 *       keeps dev output verbatim. (The verify-email line keeps its
 *       wording; its URL now opens the app.)</li>
 *   <li>No method here ever throws — mail failures must not propagate
 *       into signup, chat requests, auto-recharge, billing webhooks or
 *       the pricing cron.</li>
 * </ul>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final EmailSender sender;
    private final UserRepository users;
    private final MailProperties props;

    public MailService(EmailSender sender, UserRepository users, MailProperties props) {
        this.sender = sender;
        this.users = users;
        this.props = props;
    }

    /**
     * verify-email — sent on signup, to the account's email. The link only
     * verifies when opened signed in to the account. Someone who never
     * signed up can ignore it, or open it and release the address from
     * that account; someone who did but forgot the password can reset it.
     */
    public void sendVerificationEmail(User user) {
        sendVerificationEmail(user, user.getEmail());
    }

    /**
     * verify-email to {@code toEmail}: the account's email on a resend, or
     * the address the account is changing to (or adding, for an X.com
     * sign-in's placeholder account).
     */
    public void sendVerificationEmail(User user, String toEmail) {
        try {
            String link = props.appBaseUrl() + "/verify-email?token=" + user.getEmailVerificationToken();
            String reset = props.appBaseUrl() + "/forgot-password";
            String stubLine = "Verification URL for " + toEmail + ": " + link;
            String text = stubLine + "\n\n"
                    + "Hi " + user.getFullName() + ",\n\n"
                    + "Welcome to Godance. Please confirm this email address by opening the\n"
                    + "verification link above in the Godance app, signed in to this account.\n"
                    + "The link works for 24 hours.\n\n"
                    + "Didn't sign up? Someone may have entered your address by mistake. You\n"
                    + "can ignore this email: the account stays unverified, and the link does\n"
                    + "nothing unless it is opened signed in to that account. To use Godance\n"
                    + "with this address yourself, open the link and release the address from\n"
                    + "that account; nothing in it is shared with you.\n\n"
                    + "Signed up but forgot your password? Reset it at " + reset + "\n";
            String html = "<p>Hi " + escape(user.getFullName()) + ",</p>"
                    + "<p>Welcome to Godance. Please confirm this email address by opening "
                    + "<a href=\"" + escape(link) + "\">this link</a> in the Godance app, signed "
                    + "in to this account. The link works for 24 hours.</p>"
                    + "<p><b>Didn't sign up?</b> Someone may have entered your address by mistake. "
                    + "You can ignore this email: the account stays unverified, and the link does "
                    + "nothing unless it is opened signed in to that account. To use Godance with "
                    + "this address yourself, open the link and release the address from that "
                    + "account; nothing in it is shared with you.</p>"
                    + "<p>Signed up but forgot your password? <a href=\"" + escape(reset) + "\">Reset "
                    + "it</a>.</p>";
            sender.send(List.of(toEmail), "Verify your Godance email", text, html);
        } catch (Exception e) {
            log.error("verify-email build/send failed for {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * password-reset — {@code token} is the plaintext; only its hash is
     * stored, so this email is the one place it exists.
     */
    public void sendPasswordResetEmail(User user, String token) {
        try {
            String link = props.appBaseUrl() + "/reset-password?token=" + token;
            String stubLine = "Password reset URL for " + user.getEmail() + ": " + link;
            String text = stubLine + "\n\n"
                    + "Someone asked to reset the password of the Godance account that uses\n"
                    + "this address. To choose a new password, open the link above within\n"
                    + "an hour.\n\n"
                    + "Resetting signs out every session and revokes the account's API keys\n"
                    + "and webhook signing secret, so whoever knew the old password loses\n"
                    + "access.\n\n"
                    + "Didn't ask for this? Ignore this email; nothing changes.\n";
            String html = "<p>Someone asked to reset the password of the Godance account that "
                    + "uses this address. To choose a new password, open "
                    + "<a href=\"" + escape(link) + "\">this link</a> within an hour.</p>"
                    + "<p>Resetting signs out every session and revokes the account's API keys "
                    + "and webhook signing secret, so whoever knew the old password loses "
                    + "access.</p>"
                    + "<p>Didn't ask for this? Ignore this email; nothing changes.</p>";
            sender.send(List.of(user.getEmail()), "Reset your Godance password", text, html);
        } catch (Exception e) {
            log.error("password-reset build/send failed for {}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * parental-consent-request — to the parent address a child under 16
     * gave at signup. Carries the direct notice ({@link ParentalNotice})
     * and the single-use approval link; {@code token} is the plaintext,
     * only its hash is stored. The approval itself happens on the page the
     * link opens (a paid top-up on the child's account).
     */
    public void sendParentalConsentRequest(User child, String token, Duration linkLifetime) {
        try {
            String to = child.getParentEmail();
            String link = props.appBaseUrl() + "/parent-consent?token=" + token;
            String name = firstName(child);
            ParentalNotice notice = ParentalNotice.CURRENT;
            long days = linkLifetime.toDays();
            String stubLine = "Parental consent URL for " + to + ": " + link;

            StringBuilder text = new StringBuilder(stubLine).append("\n\n")
                    .append("Hello,\n\n")
                    .append(name).append(" signed up for Godance, an app that answers questions and\n")
                    .append("makes images and videos with AI, and gave us your email address as their\n")
                    .append("parent or guardian. We use it only to ask for your consent.\n\n")
                    .append("Because they are a child, Godance needs your consent before it collects or\n")
                    .append("uses their personal information. Until you approve, their account is locked\n")
                    .append("and they can't use it.\n\n")
                    .append("WHAT GODANCE COLLECTS\n");
            StringBuilder items = new StringBuilder();
            for (String item : notice.collected()) {
                text.append("- ").append(item).append('\n');
                items.append("<li>").append(escape(item)).append("</li>");
            }
            text.append(notice.notStored()).append("\n\n")
                    .append("WHO ELSE SEES IT\n")
                    .append(notice.sharing()).append("\n")
                    .append(notice.noSale()).append("\n\n")
                    .append("YOUR CHOICES\n")
                    .append(notice.parentRights()).append("\n")
                    .append("Full privacy policy: ").append(notice.privacyPolicyUrl()).append("\n\n")
                    .append("HOW TO APPROVE\n")
                    .append("Open the link at the top of this email. You approve by adding credit to\n")
                    .append(name).append("'s account with your own card, an amount you choose. The card\n")
                    .append("payment is how we confirm that a parent is approving. The link works once,\n")
                    .append("for ").append(days).append(" days.\n\n")
                    .append("Not your child, or you don't approve? Ignore this email and the account stays\n")
                    .append("locked. To have it deleted, email ").append(notice.contactEmail()).append(".\n");

            String html = "<p>Hello,</p>"
                    + "<p>" + escape(name) + " signed up for Godance, an app that answers questions and "
                    + "makes images and videos with AI, and gave us your email address as their parent "
                    + "or guardian. We use it only to ask for your consent.</p>"
                    + "<p>Because they are a child, Godance needs your consent before it collects or uses "
                    + "their personal information. Until you approve, their account is locked and they "
                    + "can't use it.</p>"
                    + "<h3>What Godance collects</h3><ul>" + items + "</ul>"
                    + "<p>" + escape(notice.notStored()) + "</p>"
                    + "<h3>Who else sees it</h3>"
                    + "<p>" + escape(notice.sharing()) + "</p>"
                    + "<p>" + escape(notice.noSale()) + "</p>"
                    + "<h3>Your choices</h3>"
                    + "<p>" + escape(notice.parentRights()) + " Read the full "
                    + "<a href=\"" + escape(notice.privacyPolicyUrl()) + "\">privacy policy</a>.</p>"
                    + "<h3>How to approve</h3>"
                    + "<p><a href=\"" + escape(link) + "\">Open the approval page</a>. You approve by "
                    + "adding credit to " + escape(name) + "'s account with your own card, an amount you "
                    + "choose. The card payment is how we confirm that a parent is approving. The link "
                    + "works once, for " + days + " days.</p>"
                    + "<p>Not your child, or you don't approve? Ignore this email and the account stays "
                    + "locked. To have it deleted, email " + escape(notice.contactEmail()) + ".</p>";
            sender.send(List.of(to), "Your child asked to use Godance: your consent is needed",
                    text.toString(), html);
        } catch (Exception e) {
            log.error("parental-consent-request build/send failed for {}: {}", child.getId(), e.getMessage());
        }
    }

    /** parental-consent-granted — to the child, once the parent's payment has cleared. */
    public void sendParentalConsentGranted(User child) {
        try {
            String login = props.appBaseUrl() + "/login";
            String stubLine = "Parental consent granted for " + child.getEmail();
            String text = stubLine + "\n\n"
                    + "Hi " + firstName(child) + ",\n\n"
                    + "Your parent approved your Godance account, so it's ready to use.\n"
                    + "Sign in again to start: " + login + "\n\n"
                    + "We've also sent you a link to confirm your email address.\n";
            String html = "<p>Hi " + escape(firstName(child)) + ",</p>"
                    + "<p>Your parent approved your Godance account, so it's ready to use. "
                    + "<a href=\"" + escape(login) + "\">Sign in again</a> to start.</p>"
                    + "<p>We've also sent you a link to confirm your email address.</p>";
            sender.send(List.of(child.getEmail()), "Your Godance account is ready", text, html);
        } catch (Exception e) {
            log.error("parental-consent-granted build/send failed for {}: {}", child.getId(), e.getMessage());
        }
    }

    private static String firstName(User u) {
        String full = u.getFullName() == null ? "" : u.getFullName().strip();
        return full.isEmpty() ? "Your child" : full.split("\\s+", 2)[0];
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

    /**
     * email-changed — to the account's previous (verified) address once a
     * change to {@code user}'s current email has been verified, so a change
     * the owner didn't make doesn't go unnoticed.
     */
    public void sendEmailChangedNotice(String previousEmail, User user) {
        try {
            String stubLine = "Email changed for " + previousEmail + ": now " + user.getEmail();
            String text = stubLine + "\n\n"
                    + "Hi " + user.getFullName() + ",\n\n"
                    + "The email address on your Godance account was changed from\n"
                    + previousEmail + " to " + user.getEmail() + ".\n\n"
                    + "If you didn't make this change, email hello@godance.ai right away.\n";
            String html = "<p>Hi " + escape(user.getFullName()) + ",</p>"
                    + "<p>The email address on your Godance account was changed from <b>"
                    + escape(previousEmail) + "</b> to <b>" + escape(user.getEmail()) + "</b>.</p>"
                    + "<p>If you didn't make this change, email hello@godance.ai right away.</p>";
            sender.send(List.of(previousEmail), "Your Godance email address was changed", text, html);
        } catch (Exception e) {
            log.error("email-changed build/send failed for {}: {}", user.getId(), e.getMessage());
        }
    }

    /** What a two-factor notice tells the owner. */
    public enum TwoFactorNotice {
        ENABLED("Two-factor sign-in is on for your Godance account",
                "Two-factor sign-in was turned on for your Godance account. "
                        + "Signing in now needs a code from an authenticator app."),
        DISABLED("Two-factor sign-in is off for your Godance account",
                "Two-factor sign-in was turned off for your Godance account."),
        RECOVERY_CODE_USED("A recovery code was used on your Godance account",
                "A recovery code was used to sign in to your Godance account."),
        LOCKED("Someone may have your Godance password",
                "Too many wrong codes were entered after the right password for your Godance "
                        + "account, so signing in is paused for 15 minutes. If this wasn't you, "
                        + "reset your password now."),
        RESET_BY_SUPPORT("Two-factor sign-in was reset on your Godance account",
                "Godance support turned off two-factor sign-in on your account and signed it "
                        + "out everywhere.");

        private final String subject;
        private final String body;

        TwoFactorNotice(String subject, String body) {
            this.subject = subject;
            this.body = body;
        }
    }

    /**
     * two-factor-notice — tells the owner about any change to their second
     * factor. A thief who enrols on a stolen account can't be evicted by a
     * password reset, so this email is how the owner finds out and asks for
     * help. An account with only a placeholder address gets nothing.
     */
    public void sendTwoFactorNotice(User user, TwoFactorNotice notice) {
        if (user.hasSyntheticEmail()) return;
        try {
            String stubLine = "Two-factor notice for " + user.getEmail() + ": " + notice.name();
            String text = stubLine + "\n\n"
                    + "Hi " + user.getFullName() + ",\n\n"
                    + notice.body + "\n\n"
                    + "If you didn't do this, email hello@godance.ai right away.\n";
            String html = "<p>Hi " + escape(user.getFullName()) + ",</p>"
                    + "<p>" + escape(notice.body) + "</p>"
                    + "<p>If you didn't do this, email hello@godance.ai right away.</p>";
            sender.send(List.of(user.getEmail()), notice.subject, text, html);
        } catch (Exception e) {
            log.error("two-factor notice build/send failed for {}: {}", user.getId(), e.getMessage());
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
     * the pending proposals, to everyone who can approve them: the active,
     * verified ADMIN accounts. Skips silently when there is nothing
     * pending or no such account.
     */
    public void sendPricingProposalsDigest(List<ModelChangeProposal> pending) {
        try {
            if (pending == null || pending.isEmpty()) return;
            List<String> to = users.findActiveVerifiedEmailsByRole(UserRole.ADMIN);
            if (to.isEmpty()) {
                log.debug("pricing digest skipped: no admin account to send it to");
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

    /** Minimal HTML escaping for values interpolated into the inline templates (text and attributes). */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
