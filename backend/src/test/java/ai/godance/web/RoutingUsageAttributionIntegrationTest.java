package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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
import ai.godance.domain.UsageRecord;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Round 4's Phase 2 (round 14, brief 04): a request routed through a virtual
 * model id records the policy and the rule that routed it on every usage
 * record it writes, the served attempt's and any failed attempt's, streamed or
 * not. A direct model call records neither, as before.
 *
 * <p>{@code godance-auto} sends a code prompt to its {@code hard-task} rule:
 * gpt-4o first, then claude-sonnet-4-6. WireMock stands in for OpenAI and
 * Anthropic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RoutingUsageAttributionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAi;
    static WireMockServer anthropic;

    /** The code fence makes HeuristicTaskClassifier answer CODE: godance-auto's 'hard-task' rule. */
    private static final String CODE_PROMPT = "```js\nfunction add(a, b) { return a + b; }\n```";

    @BeforeAll
    static void startMocks() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        anthropic = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
        anthropic.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAi != null) openAi.stop();
        if (anthropic != null) anthropic.stop();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.providers.anthropic.api-base", () -> anthropic.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        openAi.resetAll();
        anthropic.resetAll();
    }

    // ===== Non-streaming ================================================

    @Test
    void chatCompletions_givenVirtualModel_expectsRoutedPolicyAndRuleOnTheUsageRecord() {
        Signed u = signupWithBalance(100_00L);
        openAiAnswers();

        send(u, "godance-auto", CODE_PROMPT, false).expectStatus().isOk();

        assertThat(usageOf(u)).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o");
            assertThat(r.getOutcome()).isEqualTo(CallOutcome.OK);
            assertThat(r.getRoutedPolicy()).isEqualTo("godance-auto");
            assertThat(r.getRoutedRule()).isEqualTo("hard-task");
        });
    }

    @Test
    void chatCompletions_givenDirectModel_expectsNoRoutedPolicyOrRule() {
        Signed u = signupWithBalance(100_00L);
        openAiAnswers();

        send(u, "gpt-4o", CODE_PROMPT, false).expectStatus().isOk();

        assertThat(usageOf(u)).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o");
            assertThat(r.getRoutedPolicy()).isNull();
            assertThat(r.getRoutedRule()).isNull();
        });
    }

    @Test
    void chatCompletions_givenRoutedPrimaryFails_expectsTheFailedAndTheServedAttemptsAttributed() {
        Signed u = signupWithBalance(100_00L);
        openAiFails();
        anthropicAnswers();

        send(u, "godance-auto", CODE_PROMPT, false).expectStatus().isOk();

        List<UsageRecord> records = usageOf(u);
        assertThat(records).extracting(UsageRecord::getModelName, UsageRecord::getOutcome)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("gpt-4o", CallOutcome.ERROR),
                        org.assertj.core.groups.Tuple.tuple("claude-sonnet-4-6", CallOutcome.OK));
        assertThat(records).allSatisfy(r -> {
            assertThat(r.getRoutedPolicy()).isEqualTo("godance-auto");
            assertThat(r.getRoutedRule()).isEqualTo("hard-task");
        });
    }

    // ===== Streaming ====================================================

    @Test
    void chatCompletionsStreamed_givenVirtualModel_expectsRoutedPolicyAndRuleOnTheUsageRecord() {
        Signed u = signupWithBalance(100_00L);
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "text/event-stream")
                .withBody(openAiStream())));

        streamed(u, "godance-auto");

        // The usage record is written after the stream ends, off the stream thread.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(usageOf(u)).singleElement().satisfies(r -> {
                    assertThat(r.getModelName()).isEqualTo("gpt-4o");
                    assertThat(r.getOutcome()).isEqualTo(CallOutcome.OK);
                    assertThat(r.getRoutedPolicy()).isEqualTo("godance-auto");
                    assertThat(r.getRoutedRule()).isEqualTo("hard-task");
                }));
    }

    @Test
    void chatCompletionsStreamed_givenRoutedPrimaryFailsBeforeItsFirstChunk_expectsBothAttemptsAttributed() {
        Signed u = signupWithBalance(100_00L);
        openAiFails();
        anthropic.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "text/event-stream")
                .withBody(anthropicStream())));

        streamed(u, "godance-auto");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<UsageRecord> records = usageOf(u);
            assertThat(records).extracting(UsageRecord::getModelName, UsageRecord::getOutcome)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("gpt-4o", CallOutcome.ERROR),
                            org.assertj.core.groups.Tuple.tuple("claude-sonnet-4-6", CallOutcome.OK));
            assertThat(records).allSatisfy(r -> {
                assertThat(r.getRoutedPolicy()).isEqualTo("godance-auto");
                assertThat(r.getRoutedRule()).isEqualTo("hard-task");
            });
        });
    }

    @Test
    void chatCompletionsStreamed_givenDirectModel_expectsNoRoutedPolicyOrRule() {
        Signed u = signupWithBalance(100_00L);
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "text/event-stream")
                .withBody(openAiStream())));

        streamed(u, "gpt-4o");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(usageOf(u)).singleElement().satisfies(r -> {
                    assertThat(r.getModelName()).isEqualTo("gpt-4o");
                    assertThat(r.getRoutedPolicy()).isNull();
                    assertThat(r.getRoutedRule()).isNull();
                }));
    }

    // ===== Helpers ======================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "routed-usage-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Routed Usage Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private WebTestClient.ResponseSpec send(Signed u, String model, String prompt, boolean stream) {
        return http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", model,
                        "messages", List.of(Map.of("role", "user", "content", prompt)),
                        "stream", stream))
                .exchange();
    }

    private void streamed(Signed u, String model) {
        List<String> events = http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", model,
                        "messages", List.of(Map.of("role", "user", "content", CODE_PROMPT)),
                        "stream", true))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .collectList().block(Duration.ofSeconds(10));
        assertThat(events).anySatisfy(e -> assertThat(e).contains("[DONE]"));
    }

    /** The user's usage records. */
    private List<UsageRecord> usageOf(Signed u) {
        return usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), Instant.now().minusSeconds(120))
                .stream()
                .sorted(Comparator.comparing(UsageRecord::getRecordedAt))
                .toList();
    }

    private static void openAiAnswers() {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("""
                        {"id": "chatcmpl-routed", "object": "chat.completion", "created": 1700000000,
                         "model": "gpt-4o",
                         "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello!"},
                                      "finish_reason": "stop"}],
                         "usage": {"prompt_tokens": 1000, "completion_tokens": 500, "total_tokens": 1500}}
                        """)));
    }

    private static void openAiFails() {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)
                .withHeader("content-type", "application/json")
                .withBody("{\"error\": {\"message\": \"upstream is down\", \"type\": \"server_error\"}}")));
    }

    private static void anthropicAnswers() {
        anthropic.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\":\"msg_routed\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\",\"text\":\"Hello!\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1000,\"output_tokens\":500}}")));
    }

    private static String openAiStream() {
        return String.join("",
                "data: {\"id\":\"chatcmpl-rs\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,",
                "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-rs\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,",
                "\"model\":\"gpt-4o\",\"choices\":[],",
                "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":600,\"total_tokens\":1800}}\n\n",
                "data: [DONE]\n\n");
    }

    private static String anthropicStream() {
        return String.join("",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_rs\",\"usage\":{\"input_tokens\":1200}}}\n\n",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},",
                "\"usage\":{\"output_tokens\":600}}\n\n",
                "data: {\"type\":\"message_stop\"}\n\n");
    }
}
