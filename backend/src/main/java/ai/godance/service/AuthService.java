package ai.godance.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.security.JwtService.IssuedToken;
import ai.godance.security.WebhookSigner;
import ai.godance.service.mail.MailService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.ForgotPasswordRequest;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;
import ai.godance.web.dto.AuthDtos.SignupRequest;
import ai.godance.web.dto.TwoFactorDtos.EnableResponse;
import ai.godance.web.dto.TwoFactorDtos.EnrolResponse;

/**
 * Email + password accounts: signup, login, email verification and
 * password reset.
 *
 * <p>Signup takes any address unchecked, so someone can sign up with
 * another person's address and a password of their own. Verification
 * ({@link #verifyEmail}) can't be completed by either of them alone, so
 * that account stays unverified and Google / Apple sign-in won't link into
 * it ({@link OAuthLoginService}). Its address's owner gets it back with a
 * password reset ({@link #resetPassword}). Following the emailed link
 * proves the inbox, and the reset evicts whoever held the account before:
 * every earlier JWT stops working, and the API keys and webhook signing
 * secret are replaced.
 *
 * <p>That is why an account with a password must verify its email before
 * it can turn on two-factor sign-in. A reset never turns 2FA off, so a
 * squatter's authenticator would otherwise keep the owner out for good.
 */
@Service
public class AuthService {

    private static final SecureRandom RNG = new SecureRandom();

