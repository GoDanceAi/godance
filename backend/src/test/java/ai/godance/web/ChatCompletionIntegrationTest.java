package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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

import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ChatCompletionIntegrationTest {

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

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "chat-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Chat Tester"))
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

    private static String openAiSuccessBody(int promptTokens, int completionTokens) {
        return """
                {
                  "id": "chatcmpl-test-001",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "gpt-4o",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Hello!"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": %d, "completion_tokens": %d, "total_tokens": %d}
                }
                """.formatted(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private static Map<String, Object> request(String model, String userMessage) {
        return Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", userMessage)));
    }

    // ===== Happy path =================================================

    @Test
    void chatCompletions_happyPath_returnsOpenAiShape_debitsBalance_writesUsageRecord() {
        Signed u = signupWithBalance(100_00L);   // $100 seed

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk_dev_dummy_openai_key_do_not_use_for_real_calls"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody(openAiSuccessBody(1000, 500))));

        ChatCompletionResponse out = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o", "Hello world, this is a smoke test."))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatCompletionResponse.class).returnResult().getResponseBody();

        assertThat(out.object()).isEqualTo("chat.completion");
        assertThat(out.model()).isEqualTo("gpt-4o");
        assertThat(out.choices()).hasSize(1);
        assertThat(out.choices().get(0).message().content()).isEqualTo("Hello!");
        assertThat(out.usage().promptTokens()).isEqualTo(1000);
        assertThat(out.usage().completionTokens()).isEqualTo(500);

        // gpt-4o at 1000 prompt + 500 completion = 7500 microcents × 1.05 = 7875 → 1¢ actual.
        // Estimate covered (input only ≈ 1¢), so balance moved from $100.00 → $99.99.
        long balance = users.findById(u.id()).orElseThrow().getCreditBalanceCents();
        assertThat(balance).isEqualTo(100_00L - 1L);

        var records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(),
                java.time.Instant.now().minusSeconds(60));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getOutcome()).isEqualTo(CallOutcome.OK);
        assertThat(records.get(0).getLatencyMs()).isNotNull();
    }

    // ===== Failure modes ==============================================

    @Test
    void chatCompletions_zeroBalance_returns402_paymentRequired() {
        Signed u = signupWithBalance(0L);

        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o", "Hello"))
                .exchange()
                .expectStatus().isEqualTo(402);

        // No upstream call should have happened
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void chatCompletions_unknownModel_returns404() {
        Signed u = signupWithBalance(100_00L);

        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("imaginary-model-9000", "Hello"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void chatCompletions_unauthenticated_returns401() {
        http.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o", "Hello"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void chatCompletions_upstream500_returns502_fullyRefunds() {
        Signed u = signupWithBalance(100_00L);

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("oops")));

        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o", "Hello world, this is a smoke test."))
                .exchange()
                .expectStatus().isEqualTo(502);

        // Pre-debit refunded → balance back to $100.00
        long balance = users.findById(u.id()).orElseThrow().getCreditBalanceCents();
        assertThat(balance).isEqualTo(100_00L);
    }

    @Test
    void chatCompletions_invalidRequest_returns400() {
        Signed u = signupWithBalance(100_00L);

        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o",
                        "messages", List.of()))     // empty messages → @NotEmpty violation
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ===== Streaming ==================================================

    @Test
    void chatCompletions_streaming_relaysSseChunks_andReconcilesAfterFinal() {
        Signed u = signupWithBalance(100_00L);

        // Two delta chunks + a trailing usage chunk + [DONE]
        String sseBody = String.join("",
                "data: {\"id\":\"chatcmpl-s1\",\"object\":\"chat.completion.chunk\",",
                "\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-s1\",\"object\":\"chat.completion.chunk\",",
                "\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-s1\",\"object\":\"chat.completion.chunk\",",
                "\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":600,\"total_tokens\":1800}}\n\n",
                "data: [DONE]\n\n");

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "text/event-stream")
                        .withBody(sseBody)));

        List<String> events = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o",
                        "messages", List.of(Map.of("role", "user", "content", "Hi there")),
                        "stream", true))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class).getResponseBody()
                .collectList().block(java.time.Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"role\":\"assistant\""));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"content\":\"Hi\""));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"prompt_tokens\":1200"));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("[DONE]"));

        // Async reconcile runs on bounded-elastic — give it a moment.
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }

        // 1200 in + 600 out for gpt-4o (2500/10000 microcents per 1k) = 9000 microcents
        // × 1.05 markup = 9450 → HALF_UP /10000 = 1¢. Estimate was 1¢ → reconcile no-op.
        long balance = users.findById(u.id()).orElseThrow().getCreditBalanceCents();
        assertThat(balance).isLessThanOrEqualTo(100_00L);
        assertThat(balance).isGreaterThanOrEqualTo(100_00L - 5);   // generous bound for rounding
    }

    // Note: query-param token auth is wired in SecurityConfig
    // (`setAllowUriQueryParameter(true)`) for future GET streaming endpoints,
    // but Spring Security only honours `?access_token=` on GET per RFC 6750.
    // Our SSE endpoint is POST + JSON body, so browser EventSource (GET-only)
    // can't reach it anyway — modern browsers use fetch() streams + Authorization
    // header. No POST-with-query-param test, by design.

    // ===== Fallback chain =============================================

    @Test
    void chatCompletions_primaryFails_fallbackSucceeds_setsFallbackUsedFlag() {
        Signed u = signupWithBalance(100_00L);

        // application.yml maps gpt-4o → [gpt-4o-mini]. First call (gpt-4o) 500s,
        // second call (gpt-4o-mini) returns OK. Both hit the same WireMock URL —
        // sequenced via WireMock scenarios.
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("fallback").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("primary down"))
                .willSetStateTo("primary-failed"));
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("fallback").whenScenarioStateIs("primary-failed")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody(openAiSuccessBody(50, 25))));

        ChatCompletionResponse out = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o", "Hello fallback"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatCompletionResponse.class).returnResult().getResponseBody();

        assertThat(out.choices().get(0).message().content()).isEqualTo("Hello!");

        // Two UsageRecords: an ERROR row for the failed gpt-4o attempt and an
        // OK row for the gpt-4o-mini fallback that actually served the request.
        var records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(),
                java.time.Instant.now().minusSeconds(60));
        assertThat(records).hasSize(2);

        var served = records.stream()
                .filter(r -> r.getModelName().equals("gpt-4o-mini")).toList();
        assertThat(served).singleElement().satisfies(r -> {
            assertThat(r.isFallbackUsed()).isTrue();
            assertThat(r.getOutcome()).isEqualTo(CallOutcome.OK);
        });

        var failed = records.stream()
                .filter(r -> r.getModelName().equals("gpt-4o")).toList();
        assertThat(failed).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo(CallOutcome.ERROR);
            assertThat(r.isFallbackUsed()).isFalse();   // gpt-4o is the requested model
        });
    }

    @Test
    void chatCompletions_allAttemptsFail_returns502_fullyRefunds() {
        Signed u = signupWithBalance(100_00L);

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("everything down")));

        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("gpt-4o", "Hello"))
                .exchange()
                .expectStatus().isEqualTo(502);

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents())
                .isEqualTo(100_00L);
    }
}
