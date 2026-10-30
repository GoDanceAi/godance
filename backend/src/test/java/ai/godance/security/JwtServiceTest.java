package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import ai.godance.config.JwtProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.security.JwtService.IssuedToken;

class JwtServiceTest {

    private static final String SECRET = "test-secret-must-be-at-least-32-bytes-long-yes-it-is";
    private static final SecretKey KEY = new SecretKeySpec(SECRET.getBytes(), "HmacSHA256");
    private static final JwtProperties PROPS = new JwtProperties(SECRET, 60, "https://godance.test");

    private final JwtService jwt = new JwtService(
            new NimbusJwtEncoder(new ImmutableSecret<>(KEY)), PROPS);
    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY)
            .macAlgorithm(MacAlgorithm.HS256).build();

    @Test
    void issueFor_thenDecode_roundTripsSubjectAndScope() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("rt@godance.ai");
        u.setRole(UserRole.USER);

        IssuedToken issued = jwt.issueFor(u);
        Jwt decoded = decoder.decode(issued.token());

        assertThat(decoded.getSubject()).isEqualTo(u.getId().toString());
        assertThat(decoded.<String>getClaim("email")).isEqualTo("rt@godance.ai");
        assertThat(decoded.<String>getClaim("scope")).isEqualTo("USER");
        assertThat(decoded.getIssuer()).hasToString("https://godance.test");
        assertThat(decoded.getExpiresAt()).isCloseTo(
                Instant.now().plus(60, ChronoUnit.MINUTES),
                within(5, ChronoUnit.SECONDS));
    }

    @Test
    void issueFor_givenSessionVersion_expectsItInSessionVersionClaim() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("sv@godance.ai");
        u.setRole(UserRole.USER);
        u.setSessionVersion(3);

        Jwt decoded = decoder.decode(jwt.issueFor(u).token());

        assertThat(JwtService.sessionVersionOf(decoded)).isEqualTo(3L);
    }

    @Test
    void issueFor_givenChildWaitingForParentalConsent_expectsPendingConsentScopeInsteadOfRole() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("kid@godance.ai");
        u.setRole(UserRole.USER);
        u.setAgeStatus(AgeStatus.CONSENT_PENDING);

        Jwt decoded = decoder.decode(jwt.issueFor(u).token());

        assertThat(decoded.<String>getClaim("scope")).isEqualTo("PENDING_CONSENT");
    }

    @Test
    void issueFor_givenChildWhoseParentConsented_expectsTheRoleScope() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("kid@godance.ai");
        u.setRole(UserRole.USER);
        u.setAgeStatus(AgeStatus.CONSENT_GRANTED);

        Jwt decoded = decoder.decode(jwt.issueFor(u).token());

        assertThat(decoded.<String>getClaim("scope")).isEqualTo("USER");
    }

    @Test
    void userIdFromSubject_givenUuidString_returnsUuid() {
        UUID id = UUID.randomUUID();
        assertThat(JwtService.userIdFromSubject(id.toString())).isEqualTo(id);
    }
}
