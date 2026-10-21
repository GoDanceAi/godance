package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
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

import ai.godance.domain.CreditSource;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionResponse;

/**
 * Routing must never bypass the region allowlist (design §11 / CLAUDE.md
 * hard constraint #1). Under {@code region=alicloud}, {@code godance-quality}
 * proposes {@code gpt-5.5} (OpenAI) and {@code claude-opus-4-7} (Anthropic)
 * first — both forbidden — followed by {@code deepseek-v4-pro} (China-hosted,
 * allowed). The forbidden candidates must fall through
 * {@code ProviderGateway.dispatch()}'s 503 and the request must be served by
 * the allowed model, with no upstream HTTP call leaking to a forbidden one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RoutingRegionFallthroughIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    // One mock backs both the (forbidden) OpenAI provider and the (allowed)
    // DeepSeek provider, so its serve-event count proves exactly which
    // providers actually made an upstream call.
    static WireMockServer upstreamMock;

    @BeforeAll
    static void startMock() {
        upstreamMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamMock.start();
    }

    @AfterAll
    static void stopMock() {
        if (upstreamMock != null) upstreamMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.region", () -> "alicloud");
        r.add("godance.providers.openai.api-base", () -> upstreamMock.baseUrl());
        r.add("godance.providers.deepseek.api-base", () -> upstreamMock.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        upstreamMock.resetAll();
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "region-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Region Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, cents, CreditSource.PROMO,
                "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    @Test
    void chatCompletions_virtualQuality_forbiddenTopCandidate_fallsThroughToAllowedModel() {
        Signed u = signupWithBalance(100_00L);

        // The only stub: the China-hosted DeepSeek path. gpt-5.5 / claude-opus-4-7
        // never reach an HTTP call — dispatch() denies them in this region.
        upstreamMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-region-001",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "deepseek-v4-pro",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "Ni hao!"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30}
                                }
                                """)));

        ChatCompletionResponse out = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "godance-quality",
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatCompletionResponse.class).returnResult().getResponseBody();

        // Served by the allowed China-hosted model, not the forbidden top candidate.
        assertThat(out.model()).isEqualTo("deepseek-v4-pro");
        assertThat(out.choices().get(0).message().content()).isEqualTo("Ni hao!");

        // Exactly ONE upstream HTTP call happened — the DeepSeek one. The
        // forbidden OpenAI/Anthropic candidates were denied at dispatch(),
        // never dispatched, so nothing leaked past the allowlist.
        assertThat(upstreamMock.getAllServeEvents()).hasSize(1);

        // deepseek-v4-pro was the third candidate, so it is recorded as a fallback.
        var records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(),
                java.time.Instant.now().minusSeconds(60));
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("deepseek-v4-pro");
            assertThat(r.isFallbackUsed()).isTrue();
        });
    }

    // ===== Streaming ==================================================

    @Test
    void chatCompletions_virtualQuality_streaming_forbiddenTopCandidate_fallsThroughToAllowedModel() {
        Signed u = signupWithBalance(100_00L);

        upstreamMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "text/event-stream")
                        .withBody(String.join("",
                                "data: {\"id\":\"chatcmpl-region-s\",\"object\":\"chat.completion.chunk\",",
                                "\"created\":1700000000,\"model\":\"deepseek-v4-pro\",",
                                "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Ni hao!\"},\"finish_reason\":null}]}\n\n",
                                "data: {\"id\":\"chatcmpl-region-s\",\"object\":\"chat.completion.chunk\",",
                                "\"created\":1700000000,\"model\":\"deepseek-v4-pro\",",
                                "\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10,\"total_tokens\":30}}\n\n",
                                "data: [DONE]\n\n"))));

        List<String> events = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "godance-quality",
                        "messages", List.of(Map.of("role", "user", "content", "Hello")),
                        "stream", true))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"content\":\"Ni hao!\""));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("[DONE]"));

        // gpt-5.5 / claude-opus-4-7 were denied at dispatch(): one upstream call only.
        assertThat(upstreamMock.getAllServeEvents()).hasSize(1);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(),
                        java.time.Instant.now().minusSeconds(60)))
                        .singleElement().satisfies(r -> {
                            assertThat(r.getModelName()).isEqualTo("deepseek-v4-pro");
                            assertThat(r.isFallbackUsed()).isTrue();
                        }));
    }

    @Test
    void chatCompletions_streaming_directOpenAiModel_returns503_noUpstreamCall() {
        Signed u = signupWithBalance(100_00L);

        // gpt-4o and its fallback gpt-4o-mini are both OpenAI: the fall-through
        // loop must exhaust on dispatch() denials without one upstream call.
        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o",
                        "messages", List.of(Map.of("role", "user", "content", "Hello")),
                        "stream", true))
                .exchange()
                .expectStatus().isEqualTo(503);

        assertThat(upstreamMock.getAllServeEvents()).isEmpty();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents())
                        .isEqualTo(100_00L));
    }
}
