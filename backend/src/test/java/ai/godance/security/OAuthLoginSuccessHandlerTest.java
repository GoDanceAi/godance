package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.OAuthProperties;
import ai.godance.domain.OAuthProvider;
import ai.godance.service.OAuthLoginService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import reactor.core.publisher.Mono;

/**
 * The handler's half of the email-verification policy: it reads the
 * provider's {@code email_verified} claim into a boolean, and turns a
 * refused sign-in into a redirect the SPA can show. It also echoes the
 * SPA's login nonce on either redirect so the SPA can refuse one it didn't
 * start.
 */
class OAuthLoginSuccessHandlerTest {

    private static final String CALLBACK = "https://app.godance.test/oauth/callback";
    private static final String NONCE = "0123456789abcdef".repeat(2);

    OAuthLoginService oauthLogin;
    OAuthLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        oauthLogin = mock(OAuthLoginService.class);
        handler = new OAuthLoginSuccessHandler(
                oauthLogin, new OAuthProperties(CALLBACK, null, null, null));
    }

    private static OAuth2User principal(Object emailVerifiedClaim) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "subject-1");
        attributes.put("email", "person@godance.test");
        if (emailVerifiedClaim != null) attributes.put("email_verified", emailVerifiedClaim);
        return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"), attributes, "sub");
    }

    private MockServerWebExchange signIn(String registrationId, OAuth2User principal) {
        return signIn(registrationId, principal, null);
    }

    /** {@code loginNonce} stands in for what {@link OAuthLoginNonce} leaves on the callback exchange. */
    private MockServerWebExchange signIn(String registrationId, OAuth2User principal, String loginNonce) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/login/oauth2/code/" + registrationId));
        if (loginNonce != null) exchange.getAttributes().put(OAuthLoginNonce.EXCHANGE_ATTRIBUTE, loginNonce);
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), registrationId);
        handler.onAuthenticationSuccess(new WebFilterExchange(exchange, e -> Mono.empty()), token)
                .block();
        return exchange;
    }

    static List<Arguments> emailVerifiedClaims() {
        return List.of(
                Arguments.of(Boolean.TRUE, true),
                Arguments.of("true", true),
                Arguments.of("TRUE", true),
                Arguments.of(Boolean.FALSE, false),
                Arguments.of("false", false),
                Arguments.of("yes", false),
                Arguments.of(1, false),
                Arguments.of(null, false));
    }

    @ParameterizedTest
    @MethodSource("emailVerifiedClaims")
    void isEmailVerified_givenClaimValue_expectsOnlyTrueOrStringTrueToCount(
            Object claim, boolean expected) {
        assertThat(OAuthLoginSuccessHandler.isEmailVerified(principal(claim))).isEqualTo(expected);
    }

    @Test
    void onAuthenticationSuccess_givenAppleStringEmailVerified_passesVerifiedAndRedirectsWithToken() {
        when(oauthLogin.completeLogin(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(AuthResponse.bearer("jwt-1", Instant.parse("2026-01-01T00:00:00Z")));

        MockServerWebExchange exchange = signIn("apple", principal("true"));

        verify(oauthLogin).completeLogin(
                eq(OAuthProvider.APPLE), eq("subject-1"), eq("person@godance.test"), eq(true), any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString(
                CALLBACK + "#access_token=jwt-1&token_type=Bearer&expires_at=2026-01-01T00%3A00%3A00Z");
    }

    @Test
    void onAuthenticationSuccess_givenMissingClaim_passesUnverified() {
        when(oauthLogin.completeLogin(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(AuthResponse.bearer("jwt-2", Instant.parse("2026-01-01T00:00:00Z")));

        signIn("google", principal(null));

        verify(oauthLogin).completeLogin(
                eq(OAuthProvider.GOOGLE), eq("subject-1"), eq("person@godance.test"), eq(false), any());
    }

    @Test
    void onAuthenticationSuccess_givenRefusedSignIn_redirectsWithErrorFragment() {
        when(oauthLogin.completeLogin(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your Google email address is not verified."));

        MockServerWebExchange exchange = signIn("google", principal(Boolean.FALSE));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString(
                CALLBACK + "#error=Your+Google+email+address+is+not+verified.");
    }

    @Test
    void onAuthenticationSuccess_givenLoginNonce_expectsNonceEchoedAfterToken() {
        when(oauthLogin.completeLogin(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(AuthResponse.bearer("jwt-3", Instant.parse("2026-01-01T00:00:00Z")));

        MockServerWebExchange exchange = signIn("google", principal(Boolean.TRUE), NONCE);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString(
                CALLBACK + "#access_token=jwt-3&token_type=Bearer&expires_at=2026-01-01T00%3A00%3A00Z"
                        + "&login_nonce=" + NONCE);
    }

    @Test
    void onAuthenticationSuccess_givenRefusedSignInWithLoginNonce_expectsNonceEchoedAfterError() {
        when(oauthLogin.completeLogin(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Account disabled."));

        MockServerWebExchange exchange = signIn("google", principal(Boolean.TRUE), NONCE);

        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString(
                CALLBACK + "#error=Account+disabled.&login_nonce=" + NONCE);
    }
}
