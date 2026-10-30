package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.security.AdminRoles;
import ai.godance.service.AuditLogService;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Which role reaches which {@code /v1/admin} route, checked over HTTP for
 * every route and every role, and how a role is granted and revoked.
 * {@link #ROUTES} is the access matrix: a route missing from it, or
 * declaring anything else, fails {@link #everyAdminRoute_declaresExactlyTheRolesListedHere}.
 *
 * <p>One account per role is shared by the whole class: bcrypt makes a
 * signup per case too slow across a 13-by-4 matrix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@Import(AdminRolesIntegrationTest.UndeclaredRoute.class)
class AdminRolesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** A route under {@code /v1/admin} that forgot its {@link AdminRoles}. */
    @RestController
    static class UndeclaredRoute {
        @GetMapping("/v1/admin/undeclared-probe")
        String undeclared() {
            return "reached";
        }
    }

    private static final Set<UserRole> STAFF = EnumSet.of(UserRole.SUPPORT, UserRole.FINANCE, UserRole.ADMIN);
    private static final Set<UserRole> MONEY = EnumSet.of(UserRole.FINANCE, UserRole.ADMIN);
    private static final Set<UserRole> ADMIN = EnumSet.of(UserRole.ADMIN);

    /** A role the gate admits gets a 404 for {@code {id}}, so this suite changes nothing. */
    private record Route(HttpMethod method, String template, Object body, Set<UserRole> allowed) {
        Route(HttpMethod method, String template, Set<UserRole> allowed) {
            this(method, template, null, allowed);
        }

        String path() {
            return template.replace("{id}", template.contains("/users/") ? UUID.randomUUID().toString() : "0");
        }

        @Override
        public String toString() {
            return method + " " + template;
        }
    }

    private static final List<Route> ROUTES = List.of(
            new Route(HttpMethod.GET, "/v1/admin/me", STAFF),
            new Route(HttpMethod.GET, "/v1/admin/users", STAFF),
            new Route(HttpMethod.GET, "/v1/admin/users/{id}/ledger", STAFF),
            new Route(HttpMethod.POST, "/v1/admin/users/{id}/credit-adjust",
                    Map.of("amountCents", 100, "note", "matrix"), MONEY),
            new Route(HttpMethod.POST, "/v1/admin/users/{id}/close", ADMIN),
            new Route(HttpMethod.PUT, "/v1/admin/users/{id}/role", Map.of("role", "SUPPORT"), ADMIN),
            new Route(HttpMethod.POST, "/v1/admin/users/{id}/2fa/reset", ADMIN),
            new Route(HttpMethod.GET, "/v1/admin/usage/summary", STAFF),
            new Route(HttpMethod.GET, "/v1/admin/pricing/proposals", STAFF),
            new Route(HttpMethod.POST, "/v1/admin/pricing/proposals/{id}/approve", ADMIN),
            new Route(HttpMethod.POST, "/v1/admin/pricing/proposals/{id}/reject", ADMIN),
            new Route(HttpMethod.GET, "/v1/admin/pricing/models", STAFF),
            new Route(HttpMethod.PATCH, "/v1/admin/pricing/models/{id}", Map.of("enabled", true), ADMIN),
            new Route(HttpMethod.GET, "/v1/admin/pricing/models/{id}/history", STAFF));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired AuditLogEntryRepository audit;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes;

    private final Map<UserRole, String> bearers = new EnumMap<>(UserRole.class);
    private UUID adminId;
    private String adminEmail;
    private String adminApiKey;

    @BeforeAll
    void signInOneAccountPerRole() {
        for (UserRole role : UserRole.values()) {
            String email = freshEmail(role.name().toLowerCase());
            signUp(email);
            set(email, role, true);
            bearers.put(role, logIn(email));
            turnOnTwoFactor(email);
            if (role == UserRole.ADMIN) {
                adminEmail = email;
                adminId = users.findByEmail(email).orElseThrow().getId();
                adminApiKey = createApiKey(bearers.get(role));
            }
        }
    }

    // ===== The matrix ===================================================

    static Stream<Arguments> everyRouteAndRole() {
        return ROUTES.stream().flatMap(route ->
                Arrays.stream(UserRole.values()).map(role -> Arguments.of(route, role)));
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("everyRouteAndRole")
    void route_givenRole_isAdmittedOnlyWhenDeclared(Route route, UserRole role) {
        WebTestClient.ResponseSpec response = send(route, bearers.get(role));

        if (route.allowed().contains(role)) {
            response.expectStatus().value(status -> assertThat(status).isIn(200, 404));
        } else {
            response.expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void route_givenApiKeyOfAnAdmin_returns403(Route route) {
        send(route, "Bearer " + adminApiKey)
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    static List<Route> routes() {
        return ROUTES;
    }

    @Test
    void everyAdminRoute_declaresExactlyTheRolesListedHere() {
        Map<String, Set<UserRole>> declared = new TreeMap<>();
        routes.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType() == UndeclaredRoute.class) return;
            info.getPatternsCondition().getPatterns().stream()
                    .map(pattern -> pattern.getPatternString())
                    .filter(pattern -> pattern.startsWith("/v1/admin"))
                    .forEach(pattern -> {
                        AdminRoles roles = handler.getMethodAnnotation(AdminRoles.class);
                        assertThat(roles).as("@AdminRoles on %s", pattern).isNotNull();
                        info.getMethodsCondition().getMethods().forEach(method -> declared.put(
                                method + " " + pattern, EnumSet.copyOf(Arrays.asList(roles.value()))));
                    });
        });

        Map<String, Set<UserRole>> expected = ROUTES.stream()
                .collect(Collectors.toMap(Route::toString, Route::allowed, (a, b) -> a, TreeMap::new));
        assertThat(declared).isEqualTo(expected);
    }

    @Test
    void route_givenNoDeclaration_refusesEvenAnAdmin() {
        http.get().uri("/v1/admin/undeclared-probe")
                .header("Authorization", bearers.get(UserRole.ADMIN))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void path_givenNoRouteAtAll_refusesEvenAnAdmin() {
        http.get().uri("/v1/admin/no-such-route")
                .header("Authorization", bearers.get(UserRole.ADMIN))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void route_givenWrongMethod_refusesEvenAnAdmin() {
        http.delete().uri("/v1/admin/me")
                .header("Authorization", bearers.get(UserRole.ADMIN))
                .exchange()
                .expectStatus().isForbidden();
    }

    /** {@code SecurityConfig} must admit every role's session, or a new role is locked out of the whole API. */
    @ParameterizedTest
    @EnumSource(UserRole.class)
    void customerApi_givenAnyRole_stillWorks(UserRole role) {
        http.get().uri("/v1/auth/me")
                .header("Authorization", bearers.get(role))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo(role.name());
        http.get().uri("/v1/keys")
                .header("Authorization", bearers.get(role))
                .exchange()
                .expectStatus().isOk();
    }

    // ===== Granting and revoking ========================================

    @Test
    void changeRole_givenGrant_takesEffectOnTheNextRequestAndWritesAnAuditRow() {
        String email = freshEmail("granted");
        signUp(email);
        set(email, UserRole.USER, true);
        String before = logIn(email);
        UUID id = users.findByEmail(email).orElseThrow().getId();
        http.get().uri("/v1/admin/me").header("Authorization", before)
                .exchange().expectStatus().isForbidden();

        changeRole(id, "FINANCE").expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("FINANCE");

        // The session version moved, so the token that says USER is dead.
        http.get().uri("/v1/auth/me").header("Authorization", before)
                .exchange().expectStatus().isUnauthorized();
        String after = logIn(email);
        turnOnTwoFactor(email);
        http.get().uri("/v1/admin/me").header("Authorization", after)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("FINANCE");

        AuditLogEntry row = roleChanges(id).get(0);
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(row.getEventDetail())
                .containsEntry("adminEmail", adminEmail)
                .containsEntry("actorUserId", adminId.toString())
                .containsEntry("targetUserId", id.toString())
                .containsEntry("fromRole", "USER")
                .containsEntry("toRole", "FINANCE");
    }

    @Test
    void changeRole_givenRevoke_endsAccessOnTheNextRequestAndWritesAnAuditRow() {
        String email = freshEmail("revoked");
        signUp(email);
        set(email, UserRole.ADMIN, true);
        String before = logIn(email);
        turnOnTwoFactor(email);
        UUID id = users.findByEmail(email).orElseThrow().getId();
        http.get().uri("/v1/admin/me").header("Authorization", before)
                .exchange().expectStatus().isOk();

        changeRole(id, "USER").expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("USER");

        http.get().uri("/v1/admin/me").header("Authorization", before)
                .exchange().expectStatus().isUnauthorized();
        twoFactors.deleteById(id);   // so the next sign-in is by password alone
        http.get().uri("/v1/admin/me").header("Authorization", logIn(email))
                .exchange().expectStatus().isForbidden();
        assertThat(roleChanges(id)).singleElement()
                .satisfies(row -> assertThat(row.getEventDetail())
                        .containsEntry("fromRole", "ADMIN")
                        .containsEntry("toRole", "USER"));
    }

    @Test
    void changeRole_givenOwnAccount_returns409AndChangesNothing() {
        changeRole(adminId, "USER").expectStatus().isEqualTo(409);

        assertThat(users.findById(adminId).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(roleChanges(adminId)).isEmpty();
    }

    @Test
    void changeRole_givenTheRoleAlreadyHeld_endsNoSessionAndWritesNoAuditRow() {
        String email = freshEmail("unchanged");
        signUp(email);
        set(email, UserRole.SUPPORT, true);
        String before = logIn(email);
        turnOnTwoFactor(email);
        UUID id = users.findByEmail(email).orElseThrow().getId();

        changeRole(id, "SUPPORT").expectStatus().isOk();

        http.get().uri("/v1/admin/me").header("Authorization", before)
                .exchange().expectStatus().isOk();
        assertThat(roleChanges(id)).isEmpty();
    }

    @Test
    void changeRole_givenUnverifiedAccount_returns409() {
        String email = freshEmail("unverified");
        signUp(email);
        UUID id = users.findByEmail(email).orElseThrow().getId();

        changeRole(id, "SUPPORT").expectStatus().isEqualTo(409);

        assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void changeRole_givenNoSuchRole_returns400() {
        String email = freshEmail("bad-role");
        signUp(email);
        set(email, UserRole.USER, true);
        UUID id = users.findByEmail(email).orElseThrow().getId();

        changeRole(id, "OWNER").expectStatus().isBadRequest();
    }

    // ===== Helpers ======================================================

    private WebTestClient.ResponseSpec send(Route route, String bearer) {
        WebTestClient.RequestBodySpec request = http.method(route.method()).uri(route.path())
                .header("Authorization", bearer);
        if (route.body() == null) return request.exchange();
        return request.contentType(MediaType.APPLICATION_JSON).bodyValue(route.body()).exchange();
    }

    private WebTestClient.ResponseSpec changeRole(UUID id, String role) {
        return http.put().uri("/v1/admin/users/" + id + "/role")
                .header("Authorization", bearers.get(UserRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("role", role))
                .exchange();
    }

    private List<AuditLogEntry> roleChanges(UUID id) {
        return audit.findByEventType(AuditLogService.EVT_ADMIN_ROLE_CHANGED).stream()
                .filter(row -> id.equals(row.getUserId()))
                .toList();
    }

    private static String freshEmail(String label) {
        return label + "-" + UUID.randomUUID() + "@godance.test";
    }

    private void signUp(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Roles Test"))
                .exchange()
                .expectStatus().isCreated();
    }

    private String logIn(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    /** After the password sign-in, which would otherwise need the code. */
    private void turnOnTwoFactor(String email) {
        TwoFactorFixture.turnOn(twoFactors, users.findByEmail(email).orElseThrow().getId());
    }

    private void set(String email, UserRole role, boolean emailVerified) {
        User u = users.findByEmail(email).orElseThrow();
        u.setRole(role);
        u.setEmailVerified(emailVerified);
        users.save(u);
    }

    private String createApiKey(String bearer) {
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "matrix"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        return key.plaintext();
    }
}
