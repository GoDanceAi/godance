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
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.routing.RoutingPolicyEntity;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Round 4's invariant for a user's own policy (design §11): the regional
 * allowlist decides first, always. In the Alicloud region a policy may list
 * OpenAI's gpt-4o; {@code ProviderGateway.dispatch()} refuses it at attempt
 * time with no call made, and the next candidate serves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class UserRoutingPolicyRegionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer upstream;

    @BeforeAll
    static void startMock() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stopMock() {
        if (upstream != null) upstream.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.region", () -> "alicloud");
        r.add("godance.routing.user-policies.enabled", () -> "true");
        r.add("godance.providers.openai.api-base", () -> upstream.baseUrl());
        r.add("godance.providers.deepseek.api-base", () -> upstream.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired CreditLedgerService ledger;

    @Test
    void chat_givenOwnPolicyWhoseFirstTargetTheRegionForbids_expectsItSkippedWithNoCallAndTheNextServing() {
        upstream.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("""
                        {"id": "chatcmpl-east", "object": "chat.completion", "created": 1700000000,
                         "model": "deepseek-v4-pro",
                         "choices": [{"index": 0, "message": {"role": "assistant", "content": "Ni hao!"},
                                      "finish_reason": "stop"}],
                         "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}}
                        """)));
        String email = "region-policy-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Region Policy"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        String bearer = "Bearer " + resp.accessToken();

        // Saving it is fine: routing never pre-filters on region (design §11).
        http.put().uri("/v1/routing/policies/west-first").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("default_model", "deepseek-v4-pro", "rules", List.of(Map.of(
                        "name", "western-first", "when", Map.of("always", true),
                        "targets", List.of("gpt-4o", "deepseek-v4-pro")))))
                .exchange()
                .expectStatus().isCreated();

        http.post().uri("/v1/chat/completions").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "west-first",
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.choices[0].message.content").isEqualTo("Ni hao!");

        // gpt-4o was refused at dispatch(): one upstream call, to DeepSeek.
        assertThat(upstream.getAllServeEvents()).singleElement().satisfies(e ->
                assertThat(e.getRequest().getBodyAsString()).contains("\"deepseek-v4-pro\""));
        assertThat(usage.findByUserIdAndRecordedAtGreaterThanEqual(userId, Instant.now().minusSeconds(60)))
                .singleElement().satisfies(r -> {
                    assertThat(r.getModelName()).isEqualTo("deepseek-v4-pro");
                    assertThat(r.isFallbackUsed()).isTrue();
                    assertThat(r.getRoutedPolicy()).isEqualTo(RoutingPolicyEntity.idFor(userId, "west-first"));
                    assertThat(r.getRoutedRule()).isEqualTo("western-first");
                });
    }
}
