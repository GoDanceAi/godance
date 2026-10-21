package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
 * Email-verification rules of {@link AuthService} against a mocked
 * repository. {@code AuthAndApiKeyIntegrationTest} covers the same
 * endpoint over HTTP against Postgres.
 */
class AuthServiceTest {

    UserRepository users;
    AuditLogService audit;
    AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        audit = mock(AuditLogService.class);
        auth = new AuthService(users, mock(PasswordEncoder.class), mock(JwtService.class),
                audit, mock(MailService.class));
        when(users.findByEmailVerificationToken(any())).thenReturn(Optional.empty());
    }

    /** As left by password signup: a password, a pending verification token. */
    private User signedUpUser(String email, String token) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setFullName("Signed Up");
        u.setPasswordHash("$2a$12$placeholderplaceholderplaceholderplaceholderplaceh");
        u.setRole(UserRole.USER);
        u.setActive(true);
        u.setEmailVerified(false);
        u.setEmailVerificationToken(token);
        when(users.findByEmailVerificationToken(token)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void verifyEmail_givenTokenOfCallersAccount_marksEmailVerifiedAndClearsToken() {
        User u = signedUpUser("owner@godance.test", "tok-owner");

        auth.verifyEmail("tok-owner", u.getId(), "203.0.113.7");

        assertThat(u.isEmailVerified()).isTrue();
        assertThat(u.getEmailVerificationToken()).isNull();
        verify(users).save(u);
    }

    @Test
    void verifyEmail_givenTokenOfAnotherAccount_refusesWithoutVerifying() {
        // Pre-hijack: someone registered the owner's address with their own
        // password; the owner, signed in to a different account, opens the
        // link from the unsolicited verification email.
        User squatted = signedUpUser("owner@godance.test", "tok-squatted");
        UUID otherAccount = UUID.randomUUID();

        assertThatThrownBy(() -> auth.verifyEmail("tok-squatted", otherAccount, "203.0.113.7"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("different account");
                });

        assertThat(squatted.isEmailVerified()).isFalse();
        assertThat(squatted.getEmailVerificationToken()).isEqualTo("tok-squatted");
        verify(users, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void verifyEmail_givenUnknownToken_rejectsWith400() {
        assertThatThrownBy(() -> auth.verifyEmail("no-such-token", UUID.randomUUID(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(users, never()).save(any());
        verifyNoInteractions(audit);
    }
}
