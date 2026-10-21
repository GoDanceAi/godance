package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;

import ai.godance.config.AdminProperties;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import reactor.core.publisher.Mono;

/**
 * The admin gate decides on the caller's account row, never on the JWT
 * {@code email} claim: active, email verified, current email allowlisted,
 * and not an API-key principal.
 */
class AdminAuthorizationFilterTest {

    private static final String ADMIN_EMAIL = "admin@godance.test";

    UserRepository users;
    AdminAuthorizationFilter filter;
    AtomicBoolean chainCalled;
    WebFilterChain chain;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        AdminProperties admin = new AdminProperties();
        admin.setEmails(List.of(ADMIN_EMAIL));
        filter = new AdminAuthorizationFilter(admin, users);
        chainCalled = new AtomicBoolean();
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    @Test
    void filter_givenVerifiedActiveAllowlistedAccount_passesWithCurrentEmail() {
        User u = user(ADMIN_EMAIL, true, true);
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(u.getId(), ADMIN_EMAIL));

        assertThat(chainCalled).isTrue();
        assertThat((String) exchange.getAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE))
                .isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void filter_givenUnverifiedAccount_returns403() {
        User u = user(ADMIN_EMAIL, false, true);
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(u.getId(), ADMIN_EMAIL));

        assertDenied(exchange);
    }

    @Test
    void filter_givenEmailClaimAllowlistedButCurrentEmailNot_returns403() {
        User u = user("someone-else@godance.test", true, true);
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(u.getId(), ADMIN_EMAIL));

        assertDenied(exchange);
    }

    @Test
    void filter_givenInactiveAccount_returns403() {
        User u = user(ADMIN_EMAIL, true, false);
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(u.getId(), ADMIN_EMAIL));

        assertDenied(exchange);
    }

    @Test
    void filter_givenUnknownUser_returns403() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(id, ADMIN_EMAIL));

        assertDenied(exchange);
    }

    @Test
    void filter_givenApiKeyPrincipal_returns403WithoutLoadingUser() {
        Jwt apiKeyJwt = Jwt.withTokenValue("api-key")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("api_key_id", UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = adminRequest();

        run(exchange, apiKeyJwt);

        assertDenied(exchange);
        verifyNoInteractions(users);
    }

    @Test
    void filter_givenNonUuidSubject_returns403WithoutLoadingUser() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(ADMIN_EMAIL)
                .claim("email", ADMIN_EMAIL)
                .build();
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwt);

        assertDenied(exchange);
        verifyNoInteractions(users);
    }

    @Test
    void filter_givenNonAdminPath_passesWithoutLoadingUser() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/models"));

        filter.filter(exchange, chain).block();

        assertThat(chainCalled).isTrue();
        verifyNoInteractions(users);
    }

    // ===== Helpers ======================================================

    private void run(MockServerWebExchange exchange, Jwt jwt) {
        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();
    }

    private void assertDenied(MockServerWebExchange exchange) {
        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) exchange.getAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE)).isNull();
    }

    private User user(String email, boolean emailVerified, boolean active) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setEmailVerified(emailVerified);
        u.setActive(active);
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private static MockServerWebExchange adminRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/v1/admin/me"));
    }

    private static Jwt jwtFor(UUID userId, String emailClaim) {
        return Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(userId.toString())
                .claim("email", emailClaim)
                .build();
    }
}
