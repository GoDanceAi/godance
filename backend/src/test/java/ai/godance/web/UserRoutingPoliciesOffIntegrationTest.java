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

import ai.godance.domain.CreditSource;
import ai.godance.domain.Modality;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.routing.RoutingPolicyEntity;
import ai.godance.routing.RoutingPolicyRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * With {@code godance.routing.user-policies.enabled} off, as it ships (round
 * 14, brief 04): the policy routes answer 404 {@code routing_policies_disabled},
 * a policy left from when they were on is an unknown model, and a preset
 * routes as before whatever hint is sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class UserRoutingPoliciesOffIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAi;

    @BeforeAll
    static void startMock() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
    }

    @AfterAll
    static void stopMock() {
        if (openAi != null) openAi.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired RoutingPolicyRepository policies;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        openAi.resetAll();
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("""
                        {"id": "chatcmpl-off", "object": "chat.completion", "created": 1700000000, "model": "gpt-4o",
                         "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello!"},
                                      "finish_reason": "stop"}],
                         "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}}
                        """)));
    }

    @Test
    void policyRoutes_givenUserPoliciesOff_expect404RoutingPoliciesDisabled() {
        Signed u = signUp();
        for (WebTestClient.ResponseSpec response : List.of(
                http.get().uri("/v1/routing/policies").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange(),
                http.get().uri("/v1/routing/policies/godance-auto")
                        .header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange(),
                http.put().uri("/v1/routing/policies/mine").header(HttpHeaders.AUTHORIZATION, u.bearer())
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("default_model", "gpt-4o"))
                        .exchange(),
                http.delete().uri("/v1/routing/policies/mine")
                        .header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange())) {
            response.expectStatus().isNotFound().expectBody()
                    .jsonPath("$.code").isEqualTo("routing_policies_disabled");
        }
        assertThat(policies.findByUserIdOrderByNameAsc(u.id())).isEmpty();
    }

    @Test
    void chat_givenAPolicyKeptFromWhenTheyWereOn_expects404UnknownModelAndNoCall() {
        Signed u = signUp();
        RoutingPolicyEntity kept = new RoutingPolicyEntity();
        kept.setId(RoutingPolicyEntity.idFor(u.id(), "kept"));
        kept.setUserId(u.id());
        kept.setName("kept");
        kept.setModality(Modality.TEXT);
        kept.setEnabled(true);
        kept.setPolicyJson("{\"modality\":\"TEXT\",\"rules\":[],\"default_model\":\"gpt-4o\","
                + "\"limits\":{\"max_candidates\":4,\"allowed_providers\":[],\"excluded_providers\":[]},"
                + "\"fallbacks\":[],\"enabled\":true}");
        policies.save(kept);

        chat(u, "kept", "Hello", null).expectStatus().isNotFound().expectBody()
                .jsonPath("$.detail").isEqualTo("unknown model: kept");
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }

    @Test
    void chat_givenAPresetAndAHint_expectsTheRoutingThePresetAlwaysHad() {
        Signed u = signUp();

        chat(u, "godance-auto", "```js\nfunction add(a, b) { return a + b; }\n```", "fast")
                .expectStatus().isOk();

        assertThat(usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), Instant.now().minusSeconds(60)))
                .singleElement().satisfies(r -> {
                    assertThat(r.getModelName()).isEqualTo("gpt-4o");
                    assertThat(r.getRoutedPolicy()).isEqualTo("godance-auto");
                    assertThat(r.getRoutedRule()).isEqualTo("hard-task");
                });
    }

    @Test
    void createBatch_givenFallbacksWhileUserPoliciesAreOff_expects400FallbacksUnavailable() {
        Signed u = signUp();
        Map<?, ?> recipe = http.post().uri("/v1/recipes").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Mug", "modality", "IMAGE", "template", "A {{color}} mug.",
                        "variables", List.of(Map.of("name", "color", "default", "red")),
                        "model", "gpt-image-2", "params", Map.of("n", 1)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Object versionId = ((Map<?, ?>) recipe.get("latest_version")).get("id");

        http.post().uri("/v1/recipe-batches").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version_id", versionId, "fallbacks", true,
                        "rows", List.of(Map.of("variables", Map.of("color", "blue")))))
                .exchange()
                .expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("fallbacks_unavailable");
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signUp() {
        String email = "policies-off-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Policies Off"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(id, 100_00L, CreditSource.PROMO, "seed-" + id, "seed-" + id, "test seed");
        return new Signed(id, "Bearer " + resp.accessToken());
    }

    private WebTestClient.ResponseSpec chat(Signed u, String model, String prompt, String headerHint) {
        WebTestClient.RequestBodySpec spec = http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON);
        if (headerHint != null) spec = spec.header("X-Godance-Route", headerHint);
        return spec.bodyValue(Map.of("model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)))).exchange();
    }
}
