package ai.godance.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.domain.UserTwoFactor;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.security.JwtService;
import ai.godance.security.JwtService.IssuedToken;
import ai.godance.security.KeyVault;
import ai.godance.security.RateLimitExceededException;
import ai.godance.security.Totp;
import ai.godance.service.mail.MailService;
import ai.godance.service.mail.MailService.TwoFactorNotice;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.TwoFactorDtos.EnrolResponse;
import ai.godance.web.dto.TwoFactorDtos.StatusResponse;

/**
 * Two-factor sign-in with an authenticator app (TOTP, RFC 6238), for any
 * account, password or social. It is optional except for staff, whom
 * {@link ai.godance.security.AdminAuthorizationFilter} refuses without it.
 *
 * <p>With 2FA on, nothing that proves only the first factor may issue a
 * session. Password login, social sign-in and a password reset each hand
 * back a {@link #challenge} instead, and the session comes from
 * {@link AuthService#completeTwoFactorSignIn}. A new way to sign in must do
 * the same, or it is a way around 2FA.
 *
 * <p>Wrong codes are limited twice. {@link ai.godance.security.AuthRateLimits}
 * counts every attempt per IP and account, but fails open if Redis is down.
 * The lockout here, after {@link #MAX_FAILED_ATTEMPTS} wrong codes, is in the
 * database, so it holds then too. All six digits are guessable given time,
 * so it must not depend on Redis being up.
 *
 * <p>Every code is checked on the row locked by
 * {@link UserTwoFactorRepository#findForUpdate}, so concurrent attempts can't
 * spend one code twice or undercount failures. The callers are marked
 * {@code noRollbackFor}: a refused code must still commit its count and its
 * audit row.
 *
 * <p>A password reset leaves 2FA on, so it no longer evicts a thief who
 * enrolled on the account. The owner is emailed about every change to their
 * second factor, and {@link #resetByOperator} is the way back in.
 */
@Service
public class TwoFactorService {

    /** Consecutive wrong codes that lock the account's second factor. */
    static final int MAX_FAILED_ATTEMPTS = 5;
    /** How long that lock lasts. A right code is refused too until it ends. */
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    /** Steps either side of now to accept, for clock drift: at most three live codes. */
    private static final int DRIFT_STEPS = 1;

    private static final int SECRET_BYTES = 20;
    private static final int RECOVERY_CODE_COUNT = 10;
    /** 80 random bits, 16 Base32 characters. Too many to guess, so a fast hash is enough. */
    private static final int RECOVERY_CODE_BYTES = 10;
    private static final Pattern AUTHENTICATOR_CODE = Pattern.compile("\\d{6}");
    private static final String ISSUER = "Godance";

    private static final String METHOD_TOTP = "totp";
    private static final String METHOD_RECOVERY = "recovery-code";

    private static final SecureRandom RNG = new SecureRandom();

    private final UserTwoFactorRepository twoFactors;
    private final UserRepository users;
    private final KeyVault vault;
    private final JwtService jwt;
    private final AuditLogService audit;
    private final MailService mail;

    public TwoFactorService(UserTwoFactorRepository twoFactors, UserRepository users, KeyVault vault,
                            JwtService jwt, AuditLogService audit, MailService mail) {
        this.twoFactors = twoFactors;
        this.users = users;
        this.vault = vault;
        this.jwt = jwt;
        this.audit = audit;
        this.mail = mail;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return twoFactors.existsByUserIdAndEnabledAtIsNotNull(userId);
    }

    /**
     * What a first factor earns when 2FA is on: a short-lived token that
     * only {@code POST /v1/auth/2fa/verify} accepts. {@code via} says which
     * first factor, for the audit row.
     */
    public AuthResponse challenge(User u, String via, String ipAddress) {
        audit.record(AuditLogService.EVT_AUTH_2FA_CHALLENGE, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("via", via));
        IssuedToken issued = jwt.issueTwoFactorChallenge(u);
        return AuthResponse.twoFactor(issued.token(), issued.expiresAt());
    }

