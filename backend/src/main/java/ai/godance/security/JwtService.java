package ai.godance.security;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import ai.godance.config.JwtProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.User;

/**
 * HS256 issuance + claim assembly. Decoding is handled by the
 * {@code ReactiveJwtDecoder} bean in {@link ai.godance.config.SecurityConfig},
 * which also rejects tokens whose {@link #SESSION_VERSION_CLAIM} is stale
 * ({@link SessionVersionJwtDecoder}).
 */
@Service
public class JwtService {

    /** The user's {@code session_version} at issue time. */
    public static final String SESSION_VERSION_CLAIM = "session_version";

    /**
     * The {@code scope} of a child's account waiting for a parent's
     * approval, in place of the role. It becomes the authority
     * {@code SCOPE_PENDING_CONSENT}, which {@code SecurityConfig} admits
     * only where the waiting screen needs it. Granting consent bumps the
     * session version, so these tokens stop working then.
     */
    public static final String PENDING_CONSENT_SCOPE = "PENDING_CONSENT";
    /** The authority a {@link #PENDING_CONSENT_SCOPE} token grants. */
    public static final String PENDING_CONSENT_AUTHORITY = "SCOPE_" + PENDING_CONSENT_SCOPE;

    /**
     * The {@code scope} of a sign-in that has passed its first factor and
     * still owes a second, in place of the role. {@code SecurityConfig}
     * admits it on {@code POST /v1/auth/2fa/verify} and nowhere else.
     */
    public static final String TWO_FACTOR_PENDING_SCOPE = "TWO_FACTOR_PENDING";
    /** The authority a {@link #TWO_FACTOR_PENDING_SCOPE} token grants. */
    public static final String TWO_FACTOR_PENDING_AUTHORITY = "SCOPE_" + TWO_FACTOR_PENDING_SCOPE;

    /** How long someone has to enter their code after their password. */
    static final Duration TWO_FACTOR_CHALLENGE_TTL = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public JwtService(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    /** Issue a bearer token for {@code user}. Caller hands this to the client verbatim. */
    public IssuedToken issueFor(User user) {
        return issue(user, scopeFor(user), Duration.of(props.expiryMinutes(), ChronoUnit.MINUTES));
    }

    /**
     * Issue the short-lived token that stands for a sign-in waiting for its
     * second factor. It carries the session version, so a password reset
     * ends any challenge issued before it.
     */
    public IssuedToken issueTwoFactorChallenge(User user) {
        return issue(user, TWO_FACTOR_PENDING_SCOPE, TWO_FACTOR_CHALLENGE_TTL);
    }

    private IssuedToken issue(User user, String scope, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(exp)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("scope", scope)   // SimpleGrantedAuthority → "SCOPE_USER" / "SCOPE_ADMIN" / ...
                .claim(SESSION_VERSION_CLAIM, user.getSessionVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, exp);
    }

    /** The role, unless the account is a child's still waiting for a parent's consent. */
    static String scopeFor(User user) {
        return user.getAgeStatus() == AgeStatus.CONSENT_PENDING
                ? PENDING_CONSENT_SCOPE
                : user.getRole().name();
    }

    /** Convenience: caller already authenticated, returns the {@code sub} as UUID. */
    public static UUID userIdFromSubject(String subject) {
        return UUID.fromString(subject);
    }

    /**
     * The token's {@link #SESSION_VERSION_CLAIM}. Tokens issued before the
     * claim existed have none and count as 0, the column default; a
     * non-numeric value is -1, which no user has.
     */
    public static long sessionVersionOf(Jwt jwt) {
        Object claim = jwt.getClaims().get(SESSION_VERSION_CLAIM);
        if (claim == null) return 0L;
        return claim instanceof Number n ? n.longValue() : -1L;
    }

    public record IssuedToken(String token, Instant expiresAt) { }
}
