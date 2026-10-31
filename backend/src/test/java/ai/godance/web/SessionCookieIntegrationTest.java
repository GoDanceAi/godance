package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.SessionCookies;
import ai.godance.service.AuditLogService;
import ai.godance.service.mail.LoggingEmailSender;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The web app's session as a browser uses it: an HttpOnly cookie set at
 * sign-in, sent on its own with every request, and accepted on a
 * state-changing route only from the app's origin. Clients that don't ask
 * for the cookie (open app bundles from before it, scripts) keep working
 * with a bearer token, and API keys are untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class SessionCookieIntegrationTest {

    private static final String APP_ORIGIN = "http://localhost:5173";
    private static final String FOREIGN_ORIGIN = "https://evil.example";
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audits;
    @Autowired SessionCookies cookies;

    /** CORS compares Origin with the request's host, so a request needs one: the default URLs are relative. */
    @BeforeEach
    void useAbsoluteUrls() {
        http = http.mutate().baseUrl("http://localhost:8080").build();
    }

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    private static Map<String, Object> signupBody(String email) {
        return Map.of("email", email, "password", PASSWORD, "fullName", "Test User");
    }

    /** What the app sends on sign-in. */
    private WebTestClient.RequestHeadersSpec<?> fromApp(String path, Object body) {
        return http.post().uri(path)
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .header(SessionCookies.CLIENT_HEADER, SessionCookies.COOKIE_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    private ResponseCookie setCookie(EntityExchangeResult<?> result) {
        List<ResponseCookie> set = result.getResponseCookies().get(cookies.name());
        assertThat(set).as("one Set-Cookie for the session").hasSize(1);
        return set.get(0);
    }

    /** Signs a new account in as the app does and returns its session cookie's value. */
    private String signIn(String email) {
        fromApp("/v1/auth/signup", signupBody(email)).exchange().expectStatus().isCreated();
        return setCookie(fromApp("/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult())
                .getValue();
    }

    private WebTestClient.ResponseSpec me(String cookie) {
        return http.get().uri("/v1/auth/me").cookie(cookies.name(), cookie).exchange();
    }

    /** A state-changing route that needs a signed-in session. */
    private WebTestClient.RequestBodySpec createKey() {
        return http.post().uri("/v1/keys");
    }

    private String apiKey(String bearer) {
        return http.post().uri("/v1/keys")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "server-to-server"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody()
                .plaintext();
    }

    private String requestResetToken(String email) {
        Logger stubLog = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stubLog.addAppender(appender);
        try {
            http.post().uri("/v1/auth/password/forgot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("email", email))
                    .exchange()
                    .expectStatus().isAccepted();
        } finally {
            stubLog.detachAppender(appender);
        }
        Pattern link = Pattern.compile(
                "Password reset URL for " + Pattern.quote(email) + ": \\S+/reset-password\\?token=(\\S+)");
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(link::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reset email logged for " + email));
    }

    // ---------- signing in ---------------------------------------------

    @Test
    void login_givenCookieClient_expectsHttpOnlyStrictCookieAndNoTokenInBody() {
        String email = uniqueEmail("login-cookie");
        fromApp("/v1/auth/signup", signupBody(email)).exchange().expectStatus().isCreated();

        EntityExchangeResult<AuthResponse> login = fromApp("/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult();

        ResponseCookie cookie = setCookie(login);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).as("host-only: the API's own origin").isNull();
        assertThat(cookie.getMaxAge()).isBetween(Duration.ofMinutes(59), Duration.ofMinutes(60));

        AuthResponse body = login.getResponseBody();
        assertThat(body.accessToken()).as("page script must not see the token").isNull();
        assertThat(body.tokenType()).isEqualTo("Cookie");
        assertThat(body.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void signup_givenCookieClient_expectsCookieAndNoTokenInBody() {
        EntityExchangeResult<AuthResponse> signup = fromApp("/v1/auth/signup", signupBody(uniqueEmail("signup-cookie")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult();

        assertThat(signup.getResponseBody().accessToken()).isNull();
        me(setCookie(signup).getValue()).expectStatus().isOk();
    }

    @Test
    void login_givenNoCookieClientHeader_expectsTokenInBodyAsBefore() {
        String email = uniqueEmail("login-legacy");
        fromApp("/v1/auth/signup", signupBody(email)).exchange().expectStatus().isCreated();

        AuthResponse body = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();

        assertThat(body.tokenType()).isEqualTo("Bearer");
        http.get().uri("/v1/auth/me").header("Authorization", "Bearer " + body.accessToken())
                .exchange().expectStatus().isOk();
    }

    // ---------- using the session --------------------------------------

    @Test
    void me_givenSessionCookie_expectsSignedIn() {
        String email = uniqueEmail("me-cookie");

        me(signIn(email)).expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo(email);
    }

    @Test
    void stateChange_givenCookieFromTheApp_expectsAllowed() {
        createKey().cookie(cookies.name(), signIn(uniqueEmail("csrf-app")))
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "from-the-app"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void stateChange_givenCookieWithoutOrigin_expectsForbiddenAndNothingDone() {
        String email = uniqueEmail("csrf-no-origin");
        String cookie = signIn(email);

        createKey().cookie(cookies.name(), cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "forged"))
                .exchange()
                .expectStatus().isForbidden();

        http.get().uri("/v1/keys").cookie(cookies.name(), cookie).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void stateChange_givenCookieFromForeignOrigin_expectsForbidden() {
        createKey().cookie(cookies.name(), signIn(uniqueEmail("csrf-foreign")))
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "forged"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void stateChange_givenSiblingSubdomainOrigin_expectsForbidden() {
        // SameSite can't stop this one: another subdomain is the same site.
        createKey().cookie(cookies.name(), signIn(uniqueEmail("csrf-sibling")))
                .header(HttpHeaders.ORIGIN, "http://marketing.localhost:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "forged"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void read_givenCookieWithoutOrigin_expectsAllowed() {
        http.get().uri("/v1/keys").cookie(cookies.name(), signIn(uniqueEmail("safe-get")))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void login_givenForeignOrigin_expectsForbidden() {
        // Login CSRF: signing a victim's browser into the attacker's account.
        String email = uniqueEmail("login-csrf");
        fromApp("/v1/auth/signup", signupBody(email)).exchange().expectStatus().isCreated();

        http.post().uri("/v1/auth/login")
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void bearerToken_givenNoOrigin_expectsNoCsrfCheck() {
        AuthResponse session = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(signupBody(uniqueEmail("bearer-script")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();

        createKey().header("Authorization", "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "script"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void bearerToken_givenCookieToo_expectsNoCsrfCheck() {
        String cookie = signIn(uniqueEmail("bearer-and-cookie"));

        // A browser never adds Authorization on its own, so this request was built on purpose.
        createKey().header("Authorization", "Bearer " + cookie)
                .cookie(cookies.name(), cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "script"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void apiKey_givenNoCookie_expectsBearerStillWorks() {
        String email = uniqueEmail("api-key");
        fromApp("/v1/auth/signup", signupBody(email)).exchange().expectStatus().isCreated();
        String token = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectBody(AuthResponse.class).returnResult().getResponseBody().accessToken();

        http.get().uri("/v1/usage").header("Authorization", "Bearer " + apiKey("Bearer " + token))
                .exchange()
                .expectStatus().isOk();
    }

    // ---------- CORS ----------------------------------------------------

    @Test
    void preflight_givenAppOrigin_expectsCredentialsAndTheClientHeader() {
        http.options().uri("/v1/auth/login")
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-godance-session")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APP_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void preflight_givenForeignOrigin_expectsRefused() {
        http.options().uri("/v1/auth/login")
                .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    // ---------- a cookie that no longer works ---------------------------

    @Test
    void login_givenStaleCookie_expectsSignInAndFreshCookie() {
        String email = uniqueEmail("stale-login");
        String stale = signIn(email);
        fromLogout(stale);

        EntityExchangeResult<AuthResponse> login = fromApp("/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .cookie(cookies.name(), stale)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult();

        ResponseCookie fresh = setCookie(login);
        assertThat(fresh.getValue()).isNotBlank().isNotEqualTo(stale);
        me(fresh.getValue()).expectStatus().isOk();
    }

    @Test
    void publicRoute_givenTamperedCookie_expectsAnswerAndCookieCleared() {
        EntityExchangeResult<byte[]> status = http.get().uri("/v1/status")
                .cookie(cookies.name(), "not-a-jwt")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        assertThat(setCookie(status).getMaxAge()).isZero();
    }

    @Test
    void protectedRoute_givenTamperedCookie_expects401AndCookieCleared() {
        EntityExchangeResult<byte[]> me = http.get().uri("/v1/auth/me")
                .cookie(cookies.name(), "not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().returnResult();

        assertThat(setCookie(me).getMaxAge()).isZero();
    }

    // ---------- signing out ---------------------------------------------

    private EntityExchangeResult<byte[]> fromLogout(String cookie) {
        return http.post().uri("/v1/auth/logout")
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .cookie(cookies.name(), cookie)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().returnResult();
    }

    @Test
    void logout_expectsCookieClearedSessionVersionBumpedAndAudited() {
        String email = uniqueEmail("logout");
        String cookie = signIn(email);
        var user = users.findByEmail(email).orElseThrow();
        int versionBefore = user.getSessionVersion();

        ResponseCookie cleared = setCookie(fromLogout(cookie));

        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(users.findByEmail(email).orElseThrow().getSessionVersion()).isEqualTo(versionBefore + 1);
        assertThat(audits.findByEventType(AuditLogService.EVT_AUTH_LOGOUT)).anyMatch(
                a -> user.getId().equals(a.getUserId()) && a.getOutcome() == AuditOutcome.SUCCESS);

        // A copy of the token kept from before signing out is dead, in either transport.
        me(cookie).expectStatus().isUnauthorized();
        http.get().uri("/v1/auth/me").header("Authorization", "Bearer " + cookie)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void logout_givenStaffRole_expectsAllowed() {
        String email = uniqueEmail("logout-support");
        fromApp("/v1/auth/signup", signupBody(email)).exchange().expectStatus().isCreated();
        var user = users.findByEmail(email).orElseThrow();
        user.setRole(UserRole.SUPPORT);
        users.save(user);
        String cookie = setCookie(fromApp("/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult())
                .getValue();

        fromLogout(cookie);

        me(cookie).expectStatus().isUnauthorized();
    }

    @Test
    void logout_givenCookieWithoutOrigin_expectsForbiddenAndSessionKept() {
        String cookie = signIn(uniqueEmail("logout-csrf"));

        http.post().uri("/v1/auth/logout").cookie(cookies.name(), cookie)
                .exchange()
                .expectStatus().isForbidden();

        me(cookie).expectStatus().isOk();
    }

    @Test
    void logout_givenNoSession_expects401() {
        http.post().uri("/v1/auth/logout").header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void logout_givenApiKey_expectsForbidden() {
        String bearer = "Bearer " + http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(signupBody(uniqueEmail("logout-key")))
                .exchange()
                .expectBody(AuthResponse.class).returnResult().getResponseBody().accessToken();

        http.post().uri("/v1/auth/logout").header("Authorization", "Bearer " + apiKey(bearer))
                .exchange()
                .expectStatus().isForbidden();
    }

    // ---------- password reset ------------------------------------------

    @Test
    void passwordReset_givenCookieClient_expectsCookieClearedNoTokenAndSessionsEnded() {
        String email = uniqueEmail("reset-cookie");
        String cookie = signIn(email);

        EntityExchangeResult<byte[]> reset = fromApp("/v1/auth/password/reset",
                Map.of("token", requestResetToken(email), "newPassword", "owners-brand-new-password"))
                .cookie(cookies.name(), cookie)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().returnResult();

        assertThat(setCookie(reset).getMaxAge()).isZero();
        assertThat(reset.getResponseBody()).isNullOrEmpty();
        me(cookie).expectStatus().isUnauthorized();

        // The new password signs in.
        setCookie(fromApp("/v1/auth/login", Map.of("email", email, "password", "owners-brand-new-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult());
    }

    @Test
    void passwordReset_givenNoCookieClientHeader_expectsTokenAsBeforeAndCookieCleared() {
        String email = uniqueEmail("reset-legacy");
        signIn(email);

        EntityExchangeResult<AuthResponse> reset = http.post().uri("/v1/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", requestResetToken(email), "newPassword", "owners-brand-new-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult();

        assertThat(setCookie(reset).getMaxAge()).isZero();
        http.get().uri("/v1/auth/me").header("Authorization", "Bearer " + reset.getResponseBody().accessToken())
                .exchange().expectStatus().isOk();
    }

    // ---------- moving a bearer session into the cookie -----------------

    private AuthResponse bearerSession(Map<String, Object> signup) {
        return http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(signup)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
    }

    @Test
    void adoptSession_givenBearerSession_expectsSameTokenWithItsOwnExpiryInCookie() {
        AuthResponse session = bearerSession(signupBody(uniqueEmail("adopt")));

        ResponseCookie cookie = setCookie(http.post().uri("/v1/auth/session")
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .header("Authorization", "Bearer " + session.accessToken())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().returnResult());

        assertThat(cookie.getValue()).as("not a renewed token").isEqualTo(session.accessToken());
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isLessThanOrEqualTo(
                Duration.between(Instant.now(), session.expiresAt()).plusSeconds(1));
        me(cookie.getValue()).expectStatus().isOk();
    }

    @Test
    void adoptSession_givenChildAwaitingConsent_expectsItsRestrictedSessionInCookie() {
        String email = uniqueEmail("adopt-child");
        Map<String, Object> child = new HashMap<>(signupBody(email));
        child.put("birthMonth", YearMonth.now().minusYears(12).toString());
        child.put("parentEmail", uniqueEmail("parent"));
        AuthResponse session = bearerSession(child);

        String cookie = setCookie(http.post().uri("/v1/auth/session")
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .header("Authorization", "Bearer " + session.accessToken())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().returnResult()).getValue();

        me(cookie).expectStatus().isOk();
        http.get().uri("/v1/keys").cookie(cookies.name(), cookie)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adoptSession_givenApiKey_expectsForbiddenAndNoCookie() {
        String bearer = "Bearer " + bearerSession(signupBody(uniqueEmail("adopt-key"))).accessToken();

        http.post().uri("/v1/auth/session")
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .header("Authorization", "Bearer " + apiKey(bearer))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void adoptSession_givenRevokedToken_expects401AndNoCookie() {
        String token = bearerSession(signupBody(uniqueEmail("adopt-revoked"))).accessToken();
        http.post().uri("/v1/auth/logout").header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNoContent();

        http.post().uri("/v1/auth/session")
                .header(HttpHeaders.ORIGIN, APP_ORIGIN)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }
}
