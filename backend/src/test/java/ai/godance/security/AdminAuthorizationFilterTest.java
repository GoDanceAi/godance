package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.WebFilterChain;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import reactor.core.publisher.Mono;

/**
 * The admin gate is deny by default: the matched route must declare
 * {@link AdminRoles}, and the caller's account row, never a JWT claim,
 * must be active, verified and hold one of those roles. API keys never
 * pass.
 */
class AdminAuthorizationFilterTest {

    private static final String ADMIN_EMAIL = "admin@godance.test";

    /** Handlers for the routes the mapping below resolves to. */
    static class Routes {
        @AdminRoles({UserRole.FINANCE, UserRole.ADMIN})
        void declared() { }

        void undeclared() { }
    }

    UserRepository users;
    AtomicBoolean chainCalled;
    WebFilterChain chain;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        chainCalled = new AtomicBoolean();
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    @Test
    void filter_givenVerifiedActiveAccountWithDeclaredRole_passesWithCurrentEmailAndRole() {
        User u = user(ADMIN_EMAIL, UserRole.FINANCE, true, true);
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(u.getId(), "stale-claim@godance.test"));

        assertThat(chainCalled).isTrue();
        assertThat((String) exchange.getAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE))
                .isEqualTo(ADMIN_EMAIL);
        assertThat((UserRole) exchange.getAttribute(AdminAuthorizationFilter.ADMIN_ROLE_ATTRIBUTE))
                .isEqualTo(UserRole.FINANCE);
    }

    @Test
    void filter_givenRoleTheRouteDoesNotDeclare_returns403() {
        for (UserRole role : Set.of(UserRole.USER, UserRole.SUPPORT)) {
            User u = user(ADMIN_EMAIL, role, true, true);
            MockServerWebExchange exchange = adminRequest();

            run(exchange, jwtFor(u.getId(), ADMIN_EMAIL));

            assertDenied(exchange);
        }
    }

    @Test
    void filter_givenScopeClaimClaimsARoleTheAccountLacks_returns403() {
        User u = user(ADMIN_EMAIL, UserRole.USER, true, true);
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(u.getId().toString())
                .claim("scope", "ADMIN")
                .build();
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwt);

        assertDenied(exchange);
    }

    @Test
    void filter_givenUnverifiedAccount_returns403() {
        User u = user(ADMIN_EMAIL, UserRole.ADMIN, false, true);
        MockServerWebExchange exchange = adminRequest();

        run(exchange, jwtFor(u.getId(), ADMIN_EMAIL));

        assertDenied(exchange);
    }

    @Test
    void filter_givenInactiveAccount_returns403() {
        User u = user(ADMIN_EMAIL, UserRole.ADMIN, true, false);
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
    void filter_givenRouteWithoutDeclaration_returns403EvenForAnAdmin() {
        MockServerWebExchange exchange = adminRequest();

        run(filter(routeTo("undeclared")), exchange, jwtFor(UUID.randomUUID(), ADMIN_EMAIL));

        assertDenied(exchange);
        verifyNoInteractions(users);
    }

    @Test
    void filter_givenNoRoute_returns403EvenForAnAdmin() {
        MockServerWebExchange exchange = adminRequest();

        run(filter(e -> Mono.empty()), exchange, jwtFor(UUID.randomUUID(), ADMIN_EMAIL));

        assertDenied(exchange);
        verifyNoInteractions(users);
    }

    @Test
    void filter_givenPathMatchesButMethodDoesNot_returns403EvenForAnAdmin() {
        HandlerMapping wrongMethod = e -> Mono.error(new MethodNotAllowedException(HttpMethod.DELETE, Set.of()));
        MockServerWebExchange exchange = adminRequest();

        run(filter(wrongMethod), exchange, jwtFor(UUID.randomUUID(), ADMIN_EMAIL));

        assertDenied(exchange);
        verifyNoInteractions(users);
    }

    @Test
    void filter_givenNoSecurityContext_returns403() {
        MockServerWebExchange exchange = adminRequest();

        filter(routeTo("declared")).filter(exchange, chain).block();

        assertDenied(exchange);
    }

    @Test
    void filter_givenNonAdminPath_passesWithoutLoadingRouteOrUser() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/models"));

        filter(e -> {
            throw new AssertionError("a non-admin path must not resolve a route");
        }).filter(exchange, chain).block();

        assertThat(chainCalled).isTrue();
        verifyNoInteractions(users);
    }

    // ===== Helpers ======================================================

    private AdminAuthorizationFilter filter(HandlerMapping routes) {
        return new AdminAuthorizationFilter(routes, users);
    }

    private static HandlerMapping routeTo(String handler) {
        try {
            HandlerMethod route = new HandlerMethod(new Routes(), Routes.class.getDeclaredMethod(handler));
            return exchange -> Mono.just(route);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(handler, e);
        }
    }

    private void run(MockServerWebExchange exchange, Jwt jwt) {
        run(filter(routeTo("declared")), exchange, jwt);
    }

    private void run(AdminAuthorizationFilter filter, MockServerWebExchange exchange, Jwt jwt) {
        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();
    }

    private void assertDenied(MockServerWebExchange exchange) {
        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) exchange.getAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE)).isNull();
        assertThat((UserRole) exchange.getAttribute(AdminAuthorizationFilter.ADMIN_ROLE_ATTRIBUTE)).isNull();
    }

    private User user(String email, UserRole role, boolean emailVerified, boolean active) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setRole(role);
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
