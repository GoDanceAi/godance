package ai.godance.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.security.JwtService.IssuedToken;
import ai.godance.service.mail.MailService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.SignupRequest;

@Service
public class AuthService {

    private static final SecureRandom RNG = new SecureRandom();

    /** Shortest gap between two verification emails to the same account. */
    static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final AuditLogService audit;
    private final MailService mail;

    public AuthService(UserRepository users, PasswordEncoder passwords,
                       JwtService jwt, AuditLogService audit, MailService mail) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
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
     * then link into that account (see {@code OAuthLoginService}).
     *
     * <p>For an address being added (see {@link #addEmail}), this is the
     * point where it replaces the placeholder.
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
        if (u.getPendingEmail() != null) {
            // Someone may have signed up with it since it was requested.
            requireUnusedEmail(u.getPendingEmail());
            u.setEmail(u.getPendingEmail());
            u.setPendingEmail(null);
        }
        u.setEmailVerified(true);
        u.setEmailVerificationToken(null);
        users.save(u);
        audit.record(AuditLogService.EVT_AUTH_VERIFY, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, null);
    }

    /**
     * Send the signed-in user's verification email again, to the address
     * being added if there is one. The current token is kept, so a link
     * from an earlier email still works.
     */
    @Transactional
    public void resendVerificationEmail(UUID userId, String ipAddress) {
        User u = loadById(userId);
        if (u.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your email address is already verified.");
        }
        String to = u.getPendingEmail() != null ? u.getPendingEmail() : u.getEmail();
        if (to.endsWith(User.SYNTHETIC_EMAIL_SUFFIX)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account has no email address to verify.");
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
     * Start adding a real email to an account that only has a placeholder
     * (an X.com sign-in). The address waits in {@code pendingEmail} and
     * replaces the placeholder only when verified from this account's
     * session (see {@link #verifyEmail}). Until then it is not the
     * account's email, so it neither blocks the address's owner from
     * signing up nor lets their OAuth sign-in link into this account.
     */
    @Transactional
    public void addEmail(UUID userId, String email, String ipAddress) {
        User u = loadById(userId);
        if (!u.hasSyntheticEmail()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account already has an email address.");
        }
        String normalized = email.trim().toLowerCase();
        if (normalized.endsWith(User.SYNTHETIC_EMAIL_SUFFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter an email address you can receive mail at.");
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

        audit.record(AuditLogService.EVT_AUTH_EMAIL_ADD, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("email", normalized));
    }

    private void requireUnusedEmail(String email) {
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That email address is already used by another Godance account.");
        }
    }

    /** One verification email a minute per account, whatever triggers it. */
    private static void requireCooldownPassed(User u, Instant now) {
        Instant lastSent = u.getEmailVerificationSentAt();
        if (lastSent != null && lastSent.plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "A verification email was sent less than a minute ago. "
                            + "Check your inbox, or try again shortly.");
        }
    }

    @Transactional(readOnly = true)
    public User loadById(UUID id) {
        return users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
    }

    private static String randomToken() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
