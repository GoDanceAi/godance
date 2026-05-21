package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ai.godance.domain.OAuthProvider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.OAuthIdentityRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Exercises the OAuth link-or-create logic with a mocked provider result
 * (a {@code provider}/{@code subject}/{@code email} triple) — the same
 * data the Spring Security success handler would extract from a real
 * provider — without driving the full redirect dance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class OAuthLoginServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired OAuthLoginService oauthLogin;
    @Autowired UserRepository users;
    @Autowired OAuthIdentityRepository identities;
    @Autowired ReactiveJwtDecoder jwtDecoder;

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    @Test
    void completeLogin_givenNewProviderSubject_createsUserAndIdentity_andIssuesValidJwt() {
        String subject = "google-" + UUID.randomUUID();
        String email = uniqueEmail("oauth-new");

        AuthResponse response = oauthLogin.completeLogin(
                OAuthProvider.GOOGLE, subject, email, "Ada Lovelace");

        assertThat(response.tokenType()).isEqualTo("Bearer");
        Jwt decoded = jwtDecoder.decode(response.accessToken()).block();
        assertThat(decoded).isNotNull();

        User created = users.findByEmail(email).orElseThrow();
        assertThat(decoded.getSubject()).isEqualTo(created.getId().toString());
        assertThat(created.isEmailVerified()).isTrue();      // provider-verified email
        assertThat(created.getPasswordHash()).isNull();      // OAuth-only account
        assertThat(identities.findByProviderAndSubject(OAuthProvider.GOOGLE, subject))
                .isPresent()
                .get()
                .satisfies(id -> assertThat(id.getUserId()).isEqualTo(created.getId()));
    }

    @Test
    void completeLogin_givenEmailOfExistingUser_linksWithoutCreatingNewUser() {
        String email = uniqueEmail("oauth-link");
        User existing = new User();
        existing.setEmail(email);
        existing.setFullName("Existing User");
        existing.setPasswordHash("$2a$12$placeholderplaceholderplaceholderplaceholderplaceh");
        existing.setRole(UserRole.USER);
        existing.setActive(true);
        existing.setEmailVerified(false);
        users.save(existing);

        String subject = "apple-" + UUID.randomUUID();
        AuthResponse response = oauthLogin.completeLogin(
                OAuthProvider.APPLE, subject, email, "Existing User");

        Jwt decoded = jwtDecoder.decode(response.accessToken()).block();
        assertThat(decoded).isNotNull();
        assertThat(decoded.getSubject()).isEqualTo(existing.getId().toString());
        // The OAuth identity links to the existing account; email is now verified.
        assertThat(identities.findByProviderAndSubject(OAuthProvider.APPLE, subject))
                .get()
                .satisfies(id -> assertThat(id.getUserId()).isEqualTo(existing.getId()));
        assertThat(users.findById(existing.getId()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void completeLogin_givenKnownProviderSubject_resolvesToSameUser() {
        String subject = "x-" + UUID.randomUUID();
        String email = uniqueEmail("oauth-repeat");

        AuthResponse first = oauthLogin.completeLogin(OAuthProvider.X, subject, email, "Repeat");
        AuthResponse second = oauthLogin.completeLogin(OAuthProvider.X, subject, email, "Repeat");

        Jwt firstJwt = jwtDecoder.decode(first.accessToken()).block();
        Jwt secondJwt = jwtDecoder.decode(second.accessToken()).block();
        assertThat(firstJwt).isNotNull();
        assertThat(secondJwt).isNotNull();
        assertThat(secondJwt.getSubject()).isEqualTo(firstJwt.getSubject());
    }

    @Test
    void completeLogin_givenNoEmail_createsUserWithSyntheticAddress() {
        String subject = "x-noemail-" + UUID.randomUUID();

        AuthResponse response = oauthLogin.completeLogin(OAuthProvider.X, subject, null, null);

        Jwt decoded = jwtDecoder.decode(response.accessToken()).block();
        assertThat(decoded).isNotNull();
        UUID userId = UUID.fromString(decoded.getSubject());
        User created = users.findById(userId).orElseThrow();
        assertThat(created.getEmail()).contains(subject);
        assertThat(created.isEmailVerified()).isFalse();     // no provider email to trust
    }
}
