package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.web.server.MockWebSession;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The SPA's login nonce rides Spring's own authorization request from
 * {@code /oauth2/authorization/{id}} to the callback, where it lands on
 * the exchange for {@link OAuthLoginSuccessHandler} to echo back. Uses
 * Spring's real resolver and WebSession repository against the Google
 * (OIDC) registration; {@code OAuthLoginNonceFlowTest} covers the whole
 * chain end to end.
 */
class OAuthLoginNonceTest {

    private static final String API = "https://api.godance.test";
    private static final String NONCE = "0123456789abcdef".repeat(2);

    private final ReactiveClientRegistrationRepository registrations =
            new InMemoryReactiveClientRegistrationRepository(CommonOAuth2Provider.GOOGLE
                    .getBuilder("google").clientId("client-1").clientSecret("secret-1").build());

    private static MockServerWebExchange exchange(String url, MockWebSession session) {
        return MockServerWebExchange.builder(MockServerHttpRequest.get(url)).session(session).build();
    }

    private OAuth2AuthorizationRequest start(String query, MockWebSession session) {
        return OAuthLoginNonce.resolver(registrations)
                .resolve(exchange(API + "/oauth2/authorization/google" + query, session))
                .block();
    }

    @Test
    void resolve_givenWellFormedNonce_expectsNonceOnRequestButNotInProviderUrl() {
        OAuth2AuthorizationRequest request = start("?login_nonce=" + NONCE, new MockWebSession());

        assertThat(request.<String>getAttribute(OAuthLoginNonce.PARAM)).isEqualTo(NONCE);
        assertThat(request.getAuthorizationRequestUri())
                .startsWith("https://accounts.google.com/")
                .contains("state=")
                .doesNotContain(NONCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?login_nonce=", "?login_nonce=too-short",
            "?login_nonce=<script>alert(document.cookie)</script>"})
    void resolve_givenMissingOrMalformedNonce_expectsRequestWithoutNonce(String query) {
        OAuth2AuthorizationRequest request = start(query, new MockWebSession());

        assertThat(request.getAuthorizationRequestUri()).startsWith("https://accounts.google.com/");
        assertThat(request.getAttributes()).doesNotContainKey(OAuthLoginNonce.PARAM);
    }

    @Test
    void resolve_givenOverlongNonce_expectsRequestWithoutNonce() {
        OAuth2AuthorizationRequest request = start("?login_nonce=" + "a".repeat(129), new MockWebSession());

        assertThat(request.getAttributes()).doesNotContainKey(OAuthLoginNonce.PARAM);
    }

    @Test
    void removeAuthorizationRequest_givenCallbackForSavedRequest_expectsNonceOnCallbackExchange() {
        MockWebSession session = new MockWebSession();
        ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> repository = OAuthLoginNonce.repository();
        OAuth2AuthorizationRequest request = start("?login_nonce=" + NONCE, session);
        repository.saveAuthorizationRequest(request,
                exchange(API + "/oauth2/authorization/google", session)).block();

        MockServerWebExchange callback = exchange(
                API + "/login/oauth2/code/google?code=code-1&state=" + request.getState(), session);

        assertThat(repository.removeAuthorizationRequest(callback).block()).isEqualTo(request);
        assertThat(OAuthLoginNonce.from(callback)).isEqualTo(NONCE);
    }

    @Test
    void resolve_givenCookieSession_expectsFlagOnRequestButNotInProviderUrl() {
        OAuth2AuthorizationRequest request = start("?session=cookie", new MockWebSession());

        assertThat(request.<String>getAttribute(OAuthLoginNonce.SESSION_PARAM)).isEqualTo("cookie");
        assertThat(request.getAttributes()).doesNotContainKey(OAuthLoginNonce.PARAM);
        assertThat(request.getAuthorizationRequestUri())
                .startsWith("https://accounts.google.com/")
                .doesNotContain("session=");
    }

    @Test
    void resolve_givenOtherSessionValue_expectsNoFlag() {
        OAuth2AuthorizationRequest request = start("?session=bearer", new MockWebSession());

        assertThat(request.getAttributes()).doesNotContainKey(OAuthLoginNonce.SESSION_PARAM);
    }

    @Test
    void removeAuthorizationRequest_givenCookieSession_expectsFlagOnCallbackExchange() {
        MockWebSession session = new MockWebSession();
        ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> repository = OAuthLoginNonce.repository();
        OAuth2AuthorizationRequest request = start("?session=cookie&login_nonce=" + NONCE, session);
        repository.saveAuthorizationRequest(request,
                exchange(API + "/oauth2/authorization/google", session)).block();

        MockServerWebExchange callback = exchange(
                API + "/login/oauth2/code/google?code=code-1&state=" + request.getState(), session);
        repository.removeAuthorizationRequest(callback).block();

        assertThat(OAuthLoginNonce.isCookieClient(callback)).isTrue();
        assertThat(OAuthLoginNonce.from(callback)).isEqualTo(NONCE);
    }

    @Test
    void removeAuthorizationRequest_givenNoCookieSession_expectsNoFlagOnCallbackExchange() {
        MockWebSession session = new MockWebSession();
        ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> repository = OAuthLoginNonce.repository();
        OAuth2AuthorizationRequest request = start("?login_nonce=" + NONCE, session);
        repository.saveAuthorizationRequest(request,
                exchange(API + "/oauth2/authorization/google", session)).block();

        MockServerWebExchange callback = exchange(
                API + "/login/oauth2/code/google?code=code-1&state=" + request.getState(), session);
        repository.removeAuthorizationRequest(callback).block();

        assertThat(OAuthLoginNonce.isCookieClient(callback)).isFalse();
    }

    @Test
    void removeAuthorizationRequest_givenCallbackWithWrongState_expectsNothingOnCallbackExchange() {
        MockWebSession session = new MockWebSession();
        ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> repository = OAuthLoginNonce.repository();
        OAuth2AuthorizationRequest request = start("?login_nonce=" + NONCE, session);
        repository.saveAuthorizationRequest(request,
                exchange(API + "/oauth2/authorization/google", session)).block();

        MockServerWebExchange callback = exchange(
                API + "/login/oauth2/code/google?code=code-1&state=someone-elses-state", session);

        assertThat(repository.removeAuthorizationRequest(callback).block()).isNull();
        assertThat(OAuthLoginNonce.from(callback)).isNull();
    }
}
