package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

import ai.godance.security.AppleClientSecretGenerator;

/**
 * Guards the fail-open OAuth registration: unconfigured / placeholder /
 * empty credentials must NOT be registered and must NOT crash boot. This
 * locks the regression where an empty {@code GOOGLE_OAUTH_CLIENT_ID=} in
 * {@code .env.prod} threw "clientId cannot be empty" and took prod down.
 */
class OAuthClientConfigTest {

    private final OAuthClientConfig config = new OAuthClientConfig();

    private OAuthProperties props(OAuthProperties.Provider google,
                                  OAuthProperties.AppleProvider apple,
                                  OAuthProperties.Provider x) {
        return new OAuthProperties("https://app.godance.ai/oauth/callback", google, apple, x);
    }

    private static OAuthProperties.Provider provider(String id, String secret) {
        return new OAuthProperties.Provider(id, secret);
    }

    private AppleClientSecretGenerator appleSecret(OAuthProperties props) {
        return new AppleClientSecretGenerator(props);
    }

    private List<String> registrationIds(ReactiveClientRegistrationRepository repo) {
        // The empty case returns a lambda repo (not iterable); treat as none.
        if (!(repo instanceof InMemoryReactiveClientRegistrationRepository inMem)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        inMem.forEach(r -> ids.add(r.getRegistrationId()));
        return ids;
    }

    // ---- hasRealValue predicate ----------------------------------------

    @Test
    void hasRealValue_rejectsBlankPlaceholderAndDevValues() {
        assertThat(OAuthClientConfig.hasRealValue(null)).isFalse();
        assertThat(OAuthClientConfig.hasRealValue("")).isFalse();
        assertThat(OAuthClientConfig.hasRealValue("   ")).isFalse();
        assertThat(OAuthClientConfig.hasRealValue("oauth-not-configured")).isFalse();
        assertThat(OAuthClientConfig.hasRealValue("OAUTH-NOT-CONFIGURED")).isFalse();
        assertThat(OAuthClientConfig.hasRealValue("dev-google-client-id")).isFalse();
        assertThat(OAuthClientConfig.hasRealValue("dev.godance.signin")).isFalse();
    }

    @Test
    void hasRealValue_acceptsRealCredential() {
        assertThat(OAuthClientConfig.hasRealValue("123456789-abcdef.apps.googleusercontent.com")).isTrue();
    }

    // ---- repository assembly -------------------------------------------

    @Test
    void repository_givenNoConfiguredProviders_bootsWithEmptyRepo() {
        // Mirrors prod defaults: every provider on a placeholder, empty
        // Apple key. Must NOT throw (the crash this whole change prevents).
        OAuthProperties props = props(
                provider("oauth-not-configured", "oauth-not-configured"),
                new OAuthProperties.AppleProvider("oauth-not-configured", "oauth-not-configured", "oauth-not-configured", ""),
                provider("oauth-not-configured", "oauth-not-configured"));

        ReactiveClientRegistrationRepository repo =
                config.clientRegistrationRepository(props, appleSecret(props));

        assertThat(repo).isNotNull();
        assertThat(registrationIds(repo)).isEmpty();
        // The empty-repo contract: any lookup resolves to empty, not error.
        assertThat(repo.findByRegistrationId("google").blockOptional()).isEmpty();
    }

    @Test
    void repository_givenEmptyGoogleClientId_doesNotCrashAndSkipsGoogle() {
        // THE regression: empty-but-present GOOGLE_OAUTH_CLIENT_ID.
        OAuthProperties props = props(
                provider("", ""),
                new OAuthProperties.AppleProvider("oauth-not-configured", "", "", ""),
                provider("oauth-not-configured", "oauth-not-configured"));

        ReactiveClientRegistrationRepository repo =
                config.clientRegistrationRepository(props, appleSecret(props));

        assertThat(registrationIds(repo)).doesNotContain("google");
    }

    @Test
    void repository_givenRealGoogleCreds_registersOnlyGoogle() {
        OAuthProperties props = props(
                provider("123-abc.apps.googleusercontent.com", "GOCSPX-real-secret-value"),
                new OAuthProperties.AppleProvider("oauth-not-configured", "", "", ""),
                provider("oauth-not-configured", "oauth-not-configured"));

        ClientRegistration google =
                config.clientRegistrationRepository(props, appleSecret(props))
                        .findByRegistrationId("google").block();

        assertThat(google).isNotNull();
        assertThat(google.getClientId()).isEqualTo("123-abc.apps.googleusercontent.com");
        assertThat(registrationIds(config.clientRegistrationRepository(props, appleSecret(props))))
                .containsExactly("google");
    }

    @Test
    void repository_givenRealGoogleAndX_registersBoth() {
        OAuthProperties props = props(
                provider("123-abc.apps.googleusercontent.com", "GOCSPX-real"),
                new OAuthProperties.AppleProvider("oauth-not-configured", "", "", ""),
                provider("x-real-client-id", "x-real-secret"));

        assertThat(registrationIds(config.clientRegistrationRepository(props, appleSecret(props))))
                .containsExactlyInAnyOrder("google", "x");
    }
}
