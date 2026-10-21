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

    /** How recently an account without a password must have signed in to change its email. */
    static final Duration RECENT_SIGN_IN = Duration.ofMinutes(10);

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
     * Mark the caller's email verified. The token proves the inbox and the
     * caller's session proves the account; both are required. Signup takes
     * any address, so whoever reads the inbox may not be whoever set the
     * password. Accepting the token alone would let the inbox owner confirm
     * an account someone else pre-registered in their name, and a later
     * Google or Apple sign-in would then link into it.
     *
     * <p>For an address being added or changed to (see
     * {@link #requestEmailChange}), this is the point where it replaces the
     * account's email.
     */
    @Transactional
    public void verifyEmail(String token, UUID userId, String ipAddress) {
        User u = users.findByEmailVerificationToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid verification token"));
        if (!u.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This verification link is for a different account. "
                            + "Sign in to that account, then open the link again.");
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
        requireReauthenticated(u, currentPassword, signedInAt, ipAddress);
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

    private void requireReauthenticated(User u, String currentPassword, Instant signedInAt,
                                        String ipAddress) {
        if (u.getPasswordHash() != null) {
            if (currentPassword == null || !passwords.matches(currentPassword, u.getPasswordHash())) {
                // Own transaction: the 403 rolls back the caller's.
                audit.recordInNewTransaction(AuditLogService.EVT_AUTH_EMAIL_CHANGE,
                        AuditOutcome.FAILURE, u.getId(), ipAddress, Map.of("reason", "bad-password"));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your current password is incorrect.");
            }
        } else if (signedInAt == null || signedInAt.plus(RECENT_SIGN_IN).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "For your security, sign in again, then change your email within "
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