    /** Shortest gap between two verification emails, or two reset emails, to the same account. */
    static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);
    /** How long a verification link works after the latest email that carried it. */
    static final Duration VERIFICATION_TTL = Duration.ofHours(24);
    /** How long a password-reset link works. */
    static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

    /** How recently an account without a password must have signed in to change its email. */
    static final Duration RECENT_SIGN_IN = Duration.ofMinutes(10);

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final WebhookSigner webhooks;
    private final AuditLogService audit;
    private final MailService mail;
    private final ParentalConsentService parentalConsent;
    private final TwoFactorService twoFactor;

    public AuthService(UserRepository users, ApiKeyRepository apiKeys, PasswordEncoder passwords,
                       JwtService jwt, WebhookSigner webhooks, AuditLogService audit,
                       MailService mail, ParentalConsentService parentalConsent,
                       TwoFactorService twoFactor) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.passwords = passwords;
        this.jwt = jwt;
        this.webhooks = webhooks;
        this.audit = audit;
        this.mail = mail;
        this.parentalConsent = parentalConsent;
        this.twoFactor = twoFactor;
    }

    /**
     * Create a password account. With a {@code birthMonth} that makes the
     * person under {@link ParentalConsentService#CONSENT_AGE}, a parent's
     * email is required, the account starts
     * {@link AgeStatus#CONSENT_PENDING} (its JWT has the restricted scope),
     * and the parent, not the child, gets the first email. Without a birth
     * month (API clients) the account is an adult's: accepting the Terms
     * attests the age.
     */
    // noRollbackFor keeps the DENIED audit row. The duplicate check throws
    // before the user is saved, so the audit row is the only write it commits.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse signup(SignupRequest req, String ipAddress) {
        String email = req.email().trim().toLowerCase();
        Instant now = Instant.now();
        YearMonth birthMonth = parseBirthMonth(req.birthMonth(), now);
        boolean child = birthMonth != null
                && ParentalConsentService.needsConsent(birthMonth, LocalDate.ofInstant(now, ZoneOffset.UTC));
        String parentEmail = child ? requireParentEmail(req.parentEmail(), email) : null;
        if (users.existsByEmail(email)) {
            audit.record(AuditLogService.EVT_AUTH_SIGNUP, AuditOutcome.DENIED,
                    null, ipAddress, Map.of("email", email, "reason", "duplicate"));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }

        User u = new User();
        u.setEmail(email);
        u.setFullName(req.fullName().trim());
        u.setPasswordHash(passwords.encode(req.password()));
        u.setRole(UserRole.USER);
        u.setActive(true);
        u.setEmailVerified(false);
        u.setEmailVerificationToken(randomToken());
        u.setBirthMonth(birthMonth == null ? null : birthMonth.atDay(1));
        String consentToken = null;
        if (child) {
            // The verification link needs a full session to open, so it is
            // sent once a parent has approved (ParentalConsentService).
            consentToken = parentalConsent.requestConsent(u, parentEmail, now);
        } else {
            u.setEmailVerificationSentAt(now);
        }
        users.save(u);

        audit.record(AuditLogService.EVT_AUTH_SIGNUP, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("email", email, "ageStatus", u.getAgeStatus().name()));

        // Mail failures are swallowed inside MailService — a failed send must not fail signup.
        if (child) {
            parentalConsent.sendRequest(u, consentToken, ipAddress, "signup");
        } else {
            mail.sendVerificationEmail(u);
        }

        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    // noRollbackFor keeps the FAILURE / DENIED audit rows. Both throws come
    // before lastLoginAt is saved, so the audit row is the only write they commit.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse login(LoginRequest req, String ipAddress) {
        String email = req.email().trim().toLowerCase();
        Optional<User> maybe = users.findByEmail(email);
        if (maybe.isEmpty() || !passwords.matches(req.password(), maybe.get().getPasswordHash())) {
            audit.record(AuditLogService.EVT_AUTH_LOGIN_FAIL, AuditOutcome.FAILURE,
                    maybe.map(User::getId).orElse(null), ipAddress,
                    Map.of("email", email));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        User u = maybe.get();
        if (!u.isActive()) {
            audit.record(AuditLogService.EVT_AUTH_LOGIN_FAIL, AuditOutcome.DENIED,
                    u.getId(), ipAddress, Map.of("reason", "inactive"));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account disabled");
        }
        if (twoFactor.isEnabled(u.getId())) {
            return twoFactor.challenge(u, "password", ipAddress);
        }
        u.setLastLoginAt(Instant.now());
        users.save(u);

        audit.record(AuditLogService.EVT_AUTH_LOGIN_OK, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);

        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    /**
     * The second step of signing in, from a challenge: an authenticator
     * code or a recovery code buys the session. Disabled accounts never get
     * here, their challenge fails like any of their tokens
     * ({@link ai.godance.security.SessionVersionJwtDecoder}).
     *
     * <p>noRollbackFor keeps the failure count and audit row of a wrong code.
     * The account is read after the code is checked, which can wait on a
     * lock: saving it writes the whole row back, so it should be fresh.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse completeTwoFactorSignIn(UUID userId, String code, String ipAddress) {
        String method = twoFactor.verify(userId, code, ipAddress);
        User u = loadById(userId);
        u.setLastLoginAt(Instant.now());
        users.save(u);

        audit.record(AuditLogService.EVT_AUTH_LOGIN_OK, AuditOutcome.SUCCESS,
                userId, ipAddress, Map.of("secondFactor", method));

        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    /**
     * Start turning on two-factor sign-in. A session alone is not enough:
     * a stolen one could otherwise tie the account to the thief's
     * authenticator and keep its owner out.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EnrolResponse startTwoFactorEnrolment(UUID userId, String currentPassword,
                                                 Instant signedInAt, String ipAddress) {
        User u = loadById(userId);
        requireReauthenticated(u, currentPassword, signedInAt, ipAddress,
                AuditLogService.EVT_AUTH_2FA_ENROL, "turn on two-factor sign-in");
        if (u.getPasswordHash() != null && !u.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Verify your email address before you turn on two-factor sign-in.");
        }
        return twoFactor.startEnrolment(u, ipAddress);
    }

    /**
     * Finish enrolment with one code from the authenticator. Sessions
     * opened before this never passed the second factor, so all of them
     * end, and the caller gets a new one.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EnableResponse enableTwoFactor(UUID userId, String code, String ipAddress) {
        List<String> recoveryCodes = twoFactor.confirmEnrolment(userId, code, ipAddress);
        User u = loadById(userId);
        u.setSessionVersion(u.getSessionVersion() + 1);
        users.save(u);

        IssuedToken issued = jwt.issueFor(u);
        return new EnableResponse(recoveryCodes, AuthResponse.bearer(issued.token(), issued.expiresAt()));
    }

    /** Turn two-factor sign-in off: both factors again, as a stolen session must not be able to. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void disableTwoFactor(UUID userId, String currentPassword, String code,
                                 Instant signedInAt, String ipAddress) {
        User u = loadById(userId);
        requireReauthenticated(u, currentPassword, signedInAt, ipAddress,
                AuditLogService.EVT_AUTH_2FA_DISABLED, "turn off two-factor sign-in");
        twoFactor.disable(userId, code, ipAddress);
    }

    /**
     * Confirm the signed-in user's email. The token proves access to the
     * inbox; requiring that user's own session proves the same person
     * holds the account. Without it, the owner of an address could verify
     * an account someone else registered with it, and OAuth sign-in would
     * then link into that account (see {@code OAuthLoginService}). The
     * token expires {@link #VERIFICATION_TTL} after the latest email that
     * carried it.
     *
     * <p>For an address being added or changed to (see
     * {@link #requestEmailChange}), this is the point where it replaces the
     * account's email.
     */
    @Transactional
    public void verifyEmail(UUID userId, String token, String ipAddress) {
        User u = users.findByEmailVerificationToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid verification token"));
        if (!u.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This verification link is for a different account. "
                            + "Sign in to that account and open the link again.");
        }
        if (isExpired(u.getEmailVerificationSentAt(), VERIFICATION_TTL)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This verification link has expired. "
                            + "Send yourself a new one from the dashboard.");
        }
        String replacedEmail = null;
        if (u.getPendingEmail() != null) {
            // Someone may have signed up with it since it was requested.
            requireUnusedEmail(u.getPendingEmail());
            // Tell the old address only if it was verified: an unverified
            // one may be a stranger's (a typo at signup), and a placeholder
            // is never verified.
            if (u.isEmailVerified()) {
                replacedEmail = u.getEmail();
            }
            u.setEmail(u.getPendingEmail());
            u.setPendingEmail(null);
        }
        u.setEmailVerified(true);
        u.setEmailVerificationToken(null);
        users.save(u);
        if (replacedEmail != null) {
            mail.sendEmailChangedNotice(replacedEmail, u);
        }
        audit.record(AuditLogService.EVT_AUTH_VERIFY, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);
    }

    /**
     * Send the signed-in user's verification email again: to the address
     * being added or changed to if there is one, else to the account's own
     * unverified email. The current token is kept, so a link from an
     * earlier email still works.
     */
    @Transactional
    public void resendVerificationEmail(UUID userId, String ipAddress) {
        User u = loadById(userId);
        String to;
        if (u.getPendingEmail() != null) {
            to = u.getPendingEmail();
        } else if (u.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your email address is already verified.");
        } else if (u.hasSyntheticEmail()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account has no email address to verify.");
        } else {
            to = u.getEmail();
        }
        Instant now = Instant.now();
        requireCooldownPassed(u, now);
        if (u.getEmailVerificationToken() == null) {
            u.setEmailVerificationToken(randomToken());
        }
        u.setEmailVerificationSentAt(now);
        users.save(u);

        // Mail failures are swallowed inside MailService, as on signup.
        mail.sendVerificationEmail(u, to);

        audit.record(AuditLogService.EVT_AUTH_VERIFY_RESEND, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);
    }

    /**
     * Start changing the signed-in user's email, or adding a first real
     * one to an X.com placeholder account. The new address waits in
     * {@code pendingEmail} and replaces the current one only when verified
     * from this account's session (see {@link #verifyEmail}). Until then it
     * is not the account's email, so it neither blocks the address's owner
     * from signing up nor lets their OAuth sign-in link into this account.
     *
     * <p>A session alone is not enough. The account's email decides whose
     * OAuth sign-in can link in, so a stolen session could otherwise move
     * the account to an attacker's address for good. The caller proves
     * again who they are: with the current password, or, for an account
     * without one, by having signed in within {@link #RECENT_SIGN_IN}.
     */
    @Transactional
    public void requestEmailChange(UUID userId, String email, String currentPassword,
                                   Instant signedInAt, String ipAddress) {
        User u = loadById(userId);
        requireReauthenticated(u, currentPassword, signedInAt, ipAddress,
                AuditLogService.EVT_AUTH_EMAIL_CHANGE, "change your email");
        String normalized = email.trim().toLowerCase();
        if (normalized.endsWith(User.SYNTHETIC_EMAIL_SUFFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter an email address you can receive mail at.");
        }
        if (normalized.equals(u.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is already your email address.");
        }
        requireUnusedEmail(normalized);
        Instant now = Instant.now();
        requireCooldownPassed(u, now);

        u.setPendingEmail(normalized);
        // A new token per address, so a link sent to an earlier address
        // cannot confirm this one.
        u.setEmailVerificationToken(randomToken());
        u.setEmailVerificationSentAt(now);
        users.save(u);

        mail.sendVerificationEmail(u, normalized);

        audit.record(AuditLogService.EVT_AUTH_EMAIL_CHANGE, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("email", normalized));
    }

    /** {@code action} finishes the sentence "sign in again, then ...". */
    private void requireReauthenticated(User u, String currentPassword, Instant signedInAt,
                                        String ipAddress, String auditEvent, String action) {
        if (u.getPasswordHash() != null) {
            if (currentPassword == null || !passwords.matches(currentPassword, u.getPasswordHash())) {
                // Own transaction: the 403 rolls back the caller's.
                audit.recordInNewTransaction(auditEvent,
                        AuditOutcome.FAILURE, u.getId(), ipAddress, Map.of("reason", "bad-password"));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your current password is incorrect.");
            }
        } else if (signedInAt == null || signedInAt.plus(RECENT_SIGN_IN).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "For your security, sign in again, then " + action + " within "
                            + RECENT_SIGN_IN.toMinutes() + " minutes.");
        }
    }

    private void requireUnusedEmail(String email) {
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That email address is already used by another Godance account.");
        }
    }

    /** One verification email a minute per account, whatever triggers it. */
    private static void requireCooldownPassed(User u, Instant now) {
        if (!cooldownPassed(u, now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "A verification email was sent less than a minute ago. "
                            + "Check your inbox, or try again shortly.");
        }
    }

    private static boolean cooldownPassed(User u, Instant now) {
        Instant lastSent = u.getEmailVerificationSentAt();
        return lastSent == null || !lastSent.plus(RESEND_COOLDOWN).isAfter(now);
    }

    /**
     * Free an address from an account that never proved it owns it. The
     * token is the one from the verification email, so only someone who
     * reads that inbox can do this. Password signup takes any address, so
     * without it whoever registered someone else's address first would hold
     * it for good: the owner's signup would say it's taken, and their
     * Google or Apple sign-in would be refused (see {@code OAuthLoginService}).
     *
     * <p>A token sent to an address the account was changing to clears
     * that pending change. A token sent to the account's own unverified
     * email moves the account to a placeholder address, so the real one is
     * free to sign up with. The account itself, and anything in it, stays.
     */
    @Transactional
    public void releaseEmail(String token, String ipAddress) {
        User u = users.findByEmailVerificationToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid verification token"));
        if (isExpired(u.getEmailVerificationSentAt(), VERIFICATION_TTL)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This link has expired. You can ask for a new one from the sign-up page.");
        }
        String released;
        if (u.getPendingEmail() != null) {
            released = u.getPendingEmail();
            u.setPendingEmail(null);
        } else if (!u.isEmailVerified()) {
            released = u.getEmail();
            u.setEmail("released-" + u.getId() + User.SYNTHETIC_EMAIL_SUFFIX);
        } else {
            // A verified address has no outstanding token; refuse rather than guess.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This address has been verified by its account.");
        }
        u.setEmailVerificationToken(null);
        users.save(u);
        audit.record(AuditLogService.EVT_AUTH_EMAIL_RELEASE, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("email", released));
    }

    /**
     * Email a fresh verification link to {@code email} if an account holds
     * it without having verified it, for an owner who no longer has the
     * first email: the link lets them release the address (or, if the
     * account is theirs after all, sign in and verify). Does nothing, and
     * says nothing, otherwise: the caller isn't signed in, and the reply
     * must not tell them whether such an account exists. The one-minute
     * cooldown keeps it from being used to flood an inbox.
     */
    @Transactional
    public void sendReleaseLink(String email, String ipAddress) {
        User u = users.findByEmail(email.trim().toLowerCase()).orElse(null);
        if (u == null || u.isEmailVerified() || u.hasSyntheticEmail()) {
            return;
        }
        Instant now = Instant.now();
        if (!cooldownPassed(u, now)) {
            return;
        }
        if (u.getPendingEmail() != null || u.getEmailVerificationToken() == null) {
            // The link must stand for this address. A pending change's token
            // went to the address being changed to, so that change is dropped.
            u.setPendingEmail(null);
            u.setEmailVerificationToken(randomToken());
        }
        u.setEmailVerificationSentAt(now);
        users.save(u);

        mail.sendVerificationEmail(u, u.getEmail());

        audit.record(AuditLogService.EVT_AUTH_EMAIL_RELEASE_LINK, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);
    }

    /**
     * Email a password-reset link if {@code req.email()} belongs to an
     * active account. The caller answers the same whatever happens here,
     * so the response never says whether an address is registered.
     */
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest req, String ipAddress) {
        String email = req.email().trim().toLowerCase();
        User u = users.findByEmail(email).orElse(null);
        if (u == null) {
            audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_REQUESTED, AuditOutcome.DENIED,
                    null, ipAddress, Map.of("email", email, "reason", "unknown-email"));
            return;
        }
        if (!u.isActive()) {
            audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_REQUESTED, AuditOutcome.DENIED,
                    u.getId(), ipAddress, Map.of("reason", "inactive"));
            return;
        }
        Instant lastSent = u.getPasswordResetSentAt();
        if (lastSent != null && lastSent.plus(RESEND_COOLDOWN).isAfter(Instant.now())) {
            audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_REQUESTED, AuditOutcome.DENIED,
                    u.getId(), ipAddress, Map.of("reason", "cooldown"));
            return;
        }
        String token = randomToken();
        u.setPasswordResetTokenHash(sha256Hex(token));
        u.setPasswordResetSentAt(Instant.now());
        users.save(u);
        mail.sendPasswordResetEmail(u, token);
        audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_REQUESTED, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);
    }

    /**
     * End every session this account has: the bumped session version fails
     * each JWT issued so far, on every device.
     */
    @Transactional
    public void logout(UUID userId, String ipAddress) {
        User u = loadById(userId);
        u.setSessionVersion(u.getSessionVersion() + 1);
        users.save(u);
        audit.record(AuditLogService.EVT_AUTH_LOGOUT, AuditOutcome.SUCCESS, userId, ipAddress, null);
    }

    /**
     * Set a new password from an emailed reset link, and evict whoever held
     * the account before: bump the session version (every earlier JWT now
     * fails), delete the API keys, and rotate the webhook signing secret
     * with no grace window. Following the link proved the inbox, so the
     * email counts as verified. Returns a session for the new password,
     * or, with two-factor sign-in on, a challenge: 2FA stays on, and the
     * inbox alone must not buy a session.
     *
     * <p>noRollbackFor keeps the DENIED audit rows: both refusals throw
     * before anything else is written.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse resetPassword(ResetPasswordRequest req, String ipAddress) {
        User u = users.findByPasswordResetTokenHash(sha256Hex(req.token())).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid password reset link"));
        if (isExpired(u.getPasswordResetSentAt(), PASSWORD_RESET_TTL)) {
            audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_COMPLETED, AuditOutcome.DENIED,
                    u.getId(), ipAddress, Map.of("reason", "expired"));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This password reset link has expired. Request a new one.");
        }
        if (!u.isActive()) {
            audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_COMPLETED, AuditOutcome.DENIED,
                    u.getId(), ipAddress, Map.of("reason", "inactive"));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account disabled");
        }

        u.setPasswordHash(passwords.encode(req.newPassword()));
        u.setPasswordResetTokenHash(null);
        u.setEmailVerified(true);
        u.setEmailVerificationToken(null);
        // An address change the evicted holder started is theirs, not the owner's.
        u.setPendingEmail(null);
        u.setSessionVersion(u.getSessionVersion() + 1);
        u.setLastLoginAt(Instant.now());
        users.save(u);
        int keysRevoked = apiKeys.deleteByUserId(u.getId());
        webhooks.rotateSecretWithoutGrace(u.getId());

        audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_COMPLETED, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("apiKeysRevoked", keysRevoked));

        if (twoFactor.isEnabled(u.getId())) {
            return twoFactor.challenge(u, "password-reset", ipAddress);
        }
        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    /** {@code YYYY-MM} (format checked by the DTO), or null when not given. Not in the future. */
    private static YearMonth parseBirthMonth(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return null;
        YearMonth month = YearMonth.parse(raw.trim());
        if (month.isAfter(YearMonth.from(now.atZone(ZoneOffset.UTC)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your birth month can't be in the future.");
        }
        if (month.getYear() < 1900) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the year you were born.");
        }
        return month;
    }

    /** A child's parent's address: required, real, and not the child's own. */
    private static String requireParentEmail(String raw, String childEmail) {
        String parent = raw == null ? "" : raw.trim().toLowerCase();
        if (parent.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter a parent's or guardian's email address. We need their approval "
                            + "before you can use Godance.");
        }
        if (parent.equals(childEmail)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your parent's or guardian's email address must be different from yours.");
        }
        if (parent.endsWith(User.SYNTHETIC_EMAIL_SUFFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter an email address your parent or guardian can receive mail at.");
        }
        return parent;
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID id) {
        return MeResponse.from(loadById(id), twoFactor.isEnabled(id));
    }

    @Transactional(readOnly = true)
    public User loadById(UUID id) {
        return users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
    }

    /** A link with no issue time on record counts as expired. */
    private static boolean isExpired(Instant sentAt, Duration ttl) {
        return sentAt == null || Instant.now().isAfter(sentAt.plus(ttl));
    }

    private static String randomToken() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** What {@code users.password_reset_token_hash} (and the parental-consent token hash) stores. */
    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable; JVM is broken", e);
        }
    }
}
