package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.OAuthProvider;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.SignupCreditService.Outcome;
import ai.godance.service.admin.AdminConsoleService;
import ai.godance.service.admin.AdminConsoleService.LedgerEntryView;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;

/**
 * The signup credit with an amount set: each of the five places an email
 * becomes verified (or consent completes a verified account) grants it
 * exactly once, any two of them in sequence still grant once, and nothing
 * else ever does. {@link SignupCreditDisabledTest} covers the default
 * amount of 0, {@link SignupCreditDailyLimitTest} the daily limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.promo.signup-credit-cents=" + SignupCreditServiceTest.AMOUNT,
        "godance.promo.signup-credit-daily-limit=100000"})
@Testcontainers
class SignupCreditServiceTest {

    static final long AMOUNT = 100;
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String IP = "203.0.113.9";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired SignupCreditService signupCredit;
    @Autowired AuthService auth;
    @Autowired OAuthLoginService oauthLogin;
    @Autowired ParentalConsentService parentalConsent;
    @Autowired AdminConsoleService adminConsole;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audits;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean CreditLedgerService ledgerService;

    // ===== The five paths, each granting once ==========================

    @Test
    void verifyEmail_givenAmountSet_expectsOnePromoEntryAndGrantedAudit() {
        User u = saveUnverifiedPasswordUser();

        auth.verifyEmail(u.getId(), u.getEmailVerificationToken(), IP);

        assertGrantedOnce(u.getId());
        assertThat(auditsFor(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_GRANTED, u.getId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
                    assertThat(((Number) e.getEventDetail().get("amountCents")).longValue()).isEqualTo(AMOUNT);
                });
    }

    @Test
    void resetPassword_givenUnverifiedAccount_expectsOneGrant() {
        User u = saveUnverifiedPasswordUser();
        String token = startPasswordReset(u);

        auth.resetPassword(new ResetPasswordRequest(token, "a-brand-new-password"), IP);

        assertThat(users.findById(u.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertGrantedOnce(u.getId());
    }

    @Test
    void completeLogin_givenLinkThatVerifiesExistingAccount_expectsOneGrant() {
        String email = uniqueEmail("link");
        User u = baseUser(email);
        u.setPasswordHash(null);            // an OAuth-only account whose email was never verified
        u.setEmailVerified(false);
        users.save(u);

        oauthLogin.completeLogin(OAuthProvider.GOOGLE, "google-" + UUID.randomUUID(), email, true, "Ada");

        assertThat(users.findById(u.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertGrantedOnce(u.getId());
    }

    @Test
    void completeLogin_givenNewAccountWithEmail_expectsOneGrant() {
        String email = uniqueEmail("oauth-new");

        oauthLogin.completeLogin(OAuthProvider.APPLE, "apple-" + UUID.randomUUID(), email, true, "Grace");

        assertGrantedOnce(users.findByEmail(email).orElseThrow().getId());
    }

    @Test
    void grantFromPayment_givenChildWhoVerifiedWhileWaiting_expectsNothingOnVerifyThenOneGrantOnConsent() {
        User child = savePendingChild();

        auth.verifyEmail(child.getId(), child.getEmailVerificationToken(), IP);
        assertThat(users.findById(child.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertNoLedgerRows(child.getId());

        grantConsentAsTheWebhookWould(child.getId());

        assertThat(users.findById(child.getId()).orElseThrow().getAgeStatus())
                .isEqualTo(AgeStatus.CONSENT_GRANTED);
        assertGrantedOnce(child.getId());
    }

    @Test
    void verifyEmail_givenChildConsentedBeforeVerifying_expectsGrantOnVerification() {
        User child = savePendingChild();

        grantConsentAsTheWebhookWould(child.getId());
        assertNoLedgerRows(child.getId());

        auth.verifyEmail(child.getId(), child.getEmailVerificationToken(), IP);

        assertGrantedOnce(child.getId());
    }

    // ===== Once per account, whatever the paths ========================

    @Test
    void verifyEmail_thenResetPassword_expectsStillOneGrant() {
        User u = saveUnverifiedPasswordUser();
        auth.verifyEmail(u.getId(), u.getEmailVerificationToken(), IP);
        String token = startPasswordReset(users.findById(u.getId()).orElseThrow());

        auth.resetPassword(new ResetPasswordRequest(token, "a-brand-new-password"), IP);

        assertGrantedOnce(u.getId());
    }

    @Test
    void resetPassword_thenOAuthLinkAndConsentCall_expectsStillOneGrant() {
        User u = saveUnverifiedPasswordUser();
        auth.resetPassword(new ResetPasswordRequest(startPasswordReset(u), "a-brand-new-password"), IP);

        oauthLogin.completeLogin(OAuthProvider.GOOGLE, "google-" + UUID.randomUUID(), u.getEmail(), true, "Ada");
        signupCredit.grantIfEligible(users.findById(u.getId()).orElseThrow());

        assertGrantedOnce(u.getId());
    }

    @Test
    void verifyEmail_givenChangedEmailVerifiedAgain_expectsNothingMore() {
        User u = saveUnverifiedPasswordUser();
        auth.verifyEmail(u.getId(), u.getEmailVerificationToken(), IP);
        User verified = users.findById(u.getId()).orElseThrow();
        verified.setEmailVerificationSentAt(Instant.now().minusSeconds(120));   // past the resend cooldown
        users.save(verified);
        String newEmail = uniqueEmail("changed");
        auth.requestEmailChange(u.getId(), newEmail, PASSWORD, null, IP);
        String token = users.findById(u.getId()).orElseThrow().getEmailVerificationToken();

        auth.verifyEmail(u.getId(), token, IP);

        assertThat(users.findById(u.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);
        assertGrantedOnce(u.getId());
    }

    @Test
    void grantNow_givenSecondCall_expectsAlreadyGranted() {
        User u = saveVerifiedPasswordUser();

        assertThat(signupCredit.grantNow(u.getId())).isEqualTo(Outcome.GRANTED);
        assertThat(signupCredit.grantNow(u.getId())).isEqualTo(Outcome.ALREADY_GRANTED);

        assertGrantedOnce(u.getId());
    }

    // ===== Who gets nothing ===========================================

    @Test
    void completeLogin_givenPlaceholderEmail_expectsNothingUntilRealAddressVerified() {
        String subject = "x-" + UUID.randomUUID();
        oauthLogin.completeLogin(OAuthProvider.X, subject, null, false, "Xavier");
        User u = users.findByEmail("x-" + subject + User.SYNTHETIC_EMAIL_SUFFIX).orElseThrow();
        assertThat(u.hasSyntheticEmail()).isTrue();
        assertNoLedgerRows(u.getId());
        // Even marked verified, a placeholder address earns nothing.
        u.setEmailVerified(true);
        users.save(u);
        assertThat(signupCredit.grantNow(u.getId())).isEqualTo(Outcome.NOT_ELIGIBLE);
        assertNoLedgerRows(u.getId());

        String realEmail = uniqueEmail("real");
        auth.requestEmailChange(u.getId(), realEmail, null, Instant.now(), IP);
        auth.verifyEmail(u.getId(), users.findById(u.getId()).orElseThrow().getEmailVerificationToken(), IP);

        assertThat(users.findById(u.getId()).orElseThrow().getEmail()).isEqualTo(realEmail);
        assertGrantedOnce(u.getId());
    }

    @Test
    void completeLogin_givenLinkToAccountVerifiedBefore_expectsNoGrant() {
        // Verified before the credit existed: linking a social sign-in is no backfill.
        User u = saveVerifiedPasswordUser();

        oauthLogin.completeLogin(OAuthProvider.GOOGLE, "google-" + UUID.randomUUID(), u.getEmail(), true, "Ada");

        assertNoLedgerRows(u.getId());
    }

    @Test
    void login_givenAccountVerifiedBefore_expectsNoGrant() {
        User u = saveVerifiedPasswordUser();
        String subject = "google-" + UUID.randomUUID();
        oauthLogin.completeLogin(OAuthProvider.GOOGLE, subject, u.getEmail(), true, "Ada");

        auth.login(new LoginRequest(u.getEmail(), PASSWORD), IP);
        oauthLogin.completeLogin(OAuthProvider.GOOGLE, subject, u.getEmail(), true, "Ada");

        assertNoLedgerRows(u.getId());
    }

    @Test
    void grantNow_givenInactiveAccount_expectsNotEligible() {
        User u = baseUser(uniqueEmail("inactive"));
        u.setActive(false);
        users.save(u);

        assertThat(signupCredit.grantNow(u.getId())).isEqualTo(Outcome.NOT_ELIGIBLE);

        assertNoLedgerRows(u.getId());
    }

    @Test
    void grantIfEligible_givenCallerTransactionRollsBack_expectsNoGrant() {
        User u = saveVerifiedPasswordUser();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            signupCredit.grantIfEligible(u);
            status.setRollbackOnly();
        });

        assertNoLedgerRows(u.getId());
    }

    @Test
    void completeLogin_givenInactiveAccountRefused_expectsNoGrant() {
        String email = uniqueEmail("inactive-link");
        User u = baseUser(email);
        u.setPasswordHash(null);
        u.setEmailVerified(false);
        u.setActive(false);
        users.save(u);

        assertThatThrownBy(() -> oauthLogin.completeLogin(
                OAuthProvider.GOOGLE, "google-" + UUID.randomUUID(), email, true, "Ada"))
                .isInstanceOf(ResponseStatusException.class);

        assertNoLedgerRows(u.getId());
    }

    // ===== A failed grant never fails the caller =======================

    @Test
    void verifyEmail_givenLedgerFailure_expectsVerificationSucceedsAndFailureAudited() {
        User u = saveUnverifiedPasswordUser();
        doThrow(new IllegalStateException("ledger unavailable")).when(ledgerService)
                .topUp(eq(u.getId()), anyLong(), eq(CreditSource.PROMO), any(), any(), any());

        auth.verifyEmail(u.getId(), u.getEmailVerificationToken(), IP);

        User after = users.findById(u.getId()).orElseThrow();
        assertThat(after.isEmailVerified()).isTrue();
        assertThat(after.getEmailVerificationToken()).isNull();
        assertThat(auditsFor(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_FAILED, u.getId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
                    assertThat(e.getEventDetail()).containsEntry("error", "IllegalStateException");
                });
        assertThat(auditsFor(AuditLogService.EVT_AUTH_VERIFY, u.getId())).hasSize(1);
        assertNoLedgerRows(u.getId());
        assertThat(after.getCreditBalanceCents()).isZero();
    }

    // ===== The admin ledger view =======================================

    @Test
    void userLedger_givenSignupCredit_expectsPromoRowWithAmountAndNote() {
        User u = saveVerifiedPasswordUser();
        signupCredit.grantNow(u.getId());

        List<LedgerEntryView> entries = adminConsole.userLedger(u.getId(), 0).entries();

        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.deltaCents()).isEqualTo(AMOUNT);
            assertThat(e.source()).isEqualTo("PROMO");
            assertThat(e.sourceRef()).isEqualTo(SignupCreditService.SOURCE_REF);
            assertThat(e.note()).isEqualTo("Signup credit");
        });
    }

    // ===== Helpers =====================================================

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    private User baseUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setFullName("Test User");
        u.setPasswordHash(passwords.encode(PASSWORD));
        u.setRole(UserRole.USER);
        u.setActive(true);
        u.setEmailVerified(false);
        return u;
    }

    private User saveUnverifiedPasswordUser() {
        User u = baseUser(uniqueEmail("signup"));
        u.setEmailVerificationToken("verify-" + UUID.randomUUID());
        u.setEmailVerificationSentAt(Instant.now());
        return users.save(u);
    }

    private User saveVerifiedPasswordUser() {
        User u = baseUser(uniqueEmail("verified"));
        u.setEmailVerified(true);
        return users.save(u);
    }

    private User savePendingChild() {
        User u = saveUnverifiedPasswordUser();
        u.setAgeStatus(AgeStatus.CONSENT_PENDING);
        u.setParentEmail(uniqueEmail("parent"));
        return users.save(u);
    }

    /** What the emailed reset link carries; the account stores only its hash. */
    private String startPasswordReset(User u) {
        String token = "reset-" + UUID.randomUUID();
        u.setPasswordResetTokenHash(AuthService.sha256Hex(token));
        u.setPasswordResetSentAt(Instant.now());
        users.save(u);
        return token;
    }

    /** The Stripe webhook grants consent inside its own transaction. */
    private void grantConsentAsTheWebhookWould(UUID childId) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                parentalConsent.grantFromPayment(childId, "cs_test_" + UUID.randomUUID(),
                        "evt_test_" + UUID.randomUUID(), 500, IP));
    }

    private void assertGrantedOnce(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT delta_cents, source, source_ref, idempotency_key FROM credit_ledger WHERE user_id = ?",
                userId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("delta_cents")).longValue()).isEqualTo(AMOUNT);
            assertThat(row.get("source")).isEqualTo("PROMO");
            assertThat(row.get("source_ref")).isEqualTo(SignupCreditService.SOURCE_REF);
            assertThat(row.get("idempotency_key")).isEqualTo(SignupCreditService.KEY_PREFIX + userId);
        });
        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(AMOUNT);
        assertThat(auditsFor(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_GRANTED, userId)).hasSize(1);
    }

    private void assertNoLedgerRows(UUID userId) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM credit_ledger WHERE user_id = ?", Long.class, userId)).isZero();
        assertThat(auditsFor(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_GRANTED, userId)).isEmpty();
    }

    private List<AuditLogEntry> auditsFor(String eventType, UUID userId) {
        return audits.findByEventType(eventType).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
    }
}
