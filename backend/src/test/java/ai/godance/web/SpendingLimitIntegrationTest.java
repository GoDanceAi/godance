package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * End-to-end coverage of per-user spending caps on a billable endpoint:
 * the hard cap returns 402, prior-period usage does not count (the
 * window resets), and the 80% soft cap fires a warning without blocking.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class SpendingLimitIntegrationTest {

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
    @Autowired UsageRecordRepository usage;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    private UUID signupWithBalance() {
        String email = "spend-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Spending Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 1_000_00L, CreditSource.PROMO,
                "seed-" + userId, "seed-" + userId, "test seed");
        return userId;
    }

    private String bearerFor(UUID userId) {
        // Re-issue a fresh login for the user's known seeded credentials.
        return users.findById(userId).orElseThrow().getEmail();
    }

    private void setDailyCap(UUID userId, long capCents) {
        User user = users.findById(userId).orElseThrow();
        user.setDailyCreditCapCents(capCents);
        users.save(user);
    }

    private void seedUsage(UUID userId, long costCents, Instant recordedAt) {
        UsageRecord record = new UsageRecord();
        record.setUserId(userId);
        record.setModality(Modality.TEXT);
        record.setModelName("gpt-4o");
        record.setProvider(Provider.OPENAI);
        record.setInputTokens(0);
        record.setOutputTokens(0);
        record.setCostCents(costCents);
        record.setRegion(Region.LOCAL);
        record.setCacheHit(false);
        record.setFallbackUsed(false);
        record.setRequestId("seed-" + UUID.randomUUID());
        record.setRecordedAt(recordedAt);
        usage.save(record);
    }

    private void stubOpenAiSuccess() {
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-spend",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "gpt-4o",
                                  "choices": [{"index": 0,
                                    "message": {"role": "assistant", "content": "Hi"},
                                    "finish_reason": "stop"}],
                                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                                }
                                """)));
    }

    /** Authenticate by logging in — keeps the test on the public auth surface. */
    private String login(UUID userId) {
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", bearerFor(userId),
                        "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    private WebTestClient.ResponseSpec callChat(String bearer) {
        return http.post().uri("/v1/chat/completions")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o",
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange();
    }

    @Test
    void chatCompletions_underDailyCap_succeeds() {
        UUID userId = signupWithBalance();
        setDailyCap(userId, 100_00L);
        seedUsage(userId, 10_00L, Instant.now());
        stubOpenAiSuccess();

        callChat(login(userId)).expectStatus().isOk();
    }

    @Test
    void chatCompletions_atDailyHardCap_returns402_withoutCallingUpstream() {
        UUID userId = signupWithBalance();
        setDailyCap(userId, 10_00L);
        seedUsage(userId, 10_00L, Instant.now());   // 100% of the cap already spent today

        callChat(login(userId)).expectStatus().isEqualTo(402);

        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void chatCompletions_capHeldByAnInFlightRequest_returns402_withoutCallingUpstream() {
        UUID userId = signupWithBalance();
        setDailyCap(userId, 1L);
        // Another request is in flight and holds the whole cap; nothing has completed yet.
        ledger.preDebit(userId, 1L, "in-flight-" + UUID.randomUUID());
        stubOpenAiSuccess();

        callChat(login(userId)).expectStatus().isEqualTo(402);

        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void chatCompletions_priorPeriodUsage_doesNotCount_capResets() {
        UUID userId = signupWithBalance();
        setDailyCap(userId, 10_00L);
        // Spend far above the cap, but two days ago — outside today's window.
        seedUsage(userId, 500_00L, Instant.now().minus(2, ChronoUnit.DAYS));
        stubOpenAiSuccess();

        callChat(login(userId)).expectStatus().isOk();
    }

    @Test
    void chatCompletions_atSoftCap_firesWarning_andStillSucceeds() {
        UUID userId = signupWithBalance();
        setDailyCap(userId, 100_00L);
        seedUsage(userId, 85_00L, Instant.now());   // 85% — over the 80% soft threshold
        stubOpenAiSuccess();

        callChat(login(userId)).expectStatus().isOk();

        assertThat(users.findById(userId).orElseThrow().getDailyCapWarnedAt()).isNotNull();
    }
}
