package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.security.JwtService.IssuedToken;
import ai.godance.security.WebhookSigner;
import ai.godance.service.mail.MailService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.ForgotPasswordRequest;
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;

/**
 * {@link AuthService#verifyEmail} binds the inbox token to the account's
 * own session, so the owner of an address cannot verify an account that
 * someone else registered with it. {@link AuthService#resendVerificationEmail}
 * re-sends that token, at most once a minute. A password reset
 * ({@link AuthService#resetPassword}) hands such an account to the
 * address's owner and locks out whoever set the old password.
 */
class AuthServiceTest {

    private static final String SQUATTER_PASSWORD = "squatter-chosen-password";
    private static final String OWNER_PASSWORD = "owner-chosen-password";

    UserRepository users;
    ApiKeyRepository apiKeys;
    JwtService jwt;
    WebhookSigner webhooks;
    MailService mail;
    // Real BCrypt (minimum cost) so stored hashes are checked for what they hash.
    final PasswordEncoder passwords = new BCryptPasswordEncoder(4);
    AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        apiKeys = mock(ApiKeyRepository.class);
        jwt = mock(JwtService.class);
        webhooks = mock(WebhookSigner.class);
        mail = mock(MailService.class);
        auth = new AuthService(users, apiKeys, passwords, jwt, webhooks,
                mock(AuditLogService.class), mail);
        when(users.findByEmailVerificationToken(any())).thenReturn(Optional.empty());
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByPasswordResetTokenHash(any())).thenReturn(Optional.empty());
        when(jwt.issueFor(any())).thenAnswer(inv -> new IssuedToken(
                "jwt-for-" + inv.<User>getArgument(0).getId(), Instant.now().plusSeconds(3600)));
    }

    private User unverifiedUserWithToken(String token) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("holder@godance.test");
        u.setFullName("Holder");
        u.setPasswordHash("$2a$12$placeholderplaceholderplaceholderplaceholderplaceh");
        u.setRole(UserRole.USER);
        u.setActive(true);
        u.setEmailVerified(false);
        u.setEmailVerificationToken(token);
        u.setEmailVerificationSentAt(Instant.now().minus(Duration.ofMinutes(10)));
        if (token != null) {
            when(users.findByEmailVerificationToken(token)).thenReturn(Optional.of(u));
        }
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private static void assertRefused(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void verifyEmail_givenTokenOfSignedInUser_marksVerifiedAndClearsToken() {
        User u = unverifiedUserWithToken("tok-own");

        auth.verifyEmail(u.getId(), "tok-own", "203.0.113.7");

        assertThat(u.isEmailVerified()).isTrue();
        assertThat(u.getEmailVerificationToken()).isNull();
        verify(users).save(u);
    }

    @Test
    void verifyEmail_givenTokenOfAnotherUser_refusesAndLeavesAccountUnverified() {
        User u = unverifiedUserWithToken("tok-squatted");

        assertThatThrownBy(() -> auth.verifyEmail(UUID.randomUUID(), "tok-squatted", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("different account");
                });

        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.getEmailVerificationToken()).isEqualTo("tok-squatted");
        verify(users, never()).save(any());
    }

    @Test
    void verifyEmail_givenUnknownToken_rejectsWith400() {
        assertRefused(() -> auth.verifyEmail(UUID.randomUUID(), "tok-unknown", null),
                HttpStatus.BAD_REQUEST);

        verify(users, never()).save(any());
    }

    @Test
    void resendVerificationEmail_givenUnverifiedUserPastCooldown_sendsEmailWithSameToken() {
        User u = unverifiedUserWithToken("tok-keep");
        Instant before = Instant.now();

        auth.resendVerificationEmail(u.getId(), "203.0.113.7");

        verify(mail).sendVerificationEmail(u);
        assertThat(u.getEmailVerificationToken()).isEqualTo("tok-keep");
        assertThat(u.getEmailVerificationSentAt()).isAfterOrEqualTo(before);
        verify(users).save(u);
    }

    @Test
    void resendVerificationEmail_givenNoToken_issuesOneAndSends() {
        User u = unverifiedUserWithToken(null);

        auth.resendVerificationEmail(u.getId(), null);

        assertThat(u.getEmailVerificationToken()).isNotBlank();
        verify(mail).sendVerificationEmail(u);
    }

    @Test
    void resendVerificationEmail_givenEmailSentWithinCooldown_rejectsWith429AndSendsNothing() {
        User u = unverifiedUserWithToken("tok-recent");
        Instant recent = Instant.now().minusSeconds(10);
        u.setEmailVerificationSentAt(recent);

        assertRefused(() -> auth.resendVerificationEmail(u.getId(), null), HttpStatus.TOO_MANY_REQUESTS);

        verify(mail, never()).sendVerificationEmail(any());
        assertThat(u.getEmailVerificationSentAt()).isEqualTo(recent);
    }

    @Test
    void resendVerificationEmail_givenVerifiedUser_rejectsWith409AndSendsNothing() {
        User u = unverifiedUserWithToken(null);
        u.setEmailVerified(true);

        assertRefused(() -> auth.resendVerificationEmail(u.getId(), null), HttpStatus.CONFLICT);

        verify(mail, never()).sendVerificationEmail(any());
    }

    @Test
    void resendVerificationEmail_givenSyntheticOAuthAddress_rejectsWith409AndSendsNothing() {
        User u = unverifiedUserWithToken(null);
        u.setEmail("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        u.setPasswordHash(null);

        assertRefused(() -> auth.resendVerificationEmail(u.getId(), null), HttpStatus.CONFLICT);

        verify(mail, never()).sendVerificationEmail(any());
    }

    @Test
    void verifyEmail_givenOwnTokenOlderThanTtl_rejectsWith400AndLeavesUnverified() {
        User u = unverifiedUserWithToken("tok-old");
        u.setEmailVerificationSentAt(Instant.now().minus(AuthService.VERIFICATION_TTL).minusSeconds(1));

        assertThatThrownBy(() -> auth.verifyEmail(u.getId(), "tok-old", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("expired");
                });

        assertThat(u.isEmailVerified()).isFalse();
        verify(users, never()).save(any());
    }

    // ---------- password reset ----------------------------------------

    /** Someone signed up with the owner's address and a password of their own. */
    private User squattedAccount(String email) {
        User u = unverifiedUserWithToken("tok-squatter");
        u.setEmail(email);
        u.setPasswordHash(passwords.encode(SQUATTER_PASSWORD));
        when(users.findByEmail(email)).thenReturn(Optional.of(u));
        // Resolve a reset token the way the database would: by its stored hash.
        when(users.findByPasswordResetTokenHash(any())).thenAnswer(inv ->
                inv.getArgument(0).equals(u.getPasswordResetTokenHash())
                        ? Optional.of(u) : Optional.empty());
        return u;
    }

    /** Request a reset for {@code email} and return the token the email carried. */
    private String requestResetToken(String email) {
        auth.requestPasswordReset(new ForgotPasswordRequest(email), "203.0.113.7");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendPasswordResetEmail(any(User.class), token.capture());
        return token.getValue();
    }

    @Test
    void requestPasswordReset_givenRegisteredEmail_storesOnlyTokenHashAndEmailsToken() {
        User u = squattedAccount("owner@godance.test");

        String token = requestResetToken(" Owner@Godance.test ");

        assertThat(token).isNotBlank();
        assertThat(u.getPasswordResetTokenHash())
                .isEqualTo(AuthService.sha256Hex(token))
                .isNotEqualTo(token);
        assertThat(u.getPasswordResetSentAt()).isNotNull();
        verify(mail).sendPasswordResetEmail(eq(u), anyString());
    }

    @Test
    void requestPasswordReset_givenUnknownEmail_sendsNothing() {
        auth.requestPasswordReset(new ForgotPasswordRequest("nobody@godance.test"), null);

        verify(mail, never()).sendPasswordResetEmail(any(), any());
        verify(users, never()).save(any());
    }

    @Test
    void requestPasswordReset_givenRequestWithinCooldown_sendsNothing() {
        User u = squattedAccount("owner@godance.test");
        u.setPasswordResetTokenHash("hash-of-earlier-token");
        u.setPasswordResetSentAt(Instant.now().minusSeconds(10));

        auth.requestPasswordReset(new ForgotPasswordRequest("owner@godance.test"), null);

        assertThat(u.getPasswordResetTokenHash()).isEqualTo("hash-of-earlier-token");
        verify(mail, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void requestPasswordReset_givenInactiveUser_sendsNothing() {
        User u = squattedAccount("owner@godance.test");
        u.setActive(false);

        auth.requestPasswordReset(new ForgotPasswordRequest("owner@godance.test"), null);

        assertThat(u.getPasswordResetTokenHash()).isNull();
        verify(mail, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void resetPassword_givenValidToken_replacesPasswordAndEvictsPreviousHolder() {
        User squatted = squattedAccount("owner@godance.test");
        String token = requestResetToken("owner@godance.test");

        AuthResponse response = auth.resetPassword(
                new ResetPasswordRequest(token, OWNER_PASSWORD), "203.0.113.7");

        assertThat(passwords.matches(OWNER_PASSWORD, squatted.getPasswordHash())).isTrue();
        assertThat(passwords.matches(SQUATTER_PASSWORD, squatted.getPasswordHash())).isFalse();
        // The link proved the inbox, and the password is now that person's.
        assertThat(squatted.isEmailVerified()).isTrue();
        assertThat(squatted.getEmailVerificationToken()).isNull();
        assertThat(squatted.getPasswordResetTokenHash()).isNull();
        // Every JWT issued before the reset now fails the session-version check.
        assertThat(squatted.getSessionVersion()).isEqualTo(1);
        verify(apiKeys).deleteByUserId(squatted.getId());
        verify(webhooks).rotateSecretWithoutGrace(squatted.getId());
        assertThat(response.accessToken()).isEqualTo("jwt-for-" + squatted.getId());
    }

    @Test
    void resetPassword_givenTokenAlreadyUsed_rejectsWith400() {
        User u = squattedAccount("owner@godance.test");
        String token = requestResetToken("owner@godance.test");
        auth.resetPassword(new ResetPasswordRequest(token, OWNER_PASSWORD), null);

        assertRefused(() -> auth.resetPassword(
                new ResetPasswordRequest(token, "someone-elses-password"), null), HttpStatus.BAD_REQUEST);

        assertThat(passwords.matches(OWNER_PASSWORD, u.getPasswordHash())).isTrue();
        assertThat(u.getSessionVersion()).isEqualTo(1);
    }

    @Test
    void resetPassword_givenExpiredToken_rejectsWith400AndChangesNothing() {
        User u = squattedAccount("owner@godance.test");
        String token = requestResetToken("owner@godance.test");
        u.setPasswordResetSentAt(Instant.now().minus(AuthService.PASSWORD_RESET_TTL).minusSeconds(1));

        assertRefused(() -> auth.resetPassword(
                new ResetPasswordRequest(token, OWNER_PASSWORD), null), HttpStatus.BAD_REQUEST);

        assertThat(passwords.matches(SQUATTER_PASSWORD, u.getPasswordHash())).isTrue();
        assertThat(u.getSessionVersion()).isZero();
        verify(apiKeys, never()).deleteByUserId(any());
        verify(webhooks, never()).rotateSecretWithoutGrace(any());
        verify(jwt, never()).issueFor(any());
    }

    @Test
    void resetPassword_givenInactiveUser_rejectsWith403AndChangesNothing() {
        User u = squattedAccount("owner@godance.test");
        String token = requestResetToken("owner@godance.test");
        u.setActive(false);

        assertRefused(() -> auth.resetPassword(
                new ResetPasswordRequest(token, OWNER_PASSWORD), null), HttpStatus.FORBIDDEN);

        assertThat(passwords.matches(SQUATTER_PASSWORD, u.getPasswordHash())).isTrue();
        verify(apiKeys, never()).deleteByUserId(any());
        verify(jwt, never()).issueFor(any());
    }

    @Test
    void resetPassword_givenUnknownToken_rejectsWith400() {
        assertRefused(() -> auth.resetPassword(
                new ResetPasswordRequest("no-such-token", OWNER_PASSWORD), null), HttpStatus.BAD_REQUEST);

        verify(users, never()).save(any());
        verify(jwt, never()).issueFor(any());
    }
}
