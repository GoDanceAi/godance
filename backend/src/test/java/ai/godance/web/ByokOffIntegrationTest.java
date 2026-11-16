package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.UserRepository;
import ai.godance.security.KeyVault;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.ByokFixture.Account;

/**
 * Bring your own key ships off (round 14 brief 03): with
 * {@code godance.byok.providers} empty, its default, no key can be stored
 * and every request goes out on the platform's key at the platform price,
 * exactly as before, even for an account that holds a key stored while a
 * provider was offered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ByokOffIntegrationTest {

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
        // No godance.byok.providers: the default is what this checks.
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
        r.add("godance.providers.openai.api-key", () -> ByokFixture.PLATFORM_OPENAI_KEY);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired KeyVault vault;
    @Autowired ModelCatalog catalog;
    @Autowired CostCalculator calculator;
    @Autowired JdbcTemplate jdbc;

    ByokFixture fx;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        fx = new ByokFixture(http, users, ledger);
    }

    @Test
    void credentials_givenNoProviderOffered_expectNothingListedAndNothingStored() {
        Account a = fx.account(0);
        assertThat(fx.credentials(a.bearer())).isEmpty();
        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "OPENAI", ByokFixture.userKey("sk"), false), 400))
                .isEqualTo("byok_provider_not_supported");
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void chat_givenAKeyLeftFromWhenTheProviderWasOffered_expectsThePlatformKeyAndPrice() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        storeValidCredential(a.id(), "OPENAI", key);
        ByokFixture.stubOpenAiChat(openAiMock, ByokFixture.PLATFORM_OPENAI_KEY);

        fx.chat(a.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")).expectStatus().isOk();

        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        long platform = calculator.actualMicrocents(catalog.find("gpt-4o").orElseThrow(), 1000, 500);
        assertThat(jdbc.queryForObject("SELECT cost_microcents FROM usage_records WHERE user_id = ?", Long.class, a.id()))
                .isEqualTo(platform);
        assertThat(jdbc.queryForObject("SELECT byok FROM usage_records WHERE user_id = ?", Boolean.class, a.id()))
                .isFalse();
        // The key is listed as one no request uses, never shown.
        List<Map<String, Object>> listed = fx.credentials(a.bearer());
        assertThat(listed).singleElement().satisfies(c -> assertThat(c).containsEntry("status", "not_supported"));
        assertThat(listed.toString()).doesNotContain(key);
        // The estimate reads as it always has.
        assertThat(fx.estimate(a.bearer(), "gpt-image-2")).doesNotContainKey("byok");
    }

    private void storeValidCredential(UUID userId, String provider, String key) {
        String encrypted = vault.encrypt(key.getBytes(StandardCharsets.UTF_8), "byok:" + userId + ":" + provider);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO provider_credentials (id, user_id, provider, key_encrypted, last4, status, "
                        + "fallback_to_platform, created_at, checked_at, updated_at) VALUES (?, ?, ?, ?, ?, 'valid', false, ?, ?, ?)",
                UUID.randomUUID(), userId, provider, encrypted, key.substring(key.length() - 4), now, now, now);
    }
}