    /** A new secret, replacing any earlier unconfirmed one. 2FA stays off until it is confirmed. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EnrolResponse startEnrolment(User u, String ipAddress) {
        UUID userId = u.getId();
        UserTwoFactor row = twoFactors.findForUpdate(userId).orElseGet(UserTwoFactor::new);
        if (row.getEnabledAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Two-factor sign-in is already on.");
        }
        byte[] secret = new byte[SECRET_BYTES];
        RNG.nextBytes(secret);
        row.setUserId(userId);
        row.setSecretEncrypted(vault.encrypt(secret, context(userId)));
        row.setLastUsedStep(0L);
        row.setFailedAttempts(0);
        row.setLockedUntil(null);
        twoFactors.save(row);

        audit.record(AuditLogService.EVT_AUTH_2FA_ENROL, AuditOutcome.SUCCESS, userId, ipAddress, null);
        String base32 = Totp.base32(secret);
        return new EnrolResponse(base32, otpauthUri(base32, accountLabel(u)));
    }

    /**
     * Confirm enrolment with one code from the app, and turn 2FA on.
     * Returns the recovery codes in plain text: the only time they exist
     * outside the user's hands.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public List<String> confirmEnrolment(UUID userId, String code, String ipAddress) {
        UserTwoFactor row = twoFactors.findForUpdate(userId).orElseThrow(TwoFactorService::noEnrolment);
        if (row.getEnabledAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Two-factor sign-in is already on.");
        }
        Instant now = Instant.now();
        requireNotLocked(row, now, ipAddress, AuditLogService.EVT_AUTH_2FA_ENABLED);
        if (!spendAuthenticatorCode(row, normalize(code), now)) {
            throw refused(row, now, ipAddress, AuditLogService.EVT_AUTH_2FA_ENABLED);
        }
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<String> hashes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            byte[] raw = new byte[RECOVERY_CODE_BYTES];
            RNG.nextBytes(raw);
            String recovery = Totp.base32(raw);
            plain.add(recovery.replaceAll("(.{4})(?=.)", "$1-"));
            hashes.add(recoveryHash(userId, recovery));
        }
        row.setRecoveryCodeHashes(hashes);
        row.setEnabledAt(now);
        row.setFailedAttempts(0);
        twoFactors.save(row);

        audit.record(AuditLogService.EVT_AUTH_2FA_ENABLED, AuditOutcome.SUCCESS, userId, ipAddress, null);
        notifyOwner(userId, TwoFactorNotice.ENABLED);
        return plain;
    }

    /**
     * Check the second factor of a sign-in. Returns how it was proved, for
     * the audit row; refuses with 403, or 429 while locked.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public String verify(UUID userId, String code, String ipAddress) {
        UserTwoFactor row = twoFactors.findForUpdate(userId)
                .filter(r -> r.getEnabledAt() != null)
                // Turned off since this challenge was issued. Signing in again gives a session.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in again"));
        String method = spendCode(row, code, ipAddress, AuditLogService.EVT_AUTH_2FA_VERIFY);
        audit.record(AuditLogService.EVT_AUTH_2FA_VERIFY, AuditOutcome.SUCCESS, userId, ipAddress, Map.of(
                "method", method, "recoveryCodesRemaining", row.getRecoveryCodeHashes().size()));
        if (METHOD_RECOVERY.equals(method)) {
            notifyOwner(userId, TwoFactorNotice.RECOVERY_CODE_USED);
        }
        return method;
    }

    /** Turn 2FA off: the secret and every recovery code go. The caller has checked the first factor. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void disable(UUID userId, String code, String ipAddress) {
        UserTwoFactor row = twoFactors.findForUpdate(userId)
                .filter(r -> r.getEnabledAt() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Two-factor sign-in is not on."));
        String method = spendCode(row, code, ipAddress, AuditLogService.EVT_AUTH_2FA_DISABLED);
        twoFactors.delete(row);
        audit.record(AuditLogService.EVT_AUTH_2FA_DISABLED, AuditOutcome.SUCCESS, userId, ipAddress,
                Map.of("method", method));
        notifyOwner(userId, TwoFactorNotice.DISABLED);
    }

    /**
     * Turn 2FA off for an owner who can't: they lost their device and their
     * recovery codes, or a thief enrolled on their account. Whoever runs
     * this has checked who is asking, out of band. Every session ends, as
     * any of them may be the thief's, and the owner is told.
     */
    @Transactional
    public void resetByOperator(UUID userId, UUID actorId, String ipAddress) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        UserTwoFactor row = twoFactors.findForUpdate(userId)
                .filter(r -> r.getEnabledAt() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Two-factor sign-in is not on for this account."));
        twoFactors.delete(row);
        u.setSessionVersion(u.getSessionVersion() + 1);
        users.save(u);

        audit.record(AuditLogService.EVT_ADMIN_TWO_FACTOR_RESET, AuditOutcome.SUCCESS, userId, ipAddress,
                Map.of("actorUserId", actorId.toString()));
        mail.sendTwoFactorNotice(u, TwoFactorNotice.RESET_BY_SUPPORT);
    }

