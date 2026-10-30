package ai.godance.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;

import ai.godance.config.BillingProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import ai.godance.service.BillingService.TopUpIntent;
import ai.godance.service.mail.MailProperties;
import ai.godance.service.mail.MailService;
import ai.godance.web.dto.AuthDtos;
import ai.godance.web.dto.ParentalConsentDtos.ParentalConsentView;

/**
 * Verifiable parental consent for children's accounts.
 *
 * <p>Anyone under {@link #CONSENT_AGE} at signup gets a
 * {@link AgeStatus#CONSENT_PENDING} account, whose JWT admits almost
 * nothing (see {@code SecurityConfig}), and their parent gets an email with
 * a single-use link ({@link #requestConsent}). The parent approves by paying
 * a top-up on the child's account with their own card through Stripe
 * Checkout ({@link #startCheckout}). Prompts go to third-party AI providers,
 * so "email plus" is not enough; a card transaction is. The paid webhook
 * then grants consent ({@link #grantFromPayment}).
 *
 * <p>One global threshold, 16: the highest age GDPR Art. 8 lets a member
 * state set, so no country detection is needed, and it covers COPPA's 13.
 */
@Service
public class ParentalConsentService {

    private static final Logger log = LoggerFactory.getLogger(ParentalConsentService.class);
    private static final SecureRandom RNG = new SecureRandom();

    /** Below this age an account needs a parent's consent. */
    public static final int CONSENT_AGE = 16;
    /** How long a consent link works after the email that carried it. */
    static final Duration CONSENT_TTL = Duration.ofDays(7);
    /** Shortest gap between two consent emails for the same child. */
    static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository users;
    private final StripeService stripe;
    private final BillingProperties billing;
    private final MailService mail;
    private final MailProperties mailProps;
    private final AuditLogService audit;

    public ParentalConsentService(UserRepository users, StripeService stripe, BillingProperties billing,
                                  MailService mail, MailProperties mailProps, AuditLogService audit) {
        this.users = users;
        this.stripe = stripe;
        this.billing = billing;
        this.mail = mail;
        this.mailProps = mailProps;
        this.audit = audit;
    }

    /**
     * True when someone born in {@code birthMonth} may be under
     * {@link #CONSENT_AGE} on {@code today}. Without the day of birth, the
     * whole birth month counts as before the birthday: they count as 16
     * from the first day of the month after it, never early.
     */
    public static boolean needsConsent(YearMonth birthMonth, LocalDate today) {
        LocalDate surelySixteen = birthMonth.plusYears(CONSENT_AGE).plusMonths(1).atDay(1);
        return today.isBefore(surelySixteen);
    }

    /**
     * Put a new child's account in {@link AgeStatus#CONSENT_PENDING} and
     * mint the consent token. Call before the account is saved; send the
     * returned plaintext with {@link #sendRequest} after. Only its hash is
     * stored, as with password-reset tokens.
     */
    public String requestConsent(User child, String parentEmail, Instant now) {
        String token = randomToken();
        child.setAgeStatus(AgeStatus.CONSENT_PENDING);
        child.setParentEmail(parentEmail);
        child.setParentalConsentTokenHash(AuthService.sha256Hex(token));
        child.setParentalConsentRequestedAt(now);
        return token;
    }

    /** Email the parent the notice and the link. Mail failures are swallowed inside MailService. */
    public void sendRequest(User child, String token, String ipAddress, String reason) {
        mail.sendParentalConsentRequest(child, token, CONSENT_TTL);
        audit.record(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_REQUESTED, AuditOutcome.SUCCESS,
                child.getId(), ipAddress, Map.of("parentEmail", child.getParentEmail(), "reason", reason));
    }

    /**
     * The waiting child asks for the email again: a new link (the old one
     * stops working, since only one token hash is kept) and a new
     * {@link #CONSENT_TTL}. At most one a minute, like the verification
     * resend; the controller also applies that resend's rate limits.
     */
    @Transactional
    public void resend(UUID childId, String ipAddress) {
        User child = users.findById(childId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (child.getAgeStatus() != AgeStatus.CONSENT_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account doesn't need a parent's approval.");
        }
        Instant now = Instant.now();
        Instant last = child.getParentalConsentRequestedAt();
        if (last != null && last.plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "We emailed your parent less than a minute ago. Ask them to check their inbox, "
                            + "or try again shortly.");
        }
        String token = requestConsent(child, child.getParentEmail(), now);
        users.save(child);
        sendRequest(child, token, ipAddress, "resend");
    }

