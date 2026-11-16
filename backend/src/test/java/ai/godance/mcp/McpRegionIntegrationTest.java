package ai.godance.mcp;

import static ai.godance.mcp.McpFixture.call;
import static ai.godance.mcp.McpFixture.job;
import static ai.godance.mcp.McpFixture.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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

import io.modelcontextprotocol.client.McpSyncClient;

import ai.godance.OpenJobsGuard;
import ai.godance.mcp.McpFixture.Account;
import ai.godance.mcp.McpFixture.Key;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;

/**
 * Hard constraint 1 through MCP: the Alicloud region never routes to OpenAI.
 * A tool call goes through the same gateway as a REST request, so an OpenAI
 * model's job fails there and is refunded, and the provider is never called,
 * exactly as the same request on REST.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class McpRegionIntegrationTest {

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
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
    }

    @LocalServerPort int port;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;

    McpFixture fx;

    @BeforeEach
    void setUp() {
        fx = new McpFixture(http, users, ledger, port);
        openAiMock.resetAll();
        // Were the region check ever skipped, the provider would answer; it must never be asked.
        openAiMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json").withBody("{}")));
    }

    @Test
    void generateImage_givenAnOpenAiModelInTheAlicloudRegion_expectsTheJobFailedAndRefundedAsRestAndNoProviderCall()
            throws InterruptedException {
        Account account = fx.account(10_00L);
        Key key = fx.key(account);

        try (McpSyncClient client = fx.client(key)) {
            Object jobId = ok(call(client, "generate_image", Map.of("model", "gpt-image-2", "prompt", "A teapot")))
                    .get("job_id");
            Map<String, Object> failed = job(client, jobId, "FAILED");

            assertThat((String) failed.get("errorMessage")).contains("not available in this region");
        }
        // The same request on REST ends the same way.
        Map<?, ?> rest = http.post().uri("/v1/images/generations").header(HttpHeaders.AUTHORIZATION, key.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "A teapot"))
                .exchange().expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();
        try (McpSyncClient client = fx.client(key)) {
            assertThat((String) job(client, rest.get("job_id"), "FAILED").get("errorMessage"))
                    .contains("not available in this region");
        }

        assertThat(openAiMock.getAllServeEvents()).isEmpty();
        assertThat(users.findById(account.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10_00L);
    }

    @Test
    void generateVideo_givenAnOpenAiModelInTheAlicloudRegion_expectsTheJobFailedAndRefundedAndNoProviderCall()
            throws InterruptedException {
        Account account = fx.account(50_00L);

        try (McpSyncClient client = fx.client(fx.key(account))) {
            Object jobId = ok(call(client, "generate_video",
                    Map.of("model", "sora-2", "prompt", "Rain on a window", "seconds", 4))).get("job_id");

            job(client, jobId, "FAILED");
        }
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
        assertThat(users.findById(account.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(50_00L);
    }
}
