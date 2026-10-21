package ai.godance.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.OAuthIdentity;
import ai.godance.domain.OAuthProvider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.OAuthIdentityRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.security.JwtService.IssuedToken;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Resolves a verified OAuth provider identity into a Godance session.
 *
 * <p>Sits behind the Spring Security {@code oauth2Login()} success
 * handler but is deliberately decoupled from it — given a
 * {@code (provider, subject, email)} triple it does the link-or-create
 * and mints the <em>same</em> JWT the email+password flow issues. That
 * makes the core behaviour unit-testable without driving a full OAuth
 * redirect.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Known {@code (provider, subject)} → that user, whatever the
 *       email says.</li>
 *   <li>Else an email the provider has <em>not</em> verified → refuse.
 *       Nothing is written, so the person can verify with the provider
 *       and sign in again.</li>
 *   <li>Else a user with the provider-verified email that has a password
 *       but never verified the email → refuse. Nothing is written.</li>
 *   <li>Else a user with the provider-verified email → link a new
 *       identity to them.</li>
 *   <li>Else create a fresh OAuth-only user (no password) and link.</li>
 * </ol>
 *
 * <p>Refusing, rather than creating a second account, keeps an
 * unverified address out of {@code users.email} entirely: it can neither
 * take over an existing account nor be parked on a new one for its real
 * owner to link into later. A throwaway account would also bind the
 * {@code (provider, subject)} for good, so the person would keep landing
 * in it after verifying.
 *
 * <p>Password signup takes any address unchecked, so an unverified
 * password account proves nobody owns its email. Linking into one would
 * share it between the provider's user and whoever set the password.
 * Clearing the password on link would not evict that person: issued JWTs
 * stay valid until expiry, and API keys, the webhook signing secret and a
 * saved card survive. Refusing leaves the account untouched. Verifying
 * the email takes the emailed link opened in the account's own session
 * ({@link AuthService#verifyEmail}), so a verified password account has
 * one person behind both and is safe to link. The address's owner, if
 * they don't hold that account, resets its password instead, which
 * evicts whoever set it ({@link AuthService#resetPassword}); either way
 * the next provider sign-in links.
 */
@Service
public class OAuthLoginService {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginService.class);

    private final UserRepository users;
    private final OAuthIdentityRepository identities;
    private final JwtService jwt;
    private final AuditLogService audit;

    public OAuthLoginService(UserRepository users, OAuthIdentityRepository identities,
                             JwtService jwt, AuditLogService audit) {
        this.users = users;
        this.identities = identities;
        this.jwt = jwt;
        this.audit = audit;
    }

    /**
     * Complete an OAuth sign-in. {@code email} / {@code fullName} may be
     * null (e.g. X.com does not return an email). {@code emailVerified}
     * is the provider's {@code email_verified} claim; an email without it
     * is never trusted. Returns the bearer token the caller hands back to
     * the client.
     */
    @Transactional
    public AuthResponse completeLogin(OAuthProvider provider, String subject,
                                      String email, boolean emailVerified, String fullName) {
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "oauth provider returned no subject identifier");
        }
        String normalizedEmail = (email == null || email.isBlank())
                ? null : email.trim().toLowerCase();

        Optional<OAuthIdentity> known = identities.findByProviderAndSubject(provider, subject);
        User user;
        if (known.isPresent()) {
            user = users.findById(known.get().getUserId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "oauth identity references a missing user"));
        } else {
            if (normalizedEmail != null && !emailVerified) {
                // Same answer whether or not an account holds this email,
                // so the refusal reveals nothing about who is registered.
                log.warn("Refused {} sign-in: provider email not verified", provider);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your " + displayName(provider) + " email address is not verified. "
                                + "Verify it with " + displayName(provider) + ", then sign in again.");
            }
            user = (normalizedEmail != null)
                    ? users.findByEmail(normalizedEmail).orElse(null)
                    : null;
            if (user == null) {
                user = createOAuthUser(provider, subject, normalizedEmail, fullName);
            } else if (!user.isEmailVerified()) {
                if (user.getPasswordHash() != null) {
                    // Possibly pre-registered by someone who doesn't own the
                    // address; linking would share the account with them.
                    log.warn("Refused {} sign-in: email held by unverified password account {}",
                            provider, user.getId());
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "An account with this email address already exists and its email "
                                    + "is not verified. Sign in with your password and verify "
                                    + "your email (or reset the password if you don't know it), "
                                    + "then sign in with " + displayName(provider) + ". "
                                    + "If you didn't create that account, you can release the "
                                    + "address from the sign-up page.");
                }
                // The provider has verified ownership of this email — trust it.
                user.setEmailVerified(true);
            }
            OAuthIdentity identity = new OAuthIdentity();
            identity.setUserId(user.getId());
            identity.setProvider(provider);
            identity.setSubject(subject);
            identities.save(identity);
        }

        if (!user.isActive()) {
            // The link above may already be written. The rollback must undo
            // it, so the denial's audit row commits on its own.
            audit.recordInNewTransaction(AuditLogService.EVT_AUTH_OAUTH_LOGIN, AuditOutcome.DENIED,
                    user.getId(), null, Map.of("provider", provider.name(), "reason", "inactive"));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account disabled");
        }

        user.setLastLoginAt(Instant.now());
        users.save(user);

        audit.record(AuditLogService.EVT_AUTH_OAUTH_LOGIN, AuditOutcome.SUCCESS,
                user.getId(), null, Map.of("provider", provider.name()));

        IssuedToken issued = jwt.issueFor(user);
        return AuthResponse.bearer(issued.token(), issued.expiresAt());
    }

    private User createOAuthUser(OAuthProvider provider, String subject,
                                 String email, String fullName) {
        User u = new User();
        // No provider email (X.com) → a stable synthetic address keeps the
        // NOT NULL + UNIQUE email column satisfied; the user simply has no
        // password-login path.
        u.setEmail(email != null
                ? email
                : provider.name().toLowerCase() + "-" + subject + User.SYNTHETIC_EMAIL_SUFFIX);
        u.setFullName(fullName != null && !fullName.isBlank()
                ? fullName.trim() : defaultName(u.getEmail()));
        u.setPasswordHash(null);
        u.setRole(UserRole.USER);
        u.setActive(true);
        // Only reached with a null or provider-verified email.
        u.setEmailVerified(email != null);
        users.save(u);
        log.info("Created OAuth-only user {} via {}", u.getId(), provider);
        return u;
    }

    private static String displayName(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case APPLE -> "Apple";
            case X -> "X";
        };
    }

    private static String defaultName(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        return local.isBlank() ? "Godance User" : local;
    }
}
