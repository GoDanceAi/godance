package ai.godance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import ai.godance.security.AppleClientSecretGenerator;

/**
 * Builds the reactive OAuth2 client registrations for the three social
 * sign-in providers. Registrations are constructed in code (rather than
 * via {@code spring.security.oauth2.client.*} property binding) so that
 * Apple's dynamically generated client-secret JWT can be injected.
 *
 * <p>Providing this bean makes Spring Boot's OAuth2 client autoconfig
 * back off, so these three are the authoritative registration set.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthClientConfig {

    private static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository(
            OAuthProperties props, AppleClientSecretGenerator appleSecret) {
        return new InMemoryReactiveClientRegistrationRepository(
                google(props.google()),
                apple(props.apple(), appleSecret),
                x(props.x()));
    }

    /** Google is a standard OIDC provider — reuse Spring's common defaults. */
    private static ClientRegistration google(OAuthProperties.Provider g) {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(g.clientId())
                .clientSecret(g.clientSecret())
                .build();
    }

    /**
     * Apple Sign-in — OIDC, identity carried in the ID token (no userinfo
     * endpoint). The {@code client_secret} is the generated ES256 JWT.
     */
    private static ClientRegistration apple(OAuthProperties.AppleProvider a,
                                            AppleClientSecretGenerator appleSecret) {
        return ClientRegistration.withRegistrationId("apple")
                .clientId(a.clientId())
                .clientSecret(appleSecret.currentSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("openid", "email", "name")
                .authorizationUri("https://appleid.apple.com/auth/authorize")
                .tokenUri("https://appleid.apple.com/auth/token")
                .jwkSetUri("https://appleid.apple.com/auth/keys")
                .issuerUri("https://appleid.apple.com")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Apple")
                .build();
    }

    /**
     * X.com (Twitter) OAuth2 — not OIDC; identity comes from the
     * {@code /2/users/me} endpoint. Real logins also require PKCE; a
     * PKCE-forcing authorization-request resolver is the operator
     * follow-up noted in the PR body.
     */
    private static ClientRegistration x(OAuthProperties.Provider x) {
        return ClientRegistration.withRegistrationId("x")
                .clientId(x.clientId())
                .clientSecret(x.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("users.read", "tweet.read")
                .authorizationUri("https://twitter.com/i/oauth2/authorize")
                .tokenUri("https://api.twitter.com/2/oauth2/token")
                .userInfoUri("https://api.twitter.com/2/users/me")
                .userNameAttributeName("id")
                .clientName("X")
                .build();
    }
}
