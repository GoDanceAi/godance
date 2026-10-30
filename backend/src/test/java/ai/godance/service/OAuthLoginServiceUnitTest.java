package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.OAuthIdentity;
import ai.godance.domain.OAuthProvider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.OAuthIdentityRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.security.JwtService.IssuedToken;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Link-or-create rules of {@link OAuthLoginService} against mocked
 * repositories, so the email-verification policy runs without Docker.
 * {@link OAuthLoginServiceTest} covers the same flow against Postgres.
 */
class OAuthLoginServiceUnitTest {

    private static final String PASSWORD_HASH =
            "$2a$12$placeholderplaceholderplaceholderplaceholderplaceh";

    UserRepository users;
    OAuthIdentityRepository identities;
    JwtService jwt;
    TwoFactorService twoFactor;
    OAuthLoginService oauthLogin;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        identities = mock(OAuthIdentityRepository.class);
        jwt = mock(JwtService.class);
        twoFactor = mock(TwoFactorService.class);
        oauthLogin = new OAuthLoginService(users, identities, jwt, mock(AuditLogService.class), twoFactor);

        when(identities.findByProviderAndSubject(any(), any())).thenReturn(Optional.empty());
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        // Stamp an id on first save, as the database would.
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
        when(jwt.issueFor(any())).thenAnswer(inv -> new IssuedToken(
                "jwt-for-" + inv.<User>getArgument(0).getId(), Instant.now().plusSeconds(3600)));
    }

    private User existingUser(String email, String passwordHash, boolean emailVerified) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setFullName("Existing User");
        u.setPasswordHash(passwordHash);
        u.setRole(UserRole.USER);
        u.setActive(true);
        u.setEmailVerified(emailVerified);
        when(users.findByEmail(email)).thenReturn(Optional.of(u));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    /** As left by password signup: a password, email not yet verified. */
    private User existingPasswordUser(String email) {
        return existingUser(email, PASSWORD_HASH, false);
    }

    private OAuthIdentity savedIdentity() {
        ArgumentCaptor<OAuthIdentity> saved = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(identities).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void completeLogin_givenTwoFactorOn_returnsTheChallengeAndNoSession() {
        User existing = existingUser("two-factor@godance.test", PASSWORD_HASH, true);
        AuthResponse challenge = AuthResponse.twoFactor("challenge", Instant.now().plusSeconds(300));
        when(twoFactor.isEnabled(existing.getId())).thenReturn(true);
        when(twoFactor.challenge(existing, "oauth:GOOGLE", null)).thenReturn(challenge);

        AuthResponse response = oauthLogin.completeLogin(
                OAuthProvider.GOOGLE, "google-2fa", "two-factor@godance.test", true, "Linked");

        assertThat(response).isSameAs(challenge);
        assertThat(existing.getLastLoginAt()).isNull();
        verify(jwt, never()).issueFor(any());
    }

    @Test
    void completeLogin_givenVerifiedEmailOfVerifiedPasswordUser_linksAndKeepsPassword() {
        User existing = existingUser("linked@godance.test", PASSWORD_HASH, true);

        AuthResponse response = oauthLogin.completeLogin(
                OAuthProvider.GOOGLE, "google-123", "Linked@Godance.test", true, "Linked");

        assertThat(response.accessToken()).isEqualTo("jwt-for-" + existing.getId());
        OAuthIdentity identity = savedIdentity();
        assertThat(identity.getUserId()).isEqualTo(existing.getId());
        assertThat(identity.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(identity.getSubject()).isEqualTo("google-123");
        assertThat(existing.isEmailVerified()).isTrue();
        assertThat(existing.getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void completeLogin_givenVerifiedEmailOfUnverifiedPasswordlessUser_linksAndMarksEmailVerified() {
        User existing = existingUser("passwordless@godance.test", null, false);

        AuthResponse response = oauthLogin.completeLogin(
                OAuthProvider.APPLE, "apple-123", "passwordless@godance.test", true, "Passwordless");

        assertThat(response.accessToken()).isEqualTo("jwt-for-" + existing.getId());
        assertThat(savedIdentity().getUserId()).isEqualTo(existing.getId());
        assertThat(existing.isEmailVerified()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OAuthProvider.class, names = {"GOOGLE", "APPLE"})
    void completeLogin_givenVerifiedEmailOfUnverifiedPasswordUser_refusesWithoutLinking(
            OAuthProvider provider) {
        // Pre-hijack: someone signed up with the owner's address and a
        // password of their choosing, then the owner arrives via a provider.
        User squatted = existingPasswordUser("owner@godance.test");

        assertThatThrownBy(() -> oauthLogin.completeLogin(
                provider, "owner-subject", "Owner@Godance.test", true, "Owner"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason())
                            .contains("Sign in with your password and verify your email")
                            .contains("reset the password if you don't know it")
                            .contains("then sign in with "
                                    + (provider == OAuthProvider.GOOGLE ? "Google" : "Apple") + ".")
                            .contains("release the address");
                });

        verify(identities, never()).save(any());
        verify(users, never()).save(any());
        verify(jwt, never()).issueFor(any());
        assertThat(squatted.isEmailVerified()).isFalse();
        assertThat(squatted.getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void completeLogin_givenUnverifiedEmailOfExistingUser_refusesWithoutLinking() {
        User existing = existingPasswordUser("victim@godance.test");

        assertThatThrownBy(() -> oauthLogin.completeLogin(
                OAuthProvider.APPLE, "apple-attacker", "victim@godance.test", false, "Attacker"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("Apple email address is not verified");
                });

        verify(identities, never()).save(any());
        verify(users, never()).save(any());
        verify(jwt, never()).issueFor(any());
        assertThat(existing.isEmailVerified()).isFalse();
    }

    @Test
    void completeLogin_givenUnverifiedEmailAndNoExistingUser_refusesWithoutCreatingUser() {
        assertThatThrownBy(() -> oauthLogin.completeLogin(
                OAuthProvider.GOOGLE, "google-456", "nobody@godance.test", false, "Nobody"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(users, never()).save(any());
        verify(identities, never()).save(any());
    }

    @Test
    void completeLogin_givenVerifiedEmailAndNoExistingUser_createsVerifiedUser() {
        oauthLogin.completeLogin(OAuthProvider.GOOGLE, "google-789", "new@godance.test", true, "New");

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users, atLeastOnce()).save(created.capture());
        User user = created.getValue();
        assertThat(user.getEmail()).isEqualTo("new@godance.test");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(savedIdentity().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void completeLogin_givenNoEmail_createsNewUserWithSyntheticAddress() {
        oauthLogin.completeLogin(OAuthProvider.X, "x-42", null, false, null);

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users, atLeastOnce()).save(created.capture());
        User user = created.getValue();
        assertThat(user.getEmail()).isEqualTo("x-x-42@oauth.godance.local");
        assertThat(user.hasSyntheticEmail()).isTrue();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(savedIdentity().getUserId()).isEqualTo(user.getId());
        verify(users, never()).findByEmail(any());
    }

    @ParameterizedTest
    @CsvSource({
            "someone-else@godance.test, false",
            "someone-else@godance.test, true",
            ", false",
    })
    void completeLogin_givenKnownIdentity_resolvesToSameUserRegardlessOfEmail(
            String email, boolean emailVerified) {
        User owner = existingPasswordUser("owner@godance.test");
        OAuthIdentity identity = new OAuthIdentity();
        identity.setUserId(owner.getId());
        identity.setProvider(OAuthProvider.GOOGLE);
        identity.setSubject("google-owner");
        when(identities.findByProviderAndSubject(OAuthProvider.GOOGLE, "google-owner"))
                .thenReturn(Optional.of(identity));

        AuthResponse response = oauthLogin.completeLogin(
                OAuthProvider.GOOGLE, "google-owner", email, emailVerified, "Owner");

        assertThat(response.accessToken()).isEqualTo("jwt-for-" + owner.getId());
        verify(identities, never()).save(any());
        verify(users, never()).findByEmail(any());
        assertThat(owner.getEmail()).isEqualTo("owner@godance.test");
        assertThat(owner.isEmailVerified()).isFalse();
    }
}
