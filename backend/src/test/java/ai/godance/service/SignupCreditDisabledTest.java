package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.config.PromoProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.OAuthProvider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;

/**
 * The signup credit as it ships: no {@code PROMO_SIGNUP_CREDIT_CENTS}, so
 * the amount is 0 and none of the five paths writes a ledger row, an audit
 * event or anything else for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class SignupCreditDisabledTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String IP = "203.0.113.10";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired PromoProperties promo;
    @Autowired SignupCreditService signupCredit;
    @Autowired AuthService auth;
    @Autowired OAuthLoginService oauthLogin;
    @Autowired ParentalConsentService parentalConsent;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audits;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void promoProperties_givenNoSetting_expectsZeroAmountAndDefaultDailyLimit() {
        assertThat(promo.signupCreditCents()).isZero();
        assertThat(promo.signupCreditEnabled()).isFalse();
        assertThat(promo.signupCreditDailyLimit()).isEqualTo(200);
    }

    @Test
    void grantIfEligible_givenZeroAmount_expectsNothingRegisteredOnTheCallerTransaction() {
        User u = saveVerifiedUser();

        int registered = new TransactionTemplate(txManager).execute(status -> {
            int before = TransactionSynchronizationManager.getSynchronizations().size();
            signupCredit.grantIfEligible(u);
            return TransactionSynchronizationManager.getSynchronizations().size() - before;
        });

        assertThat(registered).isZero();
        assertNothingWritten(u.getId());
    }

    @Test
    void allFivePaths_givenZeroAmount_expectsNoLedgerRowAndNoPromoAudit() {
        // 1. Email verification
        User verifying = saveUnverifiedUser();
        auth.verifyEmail(verifying.getId(), verifying.getEmailVerificationToken(), IP);
        assertThat(users.findById(verifying.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertNothingWritten(verifying.getId());

        // 2. Password-reset completion
        User resetting = saveUnverifiedUser();
        String token = "reset-" + UUID.randomUUID();
        resetting.setPasswordResetTokenHash(AuthService.sha256Hex(token));
        resetting.setPasswordResetSentAt(Instant.now());
        users.save(resetting);
        auth.resetPassword(new ResetPasswordRequest(token, "a-brand-new-password"), IP);
        assertNothingWritten(resetting.getId());

        // 3. A social sign-in linked to an existing account, verifying it
        User linking = saveUnverifiedUser();
        linking.setPasswordHash(null);
        users.save(linking);
        oauthLogin.completeLogin(OAuthProvider.GOOGLE, "google-" + UUID.randomUUID(),
                linking.getEmail(), true, "Ada");
        assertThat(users.findById(linking.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertNothingWritten(linking.getId());

        // 4. A new account from a social sign-in with an email
        String email = "oauth-new-" + UUID.randomUUID() + "@godance.test";
        oauthLogin.completeLogin(OAuthProvider.APPLE, "apple-" + UUID.randomUUID(), email, true, "Grace");
        assertNothingWritten(users.findByEmail(email).orElseThrow().getId());

        // 5. Parental consent for a child whose email was already verified
        User child = saveVerifiedUser();
        child.setAgeStatus(AgeStatus.CONSENT_PENDING);
        child.setParentEmail("parent-" + UUID.randomUUID() + "@godance.test");
        users.save(child);
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                parentalConsent.grantFromPayment(child.getId(), "cs_test_" + UUID.randomUUID(),
                        "evt_test_" + UUID.randomUUID(), 500, IP));
        assertThat(users.findById(child.getId()).orElseThrow().getAgeStatus())
                .isEqualTo(AgeStatus.CONSENT_GRANTED);
        assertNothingWritten(child.getId());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM credit_ledger WHERE source = 'PROMO'", Long.class)).isZero();
        assertThat(audits.findByEventType(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_GRANTED)).isEmpty();
        assertThat(audits.findByEventType(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_SKIPPED)).isEmpty();
        assertThat(audits.findByEventType(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_FAILED)).isEmpty();
    }

    private User baseUser() {
        User u = new User();
        u.setEmail("signup-" + UUID.randomUUID() + "@godance.test");
        u.setFullName("Test User");
        u.setPasswordHash(passwords.encode(PASSWORD));
        u.setRole(UserRole.USER);
        u.setActive(true);
        return u;
    }

    private User saveUnverifiedUser() {
        User u = baseUser();
        u.setEmailVerified(false);
        u.setEmailVerificationToken("verify-" + UUID.randomUUID());
        u.setEmailVerificationSentAt(Instant.now());
        return users.save(u);
    }

    private User saveVerifiedUser() {
        User u = baseUser();
        u.setEmailVerified(true);
        return users.save(u);
    }

    private void assertNothingWritten(UUID userId) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM credit_ledger WHERE user_id = ?", Long.class, userId)).isZero();
        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isZero();
    }
}
