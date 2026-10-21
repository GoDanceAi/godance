package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AuditOutcome;
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
 * {@link AuthService#requestEmailChange} needs the caller to prove again who
 * they are, and keeps the new address pending until it is verified.
 */
class AuthServiceTest {

    private static final String HASH = "$2a$12$placeholderplaceholderplaceholderplaceholderplaceh";
    private static final String PASSWORD = "correct-horse-battery-staple";

    UserRepository users;
    MailService mail;
    AuditLogService audit;
    AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        mail = mock(MailService.class);
        audit = mock(AuditLogService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.matches(PASSWORD, HASH)).thenReturn(true);
        auth = new AuthService(users, passwords, mock(JwtService.class), audit, mail);
        when(users.findByEmailVerificationToken(any())).thenReturn(Optional.empty());
    }

    private User unverifiedUserWithToken(String token) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("holder@godance.test");
        u.setFullName("Holder");
        u.setPasswordHash(HASH);
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

    // ---------- requestEmailChange ----------------------------------

    /** An X.com sign-in: placeholder address, no password, never mailed. */
    private User placeholderUser() {
        User u = unverifiedUserWithToken(null);
        u.setEmail("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        u.setPasswordHash(null);
        u.setEmailVerificationSentAt(null);
        return u;
    }

    /** A password account whose current address is verified. */
    private User verifiedPasswordUser() {
        User u = unverifiedUserWithToken(null);
        u.setEmailVerified(true);
        return u;
    }

    private static Instant justSignedIn() {
        return Instant.now().minusSeconds(30);
    }

    @Test
    void requestEmailChange_givenPlaceholderAccountJustSignedIn_keepsAddressPendingAndSendsLinkThere() {
        User u = placeholderUser();

        auth.requestEmailChange(u.getId(), "  Added@Godance.test ", null, justSignedIn(), "203.0.113.7");

        assertThat(u.getPendingEmail()).isEqualTo("added@godance.test");
        assertThat(u.getEmail()).isEqualTo("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.getEmailVerificationToken()).isNotBlank();
        verify(users).save(u);
        verify(mail).sendVerificationEmail(u, "added@godance.test");
    }

    @Test
    void requestEmailChange_givenPasswordAccountAndCorrectPassword_keepsNewAddressPending() {
        User u = verifiedPasswordUser();

        auth.requestEmailChange(u.getId(), "new@godance.test", PASSWORD, null, null);

        assertThat(u.getPendingEmail()).isEqualTo("new@godance.test");
        assertThat(u.getEmail()).isEqualTo("holder@godance.test");
        assertThat(u.isEmailVerified()).isTrue();   // the current address stays verified
        verify(mail).sendVerificationEmail(u, "new@godance.test");
    }

    @Test
    void requestEmailChange_givenWrongPassword_rejectsWith403AndAuditsIt() {
        User u = verifiedPasswordUser();

        assertRefused(() -> auth.requestEmailChange(u.getId(), "new@godance.test", "guess", justSignedIn(), null),
                HttpStatus.FORBIDDEN);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
        verify(audit).recordInNewTransaction(eq(AuditLogService.EVT_AUTH_EMAIL_CHANGE),
                eq(AuditOutcome.FAILURE), eq(u.getId()), any(), any());
    }

    @Test
    void requestEmailChange_givenPasswordAccountAndNoPassword_rejectsWith403() {
        User u = verifiedPasswordUser();

        assertRefused(() -> auth.requestEmailChange(u.getId(), "new@godance.test", null, justSignedIn(), null),
                HttpStatus.FORBIDDEN);

        assertThat(u.getPendingEmail()).isNull();
    }

    @Test
    void requestEmailChange_givenPasswordlessAccountSignedInLongAgo_rejectsWith403() {
        User u = placeholderUser();
        Instant longAgo = Instant.now().minus(AuthService.RECENT_SIGN_IN).minusSeconds(1);

        assertRefused(() -> auth.requestEmailChange(u.getId(), "added@godance.test", null, longAgo, null),
                HttpStatus.FORBIDDEN);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void requestEmailChange_givenSecondAddress_issuesNewTokenSoTheFirstLinkCannotConfirmIt() {
        User u = placeholderUser();
        u.setPendingEmail("first@godance.test");
        u.setEmailVerificationToken("tok-first");

        auth.requestEmailChange(u.getId(), "second@godance.test", null, justSignedIn(), null);

        assertThat(u.getPendingEmail()).isEqualTo("second@godance.test");
        assertThat(u.getEmailVerificationToken()).isNotEqualTo("tok-first");
    }

    @Test
    void requestEmailChange_givenCurrentAddress_rejectsWith400() {
        User u = verifiedPasswordUser();

        assertRefused(() -> auth.requestEmailChange(u.getId(), "Holder@godance.test", PASSWORD, null, null),
                HttpStatus.BAD_REQUEST);

        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void requestEmailChange_givenAddressOfAnotherAccount_rejectsWith409AndSendsNothing() {
        User u = placeholderUser();
        when(users.existsByEmail("taken@godance.test")).thenReturn(true);

        assertRefused(() -> auth.requestEmailChange(u.getId(), "taken@godance.test", null, justSignedIn(), null),
                HttpStatus.CONFLICT);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void requestEmailChange_givenPlaceholderAddress_rejectsWith400() {
        User u = placeholderUser();

        assertRefused(() -> auth.requestEmailChange(u.getId(), "x-7" + User.SYNTHETIC_EMAIL_SUFFIX, null,
                        justSignedIn(), null),
                HttpStatus.BAD_REQUEST);

        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void requestEmailChange_givenEmailSentWithinCooldown_rejectsWith429() {
        User u = placeholderUser();
        u.setEmailVerificationSentAt(Instant.now().minusSeconds(10));

        assertRefused(() -> auth.requestEmailChange(u.getId(), "added@godance.test", null, justSignedIn(), null),
                HttpStatus.TOO_MANY_REQUESTS);

        assertThat(u.getPendingEmail()).isNull();
        verify(mail, never()).sendVerificationEmail(any(), any());
    }

    @Test
    void resendVerificationEmail_givenVerifiedUserChangingAddress_sendsToNewAddress() {
        User u = verifiedPasswordUser();
        u.setPendingEmail("new@godance.test");
        u.setEmailVerificationToken("tok-new");

        auth.resendVerificationEmail(u.getId(), null);

        verify(mail).sendVerificationEmail(u, "new@godance.test");
    }

    // ---------- verifyEmail with a pending address ---------------------

    private User withPendingAddress(User u, String pending) {
        u.setPendingEmail(pending);
        u.setEmailVerificationToken("tok-pending");
        when(users.findByEmailVerificationToken("tok-pending")).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void verifyEmail_givenAddressBeingAdded_replacesPlaceholderAndMarksVerified() {
        User u = withPendingAddress(placeholderUser(), "added@godance.test");

        auth.verifyEmail(u.getId(), "tok-pending", null);

        assertThat(u.getEmail()).isEqualTo("added@godance.test");
        assertThat(u.getPendingEmail()).isNull();
        assertThat(u.isEmailVerified()).isTrue();
        assertThat(u.hasSyntheticEmail()).isFalse();
        verify(mail, never()).sendEmailChangedNotice(any(), any());
    }

    @Test
    void verifyEmail_givenChangeFromVerifiedAddress_switchesAndNotifiesTheOldAddress() {
        User u = withPendingAddress(verifiedPasswordUser(), "new@godance.test");

        auth.verifyEmail(u.getId(), "tok-pending", null);

        assertThat(u.getEmail()).isEqualTo("new@godance.test");
        assertThat(u.isEmailVerified()).isTrue();
        verify(mail).sendEmailChangedNotice("holder@godance.test", u);
    }

    @Test
    void verifyEmail_givenChangeFromUnverifiedAddress_switchesWithoutMailingTheOldAddress() {
        User u = withPendingAddress(unverifiedUserWithToken(null), "fixed-typo@godance.test");

        auth.verifyEmail(u.getId(), "tok-pending", null);

        assertThat(u.getEmail()).isEqualTo("fixed-typo@godance.test");
        assertThat(u.isEmailVerified()).isTrue();
        verify(mail, never()).sendEmailChangedNotice(any(), any());
    }

    @Test
    void verifyEmail_givenAddressTakenSinceItWasRequested_rejectsWith409AndKeepsCurrentEmail() {
        User u = withPendingAddress(placeholderUser(), "added@godance.test");
        when(users.existsByEmail("added@godance.test")).thenReturn(true);

        assertRefused(() -> auth.verifyEmail(u.getId(), "tok-pending", null), HttpStatus.CONFLICT);

        assertThat(u.getEmail()).isEqualTo("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        assertThat(u.isEmailVerified()).isFalse();
        verify(users, never()).save(any());
    }
}
