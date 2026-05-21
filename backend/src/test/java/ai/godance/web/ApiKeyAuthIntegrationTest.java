package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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

import ai.godance.domain.ApiKey;
import ai.godance.domain.CreditSource;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * End-to-end coverage of API-key request authentication and the per-key
 * IP allowlist, driving the real {@code /v1/chat/completions} endpoint
 * with an API key in place of a JWT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ApiKeyAuthIntegrationTest {

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
    static void openAiProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired CreditLedgerService ledger;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    private record Account(UUID userId, String jwtBearer) { }

    private Account signupWithBalance(long cents) {
        String email = "apikey-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "API Key Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO,
                    "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Account(userId, "Bearer " + resp.accessToken());
    }

    private CreateApiKeyResponse createKey(String jwtBearer) {
        return http.post().uri("/v1/keys")
                .header("Authorization", jwtBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "integration-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
    }

    private void stubOpenAiSuccess() {
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-apikey",
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

    private static Map<String, Object> chatRequest() {
        return Map.of("model", "gpt-4o",
                "messages", List.of(Map.of("role", "user", "content", "Hello from an API key")));
    }

    // ===== Authentication =============================================

    @Test
    void chatCompletions_givenValidApiKey_authenticates_returns200() {
        Account account = signupWithBalance(100_00L);
        CreateApiKeyResponse key = createKey(account.jwtBearer());
        stubOpenAiSuccess();

        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key.plaintext())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest())
                .exchange()
                .expectStatus().isOk();

        assertThat(openAiMock.getAllServeEvents()).hasSize(1);
    }

    @Test
    void chatCompletions_givenUnknownApiKey_returns401() {
        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer gd_live_thiskeydoesnotexistanywhere")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void chatCompletions_givenInactiveApiKey_returns401() {
        Account account = signupWithBalance(100_00L);
        CreateApiKeyResponse key = createKey(account.jwtBearer());
        ApiKey entity = apiKeys.findById(key.id()).orElseThrow();
        entity.setActive(false);
        apiKeys.save(entity);

        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key.plaintext())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ===== IP allowlist ===============================================

    @Test
    void chatCompletions_givenIpAllowlist_clientOnList_returns200() {
        Account account = signupWithBalance(100_00L);
        CreateApiKeyResponse key = createKey(account.jwtBearer());
        setAllowlist(key.id(), "203.0.113.0/24");
        stubOpenAiSuccess();

        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key.plaintext())
                .header("X-Forwarded-For", "203.0.113.9")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void chatCompletions_givenIpAllowlist_clientOffList_returns403() {
        Account account = signupWithBalance(100_00L);
        CreateApiKeyResponse key = createKey(account.jwtBearer());
        setAllowlist(key.id(), "203.0.113.0/24");

        http.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key.plaintext())
                .header("X-Forwarded-For", "198.51.100.7")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest())
                .exchange()
                .expectStatus().isForbidden();

        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    private void setAllowlist(UUID keyId, String... cidrs) {
        ApiKey entity = apiKeys.findById(keyId).orElseThrow();
        entity.setIpAllowlist(List.of(cidrs));
        apiKeys.save(entity);
    }
}