    @Transactional(readOnly = true)
    public StatusResponse status(UUID userId) {
        return twoFactors.findById(userId)
                .filter(r -> r.getEnabledAt() != null)
                .map(r -> new StatusResponse(true, r.getRecoveryCodeHashes().size()))
                .orElseGet(() -> new StatusResponse(false, 0));
    }

    /** Refuse a wrong or locked-out code; a right one is spent and clears the failure count. */
    private String spendCode(UserTwoFactor row, String code, String ipAddress, String auditEvent) {
        Instant now = Instant.now();
        requireNotLocked(row, now, ipAddress, auditEvent);
        String normalized = normalize(code);
        boolean authenticator = AUTHENTICATOR_CODE.matcher(normalized).matches();
        boolean right = authenticator
                ? spendAuthenticatorCode(row, normalized, now)
                : spendRecoveryCode(row, normalized);
        if (!right) {
            throw refused(row, now, ipAddress, auditEvent);
        }
        row.setFailedAttempts(0);
        twoFactors.save(row);
        return authenticator ? METHOD_TOTP : METHOD_RECOVERY;
    }

    private void requireNotLocked(UserTwoFactor row, Instant now, String ipAddress, String auditEvent) {
        Instant until = row.getLockedUntil();
        if (until != null && until.isAfter(now)) {
            audit.record(auditEvent, AuditOutcome.DENIED, row.getUserId(), ipAddress, Map.of("reason", "locked"));
            throw new RateLimitExceededException(Duration.between(now, until));
        }
    }

    /** Count a wrong code and lock after too many. Returns the refusal to throw. */
    private ResponseStatusException refused(UserTwoFactor row, Instant now, String ipAddress, String auditEvent) {
        int failures = row.getFailedAttempts() + 1;
        boolean lock = failures >= MAX_FAILED_ATTEMPTS;
        row.setFailedAttempts(lock ? 0 : failures);
        if (lock) {
            row.setLockedUntil(now.plus(LOCKOUT));
        }
        twoFactors.save(row);
        audit.record(auditEvent, AuditOutcome.FAILURE, row.getUserId(), ipAddress,
                Map.of("reason", "bad-code", "locked", lock));
        if (lock) {
            notifyOwner(row.getUserId(), TwoFactorNotice.LOCKED);
            return new RateLimitExceededException(LOCKOUT);
        }
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "That code isn't right, or has already been used. Check your authenticator app and try again.");
    }

    /**
     * The code matches a live step above the last one used. Comparing every
     * step, with no early exit, keeps the time taken the same either way.
     */
    private boolean spendAuthenticatorCode(UserTwoFactor row, String code, Instant now) {
        byte[] secret = vault.decrypt(row.getSecretEncrypted(), context(row.getUserId()));
        byte[] given = code.getBytes(StandardCharsets.UTF_8);
        long current = Totp.stepAt(now);
        long matched = -1L;
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            byte[] expected = Totp.code(secret, step).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, given) && step > row.getLastUsedStep()) {
                matched = step;
            }
        }
        if (matched < 0) {
            return false;
        }
        row.setLastUsedStep(matched);
        return true;
    }

    private boolean spendRecoveryCode(UserTwoFactor row, String code) {
        List<String> remaining = new ArrayList<>(row.getRecoveryCodeHashes());
        if (!remaining.remove(recoveryHash(row.getUserId(), code))) {
            return false;
        }
        row.setRecoveryCodeHashes(remaining);
        return true;
    }

    private void notifyOwner(UUID userId, TwoFactorNotice notice) {
        users.findById(userId).ifPresent(u -> mail.sendTwoFactorNotice(u, notice));
    }

    /** People type codes with spaces, dashes or in lower case. */
    private static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** Salted with the user id, so a hash means nothing on another account. */
    private static String recoveryHash(UUID userId, String normalizedCode) {
        return AuthService.sha256Hex(userId + ":" + normalizedCode);
    }

    private static String context(UUID userId) {
        return "totp:" + userId;
    }

    private static ResponseStatusException noEnrolment() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Start setting up two-factor sign-in first.");
    }

    /** The account's address, or its name when its address is only a placeholder. */
    private static String accountLabel(User u) {
        return u.hasSyntheticEmail() ? u.getFullName() : u.getEmail();
    }

    private static String otpauthUri(String base32Secret, String account) {
        return "otpauth://totp/" + enc(ISSUER) + ":" + enc(account)
                + "?secret=" + base32Secret
                + "&issuer=" + enc(ISSUER)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    /** Percent-encoding for a URI, where URLEncoder's + would not mean a space. */
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
