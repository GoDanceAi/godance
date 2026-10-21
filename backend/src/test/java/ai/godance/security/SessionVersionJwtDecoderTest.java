package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import ai.godance.repository.UserRepository;
import reactor.core.publisher.Mono;

/**
 * A JWT only authenticates while its {@code session_version} claim matches
 * the user's current one, so a password reset ends every earlier session.
 */
class SessionVersionJwtDecoderTest {

    private static final String TOKEN = "header.payload.signature";

    ReactiveJwtDecoder signed;
    UserRepository users;
    SessionVersionJwtDecoder decoder;
    final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        signed = mock(ReactiveJwtDecoder.class);
        users = mock(UserRepository.class);
        decoder = new SessionVersionJwtDecoder(signed, users);
    }

    private Jwt.Builder jwtFor(String subject) {
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
    }

    private void signatureChecksOut(Jwt jwt) {
        when(signed.decode(TOKEN)).thenReturn(Mono.just(jwt));
    }

    @Test
    void decode_givenCurrentSessionVersion_expectsJwt() {
        Jwt jwt = jwtFor(userId.toString()).claim(JwtService.SESSION_VERSION_CLAIM, 2L).build();
        signatureChecksOut(jwt);
        when(users.findSessionVersionById(userId)).thenReturn(Optional.of(2));

        assertThat(decoder.decode(TOKEN).block()).isSameAs(jwt);
    }

    @Test
    void decode_givenSessionVersionBumpedSinceIssue_expectsBadJwtException() {
        // Issued before a password reset bumped the version 0 → 1.
        signatureChecksOut(jwtFor(userId.toString()).claim(JwtService.SESSION_VERSION_CLAIM, 0L).build());
        when(users.findSessionVersionById(userId)).thenReturn(Optional.of(1));

        assertThatThrownBy(() -> decoder.decode(TOKEN).block())
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void decode_givenTokenWithoutClaimAndUserNeverReset_expectsJwt() {
        // Minted before the claim existed; version 0 is the column default.
        Jwt jwt = jwtFor(userId.toString()).build();
        signatureChecksOut(jwt);
        when(users.findSessionVersionById(userId)).thenReturn(Optional.of(0));

        assertThat(decoder.decode(TOKEN).block()).isSameAs(jwt);
    }

    @Test
    void decode_givenTokenWithoutClaimAndUserReset_expectsBadJwtException() {
        signatureChecksOut(jwtFor(userId.toString()).build());
        when(users.findSessionVersionById(userId)).thenReturn(Optional.of(1));

        assertThatThrownBy(() -> decoder.decode(TOKEN).block())
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void decode_givenUserNoLongerExists_expectsBadJwtException() {
        signatureChecksOut(jwtFor(userId.toString()).claim(JwtService.SESSION_VERSION_CLAIM, 0L).build());
        when(users.findSessionVersionById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> decoder.decode(TOKEN).block())
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void decode_givenNonUuidSubject_expectsBadJwtException() {
        signatureChecksOut(jwtFor("not-a-uuid").build());

        assertThatThrownBy(() -> decoder.decode(TOKEN).block())
                .isInstanceOf(BadJwtException.class);
        verify(users, never()).findSessionVersionById(any());
    }

    @Test
    void decode_givenSignatureRejected_expectsErrorWithoutUserLookup() {
        when(signed.decode(TOKEN)).thenReturn(Mono.error(new BadJwtException("bad signature")));

        assertThatThrownBy(() -> decoder.decode(TOKEN).block())
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("bad signature");
        verify(users, never()).findSessionVersionById(any());
    }
}
