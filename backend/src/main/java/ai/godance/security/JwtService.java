package ai.godance.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import ai.godance.config.JwtProperties;
import ai.godance.domain.User;

/**
 * HS256 issuance + claim assembly. Decoding is handled by Spring
 * Security's autoconfigured {@code ReactiveJwtDecoder} bean
 * (see {@link ai.godance.config.SecurityConfig}).
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public JwtService(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    /** Issue a bearer token for {@code user}. Caller hands this to the client verbatim. */
    public IssuedToken issueFor(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.expiryMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(exp)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("scope", user.getRole().name())   // SimpleGrantedAuthority → "SCOPE_USER" / "SCOPE_ADMIN"
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, exp);
    }

    /** Convenience: caller already authenticated, returns the {@code sub} as UUID. */
    public static UUID userIdFromSubject(String subject) {
        return UUID.fromString(subject);
    }

    public record IssuedToken(String token, Instant expiresAt) { }
}
