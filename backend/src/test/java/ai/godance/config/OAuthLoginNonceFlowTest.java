package ai.godance.config;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.OAuthProvider;
import ai.godance.security.OAuthLoginSuccessHandler;
import ai.godance.service.OAuthLoginService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * The SPA's login nonce through the production OAuth chain
 * ({@link SecurityConfig#oauthLoginSecurityChain}): in on
 * {@code /oauth2/authorization/x?login_nonce=}, through the session-stored
 * authorization request and the provider's code callback, and back out in
 * the SPA callback fragment. WireMock stands in for the provider's token
 * and user-info endpoints.
 */
@SpringJUnitConfig(OAuthLoginNonceFlowTest.Config.class)
class OAuthLoginNonceFlowTest {

    private static final String CALLBACK = "https://app.godance.test/oauth/callback";
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final WireMockServer PROVIDER = startProvider();

    @Configuration
    @EnableWebFlux
    @EnableWebFluxSecurity
    static class Config {

        @Bean
        ReactiveClientRegistrationRepository clientRegistrations() {
            return new InMemoryReactiveClientRegistrationRepository(
                    ClientRegistration.withRegistrationId("x")
                            .clientId("client-1")
                            .clientSecret("secret-1")
                            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                            .scope("users.read")
                            .authorizationUri(PROVIDER.baseUrl() + "/authorize")
                            .tokenUri(PROVIDER.baseUrl() + "/token")
                            .userInfoUri(PROVIDER.baseUrl() + "/me")
                            .userNameAttributeName("id")
                            .build());
        }

        @Bean
        OAuthLoginService oauthLoginService() {
            return mock(OAuthLoginService.class);
        }

        @Bean
        SecurityWebFilterChain oauthLoginSecurityChain(ServerHttpSecurity http,
                ReactiveClientRegistrationRepository clientRegistrations, OAuthLoginService oauthLogin) {
            return new SecurityConfig().oauthLoginSecurityChain(http, clientRegistrations,
                    new OAuthLoginSuccessHandler(oauthLogin, new OAuthProperties(CALLBACK, null, null, null)));
        }
    }

    @Autowired ApplicationContext context;
    @Autowired OAuthLoginService oauthLogin;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        reset(oauthLogin);
        client = WebTestClient.bindToApplicationContext(context).configureClient()
                .baseUrl("http://localhost:8080")
                .build();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    private static WireMockServer startProvider() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/token")).willReturn(okJson(
                "{\"access_token\":\"provider-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        server.stubFor(get(urlEqualTo("/me")).willReturn(okJson("{\"id\":\"x-user-1\"}")));
        return server;
    }

    /** Starts a sign-in, comes back from the provider with a code, and returns the redirect to the SPA. */
    private URI signIn(String authorizationPath) {
        EntityExchangeResult<byte[]> start = client.get().uri(authorizationPath)
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult();
        URI authorize = start.getResponseHeaders().getLocation();
        assertThat(authorize).isNotNull();
        assertThat(authorize.toString())
                .startsWith(PROVIDER.baseUrl() + "/authorize?")
                .doesNotContain("login_nonce");
        String state = URLDecoder.decode(
                UriComponentsBuilder.fromUri(authorize).build().getQueryParams().getFirst("state"),
                StandardCharsets.UTF_8);
        ResponseCookie session = start.getResponseCookies().getFirst("SESSION");
        assertThat(session).isNotNull();

        return client.get().uri("/login/oauth2/code/x?code=code-1&state={state}", state)
                .cookie(session.getName(), session.getValue())
                .exchange()
                .expectStatus().isFound()
                .expectBody().returnResult()
                .getResponseHeaders().getLocation();
    }

    @Test
    void oauthLogin_givenLoginNonce_expectsNonceEchoedWithToken() {
        when(oauthLogin.completeLogin(eq(OAuthProvider.X), eq("x-user-1"), any(), anyBoolean(), any()))
                .thenReturn(AuthResponse.bearer("jwt-1", Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(signIn("/oauth2/authorization/x?login_nonce=" + NONCE)).hasToString(CALLBACK
                + "#access_token=jwt-1&token_type=Bearer&expires_at=2026-01-01T00%3A00%3A00Z"
                + "&login_nonce=" + NONCE);
    }

    @Test
    void oauthLogin_givenLoginNonceAndRefusedSignIn_expectsNonceEchoedWithError() {
        when(oauthLogin.completeLogin(eq(OAuthProvider.X), eq("x-user-1"), any(), anyBoolean(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "account disabled"));

        assertThat(signIn("/oauth2/authorization/x?login_nonce=" + NONCE))
                .hasToString(CALLBACK + "#error=account+disabled&login_nonce=" + NONCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/oauth2/authorization/x",
            "/oauth2/authorization/x?login_nonce=",
            "/oauth2/authorization/x?login_nonce=too-short",
            "/oauth2/authorization/x?login_nonce=" + NONCE + ".",
            "/oauth2/authorization/x?login_nonce=" + NONCE + NONCE + NONCE + NONCE + "0"})
    void oauthLogin_givenMissingOrMalformedLoginNonce_expectsNoNonceEchoed(String authorizationPath) {
        when(oauthLogin.completeLogin(eq(OAuthProvider.X), eq("x-user-1"), any(), anyBoolean(), any()))
                .thenReturn(AuthResponse.bearer("jwt-1", Instant.parse("2026-01-01T00:00:00Z")));

        assertThat(signIn(authorizationPath)).hasToString(CALLBACK
                + "#access_token=jwt-1&token_type=Bearer&expires_at=2026-01-01T00%3A00%3A00Z");
    }
}
