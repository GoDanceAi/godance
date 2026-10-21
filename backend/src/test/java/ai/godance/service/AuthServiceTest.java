package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.service.mail.MailService;

/**
 * {@link AuthService#verifyEmail} binds the inbox token to the account's
 * own session, so the owner of an address cannot verify an account that
 * someone else registered with it. {@link AuthService#resendVerificationEmail}
 * re-sends that token, at most once a minute.
 */
class AuthServiceTest {

    UserRepository users;
    MailService mail;
    AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        mail = mock(MailService.class);
        auth = new AuthService(users, mock(PasswordEncoder.class), mock(JwtService.class),
                mock(AuditLogService.class), mail);
        when(users.findByEmailVerificationToken(any())).thenReturn(Optional.empty());
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
}
