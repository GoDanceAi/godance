package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.oauth.*} in application.yml. Holds the social
 * sign-in client credentials and the SPA redirect target the success
 * handler sends the freshly minted JWT to.
 *
 * <p>Real client ids / secrets are operator-supplied via env vars; the
 * {@code application.yml} defaults are dev placeholders that let the
 * context boot without enabling a working login.
 *
 * <p>Apple is special: its OAuth "client secret" is itself a short-lived
 * ES256 JWT signed with the operator's {@code .p8} key — see
 * {@link ai.godance.security.AppleClientSecretGenerator}.
 */
@ConfigurationProperties(prefix = "godance.oauth")
public record OAuthProperties(
        String postLoginRedirectUri,
        Provider google,
        AppleProvider apple,
        Provider x
) {
    /** Standard OAuth2 confidential-client credentials (Google, X). */
    public record Provider(String clientId, String clientSecret) { }

    /** Apple Sign-in: Services ID + the signing-key material for secret-JWT generation. */
    public record AppleProvider(String clientId, String teamId, String keyId, String privateKey) { }
}
