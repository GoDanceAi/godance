package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

import ai.godance.config.SecurityProperties;

/**
 * Production's cookie: {@code __Host-} forces browsers to refuse it unless
 * it is Secure, on {@code Path=/} and host-only, so a sibling subdomain can't
 * plant or read one. It stays that way unless config explicitly turns it off.
 */
class SessionCookiesTest {

    private static final String ORIGIN = "https://app.godance.test";

    private final SessionCookies production = new SessionCookies(new SecurityProperties(List.of(ORIGIN), true));
    private final SessionCookies local = new SessionCookies(new SecurityProperties(List.of(ORIGIN), false));

    @Test
    void set_givenSecureConfig_expectsHostPrefixedSecureHttpOnlyStrictCookie() {
        MockServerHttpResponse response = new MockServerHttpResponse();

        production.set(response, "jwt-1", Instant.now().plus(Duration.ofMinutes(60)));

        assertThat(response.getCookies()).containsOnlyKeys("__Host-godance_session");
        ResponseCookie cookie = response.getCookies().getFirst("__Host-godance_session");
        assertThat(cookie.getValue()).isEqualTo("jwt-1");
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge()).isBetween(Duration.ofMinutes(59), Duration.ofMinutes(60));
    }

    @Test
    void set_givenLocalConfig_expectsPlainNameAndNoSecure() {
        MockServerHttpResponse response = new MockServerHttpResponse();

        local.set(response, "jwt-1", Instant.now().plus(Duration.ofMinutes(60)));

        ResponseCookie cookie = response.getCookies().getFirst("godance_session");
        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    void set_afterClear_expectsOneCookieAndItIsTheNewSession() {
        MockServerHttpResponse response = new MockServerHttpResponse();

        production.clear(response);
        production.set(response, "jwt-2", Instant.now().plus(Duration.ofMinutes(60)));

        assertThat(response.getCookies().get(production.name())).hasSize(1);
        assertThat(response.getCookies().getFirst(production.name()).getValue()).isEqualTo("jwt-2");
    }

    @Test
    void read_givenNoOrEmptyCookie_expectsNull() {
        assertThat(production.read(MockServerHttpRequest.get("/").build())).isNull();
        assertThat(production.read(MockServerHttpRequest.get("/")
                .cookie(new HttpCookie(production.name(), "")).build())).isNull();
    }

    @Test
    void read_givenCookieUnderTheOtherName_expectsNull() {
        assertThat(production.read(MockServerHttpRequest.get("/")
                .cookie(new HttpCookie(local.name(), "jwt-1")).build())).isNull();
    }

    @Test
    void sessionCookieSecure_givenNoConfig_expectsSecure() {
        SecurityProperties bound = new Binder(new MapConfigurationPropertySource(
                Map.of("godance.security.cors-allowed-origins", ORIGIN)))
                .bind("godance.security", SecurityProperties.class).get();

        assertThat(bound.sessionCookieSecure()).isTrue();
    }

    @Test
    void prodProfile_expectsSecureCookieNotOverridableFromTheEnvironment() throws IOException {
        var prod = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml")).get(0);

        assertThat(prod.getProperty("godance.security.session-cookie-secure")).hasToString("true");
    }
}
