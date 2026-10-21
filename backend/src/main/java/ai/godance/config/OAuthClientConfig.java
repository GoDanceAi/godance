package ai.godance.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import reactor.core.publisher.Mono;

import ai.godance.security.AppleClientSecretGenerator;

/**
 * Builds the reactive OAuth2 client registrations for the three social
 * sign-in providers. Registrations are constructed in code (rather than
 * via {@code spring.security.oauth2.client.*} property binding) so that
 * Apple's dynamically generated client-secret JWT can be injected.
 *
 * <p>Providing this bean makes Spring Boot's OAuth2 client autoconfig
 * back off, so these three are the authoritative registration set.
 *
 * <p><b>Fail-open on unconfigured providers.</b> A provider is registered
 * only when its credentials are actually set — non-blank and not one of
 * the well-known dev/prod placeholders. This is deliberate: Spring's
 * {@code ClientRegistration.Builder.build()} throws {@code "clientId
 * cannot be empty"}, so an <em>empty</em> {@code GOOGLE_OAUTH_CLIENT_ID=}
 * in {@code .env.prod} (empty-but-present beats the yml default) used to
 * kill application boot and take the whole API down. Now a missing or
 * blank credential simply omits that provider; if none are configured the
 * repository is empty and social login is disabled, but the app boots and
 * {@code /v1/**} is unaffected.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientConfig.class);

    private static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    /** Placeholder values that must NOT be treated as real credentials. */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "oauth-not-configured", "dev.godance.signin");

    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository(
            OAuthProperties props, AppleClientSecretGenerator appleSecret) {
        List<ClientRegistration> registrations = new ArrayList<>(3);
        if (isConfigured(props.google())) {
            registrations.add(google(props.google()));
        }
        if (isAppleConfigured(props.apple())) {
            registrations.add(apple(props.apple(), appleSecret));
        }
        if (isConfigured(props.x())) {
            registrations.add(x(props.x()));
        }

        if (registrations.isEmpty()) {
            log.warn("No OAuth providers configured — social sign-in disabled "
                    + "(set GOOGLE_OAUTH_CLIENT_ID / _SECRET etc. to enable). "
                    + "The app boots normally; only the social-login buttons are inert.");
            // Empty-but-present repository: the oauth2Login chain consults it
            // per-request and simply finds no registration, rather than the
            // chain failing to build. Keeps startup deterministic.
            return registrationId -> Mono.empty();
        }

        log.info("OAuth providers enabled: {}",
                registrations.stream().map(ClientRegistration::getRegistrationId).toList());
        return new InMemoryReactiveClientRegistrationRepository(registrations);
    }

    /** A standard (Google/X) provider is usable only with a real id AND secret. */
    static boolean isConfigured(OAuthProperties.Provider p) {
        return p != null && hasRealValue(p.clientId()) && hasRealValue(p.clientSecret());
    }

    /** Apple needs a real Services-ID and the signing private key present. */
    static boolean isAppleConfigured(OAuthProperties.AppleProvider a) {
        return a != null && hasRealValue(a.clientId()) && hasRealValue(a.privateKey());
    }

    /** Non-blank and not a dev/prod placeholder (case-insensitive; {@code dev-} prefix or known tokens). */
    static boolean hasRealValue(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        return !s.startsWith("dev-") && !PLACEHOLDERS.contains(s);
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
