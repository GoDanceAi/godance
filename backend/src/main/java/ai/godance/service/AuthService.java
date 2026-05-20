package ai.godance.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.SignupRequest;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final AuditLogService audit;

    public AuthService(UserRepository users, PasswordEncoder passwords,
                       JwtService jwt, AuditLogService audit) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.audit = audit;
    }

    @Transactional
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

        // Email send stub — real provider integration deferred.
        log.info("[email-stub] Verification URL for {}: /v1/auth/verify?token={}",
                u.getEmail(), u.getEmailVerificationToken());

        audit.record(AuditLogService.EVT_AUTH_SIGNUP, AuditOutcome.SUCCESS,
                u.getId(), ipAddress, Map.of("email", email));

        IssuedToken issued = jwt.issueFor(u);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    @Transactional
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

    @Transactional
    public void verifyEmail(String token, String ipAddress) {
        User u = users.findByEmailVerificationToken(token).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid verification token"));
        u.setEmailVerified(true);
        u.setEmailVerificationToken(null);
        users.save(u);
        audit.record(AuditLogService.EVT_AUTH_VERIFY, AuditOutcome.SUCCESS,
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
