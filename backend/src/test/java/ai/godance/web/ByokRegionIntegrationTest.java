package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.repository.UserRepository;
import ai.godance.security.KeyVault;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Hard constraint 1 with bring-your-own-key (round 14 brief 03): the
 * regional allowlist decides first, always. In the Alicloud region a user
 * holding a valid OpenAI credential still gets 503 for an OpenAI model and
 * spends nothing, and the provider is never called, not even to check a
 * key. The credential is stored here exactly as the save route stores it,
 * since that route itself refuses OpenAI in this region.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ByokRegionIntegrationTest {

    /** Visibly fake: never a real key, here or in any fixture. */
    static final String FAKE_KEY = "sk-test-FAKE-byok-region-0000000000000000";

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
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.region", () -> "alicloud");
        r.add("godance.byok.providers", () -> "OPENAI");
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired KeyVault vault;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        // Were the region check ever skipped, the provider would answer; it must never be asked.
        openAiMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json").withBody("{}")));
        openAiMock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json").withBody("{\"data\":[]}")));
    }

    @Test
    void chatCompletion_givenValidOpenAiCredentialInAlicloud_expects503AndNothingSpent() {
        Account u = signupWithBalance(10_00L);
        storeValidCredential(u.id(), "OPENAI");

        http.post().uri("/v1/chat/completions")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o",
                        "messages", List.of(Map.of("role", "user", "content", "Hello")),
                        "max_tokens", 16))
                .exchange()
                .expectStatus().isEqualTo(503);

        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(10_00L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_records WHERE user_id = ?", Long.class, u.id()))
                .isZero();
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void putCredential_givenOpenAiInAlicloud_expects503AndNoCallToCheckTheKey() {
        Account u = signupWithBalance(0);

        http.put().uri("/v1/provider-credentials/OPENAI")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("api_key", FAKE_KEY, "fallback_to_platform", false))
                .exchange()
                .expectStatus().isEqualTo(503);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_credentials WHERE user_id = ?", Long.class,
                u.id())).isZero();
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    // ===== Helpers ======================================================

    private record Account(UUID id, String bearer) { }

    private Account signupWithBalance(long cents) {
        String email = "byok-region-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Byok Region"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) ledger.topUp(id, cents, CreditSource.PROMO, "seed-" + id, "seed-" + id, "test seed");
        return new Account(id, "Bearer " + resp.accessToken());
    }

    /** A credential as the save route stores it: encrypted through the key vault, checked and valid. */
    private void storeValidCredential(UUID userId, String provider) {
        String encrypted = vault.encrypt(FAKE_KEY.getBytes(StandardCharsets.UTF_8), "byok:" + userId + ":" + provider);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO provider_credentials (id, user_id, provider, key_encrypted, last4, status, "
                        + "fallback_to_platform, created_at, checked_at, updated_at) VALUES (?, ?, ?, ?, ?, 'valid', false, ?, ?, ?)",
                UUID.randomUUID(), userId, provider, encrypted, FAKE_KEY.substring(FAKE_KEY.length() - 4), now, now, now);
    }
}
