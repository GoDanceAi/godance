package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.ChatCompletionService;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionResponse;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Routing wired into {@code /v1/chat/completions} under the default
 * {@code region=local} (so a Western model is dispatchable). Covers:
 * a virtual id resolves to a concrete model (streaming + non-streaming),
 * the margin-attribution log line is emitted against the requestId, a
 * direct call is unchanged, and {@code /v1/models} advertises the virtual
 * ids alongside an unchanged catalog. The Alicloud fall-through invariant
 * lives in {@link RoutingRegionFallthroughIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RoutingChatCompletionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer openAiMock;

    // godance-auto 'hard-task' (CODE) routes to gpt-4o first; the code fence
    // makes HeuristicTaskClassifier return CODE.
    private static final String CODE_PROMPT = "```js\nfunction add(a, b) { return a + b; }\n```";

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

    private ListAppender<ILoggingEvent> routingLogs;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        routingLogs = new ListAppender<>();
        routingLogs.start();
        ((Logger) LoggerFactory.getLogger(ChatCompletionService.class)).addAppender(routingLogs);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(ChatCompletionService.class)).detachAppender(routingLogs);
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "route-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Route Tester"))
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
                  "id": "chatcmpl-route-001",
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
        return Map.of("model", model,
                "messages", List.of(Map.of("role", "user", "content", userMessage)));
    }

    private boolean loggedRoutingFor(String virtualId) {
        return routingLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("Routing decision:")
                        && m.contains("virtualId=" + virtualId));
    }

    // ===== Virtual id resolves (non-streaming) ========================

    @Test
    void chatCompletions_virtualAuto_resolvesToConcreteModel_andLogsDecision() {
        Signed u = signupWithBalance(100_00L);

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody(openAiSuccessBody(1000, 500))));

        ChatCompletionResponse out = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("godance-auto", CODE_PROMPT))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatCompletionResponse.class).returnResult().getResponseBody();

        // OpenAI-compatible response served by a CONCRETE model, not the virtual id.
        assertThat(out.object()).isEqualTo("chat.completion");
        assertThat(out.model()).isEqualTo("gpt-4o");
        assertThat(out.choices().get(0).message().content()).isEqualTo("Hello!");

        // Margin-attribution log: requestId -> virtualId -> candidates.
        assertThat(loggedRoutingFor("godance-auto")).isTrue();

        // A UsageRecord exists for the concrete served model (joins on requestId).
        var records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(),
                java.time.Instant.now().minusSeconds(60));
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o");
            assertThat(r.getOutcome()).isEqualTo(CallOutcome.OK);
        });

        // Balance moved (routed call was billed against the concrete model).
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isLessThan(100_00L);
    }

    // ===== Virtual id resolves (streaming) ============================

    @Test
    void chatCompletions_virtualAuto_streaming_resolvesToConcreteModel() {
        Signed u = signupWithBalance(100_00L);

        String sseBody = String.join("",
                "data: {\"id\":\"chatcmpl-rs\",\"object\":\"chat.completion.chunk\",",
                "\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-rs\",\"object\":\"chat.completion.chunk\",",
                "\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-rs\",\"object\":\"chat.completion.chunk\",",
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
                        "model", "godance-auto",
                        "messages", List.of(Map.of("role", "user", "content", CODE_PROMPT)),
                        "stream", true))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class).getResponseBody()
                .collectList().block(java.time.Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"content\":\"Hi\""));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("[DONE]"));
        assertThat(loggedRoutingFor("godance-auto")).isTrue();
    }

    // ===== Direct call regression =====================================

    @Test
    void chatCompletions_directModel_isUnchanged_byteForByte() {
        Signed u = signupWithBalance(100_00L);

        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
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

        assertThat(out.model()).isEqualTo("gpt-4o");
        assertThat(out.usage().promptTokens()).isEqualTo(1000);

        // Same billing as baseline happy path: $100.00 -> $99.99.
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L - 1L);

        // A direct call emits NO routing-decision log (passthrough, policyId null).
        assertThat(loggedRoutingFor("gpt-4o")).isFalse();
        assertThat(routingLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.startsWith("Routing decision:"))).isTrue();

        var records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(),
                java.time.Instant.now().minusSeconds(60));
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o");
            assertThat(r.isFallbackUsed()).isFalse();
            assertThat(r.getOutcome()).isEqualTo(CallOutcome.OK);
        });
    }

    // ===== /v1/models advertises virtual ids ==========================

    @Test
    void models_listsVirtualIds_alongsideUnchangedCatalog() {
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class)
                .returnResult().getResponseBody();

        assertThat(body.object()).isEqualTo("list");

        // Catalog entry unchanged (same shape as before this change).
        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("gpt-4o-mini");
            assertThat(m.ownedBy()).isEqualTo("openai");
            assertThat(m.godance()).containsEntry("modality", "TEXT");
            assertThat(m.godance()).containsKey("pricing");
        });

        // All three virtual ids present, additive, marked virtual.
        for (String virtualId : List.of("godance-fast", "godance-auto", "godance-quality")) {
            assertThat(body.data()).anySatisfy(m -> {
                assertThat(m.id()).isEqualTo(virtualId);
                assertThat(m.ownedBy()).isEqualTo("godance");
                assertThat(m.godance()).containsEntry("virtual", true);
                assertThat(m.godance()).containsEntry("modality", "TEXT");
            });
        }
    }
}
