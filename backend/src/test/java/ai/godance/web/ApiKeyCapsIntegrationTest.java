package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.SpendingLimitService;
import ai.godance.service.SpendingLimitService.KeySpend;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Round 12: the owner of an API key sets what it may spend in a day and in a
 * month, and when it stops working. The caps are enforced by the path that
 * already enforced them ({@code SpendingLimitService}); these tests cover
 * setting them through {@code /v1/keys}, the spend figures on a key, and a
 * key past its expiry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ApiKeyCapsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMock() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMock() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void openAiProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired UsageRecordRepository usage;
    @Autowired AuditLogEntryRepository audit;
    @Autowired CreditLedgerService ledger;
    @Autowired SpendingLimitService spendingLimits;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    // ===== The cap an owner sets is the cap that is enforced ==========

    @Test
    void chatCompletions_givenKeyCreatedWithDailyCap100ThatSpent100Today_expects402AndNoProviderCall() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "capped", "daily_cap_cents", 100));
        stubOpenAiSuccess();

        // Under its cap the key works.
        seedKeyUsage(u.id(), key.id(), 40L, Instant.now());
        callChat(key.bearer()).expectStatus().isOk();
        int callsBefore = openAiMock.getAllServeEvents().size();

        // Once it has spent 100 cents today it is refused, and the provider is never called.
        seedKeyUsage(u.id(), key.id(), 60L, Instant.now());
        callChat(key.bearer())
                .expectStatus().isEqualTo(402)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).startsWith("API-key daily spending cap"));
        assertThat(openAiMock.getAllServeEvents()).hasSize(callsBefore);

        // The cap is the key's, not the account's: a signed-in session still gets through.
        callChat(u.bearer()).expectStatus().isOk();
    }

    @Test
    void chatCompletions_givenKeyCreatedWithMonthlyCapThatSpentItThisMonth_expects402() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "capped", "monthly_cap_cents", 250));
        stubOpenAiSuccess();
        seedKeyUsage(u.id(), key.id(), 250L, Instant.now());

        callChat(key.bearer())
                .expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).startsWith("API-key monthly spending cap"));
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    // ===== Caps and an expiry on create ===============================

    @Test
    void createKey_givenCapsAndAnExpiry_expectsThemOnTheResponseInTheListAndInTheAuditRow() {
        Signed u = signupWithBalance(0L);
        String expiry = inDays(30);

        Map<String, Object> created = json(postKey(u.bearer(), Map.of("name", "for the sheet",
                "daily_cap_cents", 500, "monthly_cap_cents", 4000, "expires_at", expiry))
                .expectStatus().isCreated());

        assertThat(created).containsEntry("daily_cap_cents", 500)
                .containsEntry("monthly_cap_cents", 4000)
                .containsEntry("expires_at", expiry);
        assertThat((String) created.get("plaintext")).startsWith("gd_live_");
        UUID keyId = UUID.fromString((String) created.get("id"));

        assertThat(listed(u, keyId))
                .containsEntry("daily_cap_cents", 500)
                .containsEntry("monthly_cap_cents", 4000)
                .containsEntry("expires_at", expiry)
                // The name clients read before the caps is still there, with the same value.
                .containsEntry("expiresAt", expiry)
                .containsEntry("spent_today_cents", 0)
                .containsEntry("spent_month_cents", 0)
                .doesNotContainKey("plaintext");

        var stored = apiKeys.findById(keyId).orElseThrow();
        assertThat(stored.getDailyCreditCapCents()).isEqualTo(500L);
        assertThat(stored.getMonthlyCreditCapCents()).isEqualTo(4000L);
        assertThat(stored.getExpiresAt()).isEqualTo(Instant.parse(expiry));

        assertThat(auditRows(AuditLogService.EVT_API_KEY_CREATED, u)).singleElement()
                .satisfies(row -> assertThat(row.getEventDetail())
                        .containsEntry("apiKeyId", keyId.toString())
                        .containsEntry("daily_cap_cents", 500)
                        .containsEntry("monthly_cap_cents", 4000)
                        .containsEntry("expires_at", expiry));
        // Creating a key is not a change of caps.
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).isEmpty();
    }

    @Test
    void createKey_givenNoLimits_expectsNoCapsNoExpiryAndNothingSpent() {
        Signed u = signupWithBalance(0L);

        Map<String, Object> created = json(postKey(u.bearer(), Map.of("name", "plain"))
                .expectStatus().isCreated());

        assertThat(created).containsEntry("daily_cap_cents", null)
                .containsEntry("monthly_cap_cents", null)
                .containsEntry("expires_at", null);
        assertThat(listed(u, UUID.fromString((String) created.get("id"))))
                .containsEntry("daily_cap_cents", null)
                .containsEntry("monthly_cap_cents", null)
                .containsEntry("expires_at", null)
                .containsEntry("expiresAt", null)
                .containsEntry("spent_today_cents", 0)
                .containsEntry("spent_month_cents", 0);
    }

    @Test
    void createKey_givenNullLimits_expectsTheSameAsLeavingThemOut() {
        Signed u = signupWithBalance(0L);

        postKey(u.bearer(), "{\"name\":\"explicit\",\"daily_cap_cents\":null,"
                + "\"monthly_cap_cents\":null,\"expires_at\":null}")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.daily_cap_cents").value(v -> assertThat(v).isNull())
                .jsonPath("$.monthly_cap_cents").value(v -> assertThat(v).isNull())
                .jsonPath("$.expires_at").value(v -> assertThat(v).isNull());
    }

    // ===== Caps and an expiry by PATCH ================================

    @Test
    void updateKey_givenADailyCap_expectsItEnforcedOnTheSummaryAndAuditedWithOldAndNew() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "open"));
        stubOpenAiSuccess();
        seedKeyUsage(u.id(), key.id(), 100L, Instant.now());
        callChat(key.bearer()).expectStatus().isOk();   // no cap yet

        patchKey(u.bearer(), key.id(), Map.of("daily_cap_cents", 100))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(key.id().toString())
                .jsonPath("$.name").isEqualTo("open")
                .jsonPath("$.daily_cap_cents").isEqualTo(100)
                .jsonPath("$.monthly_cap_cents").value(v -> assertThat(v).isNull())
                .jsonPath("$.expires_at").value(v -> assertThat(v).isNull())
                .jsonPath("$.plaintext").doesNotExist();

        callChat(key.bearer()).expectStatus().isEqualTo(402);

        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getEventDetail())
                            .containsEntry("apiKeyId", key.id().toString())
                            .containsEntry("name", "open");
                    assertThat(limits(row.getEventDetail().get("old")))
                            .containsEntry("daily_cap_cents", null)
                            .containsEntry("monthly_cap_cents", null)
                            .containsEntry("expires_at", null);
                    assertThat(limits(row.getEventDetail().get("new")))
                            .containsEntry("daily_cap_cents", 100)
                            .containsEntry("monthly_cap_cents", null)
                            .containsEntry("expires_at", null);
                });
        // A cap is not the image setting: no apikey.updated row.
        assertThat(auditRows(AuditLogService.EVT_API_KEY_UPDATED, u)).isEmpty();
    }

    @Test
    void updateKey_givenOneField_expectsTheOthersLeftAsTheyWere() {
        Signed u = signupWithBalance(0L);
        String expiry = inDays(10);
        Key key = createKey(u, Map.of("name", "all three",
                "daily_cap_cents", 500, "monthly_cap_cents", 4000, "expires_at", expiry));

        patchKey(u.bearer(), key.id(), Map.of("monthly_cap_cents", 9000))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.daily_cap_cents").isEqualTo(500)
                .jsonPath("$.monthly_cap_cents").isEqualTo(9000)
                .jsonPath("$.expires_at").isEqualTo(expiry)
                .jsonPath("$.image_response").isEqualTo("async");

        String later = inDays(20);
        patchKey(u.bearer(), key.id(), Map.of("expires_at", later))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.daily_cap_cents").isEqualTo(500)
                .jsonPath("$.monthly_cap_cents").isEqualTo(9000)
                .jsonPath("$.expires_at").isEqualTo(later)
                .jsonPath("$.expiresAt").isEqualTo(later);

        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).hasSize(2)
                .last().satisfies(row -> {
                    assertThat(limits(row.getEventDetail().get("old")))
                            .containsEntry("daily_cap_cents", 500)
                            .containsEntry("monthly_cap_cents", 9000)
                            .containsEntry("expires_at", expiry);
                    assertThat(limits(row.getEventDetail().get("new")))
                            .containsEntry("daily_cap_cents", 500)
                            .containsEntry("monthly_cap_cents", 9000)
                            .containsEntry("expires_at", later);
                });
    }

    @Test
    void updateKey_givenANullCap_expectsItClearedAndTheKeyWorkingAgain() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "capped", "daily_cap_cents", 100, "monthly_cap_cents", 4000));
        stubOpenAiSuccess();
        seedKeyUsage(u.id(), key.id(), 100L, Instant.now());
        callChat(key.bearer()).expectStatus().isEqualTo(402);

        patchKey(u.bearer(), key.id(), "{\"daily_cap_cents\":null}")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.daily_cap_cents").value(v -> assertThat(v).isNull())
                // Only the cap that was named is cleared.
                .jsonPath("$.monthly_cap_cents").isEqualTo(4000)
                .jsonPath("$.spent_today_cents").isEqualTo(100);

        assertThat(apiKeys.findById(key.id()).orElseThrow().getDailyCreditCapCents()).isNull();
        callChat(key.bearer()).expectStatus().isOk();
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).singleElement()
                .satisfies(row -> {
                    assertThat(limits(row.getEventDetail().get("old"))).containsEntry("daily_cap_cents", 100);
                    assertThat(limits(row.getEventDetail().get("new"))).containsEntry("daily_cap_cents", null);
                });
    }

    @Test
    void updateKey_givenANullExpiryAndANullMonthlyCap_expectsBothCleared() {
        Signed u = signupWithBalance(0L);
        Key key = createKey(u, Map.of("name", "short-lived",
                "daily_cap_cents", 500, "monthly_cap_cents", 4000, "expires_at", inDays(3)));

        patchKey(u.bearer(), key.id(), "{\"monthly_cap_cents\":null,\"expires_at\":null}")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.daily_cap_cents").isEqualTo(500)
                .jsonPath("$.monthly_cap_cents").value(v -> assertThat(v).isNull())
                .jsonPath("$.expires_at").value(v -> assertThat(v).isNull())
                .jsonPath("$.expiresAt").value(v -> assertThat(v).isNull());

        var stored = apiKeys.findById(key.id()).orElseThrow();
        assertThat(stored.getMonthlyCreditCapCents()).isNull();
        assertThat(stored.getExpiresAt()).isNull();
    }

    @Test
    void updateKey_givenTheValuesItAlreadyHas_expectsNoAuditRow() {
        Signed u = signupWithBalance(0L);
        String expiry = inDays(10);
        Key key = createKey(u, Map.of("name", "steady", "daily_cap_cents", 500, "expires_at", expiry));

        patchKey(u.bearer(), key.id(), "{\"daily_cap_cents\":500,\"monthly_cap_cents\":null,"
                + "\"expires_at\":\"" + expiry + "\"}")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.daily_cap_cents").isEqualTo(500);

        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).isEmpty();
    }

    @Test
    void updateKey_givenACapAndTheImageSettingTogether_expectsBothChangedAndEachAudited() {
        Signed u = signupWithBalance(0L);
        Key key = createKey(u, Map.of("name", "both", "image_response", "async"));

        patchKey(u.bearer(), key.id(), Map.of("image_response", "sync", "daily_cap_cents", 700))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.image_response").isEqualTo("sync")
                .jsonPath("$.daily_cap_cents").isEqualTo(700);

        assertThat(auditRows(AuditLogService.EVT_API_KEY_UPDATED, u)).singleElement()
                .satisfies(row -> assertThat(row.getEventDetail())
                        .containsEntry("field", "image_response").containsEntry("value", "sync"));
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).singleElement()
                .satisfies(row -> assertThat(limits(row.getEventDetail().get("new")))
                        .containsEntry("daily_cap_cents", 700));
    }

    @Test
    void updateKey_givenAChangedCap_expectsItsEightyPercentWarningDueAgain() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "warned", "daily_cap_cents", 1000, "monthly_cap_cents", 1000));
        stubOpenAiSuccess();
        seedKeyUsage(u.id(), key.id(), 850L, Instant.now());
        callChat(key.bearer()).expectStatus().isOk();   // 85% of both caps: both warnings are sent
        var warned = apiKeys.findById(key.id()).orElseThrow();
        assertThat(warned.getDailyCapWarnedAt()).isNotNull();
        assertThat(warned.getMonthlyCapWarnedAt()).isNotNull();

        patchKey(u.bearer(), key.id(), Map.of("daily_cap_cents", 5000)).expectStatus().isOk();

        var raised = apiKeys.findById(key.id()).orElseThrow();
        assertThat(raised.getDailyCapWarnedAt()).isNull();
        // The monthly cap did not change, so its warning stays sent.
        assertThat(raised.getMonthlyCapWarnedAt()).isNotNull();
    }

    // ===== Validation =================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "\"daily_cap_cents\":0",
            "\"daily_cap_cents\":-5",
            "\"daily_cap_cents\":1.5",
            "\"daily_cap_cents\":\"100\"",
            "\"daily_cap_cents\":true",
            "\"daily_cap_cents\":99999999999999999999",
            "\"monthly_cap_cents\":0",
            "\"monthly_cap_cents\":-1",
            "\"monthly_cap_cents\":[100]",
            "\"expires_at\":\"2020-01-01T00:00:00Z\"",
            "\"expires_at\":\"next week\"",
            "\"expires_at\":\"2099-01-31\"",
            "\"expires_at\":4102444800",
            "\"expires_at\":\"\""})
    void createKey_givenALimitThatIsNotValid_expects400AndNoKey(String limit) {
        Signed u = signupWithBalance(0L);

        postKey(u.bearer(), "{\"name\":\"bad\"," + limit + "}")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(apiKeys.findByUserId(u.id())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"daily_cap_cents\":0",
            "\"daily_cap_cents\":-5",
            "\"daily_cap_cents\":1.5",
            "\"daily_cap_cents\":\"100\"",
            "\"daily_cap_cents\":99999999999999999999",
            "\"monthly_cap_cents\":0",
            "\"expires_at\":\"2020-01-01T00:00:00Z\"",
            "\"expires_at\":\"next week\"",
            "\"expires_at\":4102444800",
            // One bad value refuses the whole request, valid fields included.
            "\"daily_cap_cents\":900,\"monthly_cap_cents\":0",
            "\"image_response\":\"sync\",\"daily_cap_cents\":-1",
            "\"daily_cap_cents\":900,\"image_response\":null",
            "\"daily_cap_cents\":900,\"expires_at\":\"2020-01-01T00:00:00Z\""})
    void updateKey_givenALimitThatIsNotValid_expects400AndNothingChanged(String limits) {
        Signed u = signupWithBalance(0L);
        String expiry = inDays(10);
        Key key = createKey(u, Map.of("name", "kept", "image_response", "async",
                "daily_cap_cents", 500, "monthly_cap_cents", 4000, "expires_at", expiry));

        patchKey(u.bearer(), key.id(), "{" + limits + "}")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(listed(u, key.id()))
                .containsEntry("daily_cap_cents", 500)
                .containsEntry("monthly_cap_cents", 4000)
                .containsEntry("expires_at", expiry)
                .containsEntry("image_response", "async");
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).isEmpty();
    }

    @Test
    void updateKey_givenAZeroCap_expectsTheReasonInTheProblem() {
        Signed u = signupWithBalance(0L);
        Key key = createKey(u, Map.of("name", "kept"));

        patchKey(u.bearer(), key.id(), Map.of("daily_cap_cents", 0))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo(
                        "daily_cap_cents must be a whole number of cents, at least 1, or null for no cap");
        patchKey(u.bearer(), key.id(), Map.of("expires_at", "2020-01-01T00:00:00Z"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("expires_at must be in the future");
    }

    // ===== What a key has spent =======================================

    @Test
    void listKeys_givenUsageInAndOutOfTheWindows_expectsTheFiguresSpendingLimitServiceEnforces() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "spender"));
        Key other = createKey(u, Map.of("name", "other"));
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yesterday = dayStart.minusSeconds(1);

        seedKeyUsage(u.id(), key.id(), 30L, Instant.now());
        seedKeyUsage(u.id(), key.id(), 12L, dayStart);                  // the day's first instant counts
        seedKeyUsage(u.id(), key.id(), 7L, yesterday);                  // this month (unless today is the 1st), not today
        seedKeyUsage(u.id(), key.id(), 1000L, monthStart.minusSeconds(1));   // last month: in neither
        seedKeyUsage(u.id(), other.id(), 5L, Instant.now());            // another key's spend
        seedKeyUsage(u.id(), null, 900L, Instant.now());                // the account's own, with no key
        long today = 42L;
        long month = today + (yesterday.isBefore(monthStart) ? 0L : 7L);

        assertThat(listed(u, key.id()))
                .containsEntry("spent_today_cents", (int) today)
                .containsEntry("spent_month_cents", (int) month);
        assertThat(listed(u, other.id()))
                .containsEntry("spent_today_cents", 5)
                .containsEntry("spent_month_cents", 5);

        // The same figures, from the service that enforces the caps and from its query.
        assertThat(spendingLimits.spendOf(key.id())).isEqualTo(new KeySpend(today, month));
        assertThat(usage.sumCostCentsForApiKeySince(key.id(), dayStart)).isEqualTo(today);
        assertThat(usage.sumCostCentsForApiKeySince(key.id(), monthStart)).isEqualTo(month);

        // And the figure a refusal quotes: cap the key at exactly what it has spent today.
        patchKey(u.bearer(), key.id(), Map.of("daily_cap_cents", today))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.spent_today_cents").isEqualTo((int) today)
                .jsonPath("$.spent_month_cents").isEqualTo((int) month);
        callChat(key.bearer())
                .expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.detail").isEqualTo("API-key daily spending cap reached ("
                        + today + " of " + today + " cents used) — resets at the start of the next period");
    }

    @Test
    void listKeys_givenAKeyThatJustSpent_expectsTheChargeInBothFigures() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "live", "daily_cap_cents", 5000));
        stubOpenAiSuccess(200_000, 20_000);   // enough tokens to cost whole cents

        callChat(key.bearer()).expectStatus().isOk();

        // What the request was charged, as the usage log has it, is what the key shows.
        long charged = usage.sumCostCentsForApiKeySince(key.id(), Instant.EPOCH);
        assertThat(charged).isPositive();
        assertThat(listed(u, key.id()))
                .containsEntry("spent_today_cents", (int) charged)
                .containsEntry("spent_month_cents", (int) charged);
    }

    // ===== Expiry =====================================================

    @Test
    void chatCompletions_givenAKeyBeforeItsExpiry_expects200() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "valid for a day", "expires_at", inDays(1)));
        stubOpenAiSuccess();

        callChat(key.bearer()).expectStatus().isOk();
    }

    @Test
    void chatCompletions_givenAnExpiredKey_expects401AsForARevokedKeyAndNoProviderCall() {
        Signed u = signupWithBalance(100_00L);
        Key expired = createKey(u, Map.of("name", "expired", "expires_at", inDays(1)));
        Key revoked = createKey(u, Map.of("name", "revoked"));
        Key deleted = createKey(u, Map.of("name", "deleted"));
        stubOpenAiSuccess();
        expire(expired.id());
        var inactive = apiKeys.findById(revoked.id()).orElseThrow();
        inactive.setActive(false);
        apiKeys.save(inactive);
        http.delete().uri("/v1/keys/{id}", deleted.id())
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange().expectStatus().isNoContent();

        // The same refusal for all three: 401 problem+json, before anything else happens.
        callChat(expired.bearer())
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("API key has expired");
        callChat(revoked.bearer())
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("API key is inactive");
        callChat(deleted.bearer())
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("invalid API key");
        assertThat(openAiMock.getAllServeEvents()).isEmpty();

        // On every route a key can reach, not only the billable ones.
        http.get().uri("/v1/jobs").header(HttpHeaders.AUTHORIZATION, expired.bearer())
                .exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/usage").header(HttpHeaders.AUTHORIZATION, expired.bearer())
                .exchange().expectStatus().isUnauthorized();
        http.post().uri("/v1/estimates").header(HttpHeaders.AUTHORIZATION, expired.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "a mug"))
                .exchange().expectStatus().isUnauthorized();

        // The owner still sees the key, with the expiry that stopped it.
        assertThat(listed(u, expired.id())).containsEntry("active", true);
        assertThat(Instant.parse((String) listed(u, expired.id()).get("expires_at"))).isBefore(Instant.now());
    }

    @Test
    void updateKey_givenALaterExpiryOnAnExpiredKey_expectsItWorkingAgain() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "renewed", "expires_at", inDays(1)));
        stubOpenAiSuccess();
        expire(key.id());
        callChat(key.bearer()).expectStatus().isUnauthorized();

        patchKey(u.bearer(), key.id(), Map.of("expires_at", inDays(7))).expectStatus().isOk();
        callChat(key.bearer()).expectStatus().isOk();

        expire(key.id());
        callChat(key.bearer()).expectStatus().isUnauthorized();
        patchKey(u.bearer(), key.id(), "{\"expires_at\":null}").expectStatus().isOk();
        callChat(key.bearer()).expectStatus().isOk();
    }

    @Test
    void createKey_givenAnExpiryWithAnOffsetAndFractions_expectsItInUtcToTheSecond() {
        Signed u = signupWithBalance(0L);

        postKey(u.bearer(), Map.of("name", "offset", "expires_at", "2099-06-30T08:00:00.987654321+08:00"))
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.expires_at").isEqualTo("2099-06-30T00:00:00Z");
    }

    // ===== Owner only, session only ===================================

    @Test
    void updateKey_givenAnotherUsersKey_expects404AndNothingChanged() {
        Signed owner = signupWithBalance(0L);
        Key key = createKey(owner, Map.of("name", "mine", "daily_cap_cents", 500));
        Signed other = signupWithBalance(0L);

        patchKey(other.bearer(), key.id(), Map.of("daily_cap_cents", 999_999)).expectStatus().isNotFound();
        patchKey(other.bearer(), key.id(), "{\"daily_cap_cents\":null}").expectStatus().isNotFound();
        patchKey(other.bearer(), UUID.randomUUID(), Map.of("daily_cap_cents", 100)).expectStatus().isNotFound();

        assertThat(listed(owner, key.id())).containsEntry("daily_cap_cents", 500);
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, owner)).isEmpty();
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, other)).isEmpty();
    }

    @Test
    void listKeys_givenAnotherUser_expectsNeitherTheKeyNorItsSpend() {
        Signed owner = signupWithBalance(0L);
        Key key = createKey(owner, Map.of("name", "mine", "daily_cap_cents", 500));
        seedKeyUsage(owner.id(), key.id(), 40L, Instant.now());
        Signed other = signupWithBalance(0L);

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void updateKey_givenAnApiKeyLiftingItsOwnCap_expects403AndTheCapStillEnforced() {
        Signed u = signupWithBalance(100_00L);
        Key key = createKey(u, Map.of("name", "capped", "daily_cap_cents", 100, "expires_at", inDays(1)));
        stubOpenAiSuccess();
        seedKeyUsage(u.id(), key.id(), 100L, Instant.now());

        // A key cannot lift its cap, raise it or push its expiry out: these routes take a session only.
        for (String body : List.of("{\"daily_cap_cents\":null}", "{\"daily_cap_cents\":1000000}",
                "{\"expires_at\":null}", "{\"expires_at\":\"" + inDays(900) + "\"}")) {
            patchKey(key.bearer(), key.id(), body)
                    .expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.detail")
                            .isEqualTo("API keys can call the model API only; sign in to manage the account.");
        }

        assertThat(listed(u, key.id())).containsEntry("daily_cap_cents", 100);
        callChat(key.bearer()).expectStatus().isEqualTo(402);
        assertThat(auditRows(AuditLogService.EVT_API_KEY_CAPS_CHANGED, u)).isEmpty();
    }

    @Test
    void createAndListKeys_givenACappedApiKey_expects403AndNoUncappedSibling() {
        Signed u = signupWithBalance(0L);
        Key key = createKey(u, Map.of("name", "capped", "daily_cap_cents", 100));

        // Nor can it mint a sibling without a cap, or read the caps of the account's keys.
        postKey(key.bearer(), Map.of("name", "sibling")).expectStatus().isForbidden();
        postKey(key.bearer(), Map.of("name", "sibling", "daily_cap_cents", 100_000)).expectStatus().isForbidden();
        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, key.bearer()).exchange()
                .expectStatus().isForbidden();

        assertThat(apiKeys.findByUserId(u.id())).hasSize(1);
    }

    @Test
    void keyRoutes_givenNoSession_expect401() {
        http.post().uri("/v1/keys").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "anonymous", "daily_cap_cents", 100))
                .exchange().expectStatus().isUnauthorized();
        http.patch().uri("/v1/keys/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("daily_cap_cents", 100))
                .exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/keys").exchange().expectStatus().isUnauthorized();
    }

    // ===== Helpers ====================================================

    record Signed(UUID id, String bearer) { }

    record Key(UUID id, String bearer) { }

    /** An instant this many days from now, to the second, as the API writes one. */
    static String inDays(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Puts a key past its expiry, which the API refuses to do: only time does. */
    void expire(UUID keyId) {
        var entity = apiKeys.findById(keyId).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        apiKeys.save(entity);
    }

    WebTestClient.ResponseSpec patchKey(String bearer, UUID keyId, Object body) {
        return http.patch().uri("/v1/keys/{id}", keyId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** One key as {@code GET /v1/keys} lists it for its owner. */
    @SuppressWarnings("unchecked")
    Map<String, Object> listed(Signed u, UUID keyId) {
        List<Map<String, Object>> keys = (List<Map<String, Object>>) (List<?>) http.get().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        return keys.stream().filter(k -> keyId.toString().equals(k.get("id"))).findFirst().orElseThrow();
    }

    /** This account's audit rows of one event type, oldest first. */
    List<AuditLogEntry> auditRows(String eventType, Signed u) {
        return audit.findByEventType(eventType).stream()
                .filter(row -> u.id().equals(row.getUserId()))
                .sorted(Comparator.comparing(AuditLogEntry::getCreatedAt))
                .toList();
    }

    /** The {@code old} or {@code new} object of an {@code api_key.caps_changed} row. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> limits(Object detailValue) {
        assertThat(detailValue).isInstanceOf(Map.class);
        return (Map<String, Object>) detailValue;
    }

    Signed signupWithBalance(long cents) {
        String email = "keycaps-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Key Caps Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    WebTestClient.ResponseSpec postKey(String bearer, Object body) {
        return http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** A key made through the API with this body; its id and its bearer. */
    Key createKey(Signed u, Object body) {
        Map<String, Object> created = json(postKey(u.bearer(), body).expectStatus().isCreated());
        return new Key(UUID.fromString((String) created.get("id")), "Bearer " + created.get("plaintext"));
    }

    /** A response's JSON object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> json(WebTestClient.ResponseSpec response) {
        return (Map<String, Object>) response.expectBody(Map.class).returnResult().getResponseBody();
    }

    /** A completed request charged to this key, as the usage log records one. */
    void seedKeyUsage(UUID userId, UUID keyId, long costCents, Instant recordedAt) {
        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(keyId);
        r.setModality(Modality.TEXT);
        r.setModelName("gpt-4o");
        r.setProvider(Provider.OPENAI);
        r.setInputTokens(0);
        r.setOutputTokens(0);
        r.setCostCents(costCents);
        r.setRegion(Region.LOCAL);
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setOutcome(CallOutcome.OK);
        r.setRequestId("seed-" + UUID.randomUUID());
        r.setRecordedAt(recordedAt);
        usage.save(r);
    }

    void stubOpenAiSuccess() {
        stubOpenAiSuccess(10, 5);
    }

    /** The provider answers, and reports this many tokens used. */
    void stubOpenAiSuccess(int promptTokens, int completionTokens) {
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-keycaps",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "gpt-4o",
                                  "choices": [{"index": 0,
                                    "message": {"role": "assistant", "content": "Hi"},
                                    "finish_reason": "stop"}],
                                  "usage": {"prompt_tokens": %d, "completion_tokens": %d, "total_tokens": %d}
                                }
                                """.formatted(promptTokens, completionTokens, promptTokens + completionTokens))));
    }

    WebTestClient.ResponseSpec callChat(String bearer) {
        return http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "max_tokens", 16,
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange();
    }
}
