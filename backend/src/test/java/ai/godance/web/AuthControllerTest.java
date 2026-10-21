package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.AuthRateLimits;
import ai.godance.service.AuthService;
import ai.godance.web.dto.AuthDtos.ChangeEmailRequest;

/**
 * The email endpoints act only for a signed-in session: an API key
 * (which {@link ApiKeyAuthFilter} presents as a {@link Jwt} carrying
 * {@code api_key_id}) is refused before the service is called.
 */
class AuthControllerTest {

    private final UUID userId = UUID.randomUUID();
    private final Instant signedInAt = Instant.parse("2026-10-21T18:00:00Z");

    AuthService auth;
    AuthRateLimits rateLimits;
    AuthController controller;
    MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        rateLimits = mock(AuthRateLimits.class);
        controller = new AuthController(auth, rateLimits);
        exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/auth/email"));
    }

    private Jwt session() {
        return Jwt.withTokenValue("session").header("alg", "HS256")
                .subject(userId.toString()).issuedAt(signedInAt).build();
    }

    private Jwt apiKey() {
        return Jwt.withTokenValue("api-key").header("alg", "none")
                .subject(userId.toString()).issuedAt(Instant.now())
                .claim(ApiKeyAuthFilter.API_KEY_ID_CLAIM, UUID.randomUUID().toString()).build();
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void changeEmail_givenApiKey_refusesWithoutCallingService() {
        assertForbidden(() -> controller.changeEmail(apiKey(),
                new ChangeEmailRequest("new@godance.test", "pw"), exchange));

        verifyNoInteractions(auth);
    }

    @Test
    void verify_givenApiKey_refusesWithoutCallingService() {
        assertForbidden(() -> controller.verify("tok", apiKey(), exchange));

        verifyNoInteractions(auth);
    }

    @Test
    void resendVerification_givenApiKey_refusesWithoutCallingService() {
        assertForbidden(() -> controller.resendVerification(apiKey(), exchange));

        verifyNoInteractions(auth);
    }

    @Test
    void changeEmail_givenSession_passesItsSignInTimeToTheService() {
        controller.changeEmail(session(), new ChangeEmailRequest("new@godance.test", "pw"), exchange)
                .block();

        verify(auth).requestEmailChange(eq(userId), eq("new@godance.test"), eq("pw"),
                eq(signedInAt), any());
    }
}
