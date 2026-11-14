package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.ImageSyncService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * A client that goes away while its image request waits (round 10 brief
 * 04): the wait stops, and the job carries on and is settled as usual.
 *
 * <p>On a real port, because the disconnect has to be a real one: the test
 * writes the request on a socket and closes it mid-wait.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "godance.images.sync-poll-interval=50ms"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class ImageSyncDisconnectIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base",  () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint",    MINIO::getS3URL);
        r.add("godance.object-storage.access-key",  MINIO::getUserName);
        r.add("godance.object-storage.secret-key",  MINIO::getPassword);
        r.add("godance.object-storage.bucket",      () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired MeterRegistry meters;

    @Test
    void generate_givenTheClientDisconnectsMidWait_expectsTheWaitStoppedAndTheJobFinishingNormally() throws Exception {
        String email = "sync-gone-" + UUID.randomUUID() + "@godance.test";
        AuthResponse session = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Gone"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "gone", "image_response", "sync"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        // The provider takes three seconds: long enough to leave while the request waits.
        String b64 = Base64.getEncoder().encodeToString(ImageSyncResponseIntegrationTest.TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(3_000)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
        double cancelledBefore = waits("cancelled");
        double doneBefore = waits("done");

        String body = "{\"model\":\"gpt-image-2\",\"prompt\":\"A render nobody waits for\"}";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /v1/images/generations HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + key.get("plaintext") + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "\r\n" + body).getBytes(StandardCharsets.UTF_8));
            out.flush();
            // The job is accepted and running: the request is now waiting on it.
            await(5_000, () -> jobsOf(userId).stream().anyMatch(j -> j.getStatus() == JobStatus.RUNNING));
        }   // the client is gone

        // The wait stops well before the job ends, and is counted as neither done nor failed.
        await(2_000, () -> waits("cancelled") == cancelledBefore + 1);
        GenerationJob job = jobsOf(userId).get(0);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

        // The job carries on: done, stored and charged once, as if the client had stayed.
        await(10_000, () -> jobsOf(userId).get(0).getStatus() == JobStatus.DONE);
        assertThat(jobsOf(userId)).hasSize(1);
        assertThat(jobsOf(userId).get(0).getActualCostCents()).isEqualTo(6L);
        assertThat(ledger.authoritativeBalance(userId)).isEqualTo(100_00L - 6L);
        assertThat(waits("done")).isEqualTo(doneBefore);
        assertThat(waits("cancelled")).isEqualTo(cancelledBefore + 1);
    }

    private List<GenerationJob> jobsOf(UUID userId) {
        return jobs.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private double waits(String outcome) {
        return meters.counter(ImageSyncService.WAITS_METER, "outcome", outcome).count();
    }

    private static void await(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("condition met within %d ms", timeoutMs).isLessThan(deadline);
            Thread.sleep(25);
        }
    }
}
