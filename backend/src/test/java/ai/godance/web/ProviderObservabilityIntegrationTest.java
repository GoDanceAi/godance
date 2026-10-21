package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.ProviderHealthRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.ProviderHealthAggregator;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionResponse;

/**
 * Brief 02 — provider observability + outage-aware routing.
 *
 * <ol>
 *   <li>The aggregator fold turns {@code UsageRecord} latency + outcome into
 *       {@code provider_health} percentiles and error/rate-limit counts.</li>
 *   <li>{@code GET /v1/status} returns the enriched shape (error rate + state).</li>
 *   <li>An error spike de-prefers a model so the router uses the fallback.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ProviderObservabilityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

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
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired ProviderHealthRepository health;
    @Autowired ProviderHealthAggregator aggregator;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    // ===== 1. Aggregator fold ========================================

    @Test
    void fold_givenSeededUsageWithLatencyAndOutcome_populatesPercentilesAndCounts() {
        UUID userId = freshUser();
        Instant end   = floor5min(Instant.now());
        Instant start = end.minus(5, ChronoUnit.MINUTES);
        Instant at    = start.plusSeconds(30);

        // 10 calls in the window: 7 OK, 2 ERROR, 1 RATE_LIMITED; latencies 100..1000ms.
        int[] latencies = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        for (int i = 0; i < latencies.length; i++) {
            CallOutcome outcome = i < 7 ? CallOutcome.OK
                    : (i < 9 ? CallOutcome.ERROR : CallOutcome.RATE_LIMITED);
            seedUsage(userId, "obs-agg-model", latencies[i], outcome, at);
        }

        aggregator.fold(start, end);

        ProviderHealth row = health
                .findFirstByProviderAndModelOrderByWindowEndDesc(Provider.OPENAI, "obs-agg-model")
                .orElseThrow();
        assertThat(row.getRequestCount()).isEqualTo(10);
        assertThat(row.getErrorCount()).isEqualTo(2);
        assertThat(row.getRateLimitCount()).isEqualTo(1);
        // nearest-rank over [100..1000]: p50 = 500, p95 = p99 = 1000.
        assertThat(row.getP50Ms()).isEqualTo(500);
        assertThat(row.getP95Ms()).isEqualTo(1000);
        assertThat(row.getP99Ms()).isEqualTo(1000);
    }

    // ===== 2. /v1/status enriched shape ==============================

    @Test
    void status_givenDegradedWindow_returnsErrorRateAndDegradedState() {
        // 50 requests, 40 failures → 80% error rate, well over the 10% gate.
        seedHealth("obs-status-model", 50, 40, 0, 120, 480, 900);

        StatusController.StatusResponse body = http.get().uri("/v1/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusController.StatusResponse.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.providers()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo(Provider.OPENAI);
            assertThat(p.model()).isEqualTo("obs-status-model");
            assertThat(p.errorRate()).isCloseTo(0.8, within(1e-9));
            assertThat(p.p50Ms()).isEqualTo(120);
            assertThat(p.p95Ms()).isEqualTo(480);
            assertThat(p.p99Ms()).isEqualTo(900);
            assertThat(p.state()).isEqualTo("degraded");
        });
    }

    // ===== 3. Outage-aware routing ===================================

    @Test
    void chatCompletions_givenDegradedPrimary_dePrefersIt_usesFallback() {
        Signed u = signupWithBalance(100_00L);

        // gpt-4o's latest health window is degraded (83% failure rate). The
        // application.yml fallback chain maps gpt-4o -> [gpt-4o-mini]; the
        // router should de-prefer gpt-4o and dispatch gpt-4o-mini first.
        seedHealth("gpt-4o", 60, 50, 0, 1500, 5000, 9000);

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody(openAiSuccessBody(40, 20))));

        ChatCompletionResponse out = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o",
                        "messages", List.of(Map.of("role", "user", "content", "Route me"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatCompletionResponse.class).returnResult().getResponseBody();

        assertThat(out).isNotNull();
        assertThat(out.choices().get(0).message().content()).isEqualTo("Hello!");

        // Exactly one upstream call, and it went to the healthy fallback —
        // the degraded primary was never dispatched.
        assertThat(openAiMock.getAllServeEvents()).hasSize(1);
        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("gpt-4o-mini")));

        var records = usage.findByUserIdAndRecordedAtGreaterThanEqual(
                u.id(), Instant.now().minusSeconds(60));
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o-mini");
            assertThat(r.isFallbackUsed()).isTrue();
            assertThat(r.getOutcome()).isEqualTo(CallOutcome.OK);
            assertThat(r.getLatencyMs()).isNotNull();
        });
    }

    // ===== helpers ===================================================

    private record Signed(UUID id, String bearer) { }

    private UUID freshUser() {
        return signupWithBalance(0L).id();
    }

    private Signed signupWithBalance(long cents) {
        String email = "obs-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Observability Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO,
                    "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private void seedUsage(UUID userId, String model, int latencyMs,
                           CallOutcome outcome, Instant recordedAt) {
        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setModality(Modality.TEXT);
        r.setModelName(model);
        r.setProvider(Provider.OPENAI);
        r.setInputTokens(100);
        r.setOutputTokens(50);
        r.setCostCents(5);
        r.setProviderCostCents(4L);
        r.setRegion(Region.LOCAL);
        r.setRequestId("req-" + UUID.randomUUID());
        r.setLatencyMs(latencyMs);
        r.setOutcome(outcome);
        r.setRecordedAt(recordedAt);
        usage.save(r);
    }

    /** Seed a fresh {@code provider_health} window ending now. */
    private void seedHealth(String model, long requests, long errors, long rateLimits,
                            int p50, int p95, int p99) {
        ProviderHealth h = new ProviderHealth();
        h.setProvider(Provider.OPENAI);
        h.setModel(model);
        h.setWindowEnd(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        h.setWindowStart(h.getWindowEnd().minus(5, ChronoUnit.MINUTES));
        h.setRequestCount(requests);
        h.setErrorCount(errors);
        h.setRateLimitCount(rateLimits);
        h.setP50Ms(p50);
        h.setP95Ms(p95);
        h.setP99Ms(p99);
        health.save(h);
    }

    private static String openAiSuccessBody(int promptTokens, int completionTokens) {
        return """
                {
                  "id": "chatcmpl-obs-001",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "gpt-4o-mini",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Hello!"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": %d, "completion_tokens": %d, "total_tokens": %d}
                }
                """.formatted(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private static Instant floor5min(Instant t) {
        long ms = t.toEpochMilli();
        long step = java.time.Duration.ofMinutes(5).toMillis();
        return Instant.ofEpochMilli(ms - (ms % step));
    }
}
