package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.OAuthProvider;
import ai.godance.domain.User;
import ai.godance.domain.UserTwoFactor;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.security.AdminAuthorizationFilter;
import ai.godance.security.KeyVault;
import ai.godance.security.Totp;
import ai.godance.service.AuditLogService;
import ai.godance.service.AuthService;
import ai.godance.service.OAuthLoginService;
import ai.godance.service.mail.MailService;
import ai.godance.service.mail.MailService.TwoFactorNotice;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.TwoFactorDtos.EnableResponse;
import ai.godance.web.dto.TwoFactorDtos.EnrolResponse;
import ai.godance.web.dto.TwoFactorDtos.StatusResponse;

/**
 * Two-factor sign-in over HTTP against a real database, one test for each
 * promise of the feature: enrolment, every way in needing the second factor,
 * codes that work only once, limits that hold, and a secret that is
 * unreadable at rest.
 *
 * <p>Rate limits are off in the test profile, so what is tested here is the
 * lockout in the database. {@code AuthRateLimitIntegrationTest} covers the
 * Redis limits on these routes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class TwoFactorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String WRONG_CODE = "000000";
    private static final String ADMIN_EMAIL = "two-factor-admin@godance.test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> ADMIN_EMAIL);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired AuditLogEntryRepository audit;
    @Autowired KeyVault vault;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired OAuthLoginService oauthLogin;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean MailService mail;

    // ===== Enrolment =====================================================

    @Test
    void enrol_givenPassword_returnsSecretAndUriButLeavesTwoFactorOff() {
        Person p = signUpVerified();

        EnrolResponse enrol = enrol(p.bearer).expectStatus().isOk()
                .expectBody(EnrolResponse.class).returnResult().getResponseBody();

        assertThat(enrol.secret()).matches("[A-Z2-7]{32}");
        assertThat(enrol.otpauthUri())
                .startsWith("otpauth://totp/Godance:" + p.email.replace("@", "%40") + "?")
                .contains("secret=" + enrol.secret())
                .contains("issuer=Godance")
                .contains("algorithm=SHA1&digits=6&period=30");
        // Not on until a code proves the app holds the secret.
        assertThat(status(p.bearer).enabled()).isFalse();
        assertThat(login(p.email, PASSWORD).accessToken()).isNotNull();
    }

    @Test
    void enrol_givenWrongOrMissingPassword_returns403AndAudits() {
        Person p = signUpVerified();

        http.post().uri("/v1/auth/2fa/enrol")
                .header(HttpHeaders.AUTHORIZATION, p.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", "not-the-password"))
                .exchange()
                .expectStatus().isForbidden();
        http.post().uri("/v1/auth/2fa/enrol")
                .header(HttpHeaders.AUTHORIZATION, p.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isForbidden();

        assertThat(twoFactors.findById(p.id)).isEmpty();
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_ENROL, p.id))
                .extracting(AuditLogEntry::getOutcome)
                .containsExactly(AuditOutcome.FAILURE, AuditOutcome.FAILURE);
    }

    @Test
    void enrol_givenUnverifiedPasswordAccount_returns403() {
        // A squatter's authenticator on someone else's address would outlive
        // the password reset that is meant to evict them.
        String email = uniqueEmail();
        String bearer = signUp(email);

        enrol(bearer).expectStatus().isForbidden();

        assertThat(twoFactors.findById(userId(email))).isEmpty();
    }

    @Test
    void enrol_givenTwoFactorAlreadyOn_returns409() {
        Person p = withTwoFactor();

        enrol(p.bearer).expectStatus().isEqualTo(409);
    }

    @Test
    void enrol_givenStartedTwice_keepsOnlyTheNewerSecret() {
        Person p = signUpVerified();
        EnrolResponse first = enrol(p.bearer).expectStatus().isOk()
                .expectBody(EnrolResponse.class).returnResult().getResponseBody();
        EnrolResponse second = enrol(p.bearer).expectStatus().isOk()
                .expectBody(EnrolResponse.class).returnResult().getResponseBody();

        assertThat(second.secret()).isNotEqualTo(first.secret());
        assertThat(Totp.base32(storedSecret(p.id))).isEqualTo(second.secret());
    }

    @Test
    void enable_givenWrongCode_returns403AndStaysOff() {
        Person p = signUpVerified();
        enrol(p.bearer).expectStatus().isOk();

        enable(p.bearer, WRONG_CODE).expectStatus().isForbidden();

        assertThat(status(p.bearer).enabled()).isFalse();
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_ENABLED, p.id))
                .extracting(AuditLogEntry::getOutcome).containsExactly(AuditOutcome.FAILURE);
    }

    @Test
    void enable_givenNoEnrolment_returns409() {
        Person p = signUpVerified();

        enable(p.bearer, WRONG_CODE).expectStatus().isEqualTo(409);
    }

    @Test
    void enable_givenRightCode_issuesRecoveryCodesAndEndsEarlierSessions() {
        Person p = signUpVerified();
        String otherDevice = login(p.email, PASSWORD).accessToken();
        enrol(p.bearer).expectStatus().isOk();

        EnableResponse on = enable(p.bearer, liveCode(p.id)).expectStatus().isOk()
                .expectBody(EnableResponse.class).returnResult().getResponseBody();

        assertThat(on.recoveryCodes()).hasSize(10).doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).matches("([A-Z2-7]{4}-){3}[A-Z2-7]{4}"));
        // A session opened before 2FA never passed it, so it ends...
        me(p.bearer).expectStatus().isUnauthorized();
        me("Bearer " + otherDevice).expectStatus().isUnauthorized();
        // ...and the caller's own is replaced.
        MeResponse me = me("Bearer " + on.session().accessToken()).expectStatus().isOk()
                .expectBody(MeResponse.class).returnResult().getResponseBody();
        assertThat(me.twoFactorEnabled()).isTrue();
        assertThat(status("Bearer " + on.session().accessToken()))
                .isEqualTo(new StatusResponse(true, 10));
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_ENABLED, p.id))
                .extracting(AuditLogEntry::getOutcome).containsExactly(AuditOutcome.SUCCESS);
    }

    // ===== At rest =======================================================

    @Test
    void secretAndRecoveryCodes_atRest_expectsNeitherReadable() {
        Person p = signUpVerified();
        EnrolResponse enrol = enrol(p.bearer).expectStatus().isOk()
                .expectBody(EnrolResponse.class).returnResult().getResponseBody();
        List<String> recovery = enable(p.bearer, liveCode(p.id)).expectStatus().isOk()
                .expectBody(EnableResponse.class).returnResult().getResponseBody().recoveryCodes();

        // The raw row, as a backup or a stolen dump would show it.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT secret_encrypted, recovery_code_hashes::text AS hashes FROM user_two_factor "
                        + "WHERE user_id = ?", p.id);
        String secretColumn = (String) row.get("secret_encrypted");
        String hashesColumn = (String) row.get("hashes");

        assertThat(secretColumn).startsWith("v1:").doesNotContain(enrol.secret());
        assertThat(vault.decrypt(secretColumn, "totp:" + p.id))
                .satisfies(bytes -> assertThat(Totp.base32(bytes)).isEqualTo(enrol.secret()));
        for (String code : recovery) {
            assertThat(hashesColumn).doesNotContain(code).doesNotContain(code.replace("-", ""));
        }
        assertThat(twoFactors.findById(p.id).orElseThrow().getRecoveryCodeHashes())
                .hasSize(10).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    // ===== Signing in ====================================================

    @Test
    void login_givenTwoFactorOn_returnsChallengeAndNoSession() {
        Person p = withTwoFactor();

        AuthResponse first = login(p.email, PASSWORD);

        assertThat(first.accessToken()).isNull();
        assertThat(first.twoFactorToken()).isNotBlank();
        assertThat(first.expiresAt()).isBefore(Instant.now().plusSeconds(301));
        // Audited as a challenge: this sign-in has not succeeded yet.
        assertThat(auditRows(AuditLogService.EVT_AUTH_LOGIN_OK, p.id)).isEmpty();
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_CHALLENGE, p.id)).hasSize(1);
    }

    @Test
    void login_givenWrongPassword_neverReachesTheSecondFactor() {
        Person p = withTwoFactor();

        http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", p.email, "password", "not-the-password"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.twoFactorToken").doesNotExist();
    }

    @Test
    void challenge_givenAnyRouteButVerify_isRefused() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();

        for (String route : List.of("/v1/auth/me", "/v1/auth/2fa", "/v1/keys", "/v1/billing/payment-method",
                "/v1/webhooks/secret", "/v1/admin/me", "/v1/usage", "/v1/jobs")) {
            http.get().uri(route)
                    .header(HttpHeaders.AUTHORIZATION, challenge)
                    .exchange()
                    .expectStatus().isForbidden();
        }
        http.post().uri("/v1/auth/2fa/disable")
                .header(HttpHeaders.AUTHORIZATION, challenge)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", PASSWORD, "code", WRONG_CODE))
                .exchange()
                .expectStatus().isForbidden();
        http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, challenge)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "any", "messages", List.of()))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void verify_givenSessionOrNoToken_isRefused() {
        Person p = withTwoFactor();

        verify(p.bearer, WRONG_CODE).expectStatus().isForbidden();
        http.post().uri("/v1/auth/2fa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", WRONG_CODE))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void verify_givenRightCode_issuesSessionAndAudits() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();

        AuthResponse session = verify(challenge, liveCode(p.id)).expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();

        me("Bearer " + session.accessToken()).expectStatus().isOk();
        assertThat(users.findById(p.id).orElseThrow().getLastLoginAt()).isNotNull();
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_VERIFY, p.id))
                .singleElement().satisfies(row -> {
                    assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
                    assertThat(row.getEventDetail()).containsEntry("method", "totp");
                });
        assertThat(auditRows(AuditLogService.EVT_AUTH_LOGIN_OK, p.id)).hasSize(1);
    }

    @Test
    void verify_givenCodeJustUsed_isRefused() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        String code = liveCode(p.id);

        verify(challenge, code).expectStatus().isOk();

        // RFC 6238 section 5.2: a phished code must not work a second time.
        verify(challenge, code).expectStatus().isForbidden();
    }

    @Test
    void verify_givenCodeFromLongAgo_isRefused() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        forgetUsedCodes(p.id);
        String stale = Totp.code(storedSecret(p.id), Totp.stepAt(Instant.now()) - 4);

        verify(challenge, stale).expectStatus().isForbidden();
    }

    @Test
    void verify_givenClockDriftOfOneStep_isAccepted() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        forgetUsedCodes(p.id);
        String behind = Totp.code(storedSecret(p.id), Totp.stepAt(Instant.now()) + 1);

        verify(challenge, behind).expectStatus().isOk();
    }

    @Test
    void verify_givenRecoveryCode_worksOnceHoweverItIsTyped() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        String code = p.recoveryCodes.get(0);

        AuthResponse session = verify(challenge, " " + code.toLowerCase().replace("-", " ") + " ")
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();

        verify(challenge, code).expectStatus().isForbidden();
        assertThat(status("Bearer " + session.accessToken()).recoveryCodesRemaining()).isEqualTo(9);
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_VERIFY, p.id))
                .filteredOn(row -> row.getOutcome() == AuditOutcome.SUCCESS)
                .singleElement().satisfies(row -> {
                    assertThat(row.getEventDetail()).containsEntry("method", "recovery-code");
                    assertThat(row.getEventDetail()).containsEntry("recoveryCodesRemaining", 9);
                });
    }

    @Test
    void verify_givenAnotherAccountsRecoveryCode_isRefused() {
        Person mine = withTwoFactor();
        Person theirs = withTwoFactor();
        String challenge = "Bearer " + login(mine.email, PASSWORD).twoFactorToken();

        verify(challenge, theirs.recoveryCodes.get(0)).expectStatus().isForbidden();
    }

    @Test
    void verify_givenFiveWrongCodes_locksEvenTheRightCodeAndAudits() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();

        for (int i = 0; i < 4; i++) {
            verify(challenge, WRONG_CODE).expectStatus().isForbidden();
        }
        verify(challenge, WRONG_CODE).expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);

        // Locked to the account: signing in again doesn't get a fresh five.
        String again = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        verify(again, liveCode(p.id)).expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);
        verify(again, p.recoveryCodes.get(0)).expectStatus().isEqualTo(429);

        List<AuditLogEntry> rows = auditRows(AuditLogService.EVT_AUTH_2FA_VERIFY, p.id);
        assertThat(rows).filteredOn(r -> r.getOutcome() == AuditOutcome.FAILURE).hasSize(5);
        assertThat(rows).filteredOn(r -> r.getOutcome() == AuditOutcome.DENIED).hasSize(2);
        assertThat(rows).filteredOn(r -> r.getOutcome() == AuditOutcome.SUCCESS).isEmpty();
    }

    @Test
    void verify_afterLockoutEnds_acceptsTheRightCode() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        for (int i = 0; i < 5; i++) {
            verify(challenge, WRONG_CODE);
        }
        UserTwoFactor row = twoFactors.findById(p.id).orElseThrow();
        row.setLockedUntil(Instant.now().minusSeconds(1));
        twoFactors.save(row);

        verify(challenge, liveCode(p.id)).expectStatus().isOk();
    }

    @Test
    void verify_givenRightCode_clearsTheFailureCount() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        for (int i = 0; i < 4; i++) {
            verify(challenge, WRONG_CODE).expectStatus().isForbidden();
        }

        verify(challenge, liveCode(p.id)).expectStatus().isOk();

        assertThat(twoFactors.findById(p.id).orElseThrow().getFailedAttempts()).isZero();
    }

    @Test
    void verify_givenOneCodeRacedOnManyRequests_acceptsItOnce() throws Exception {
        Person p = withTwoFactor();
        String code = liveCode(p.id);

        assertThat(raceToSignIn(p.id, code)).isEqualTo(1);
    }

    @Test
    void verify_givenOneRecoveryCodeRacedOnManyRequests_acceptsItOnce() throws Exception {
        Person p = withTwoFactor();

        assertThat(raceToSignIn(p.id, p.recoveryCodes.get(0))).isEqualTo(1);
    }

    @Test
    void verify_givenPasswordResetAfterChallenge_refusesTheOldChallenge() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();

        resetPassword(p, "a-brand-new-password").expectStatus().isOk();

        verify(challenge, liveCode(p.id)).expectStatus().isUnauthorized();
    }

    @Test
    void verify_givenTwoFactorTurnedOffSinceChallenge_returns401() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();
        disable(p.bearer, PASSWORD, liveCode(p.id)).expectStatus().isNoContent();

        verify(challenge, WRONG_CODE).expectStatus().isUnauthorized();
    }

    // ===== Other ways in =================================================

    @Test
    void oauthSignIn_givenTwoFactorOn_returnsChallengeAndNoSession() {
        // A provider sign-in linked to the same address would otherwise walk around 2FA.
        Person p = withTwoFactor();

        AuthResponse out = oauthLogin.completeLogin(OAuthProvider.GOOGLE, "google-" + p.id, p.email, true, "Test");

        assertThat(out.accessToken()).isNull();
        assertThat(out.twoFactorToken()).isNotBlank();
        verify("Bearer " + out.twoFactorToken(), liveCode(p.id)).expectStatus().isOk();
    }

    @Test
    void oauthOnlyAccount_canEnrolWithoutPasswordAndIsThenChallenged() {
        String subject = "google-" + UUID.randomUUID();
        String email = uniqueEmail();
        AuthResponse first = oauthLogin.completeLogin(OAuthProvider.GOOGLE, subject, email, true, "Social Admin");
        assertThat(first.accessToken()).isNotNull();
        String bearer = "Bearer " + first.accessToken();
        UUID id = userId(email);

        http.post().uri("/v1/auth/2fa/enrol")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isOk();
        enable(bearer, liveCode(id)).expectStatus().isOk();

        AuthResponse next = oauthLogin.completeLogin(OAuthProvider.GOOGLE, subject, email, true, "Social Admin");
        assertThat(next.accessToken()).isNull();
        assertThat(next.twoFactorToken()).isNotBlank();
    }

    @Test
    void passwordReset_givenTwoFactorOn_keepsItOnAndAsksForTheCode() {
        Person p = withTwoFactor();
        String newPassword = "a-brand-new-password";

        AuthResponse out = resetPassword(p, newPassword).expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();

        // The inbox alone doesn't buy a session, and 2FA is still on.
        assertThat(out.accessToken()).isNull();
        assertThat(out.twoFactorToken()).isNotBlank();
        assertThat(twoFactors.existsByUserIdAndEnabledAtIsNotNull(p.id)).isTrue();
        assertThat(twoFactors.findById(p.id).orElseThrow().getRecoveryCodeHashes()).hasSize(10);

        AuthResponse session = verify("Bearer " + out.twoFactorToken(), liveCode(p.id)).expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        me("Bearer " + session.accessToken()).expectStatus().isOk();
        assertThat(login(p.email, newPassword).twoFactorToken()).isNotBlank();
    }

    @Test
    void passwordReset_givenNoTwoFactor_stillReturnsASession() {
        Person p = signUpVerified();

        AuthResponse out = resetPassword(p, "a-brand-new-password").expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();

        assertThat(out.accessToken()).isNotNull();
        assertThat(out.twoFactorToken()).isNull();
    }

    // ===== Turning it off ================================================

    @Test
    void disable_givenPasswordAndCode_turnsItOffAndLoginIsOneStepAgain() {
        Person p = withTwoFactor();

        disable(p.bearer, PASSWORD, liveCode(p.id)).expectStatus().isNoContent();

        assertThat(twoFactors.findById(p.id)).isEmpty();
        assertThat(login(p.email, PASSWORD).accessToken()).isNotNull();
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_DISABLED, p.id))
                .singleElement().satisfies(row -> assertThat(row.getOutcome()).isEqualTo(AuditOutcome.SUCCESS));
    }

    @Test
    void disable_givenWrongPassword_keepsItOnWhateverTheCode() {
        Person p = withTwoFactor();

        disable(p.bearer, "not-the-password", liveCode(p.id)).expectStatus().isForbidden();

        assertThat(status(p.bearer).enabled()).isTrue();
        assertThat(auditRows(AuditLogService.EVT_AUTH_2FA_DISABLED, p.id))
                .singleElement().satisfies(row -> {
                    assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
                    assertThat(row.getEventDetail()).containsEntry("reason", "bad-password");
                });
    }

    @Test
    void disable_givenWrongCode_keepsItOnAndCountsTowardTheLockout() {
        Person p = withTwoFactor();

        disable(p.bearer, PASSWORD, WRONG_CODE).expectStatus().isForbidden();

        assertThat(status(p.bearer).enabled()).isTrue();
        assertThat(twoFactors.findById(p.id).orElseThrow().getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void disable_givenMissingCode_returns400() {
        Person p = withTwoFactor();

        http.post().uri("/v1/auth/2fa/disable")
                .header(HttpHeaders.AUTHORIZATION, p.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", PASSWORD))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(status(p.bearer).enabled()).isTrue();
    }

    @Test
    void disable_givenRecoveryCode_letsSomeoneWhoLostTheirDeviceStartOver() {
        Person p = withTwoFactor();

        disable(p.bearer, PASSWORD, p.recoveryCodes.get(0)).expectStatus().isNoContent();

        assertThat(status(p.bearer).enabled()).isFalse();
    }

    @Test
    void disable_givenTwoFactorOff_returns409() {
        Person p = signUpVerified();

        disable(p.bearer, PASSWORD, WRONG_CODE).expectStatus().isEqualTo(409);
    }

    // ===== API keys and admins ===========================================

    @Test
    void apiKey_givenAnyTwoFactorRoute_isRefused() {
        Person p = withTwoFactor();
        String key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, p.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "two-factor-test"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody().plaintext();

        http.get().uri("/v1/auth/2fa")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .exchange()
                .expectStatus().isForbidden();
        disable("Bearer " + key, PASSWORD, liveCode(p.id)).expectStatus().isForbidden();
        enrol("Bearer " + key).expectStatus().isForbidden();

        assertThat(twoFactors.existsByUserIdAndEnabledAtIsNotNull(p.id)).isTrue();
    }

    @Test
    void admin_withoutTwoFactor_isRefusedUntilEnrolledThroughTheRealFlow() {
        Person admin = signUpVerified(ADMIN_EMAIL);

        http.get().uri("/v1/admin/me")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(AdminAuthorizationFilter.TWO_FACTOR_REQUIRED_CODE);

        // Enrolling is outside /v1/admin, so the gate can't lock an admin out of it.
        enrol(admin.bearer).expectStatus().isOk();
        EnableResponse on = enable(admin.bearer, liveCode(admin.id)).expectStatus().isOk()
                .expectBody(EnableResponse.class).returnResult().getResponseBody();

        http.get().uri("/v1/admin/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + on.session().accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo(ADMIN_EMAIL);

        // Turning 2FA off closes the console again.
        disable("Bearer " + on.session().accessToken(), PASSWORD, liveCode(admin.id))
                .expectStatus().isNoContent();
        http.get().uri("/v1/admin/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + on.session().accessToken())
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== The owner is told =============================================

    @Test
    void enable_tellsTheOwner() {
        Person p = withTwoFactor();

        assertNotified(p.id, TwoFactorNotice.ENABLED, 1);
    }

    @Test
    void disable_tellsTheOwner() {
        Person p = withTwoFactor();

        disable(p.bearer, PASSWORD, liveCode(p.id)).expectStatus().isNoContent();

        assertNotified(p.id, TwoFactorNotice.DISABLED, 1);
    }

    @Test
    void verify_givenRecoveryCode_tellsTheOwnerButNotForAnAuthenticatorCode() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();

        verify(challenge, liveCode(p.id)).expectStatus().isOk();
        assertNotified(p.id, TwoFactorNotice.RECOVERY_CODE_USED, 0);

        verify(challenge, p.recoveryCodes.get(0)).expectStatus().isOk();
        assertNotified(p.id, TwoFactorNotice.RECOVERY_CODE_USED, 1);
    }

    @Test
    void verify_givenLockout_tellsTheOwnerOnce() {
        Person p = withTwoFactor();
        String challenge = "Bearer " + login(p.email, PASSWORD).twoFactorToken();

        for (int i = 0; i < 7; i++) {
            verify(challenge, WRONG_CODE);
        }

        assertNotified(p.id, TwoFactorNotice.LOCKED, 1);
    }

    // ===== Operator reset ================================================

    @Test
    void operatorReset_endsTwoFactorAndEverySessionAuditsAndTellsTheOwner() {
        Person owner = withTwoFactor();
        String thiefSession = login(owner.email, PASSWORD).twoFactorToken();
        String admin = adminSession();

        http.post().uri("/v1/admin/users/" + owner.id + "/2fa/reset")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus().isNoContent();

        assertThat(twoFactors.findById(owner.id)).isEmpty();
        me(owner.bearer).expectStatus().isUnauthorized();
        verify("Bearer " + thiefSession, WRONG_CODE).expectStatus().isUnauthorized();
        assertThat(login(owner.email, PASSWORD).accessToken()).isNotNull();
        assertThat(auditRows(AuditLogService.EVT_ADMIN_TWO_FACTOR_RESET, owner.id))
                .singleElement().satisfies(row -> assertThat(row.getEventDetail()).containsKey("actorUserId"));
        assertNotified(owner.id, TwoFactorNotice.RESET_BY_SUPPORT, 1);
    }

    @Test
    void operatorReset_givenNonAdmin_isRefusedAndChangesNothing() {
        Person owner = withTwoFactor();
        Person stranger = withTwoFactor();

        http.post().uri("/v1/admin/users/" + owner.id + "/2fa/reset")
                .header(HttpHeaders.AUTHORIZATION, stranger.bearer)
                .exchange()
                .expectStatus().isForbidden();
        http.post().uri("/v1/admin/users/" + owner.id + "/2fa/reset")
                .header(HttpHeaders.AUTHORIZATION, owner.bearer)
                .exchange()
                .expectStatus().isForbidden();

        assertThat(twoFactors.existsByUserIdAndEnabledAtIsNotNull(owner.id)).isTrue();
    }

    @Test
    void operatorReset_givenTwoFactorOffOrUnknownUser_saysSo() {
        Person plain = signUpVerified();
        String admin = adminSession();

        http.post().uri("/v1/admin/users/" + plain.id + "/2fa/reset")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus().isEqualTo(409);
        http.post().uri("/v1/admin/users/" + UUID.randomUUID() + "/2fa/reset")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ===== Stale writes ==================================================

    @Test
    void staleCopyOfTheUsersRow_cannotRestoreEndedSessionsOrUndoABalanceChange() {
        Person p = signUpVerified();
        enrol(p.bearer).expectStatus().isOk();
        String code = liveCode(p.id);
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Any request that saves the row, such as a webhook secret rotation,
        // holding its copy while 2FA goes on and a debit lands.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            User stale = users.findById(p.id).orElseThrow();
            enable(p.bearer, code).expectStatus().isOk();
            requiresNew.executeWithoutResult(inner -> jdbc.update(
                    "UPDATE users SET credit_balance_cents = credit_balance_cents + 500 WHERE id = ?", p.id));
            stale.setWebhookSigningSecret("whsec_saved_by_the_stale_copy");
            users.save(stale);
        });

        me(p.bearer).expectStatus().isUnauthorized();
        User row = users.findById(p.id).orElseThrow();
        assertThat(row.getCreditBalanceCents()).isEqualTo(500);
        assertThat(row.getWebhookSigningSecret()).isEqualTo("whsec_saved_by_the_stale_copy");
    }

    // ===== Helpers =======================================================

    /** A session on the allowlisted admin account, signing up on first use. */
    private String adminSession() {
        User existing = users.findByEmail(ADMIN_EMAIL).orElse(null);
        if (existing == null) {
            Person admin = signUpVerified(ADMIN_EMAIL);
            TwoFactorFixture.turnOn(twoFactors, admin.id);
            return admin.bearer;
        }
        twoFactors.deleteById(existing.getId());
        String bearer = "Bearer " + login(ADMIN_EMAIL, PASSWORD).accessToken();
        TwoFactorFixture.turnOn(twoFactors, existing.getId());
        return bearer;
    }

    private void assertNotified(UUID userId, TwoFactorNotice notice, int times) {
        Mockito.verify(mail, Mockito.times(times)).sendTwoFactorNotice(
                ArgumentMatchers.argThat(u -> userId.equals(u.getId())), ArgumentMatchers.eq(notice));
    }

    private record Person(String email, UUID id, String bearer, List<String> recoveryCodes) { }

    private static String uniqueEmail() {
        return "two-factor-" + UUID.randomUUID() + "@godance.test";
    }

    private UUID userId(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    private String signUp(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Two Factor Test"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    private Person signUpVerified() {
        return signUpVerified(uniqueEmail());
    }

    private Person signUpVerified(String email) {
        String bearer = signUp(email);
        User u = users.findByEmail(email).orElseThrow();
        u.setEmailVerified(true);
        users.save(u);
        return new Person(email, u.getId(), bearer, List.of());
    }

    /** A verified account that has been through enrolment, holding a session from after it. */
    private Person withTwoFactor() {
        Person p = signUpVerified();
        enrol(p.bearer).expectStatus().isOk();
        EnableResponse on = enable(p.bearer, liveCode(p.id)).expectStatus().isOk()
                .expectBody(EnableResponse.class).returnResult().getResponseBody();
        return new Person(p.email, p.id, "Bearer " + on.session().accessToken(), on.recoveryCodes());
    }

    private ResponseSpec enrol(String bearer) {
        return http.post().uri("/v1/auth/2fa/enrol")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", PASSWORD))
                .exchange();
    }

    private ResponseSpec enable(String bearer, String code) {
        return http.post().uri("/v1/auth/2fa/enable")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code))
                .exchange();
    }

    private ResponseSpec disable(String bearer, String password, String code) {
        return http.post().uri("/v1/auth/2fa/disable")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", password, "code", code))
                .exchange();
    }

    private ResponseSpec verify(String challengeBearer, String code) {
        return http.post().uri("/v1/auth/2fa/verify")
                .header(HttpHeaders.AUTHORIZATION, challengeBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code))
                .exchange();
    }

    private AuthResponse login(String email, String password) {
        return http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
    }

    private ResponseSpec me(String bearer) {
        return http.get().uri("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    private StatusResponse status(String bearer) {
        return http.get().uri("/v1/auth/2fa")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class).returnResult().getResponseBody();
    }

    /** Follows the emailed link: the token's hash is planted, as the email isn't readable here. */
    private ResponseSpec resetPassword(Person p, String newPassword) {
        String token = "reset-" + UUID.randomUUID();
        User u = users.findById(p.id).orElseThrow();
        u.setPasswordResetTokenHash(sha256Hex(token));
        u.setPasswordResetSentAt(Instant.now());
        users.save(u);
        return http.post().uri("/v1/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", token, "newPassword", newPassword))
                .exchange();
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] storedSecret(UUID userId) {
        return vault.decrypt(twoFactors.findById(userId).orElseThrow().getSecretEncrypted(), "totp:" + userId);
    }

    /**
     * The code an authenticator shows now. A test's earlier code may have
     * spent this step, which would refuse this one, so the record of that is
     * cleared first; replay is tested by re-sending a code, not by this.
     */
    private String liveCode(UUID userId) {
        forgetUsedCodes(userId);
        return Totp.code(storedSecret(userId), Totp.stepAt(Instant.now()));
    }

    private void forgetUsedCodes(UUID userId) {
        UserTwoFactor row = twoFactors.findById(userId).orElseThrow();
        row.setLastUsedStep(0L);
        twoFactors.save(row);
    }

    private List<AuditLogEntry> auditRows(String eventType, UUID userId) {
        return audit.findByEventType(eventType).stream()
                .filter(row -> userId.equals(row.getUserId()))
                .toList();
    }

    /** Sends one code down eight threads at once; returns how many got a session. */
    private int raceToSignIn(UUID userId, String code) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(() -> {
                    try {
                        auth.completeTwoFactorSignIn(userId, code, "127.0.0.1");
                        return true;
                    } catch (ResponseStatusException refused) {
                        return false;
                    }
                });
            }
            int won = 0;
            for (Future<Boolean> attempt : pool.invokeAll(attempts)) {
                if (attempt.get()) won++;
            }
            return won;
        } finally {
            pool.shutdown();
        }
    }
}