    /** What the consent page shows for {@code token}. */
    @Transactional(readOnly = true)
    public ParentalConsentView view(String token) {
        User child = pendingChildFor(token);
        return new ParentalConsentView(
                firstName(child.getFullName()),
                AuthDtos.maskEmail(child.getEmail()),
                billing.topupMinCents(),
                billing.topupMaxCents(),
                billing.currency(),
                child.getParentalConsentRequestedAt().plus(CONSENT_TTL),
                ParentalNotice.CURRENT);
    }

    /**
     * Start the Checkout Session the parent pays to approve: a top-up on
     * the child's account, paid by the parent ({@code customer_email}),
     * marked as parental consent in its metadata. The token stays valid
     * until a payment clears, so a cancelled checkout can be retried.
     */
    @Transactional
    public TopUpIntent startCheckout(String token, long amountCents, String ipAddress) {
        if (amountCents < billing.topupMinCents() || amountCents > billing.topupMaxCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amountCents must be between " + billing.topupMinCents()
                            + " and " + billing.topupMaxCents());
        }
        User child = pendingChildFor(token);
        String page = mailProps.appBaseUrl() + "/parent-consent";
        try {
            Session session = stripe.createParentalConsentCheckout(child, child.getParentEmail(),
                    amountCents, page + "?status=approved", page + "?status=cancelled");
            audit.record(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_CHECKOUT, AuditOutcome.SUCCESS,
                    child.getId(), ipAddress, Map.of("amountCents", amountCents, "sessionId", session.getId()));
            return new TopUpIntent(session.getId(), session.getUrl(), amountCents);
        } catch (StripeException e) {
            log.warn("Stripe parental-consent checkout failed for user {}: {}", child.getId(), e.getMessage());
            audit.record(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_CHECKOUT, AuditOutcome.FAILURE,
                    child.getId(), ipAddress, Map.of("amountCents", amountCents, "error", e.getMessage()));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment provider unavailable");
        }
    }

    /**
     * The parent's approving payment has cleared (called from the Stripe
     * webhook, inside its transaction, once the top-up is credited): grant
     * consent, clear the token, end the child's restricted sessions, and
     * tell the child. Does nothing for an account that isn't waiting (a
     * second paid session, a redelivered event), so it is safe to call
     * for every paid consent session.
     */
    public void grantFromPayment(UUID childId, String sessionId, String eventId, long amountCents,
                                 String ipAddress) {
        Instant now = Instant.now();
        if (users.grantParentalConsent(childId, now) == 0) {
            return;
        }
        User child = users.findById(childId).orElseThrow();
        audit.record(AuditLogService.EVT_AUTH_PARENTAL_CONSENT_GRANTED, AuditOutcome.SUCCESS,
                childId, ipAddress, Map.of(
                        "parentEmail", String.valueOf(child.getParentEmail()),
                        "sessionId", sessionId,
                        "eventId", eventId,
                        "amountCents", amountCents));
        // The verification email was held back while the account was
        // restricted: its link needs a full session to open.
        if (!child.isEmailVerified() && !child.hasSyntheticEmail()
                && child.getEmailVerificationToken() != null) {
            child.setEmailVerificationSentAt(now);
            users.save(child);
            mail.sendVerificationEmail(child);
        }
        mail.sendParentalConsentGranted(child);
    }

    /** The child waiting on {@code token}: 404 for an unknown or used link, 410 for an expired one. */
    private User pendingChildFor(String token) {
        User child = users.findByParentalConsentTokenHash(AuthService.sha256Hex(token))
                .filter(u -> u.getAgeStatus() == AgeStatus.CONSENT_PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This approval link isn't valid. It may have been used already, "
                                + "or a newer one may have been sent."));
        Instant requested = child.getParentalConsentRequestedAt();
        if (requested == null || Instant.now().isAfter(requested.plus(CONSENT_TTL))) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This approval link has expired. Ask your child to send a new one "
                            + "from the Godance app.");
        }
        return child;
    }

    private static String firstName(String fullName) {
        String full = fullName == null ? "" : fullName.strip();
        return full.isEmpty() ? "Your child" : full.split("\\s+", 2)[0];
    }

    private static String randomToken() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
