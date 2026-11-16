package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.routing.RoutingPolicyEntity;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Calling one's own routing policy (round 4's Phase 3, round 14 brief 04):
 * routed by its rules and audited under its stored id, the
 * {@code X-Godance-Route} hint, the compliance opt-out (an excluded
 * provider is never tried, its static fallbacks included), the cost
 * ceiling, and what another account or a direct call sees. WireMock stands
 * in for OpenAI, Anthropic and DeepSeek.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RoutingPolicyRequestsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAi;
    static WireMockServer anthropic;
    static WireMockServer deepseek;

    private static final String CODE_PROMPT = "```js\nfunction add(a, b) { return a + b; }\n```";

    @BeforeAll
    static void startMocks() {
        openAi = started();
        anthropic = started();
        deepseek = started();
    }

    private static WireMockServer started() {
        WireMockServer s = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        s.start();
        return s;
    }

    @AfterAll
    static void stopMocks() {
        for (WireMockServer s : new WireMockServer[] {openAi, anthropic, deepseek}) if (s != null) s.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.routing.user-policies.enabled", () -> "true");
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.providers.anthropic.api-base", () -> anthropic.baseUrl());
        r.add("godance.providers.deepseek.api-base", () -> deepseek.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        for (WireMockServer s : new WireMockServer[] {openAi, anthropic, deepseek}) s.resetAll();
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(answer("gpt-4o")));
        deepseek.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(answer("deepseek-v4-flash")));
        anthropic.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(500)
                .withHeader("content-type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"down\"}}")));
    }

    // ===== Called by its id =============================================

    @Test
    void chat_givenOwnPolicy_expectsItsRuleToRouteAndTheUsageToNameItsStoredId() {
        Signed u = signUp();
        putPolicy(u, "my-coder", Map.of("default_model", "deepseek-v4-flash",
                "rules", List.of(Map.of("name", "code", "when", Map.of("task_in", List.of("CODE")),
                        "targets", List.of("gpt-4o")))));

        chat(u, "my-coder", CODE_PROMPT, null, null).expectStatus().isOk().expectBody()
                .jsonPath("$.model").isEqualTo("gpt-4o");

        assertThat(usageOf(u)).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o");
            assertThat(r.getRoutedPolicy()).isEqualTo(RoutingPolicyEntity.idFor(u.id(), "my-coder"));
            assertThat(r.getRoutedRule()).isEqualTo("code");
        });
        assertThat(deepseek.getAllServeEvents()).isEmpty();
    }

    @Test
    void chat_givenOwnPolicyAndNoMatchingRule_expectsTheDefaultModel() {
        Signed u = signUp();
        putPolicy(u, "my-coder", Map.of("default_model", "deepseek-v4-flash",
                "rules", List.of(Map.of("name", "code", "when", Map.of("task_in", List.of("CODE")),
                        "targets", List.of("gpt-4o")))));

        chat(u, "my-coder", "Hello there", null, null).expectStatus().isOk();

        assertThat(usageOf(u)).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("deepseek-v4-flash");
            assertThat(r.getRoutedRule()).isEqualTo("default");
        });
    }

    // ===== The hint ======================================================

    @Test
    void chat_givenTheRouteHint_expectsTheRuleThatNamesIt_headerBeforeBody() {
        Signed u = signUp();
        putPolicy(u, "hinted", Map.of("default_model", "gpt-4o-mini",
                "rules", List.of(
                        Map.of("name", "fast", "when", Map.of("hint_equals", "fast"),
                                "targets", List.of("deepseek-v4-flash")),
                        Map.of("name", "smart", "when", Map.of("always", true), "targets", List.of("gpt-4o")))));

        chat(u, "hinted", "Hello", null, null).expectStatus().isOk();          // no hint
        chat(u, "hinted", "Hello", "FAST", null).expectStatus().isOk();        // header, any case
        chat(u, "hinted", "Hello", null, "fast").expectStatus().isOk();        // body field
        chat(u, "hinted", "Hello", "smart-ish", "fast").expectStatus().isOk(); // the header wins

        assertThat(usageOf(u)).extracting(UsageRecord::getRoutedRule)
                .containsExactly("smart", "fast", "fast", "smart");
    }

    @Test
    void chat_givenADirectModelAndAHint_expectsTheCallUnchanged() {
        Signed u = signUp();

        chat(u, "gpt-4o", "Hello", "fast", "fast").expectStatus().isOk();

        assertThat(usageOf(u)).singleElement().satisfies(r -> {
            assertThat(r.getModelName()).isEqualTo("gpt-4o");
            assertThat(r.getRoutedPolicy()).isNull();
            assertThat(r.getRoutedRule()).isNull();
        });
    }

    // ===== The compliance opt-out =======================================

    @Test
    void chat_givenAnExcludedProvider_expectsNoneOfItsModelsTried_notEvenFromTheFallbackChain() {
        Signed u = signUp();
        // claude-sonnet-4-6 falls back to claude-haiku-4-5, then gpt-5.4 (OpenAI).
        putPolicy(u, "no-openai", Map.of("default_model", "claude-sonnet-4-6",
                "limits", Map.of("excluded_providers", List.of("OPENAI"))));

        chat(u, "no-openai", "Hello", null, null).expectStatus().isEqualTo(502);

        assertThat(openAi.getAllServeEvents()).isEmpty();
        assertThat(anthropic.getAllServeEvents()).isNotEmpty();
        assertThat(usageOf(u)).extracting(UsageRecord::getModelName, UsageRecord::getOutcome)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("claude-sonnet-4-6", CallOutcome.ERROR),
                        org.assertj.core.groups.Tuple.tuple("claude-haiku-4-5", CallOutcome.ERROR));
        // Refunded in full: a request no model served costs nothing.
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
    }

    @Test
    void chat_givenTheSameModelDirectly_expectsTheStaticFallbackChainToReachOpenAi() {
        Signed u = signUp();

        chat(u, "claude-sonnet-4-6", "Hello", null, null).expectStatus().isOk();

        // Without a policy's opt-out, the chain goes on to gpt-5.4: the case above is the policy's doing.
        assertThat(openAi.getAllServeEvents()).hasSize(1);
    }

    // ===== Whose policy, and when =======================================

    @Test
    void chat_givenAnotherAccountsPolicyName_expects404UnknownModel() {
        Signed owner = signUp();
        Signed other = signUp();
        putPolicy(owner, "secret-route", Map.of("default_model", "gpt-4o"));

        chat(other, "secret-route", "Hello", null, null).expectStatus().isNotFound().expectBody()
                .jsonPath("$.detail").isEqualTo("unknown model: secret-route");
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    @Test
    void chat_givenADisabledPolicy_expects404UnknownModel() {
        Signed u = signUp();
        putPolicy(u, "parked", Map.of("default_model", "gpt-4o", "enabled", false));

        chat(u, "parked", "Hello", null, null).expectStatus().isNotFound();
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    // ===== The cost ceiling =============================================

    @Test
    void chat_givenACeilingEveryModelIsOver_expects400AndNothingCharged() {
        Signed u = signUp();
        putPolicy(u, "frugal", Map.of("default_model", "gpt-4o",
                "limits", Map.of("max_estimate_cents_ceiling", 1)));

        // About 20,000 input tokens: gpt-4o's estimate is several cents.
        chat(u, "frugal", "x".repeat(60_000), null, null).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.detail").value(d -> assertThat(d).asString()
                        .contains("estimated above its max_estimate_cents_ceiling of 1 cents"));

        assertThat(openAi.getAllServeEvents()).isEmpty();
        assertThat(usageOf(u)).isEmpty();
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
    }

    // ===== Helpers ======================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signUp() {
        String email = "policy-calls-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Policy Caller"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(id, 100_00L, CreditSource.PROMO, "seed-" + id, "seed-" + id, "test seed");
        return new Signed(id, "Bearer " + resp.accessToken());
    }

    private void putPolicy(Signed u, String id, Map<String, Object> body) {
        http.put().uri("/v1/routing/policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();
    }

    private WebTestClient.ResponseSpec chat(Signed u, String model, String prompt, String headerHint,
                                            String bodyHint) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (bodyHint != null) body.put("route_hint", bodyHint);
        WebTestClient.RequestBodySpec spec = http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON);
        if (headerHint != null) spec = spec.header("X-Godance-Route", headerHint);
        return spec.bodyValue(body).exchange();
    }

    /** The user's usage records, oldest first. */
    private List<UsageRecord> usageOf(Signed u) {
        return usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), Instant.now().minusSeconds(120)).stream()
                .sorted(java.util.Comparator.comparing(UsageRecord::getRecordedAt))
                .toList();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder answer(String model) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody("""
                {"id": "chatcmpl-policy", "object": "chat.completion", "created": 1700000000, "model": "%s",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello!"},
                              "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}}
                """.formatted(model));
    }
}
