package ai.godance.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;
import ai.godance.web.dto.AuthDtos.SignupRequest;

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

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final WebhookSigner webhooks;
    private final AuditLogService audit;
    private final MailService mail;

    public AuthService(UserRepository users, ApiKeyRepository apiKeys, PasswordEncoder passwords,
                       JwtService jwt, WebhookSigner webhooks, AuditLogService audit,
                       MailService mail) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.passwords = passwords;
        this.jwt = jwt;
        this.webhooks = webhooks;
        this.audit = audit;
        this.mail = mail;
    }

    // noRollbackFor keeps the DENIED audit row. The duplicate check throws
    // before the user is saved, so the audit row is the only write it commits.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse signup(SignupRequest req, String ipAddress) {
        String email = req.email().trim().toLowerCase();
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
        u.setEmailVerificationSentAt(Instant.now());
        users.save(u);

        // Mail failures are swallowed inside MailService — a failed send must not fail signup.
        mail.sendVerificationEmail(u);

        audit.record(AuditLogService.EVT_AUTH_SIGNUP, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("email", email));

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
        u.setLastLoginAt(Instant.now());
        users.save(u);

        audit.record(AuditLogService.EVT_AUTH_LOGIN_OK, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);

        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    /**
     * Confirm the signed-in user's email. The token proves access to the
     * inbox; requiring that user's own session proves the same person
     * holds the account. Without it, the owner of an address could verify
     * an account someone else registered with it, and OAuth sign-in would
     * then link into that account (see {@code OAuthLoginService}). The
     * token expires {@link #VERIFICATION_TTL} after the latest email that
     * carried it.
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
        u.setEmailVerified(true);
        u.setEmailVerificationToken(null);
        users.save(u);
        audit.record(AuditLogService.EVT_AUTH_VERIFY, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);
    }

    /**
     * Send the signed-in user's verification email again. The current
     * token is kept, so a link from an earlier email still works.
     */
    @Transactional
    public void resendVerificationEmail(UUID userId, String ipAddress) {
        User u = loadById(userId);
        if (u.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your email address is already verified.");
        }
        if (u.hasSyntheticEmail()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account has no email address to verify.");
        }
        Instant now = Instant.now();
        Instant lastSent = u.getEmailVerificationSentAt();
        if (lastSent != null && lastSent.plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "A verification email was sent less than a minute ago. "
                            + "Check your inbox, or try again shortly.");
        }
        if (u.getEmailVerificationToken() == null) {
            u.setEmailVerificationToken(randomToken());
        }
        u.setEmailVerificationSentAt(now);
        users.save(u);

        // Mail failures are swallowed inside MailService, as on signup.
        mail.sendVerificationEmail(u);

        audit.record(AuditLogService.EVT_AUTH_VERIFY_RESEND, AuditOutcome.SUCCESS,
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
     * Set a new password from an emailed reset link, and evict whoever held
     * the account before: bump the session version (every earlier JWT now
     * fails), delete the API keys, and rotate the webhook signing secret
     * with no grace window. Following the link proved the inbox, so the
     * email counts as verified. Returns a session for the new password.
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
        u.setSessionVersion(u.getSessionVersion() + 1);
        u.setLastLoginAt(Instant.now());
        users.save(u);
        int keysRevoked = apiKeys.deleteByUserId(u.getId());
        webhooks.rotateSecretWithoutGrace(u.getId());

        audit.record(AuditLogService.EVT_AUTH_PASSWORD_RESET_COMPLETED, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("apiKeysRevoked", keysRevoked));

        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
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

    /** What {@code users.password_reset_token_hash} stores for a reset token. */
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
