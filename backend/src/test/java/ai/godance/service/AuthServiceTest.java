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
 * re-sends that token, at most once a minute. {@link AuthService#addEmail}
 * keeps an address for an X.com placeholder account pending until verified.
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

        verify(mail).sendVerificationEmail(u, "holder@godance.test");
        assertThat(u.getEmailVerificationToken()).isEqualTo("tok-keep");
        assertThat(u.getEmailVerificationSentAt()).isAfterOrEqualTo(before);
        verify(users).save(u);
    }

    @Test
    void resendVerificationEmail_givenNoToken_issuesOneAndSends() {
        User u = unverifiedUserWithToken(null);

        auth.resendVerificationEmail(u.getId(), null);

        assertThat(u.getEmailVerificationToken()).isNotBlank();
        verify(mail).sendVerificationEmail(u, "holder@godance.test");
    }

    @Test
    void resendVerificationEmail_givenEmailSentWithinCooldown_rejectsWith429AndSendsNothing() {
        User u = unverifiedUserWithToken("tok-recent");
        Instant recent = Instant.now().minusSeconds(10);
        u.setEmailVerificationSentAt(recent);

        assertRefused(() -> auth.resendVerificationEmail(u.getId(), null), HttpStatus.TOO_MANY_REQUESTS);

        verify(mail, never()).sendVerificationEmail(any(), any());
        assertThat(u.getEmailVerificationSentAt()).isEqualTo(recent);
    }

    @Test
    void resendVerificationEmail_givenVerifiedUser_rejectsWith409AndSendsNothing() {
        User u = unverifiedUserWithToken(null);
        u.setEmailVerified(true);

        assertRefused(() -> auth.resendVerificationEmail(u.getId(), null), HttpStatus.CONFLICT);

        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void resendVerificationEmail_givenSyntheticOAuthAddress_rejectsWith409AndSendsNothing() {
        User u = placeholderUser();

        assertRefused(() -> auth.resendVerificationEmail(u.getId(), null), HttpStatus.CONFLICT);

        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void resendVerificationEmail_givenAddressBeingAdded_sendsToThatAddress() {
        User u = placeholderUser();
        u.setPendingEmail("added@godance.test");
        u.setEmailVerificationToken("tok-added");

        auth.resendVerificationEmail(u.getId(), null);

        verify(mail).sendVerificationEmail(u, "added@godance.test");
    }

    // ---------- addEmail ----------------------------------------------

    /** An X.com sign-in: placeholder address, no password, never mailed. */
    private User placeholderUser() {
        User u = unverifiedUserWithToken(null);
        u.setEmail("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        u.setPasswordHash(null);
        u.setEmailVerificationSentAt(null);
        return u;
    }

    @Test
    void addEmail_givenPlaceholderAccount_keepsAddressPendingAndSendsLinkThere() {
        User u = placeholderUser();

        auth.addEmail(u.getId(), "  Added@Godance.test ", "203.0.113.7");

        assertThat(u.getPendingEmail()).isEqualTo("added@godance.test");
        assertThat(u.getEmail()).isEqualTo("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.getEmailVerificationToken()).isNotBlank();
        verify(users).save(u);
        verify(mail).sendVerificationEmail(u, "added@godance.test");
    }

    @Test
    void addEmail_givenSecondAddress_issuesNewTokenSoTheFirstLinkCannotConfirmIt() {
        User u = placeholderUser();
        u.setPendingEmail("first@godance.test");
        u.setEmailVerificationToken("tok-first");

        auth.addEmail(u.getId(), "second@godance.test", null);

        assertThat(u.getPendingEmail()).isEqualTo("second@godance.test");
        assertThat(u.getEmailVerificationToken()).isNotEqualTo("tok-first");
    }

    @Test
    void addEmail_givenAccountWithRealEmail_rejectsWith409() {
        User u = unverifiedUserWithToken("tok-own");

        assertRefused(() -> auth.addEmail(u.getId(), "other@godance.test", null), HttpStatus.CONFLICT);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void addEmail_givenAddressOfAnotherAccount_rejectsWith409AndSendsNothing() {
        User u = placeholderUser();
        when(users.existsByEmail("taken@godance.test")).thenReturn(true);

        assertRefused(() -> auth.addEmail(u.getId(), "taken@godance.test", null), HttpStatus.CONFLICT);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void addEmail_givenPlaceholderAddress_rejectsWith400() {
        User u = placeholderUser();

        assertRefused(() -> auth.addEmail(u.getId(), "x-7" + User.SYNTHETIC_EMAIL_SUFFIX, null),
                HttpStatus.BAD_REQUEST);

        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void addEmail_givenEmailSentWithinCooldown_rejectsWith429() {
        User u = placeholderUser();
        u.setEmailVerificationSentAt(Instant.now().minusSeconds(10));

        assertRefused(() -> auth.addEmail(u.getId(), "added@godance.test", null),
                HttpStatus.TOO_MANY_REQUESTS);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void verifyEmail_givenAddressBeingAdded_replacesPlaceholderAndMarksVerified() {
        User u = placeholderUser();
        u.setPendingEmail("added@godance.test");
        u.setEmailVerificationToken("tok-added");
        when(users.findByEmailVerificationToken("tok-added")).thenReturn(Optional.of(u));

        auth.verifyEmail(u.getId(), "tok-added", null);

        assertThat(u.getEmail()).isEqualTo("added@godance.test");
        assertThat(u.getPendingEmail()).isNull();
        assertThat(u.isEmailVerified()).isTrue();
        assertThat(u.hasSyntheticEmail()).isFalse();
    }

    @Test
    void verifyEmail_givenAddressTakenSinceItWasAdded_rejectsWith409AndKeepsPlaceholder() {
        User u = placeholderUser();
        u.setPendingEmail("added@godance.test");
        u.setEmailVerificationToken("tok-added");
        when(users.findByEmailVerificationToken("tok-added")).thenReturn(Optional.of(u));
        when(users.existsByEmail("added@godance.test")).thenReturn(true);

        assertRefused(() -> auth.verifyEmail(u.getId(), "tok-added", null), HttpStatus.CONFLICT);

        assertThat(u.getEmail()).isEqualTo("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        assertThat(u.isEmailVerified()).isFalse();
        verify(users, never()).save(any());
    }
}
