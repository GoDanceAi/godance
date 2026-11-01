package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.SecurityProperties;
import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.AuthRateLimits;
import ai.godance.security.SessionCookies;
import ai.godance.service.AuthService;
import ai.godance.web.dto.AuthDtos.ChangeEmailRequest;
import ai.godance.web.dto.AuthDtos.ReleaseLinkRequest;
import reactor.core.publisher.Mono;

/**
 * The email endpoints act only for a signed-in session: an API key
 * (which {@link ApiKeyAuthFilter} presents as a {@link Jwt} carrying
 * {@code api_key_id}) is refused before the service is called. The
 * anonymous release-link endpoint answers 202 whether or not its rate
 * limit lets the email go out.
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
        controller = new AuthController(auth, rateLimits, new SessionCookies(
                new SecurityProperties(List.of("http://localhost:5173"), false)));
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
        assertForbidden(() -> controller.verify(apiKey(), "tok", exchange));

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

    @Test
    void sendReleaseLink_givenRateLimitReached_answers202WithoutSending() {
        when(rateLimits.releaseLink(any(), eq("held@godance.test"))).thenReturn(Mono.just(false));

        var response = controller.sendReleaseLink(new ReleaseLinkRequest("held@godance.test"), exchange).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(auth);
    }

    @Test
    void sendReleaseLink_givenWithinLimits_sendsAndAnswers202() {
        when(rateLimits.releaseLink(any(), eq("held@godance.test"))).thenReturn(Mono.just(true));

        var response = controller.sendReleaseLink(new ReleaseLinkRequest("held@godance.test"), exchange).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(auth).sendReleaseLink(eq("held@godance.test"), any());
    }
}
