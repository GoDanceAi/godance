package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import ai.godance.domain.AuditLogEntry;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.security.KeyVault;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.ByokFixture.Account;

/**
 * Storing a provider key (round 14 brief 03): checked with the provider
 * before it is kept, kept only through the key vault, and never returned,
 * logged or audited beyond its last four characters. Session only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ProviderCredentialIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAiMock;
    static WireMockServer anthropicMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
        anthropicMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        anthropicMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
        if (anthropicMock != null) anthropicMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.byok.providers", () -> "OPENAI,ANTHROPIC");
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
        r.add("godance.providers.openai.api-key", () -> ByokFixture.PLATFORM_OPENAI_KEY);
        r.add("godance.providers.anthropic.api-base", () -> anthropicMock.baseUrl());
        r.add("godance.providers.anthropic.api-key", () -> ByokFixture.PLATFORM_ANTHROPIC_KEY);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired CreditLedgerService ledger;
    @Autowired AuditLogEntryRepository audit;
    @Autowired KeyVault vault;
    @Autowired JdbcTemplate jdbc;

    ByokFixture fx;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        anthropicMock.resetAll();
        fx = new ByokFixture(http, users, ledger);
    }

    // ===== Saving =======================================================

    @Test
    void put_givenKeyTheProviderAccepts_expectsStoredEncryptedAndOnlyLast4Shown(CapturedOutput output) {
        Account a = fx.account(0);
        String key = ByokFixture.userKey("sk");
        openAiMock.stubFor(get(urlEqualTo("/v1/models")).withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"object\":\"list\",\"data\":[]}")));

        String body = fx.putCredential(a.bearer(), "OPENAI", key, false)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).doesNotContain(key).contains("\"last4\":\"" + key.substring(key.length() - 4) + "\"")
                .contains("\"status\":\"valid\"").contains("\"used_for\":[\"chat\",\"images\"]")
                .contains("\"fee_basis_points\":500");
        // The provider was asked with the key, once, at its model list.
        openAiMock.verify(1, getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer " + key)));
        // Stored only through the key vault, bound to this user and provider.
        String stored = jdbc.queryForObject("SELECT key_encrypted FROM provider_credentials WHERE user_id = ?",
                String.class, a.id());
        assertThat(stored).startsWith("v1:").doesNotContain(key);
        assertThat(new String(vault.decrypt(stored, "byok:" + a.id() + ":OPENAI"), StandardCharsets.UTF_8))
                .isEqualTo(key);
        // Listed with every offered provider; Anthropic has none yet.
        List<Map<String, Object>> listed = fx.credentials(a.bearer());
        assertThat(listed).extracting(c -> c.get("provider")).containsExactly("OPENAI", "ANTHROPIC");
        assertThat(listed.get(1)).containsEntry("status", "none").containsEntry("last4", null);
        assertThat(listed.toString()).doesNotContain(key);
        // Audited without the key.
        assertThat(events("byok.saved", a)).singleElement().satisfies(e -> {
            assertThat(e.getEventDetail()).containsEntry("provider", "OPENAI")
                    .containsEntry("last4", key.substring(key.length() - 4));
            assertThat(e.getEventDetail().toString()).doesNotContain(key);
        });
        assertThat(output.getAll()).doesNotContain(key);
    }

    @Test
    void put_givenKeyTheProviderRefuses_expects400NothingStoredAndAuditedWithoutTheKey(CapturedOutput output) {
        Account a = fx.account(0);
        String key = ByokFixture.userKey("sk");
        openAiMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(401)
                .withHeader("content-type", "application/json")
                .withBody("{\"error\":{\"message\":\"Incorrect API key provided: " + key + "\"}}")));

        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "OPENAI", key, false), 400))
                .isEqualTo("byok_key_rejected");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_credentials WHERE user_id = ?", Long.class, a.id()))
                .isZero();
        assertThat(events("byok.rejected", a)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail().toString()).doesNotContain(key));
        // Not even the provider's answer, which here repeats the key, reaches a log.
        assertThat(output.getAll()).doesNotContain(key);
    }

    @Test
    void put_givenProviderGivesNoAnswer_expects502AndNothingStored() {
        Account a = fx.account(0);
        openAiMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(503)));

        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "OPENAI", ByokFixture.userKey("sk"), false), 502))
                .isEqualTo("byok_check_failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_credentials WHERE user_id = ?", Long.class, a.id()))
                .isZero();
    }

    @Test
    void put_givenAnthropicKey_expectsCheckedWithItsOwnHeaders() {
        Account a = fx.account(0);
        String key = ByokFixture.userKey("sk-ant");

        fx.saveAnthropicKey(anthropicMock, a, key, false);

        anthropicMock.verify(1, getRequestedFor(urlPathEqualTo("/v1/models"))
                .withHeader("x-api-key", equalTo(key))
                .withHeader("anthropic-version", equalTo("2023-06-01")));
        assertThat(fx.credentials(a.bearer())).anySatisfy(c -> assertThat(c).containsEntry("provider", "ANTHROPIC")
                .containsEntry("status", "valid").containsEntry("used_for", List.of("chat")));
    }

    @Test
    void put_givenProviderNotOfferedOrUnknown_expects400NotSupported() {
        Account a = fx.account(0);
        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "DEEPSEEK", "sk-FAKE-1234", false), 400))
                .isEqualTo("byok_provider_not_supported");
        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "NOT_A_PROVIDER", "sk-FAKE-1234", false), 400))
                .isEqualTo("byok_provider_not_supported");
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void put_givenBlankOrSpacedKey_expects400InvalidApiKeyAndNoCheck() {
        Account a = fx.account(0);
        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "OPENAI", "   ", false), 400))
                .isEqualTo("invalid_api_key");
        assertThat(ByokFixture.codeOf(fx.putCredential(a.bearer(), "OPENAI", "sk-FAKE two parts", false), 400))
                .isEqualTo("invalid_api_key");
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void put_withoutKey_expectsOnlyTheFallbackChangedAndNoNewCheck() {
        Account a = fx.account(0);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);

        String body = fx.putCredential(a.bearer(), "OPENAI", null, true)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"fallback_to_platform\":true").contains("\"status\":\"valid\"");
        openAiMock.verify(1, getRequestedFor(urlEqualTo("/v1/models")));
        // Without a stored key there is nothing to change.
        Account other = fx.account(0);
        assertThat(ByokFixture.codeOf(fx.putCredential(other.bearer(), "OPENAI", null, true), 404))
                .isEqualTo("byok_credential_not_found");
    }

    @Test
    void delete_givenStoredKey_expects204ThenNoneAndAudited() {
        Account a = fx.account(0);
        fx.saveOpenAiKey(openAiMock, a, ByokFixture.userKey("sk"), false);

        http.delete().uri("/v1/provider-credentials/OPENAI").header("Authorization", a.bearer()).exchange()
                .expectStatus().isNoContent();

        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("status", "none");
        assertThat(events("byok.removed", a)).hasSize(1);
        assertThat(ByokFixture.codeOf(http.delete().uri("/v1/provider-credentials/OPENAI")
                .header("Authorization", a.bearer()).exchange(), 404)).isEqualTo("byok_credential_not_found");
    }

    @Test
    void routes_givenAnApiKey_expect403() {
        Account a = fx.account(0);
        String apiKey = fx.apiKey(a);
        http.get().uri("/v1/provider-credentials").header("Authorization", apiKey).exchange()
                .expectStatus().isForbidden();
        fx.putCredential(apiKey, "OPENAI", ByokFixture.userKey("sk"), false).expectStatus().isForbidden();
        http.delete().uri("/v1/provider-credentials/OPENAI").header("Authorization", apiKey).exchange()
                .expectStatus().isForbidden();
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void closeAccount_givenStoredKeys_expectsThemDeleted() {
        Account a = fx.account(0);
        fx.saveOpenAiKey(openAiMock, a, ByokFixture.userKey("sk"), false);
        fx.saveAnthropicKey(anthropicMock, a, ByokFixture.userKey("sk-ant"), false);
        Account admin = fx.admin(twoFactors);

        http.post().uri("/v1/admin/users/" + a.id() + "/close").header("Authorization", admin.bearer()).exchange()
                .expectStatus().isOk();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_credentials WHERE user_id = ?", Long.class, a.id()))
                .isZero();
        Map<String, Object> closed = audit.findByEventType("admin.account.closed").stream()
                .filter(e -> a.id().equals(e.getUserId())).findFirst().orElseThrow().getEventDetail();
        assertThat(closed).containsEntry("providerKeysDeleted", 2);
    }

    // ===== Helpers ======================================================

    private List<AuditLogEntry> events(String type, Account a) {
        return audit.findByEventType(type).stream().filter(e -> a.id().equals(e.getUserId())).toList();
    }
}
