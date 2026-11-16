package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.User;
import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.ByokFixture.Account;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Requests on the caller's own key (round 14 brief 03). Each migrated
 * adapter sends the caller's key, checked on the wire by WireMock; an
 * adapter not migrated sends the platform's. A request on the caller's key
 * holds and settles only Godance's fee, and the estimate is that hold. A key
 * the provider refuses is marked invalid and either ends the request with
 * nothing charged or, with the caller's leave, is retried once on the
 * platform's key at the platform price. Spending caps hold the fee.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ByokRequestsIntegrationTest {

    static final int FEE_BP = 500;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

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
        r.add("godance.byok.fee-basis-points", () -> String.valueOf(FEE_BP));
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
        r.add("godance.providers.openai.api-key", () -> ByokFixture.PLATFORM_OPENAI_KEY);
        r.add("godance.providers.anthropic.api-base", () -> anthropicMock.baseUrl());
        r.add("godance.providers.anthropic.api-key", () -> ByokFixture.PLATFORM_ANTHROPIC_KEY);
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired CreditLedgerService ledger;
    @Autowired AuditLogEntryRepository audit;
    @Autowired ModelCatalog catalog;
    @Autowired CostCalculator calculator;
    @Autowired JdbcTemplate jdbc;

    ByokFixture fx;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        anthropicMock.resetAll();
        fx = new ByokFixture(http, users, ledger);
    }

    // ===== Each migrated adapter sends the caller's key =================

    @Test
    void chat_givenOpenAiKey_expectsTheCallersKeySentAndOnlyTheFeeBilled(CapturedOutput output) {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        stubOpenAiChat(key, 100_000, 50_000);

        fx.chat(a.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")).expectStatus().isOk();

        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + key)));
        openAiMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        ModelInfo model = catalog.find("gpt-4o").orElseThrow();
        long fee = CostCalculator.feeMicrocents(calculator.listMicrocents(model, 100_000, 50_000), FEE_BP);
        // 750,000 µ¢ at list price; 5% is 37,500 µ¢: 3 cents billed, 7,500 µ¢ carried.
        assertThat(fee).isEqualTo(37_500L);
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L - 3);
        assertThat(pendingMicrocents(a)).isEqualTo(7_500L);
        Map<String, Object> usage = lastUsage(a);
        assertThat(usage).containsEntry("byok", true).containsEntry("cost_cents", 3L)
                .containsEntry("cost_microcents", 37_500L).containsEntry("provider_cost_microcents", 0L);
        assertThat(output.getAll()).doesNotContain(key);
    }

    @Test
    void chat_givenNoKey_expectsThePlatformKeyAndPlatformPrice() {
        Account a = fx.account(10_00L);
        stubOpenAiChat(ByokFixture.PLATFORM_OPENAI_KEY, 100_000, 50_000);

        fx.chat(a.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")).expectStatus().isOk();

        ModelInfo model = catalog.find("gpt-4o").orElseThrow();
        long platform = calculator.actualMicrocents(model, 100_000, 50_000);
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L - platform / 10_000);
        assertThat(lastUsage(a)).containsEntry("byok", false);
    }

    @Test
    void chatStream_givenOpenAiKey_expectsTheCallersKeySentAndTheFeeSettled() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        String sse = String.join("",
                "data: {\"id\":\"chatcmpl-s\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-s\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[],\"usage\":{\"prompt_tokens\":100000,\"completion_tokens\":50000,\"total_tokens\":150000}}\n\n",
                "data: [DONE]\n\n");
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions")).withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "text/event-stream").withBody(sse)));

        List<String> events = http.post().uri("/v1/chat/completions")
                .header("Authorization", a.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"content\":\"Hi\""));
        Map<String, Object> usage = awaitUsage(a);
        assertThat(usage).containsEntry("byok", true).containsEntry("cost_microcents", 37_500L);
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L - 3);
    }

    @Test
    void chat_givenAnthropicKey_expectsItSentAsXApiKey() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk-ant");
        fx.saveAnthropicKey(anthropicMock, a, key, false);
        anthropicMock.stubFor(post(urlEqualTo("/v1/messages")).withHeader("x-api-key", equalTo(key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"msg_byok\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\",\"text\":\"Hello.\"}],"
                                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":100000,\"output_tokens\":50000}}")));

        fx.chat(a.bearer(), ByokFixture.chatBody("claude-sonnet-4-6", "Hello")).expectStatus().isOk();

        anthropicMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")).withHeader("x-api-key", equalTo(key)));
        ModelInfo model = catalog.find("claude-sonnet-4-6").orElseThrow();
        long fee = CostCalculator.feeMicrocents(calculator.listMicrocents(model, 100_000, 50_000), FEE_BP);
        assertThat(lastUsage(a)).containsEntry("byok", true).containsEntry("cost_microcents", fee);
    }

    @Test
    void image_givenOpenAiKey_expectsTheCallersKeyAndTheEstimateAsTheHold() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        ByokFixture.stubOpenAiImage(openAiMock, key);

        Map<String, Object> estimate = fx.estimate(a.bearer(), "gpt-image-2");
        UUID jobId = fx.submitImage(a.bearer(), "gpt-image-2");
        JobView job = fx.awaitJob(jobId, a.bearer(), JobStatus.DONE);

        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", equalTo("Bearer " + key)));
        assertThat(estimate).containsEntry("byok", true);
        assertThat(job.estimatedCostCents()).isEqualTo(((Number) estimate.get("estimated_cost_cents")).longValue());
        ModelInfo model = catalog.find("gpt-image-2").orElseThrow();
        long fee = CostCalculator.feeMicrocents(calculator.imageListMicrocents(model, 1, 0), FEE_BP);
        assertThat(job.estimatedCostCents()).isEqualTo(CostCalculator.feeHoldCents(fee));
        // 53,000 µ¢ at list price; 5% is 2,650 µ¢: nothing billed yet, all of it carried.
        assertThat(job.actualCostCents()).isZero();
        assertThat(pendingMicrocents(a)).isEqualTo(fee);
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L);
        assertThat(lastUsage(a)).containsEntry("byok", true).containsEntry("cost_microcents", fee)
                .containsEntry("provider_cost_microcents", 0L);
    }

    @Test
    void estimates_givenEveryOpenAiImageModel_expectEachToEqualThePreDebitOnAndOffTheCallersKey() {
        Account byok = fx.account(50_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, byok, key, false);
        Account platform = fx.account(50_00L);
        ByokFixture.stubOpenAiImage(openAiMock, key);
        ByokFixture.stubOpenAiImage(openAiMock, ByokFixture.PLATFORM_OPENAI_KEY);

        List<ModelInfo> models = catalog.all().stream()
                .filter(m -> m.provider() == Provider.OPENAI && m.modality() == Modality.IMAGE).toList();
        assertThat(models).isNotEmpty();
        for (ModelInfo model : models) {
            for (Account who : List.of(byok, platform)) {
                Map<String, Object> estimate = fx.estimate(who.bearer(), model.modelName());
                UUID jobId = fx.submitImage(who.bearer(), model.modelName());
                long held = jdbc.queryForObject("SELECT estimated_cost_cents FROM generation_jobs WHERE id = ?",
                        Long.class, jobId);
                assertThat(held).as(model.modelName() + (who == byok ? " on the caller's key" : " on the platform's"))
                        .isEqualTo(((Number) estimate.get("estimated_cost_cents")).longValue());
                if (who == byok) assertThat(estimate).containsEntry("byok", true);
                else assertThat(estimate).doesNotContainKey("byok");
                fx.awaitJob(jobId, who.bearer(), JobStatus.DONE);
            }
        }
    }

    // ===== An adapter not migrated keeps the platform's key =============

    @Test
    void video_givenOpenAiKey_expectsThePlatformsKeyAndPlatformPriceForAnAdapterNotMigrated() {
        Account a = fx.account(50_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        String platform = "Bearer " + ByokFixture.PLATFORM_OPENAI_KEY;
        openAiMock.stubFor(post(urlEqualTo("/v1/videos")).withHeader("Authorization", equalTo(platform))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_byok\",\"status\":\"queued\"}")));
        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_byok")).withHeader("Authorization", equalTo(platform))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_byok\",\"status\":\"completed\"}")));
        openAiMock.stubFor(get(urlEqualTo("/v1/videos/vid_byok/content")).withHeader("Authorization", equalTo(platform))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4")
                        .withBody("FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8))));

        Map<String, Object> estimate = http.post().uri("/v1/estimates").header("Authorization", a.bearer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("model", "sora-2", "seconds", 4))
                .exchange().expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
        UUID jobId = http.post().uri("/v1/videos/generations").header("Authorization", a.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "sora-2", "prompt", "Rain on a window", "seconds", 4))
                .exchange().expectStatus().isAccepted()
                .expectBody(ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
        JobView job = fx.awaitJob(jobId, a.bearer(), JobStatus.DONE);

        assertThat(estimate).doesNotContainKey("byok");
        assertThat(job.estimatedCostCents()).isEqualTo(((Number) estimate.get("estimated_cost_cents")).longValue());
        openAiMock.verify(0, postRequestedFor(urlEqualTo("/v1/videos")).withHeader("Authorization", equalTo("Bearer " + key)));
        assertThat(lastUsage(a)).containsEntry("byok", false);
        // The settings say what the key is used for, and video is not among them.
        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("used_for", List.of("chat", "images"));
    }

    // ===== A key the provider refuses ===================================

    @Test
    void chat_givenRefusedKeyWithoutFallback_expects402NothingSpentAndKeyInvalid(CapturedOutput output) {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        ByokFixture.stubOpenAiRefuses(openAiMock, "/v1/chat/completions", key);
        stubOpenAiChat(ByokFixture.PLATFORM_OPENAI_KEY, 100_000, 50_000);

        assertThat(ByokFixture.codeOf(fx.chat(a.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")), 402))
                .isEqualTo("byok_credential_rejected");

        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L);
        openAiMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("status", "invalid");
        assertThat(audit.findByEventType("byok.rejected").stream().filter(e -> a.id().equals(e.getUserId())))
                .singleElement().satisfies(e -> assertThat(e.getEventDetail()).containsEntry("when", "use"));
        // The next request is priced and sent on the platform's key.
        fx.chat(a.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")).expectStatus().isOk();
        assertThat(lastUsage(a)).containsEntry("byok", false);
        assertThat(output.getAll()).doesNotContain(key);
    }

    @Test
    void chat_givenRefusedKeyWithFallback_expectsOneRetryOnThePlatformsKeyBilledOnceAtThePlatformPrice() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, true);
        ByokFixture.stubOpenAiRefuses(openAiMock, "/v1/chat/completions", key);
        stubOpenAiChat(ByokFixture.PLATFORM_OPENAI_KEY, 100_000, 50_000);

        fx.chat(a.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")).expectStatus().isOk();

        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + key)));
        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        ModelInfo model = catalog.find("gpt-4o").orElseThrow();
        long platform = calculator.actualMicrocents(model, 100_000, 50_000);
        // Billed at the platform price once; the fee's hold went back in full.
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L - platform / 10_000);
        assertThat(pendingMicrocents(a)).isEqualTo(platform % 10_000);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT byok, cost_cents, cost_microcents, outcome FROM usage_records WHERE user_id = ? ORDER BY recorded_at, id",
                a.id());
        assertThat(rows).hasSize(2);
        // The refused attempt is recorded as a failure on the caller's key, charged nothing.
        assertThat(rows.get(0)).containsEntry("byok", true).containsEntry("outcome", "ERROR");
        assertThat(rows.get(1)).containsEntry("byok", false).containsEntry("cost_microcents", platform)
                .containsEntry("outcome", "OK");
        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("status", "invalid");
    }

    @Test
    void chatStream_givenRefusedKeyWithFallback_expectsOneRetryOnThePlatformsKeyBilledOnce() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, true);
        ByokFixture.stubOpenAiRefuses(openAiMock, "/v1/chat/completions", key);
        String sse = String.join("",
                "data: {\"id\":\"chatcmpl-p\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"chatcmpl-p\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-4o\",",
                "\"choices\":[],\"usage\":{\"prompt_tokens\":100000,\"completion_tokens\":50000,\"total_tokens\":150000}}\n\n",
                "data: [DONE]\n\n");
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "text/event-stream").withBody(sse)));

        List<String> events = http.post().uri("/v1/chat/completions")
                .header("Authorization", a.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).anySatisfy(e -> assertThat(e).contains("\"content\":\"Hi\""));
        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + key)));
        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        long platform = calculator.actualMicrocents(catalog.find("gpt-4o").orElseThrow(), 100_000, 50_000);
        long deadline = System.currentTimeMillis() + 10_000;
        while (ledger.authoritativeBalance(a.id()) != 10_00L - platform / 10_000 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        // The fee's hold went back in full; the platform price was billed once.
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L - platform / 10_000);
        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("status", "invalid");
    }

    @Test
    void chatStream_givenRefusedKeyWithoutFallback_expects402AndNothingSpent() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        ByokFixture.stubOpenAiRefuses(openAiMock, "/v1/chat/completions", key);

        http.post().uri("/v1/chat/completions")
                .header("Authorization", a.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "Hello"))))
                .exchange()
                .expectStatus().isEqualTo(402);

        long deadline = System.currentTimeMillis() + 10_000;
        while (ledger.authoritativeBalance(a.id()) != 10_00L && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L);
        openAiMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("status", "invalid");
    }

    @Test
    void image_givenRefusedKeyWithFallback_expectsTheJobRetriedOnThePlatformsKeyAtThePlatformPrice() {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, true);
        ByokFixture.stubOpenAiRefuses(openAiMock, "/v1/images/generations", key);
        ByokFixture.stubOpenAiImage(openAiMock, ByokFixture.PLATFORM_OPENAI_KEY);
        long platformQuote = ((Number) fx.estimate(fx.account(0).bearer(), "gpt-image-2").get("estimated_cost_cents"))
                .longValue();

        UUID jobId = fx.submitImage(a.bearer(), "gpt-image-2");
        JobView job = fx.awaitJob(jobId, a.bearer(), JobStatus.DONE);

        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", equalTo("Bearer " + key)));
        openAiMock.verify(1, postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
        assertThat(job.estimatedCostCents()).isEqualTo(platformQuote);
        assertThat(job.actualCostCents()).isEqualTo(platformQuote);
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L - platformQuote);
        assertThat(lastUsage(a)).containsEntry("byok", false);
        assertThat(fx.credentials(a.bearer()).get(0)).containsEntry("status", "invalid");
    }

    @Test
    void image_givenRefusedKeyWithoutFallback_expectsTheJobFailedAndRefunded(CapturedOutput output) {
        Account a = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, a, key, false);
        ByokFixture.stubOpenAiRefuses(openAiMock, "/v1/images/generations", key);

        UUID jobId = fx.submitImage(a.bearer(), "gpt-image-2");
        JobView job = fx.awaitJob(jobId, a.bearer(), JobStatus.FAILED);

        assertThat(job.errorMessage()).contains("refused the caller's key").doesNotContain(key);
        assertThat(ledger.authoritativeBalance(a.id())).isEqualTo(10_00L);
        assertThat(output.getAll()).doesNotContain(key);
        openAiMock.verify(0, postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withHeader("Authorization", equalTo("Bearer " + ByokFixture.PLATFORM_OPENAI_KEY)));
    }

    // ===== Caps hold the fee ============================================

    @Test
    void caps_givenADailyCap_expectTheFeeHeldAndCountedAgainstIt() {
        Account byok = fx.account(10_00L);
        String key = ByokFixture.userKey("sk");
        fx.saveOpenAiKey(openAiMock, byok, key, false);
        setDailyCap(byok, 2L);
        Account platform = fx.account(10_00L);
        setDailyCap(platform, 2L);
        ByokFixture.stubOpenAiImage(openAiMock, key);
        ByokFixture.stubOpenAiImage(openAiMock, ByokFixture.PLATFORM_OPENAI_KEY);

        // On the caller's key the hold is the fee, 1 cent: under the 2-cent cap.
        fx.awaitJob(fx.submitImage(byok.bearer(), "gpt-image-2"), byok.bearer(), JobStatus.DONE);
        // On the platform's key the hold is the price, 6 cents: over it.
        imageRequest(platform).expectStatus().isEqualTo(402);

        // What the fee bills counts against the cap like any charge. The chat's fee
        // (37,500 µ¢) and the image's carried 2,650 µ¢ bill 4 cents, which leaves
        // the account past its 2-cent cap.
        stubOpenAiChat(key, 100_000, 50_000);
        fx.chat(byok.bearer(), ByokFixture.chatBody("gpt-4o", "Hello")).expectStatus().isOk();
        assertThat(lastUsage(byok)).containsEntry("cost_cents", 4L);
        imageRequest(byok).expectStatus().isEqualTo(402);
    }

    // ===== Helpers ======================================================

    private static void stubOpenAiChat(String key, int prompt, int completion) {
        openAiMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer " + key))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"chatcmpl-byok\",\"object\":\"chat.completion\",\"created\":1700000000,"
                                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"Hello.\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":"
                                + prompt + ",\"completion_tokens\":" + completion + ",\"total_tokens\":"
                                + (prompt + completion) + "}}")));
    }

    private long pendingMicrocents(Account a) {
        return jdbc.queryForObject("SELECT pending_microcents FROM users WHERE id = ?", Long.class, a.id());
    }

    private Map<String, Object> lastUsage(Account a) {
        return jdbc.queryForMap("SELECT byok, cost_cents, cost_microcents, provider_cost_microcents FROM usage_records "
                + "WHERE user_id = ? ORDER BY recorded_at DESC, id DESC LIMIT 1", a.id());
    }

    private Map<String, Object> awaitUsage(Account a) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM usage_records WHERE user_id = ?", Long.class, a.id());
            if (n != null && n > 0) return lastUsage(a);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("no usage record for " + a.id());
    }

    private void setDailyCap(Account a, long cents) {
        User u = users.findById(a.id()).orElseThrow();
        u.setDailyCreditCapCents(cents);
        users.save(u);
    }

    private WebTestClient.ResponseSpec imageRequest(Account a) {
        return http.post().uri("/v1/images/generations").header("Authorization", a.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "A brass teapot on linen", "n", 1))
                .exchange();
    }
}
