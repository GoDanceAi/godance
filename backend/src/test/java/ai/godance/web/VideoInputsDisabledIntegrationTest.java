package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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

import ai.godance.OpenJobsGuard;
import ai.godance.config.InputsProperties;
import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Video inputs with nothing configured, which is how they ship: inputs are
 * off. Every new field answers {@code inputs_disabled} and nothing else
 * happens; a request without them runs exactly as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class VideoInputsDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer ark;

    @BeforeAll
    static void startMock() {
        ark = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ark.start();
    }

    @AfterAll
    static void stopMock() {
        if (ark != null) ark.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.volcengine.api-base", () -> ark.baseUrl());
        r.add("godance.providers.volcengine.poll-interval", () -> "50ms");
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-video-inputs-disabled-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired InputsProperties inputsProperties;

    @BeforeEach
    void reset() {
        ark.resetAll();
    }

    @Test
    void generate_givenInputsDisabledAndAnyOfTheNewFields_expects403InputsDisabledAndNothingElse() {
        assertThat(inputsProperties.enabled()).isFalse();
        String email = "video-inputs-off-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        Map<String, Object> aFile = Map.of("file_id", UUID.randomUUID().toString());
        Map<String, Object> anOutput = Map.of("job_output", Map.of("job_id", UUID.randomUUID().toString(), "index", 0));

        // Each field alone, in either form; on a model that takes it, one that does not, and one that does not exist.
        for (String model : List.of("seedance-2.0", "sora-2", "no-such-model")) {
            for (Map<String, Object> fields : List.<Map<String, Object>>of(
                    Map.of("first_frame", aFile),
                    Map.of("first_frame", anOutput),
                    Map.of("last_frame", aFile),
                    Map.of("reference_images", List.of(anOutput)),
                    Map.of("first_frame", aFile, "last_frame", anOutput, "reference_images", List.of(aFile)))) {
                Map<String, Object> body = new LinkedHashMap<>(Map.of("model", model, "prompt", "From an image.", "seconds", 5));
                body.putAll(fields);

                http.post().uri("/v1/videos/generations")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange()
                        .expectStatus().isForbidden()
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectBody()
                        .jsonPath("$.code").isEqualTo("inputs_disabled")
                        .jsonPath("$.detail").isEqualTo("Image inputs are not available.");
            }
        }

        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        assertThat(ark.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void generate_givenInputsDisabledAndNoneOfTheNewFields_expectsTheRequestToRunAsBefore() throws Exception {
        String email = "video-inputs-off-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        ark.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json").withBody("{\"id\":\"task_off\"}")));
        ark.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_off"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"task_off\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"" + ark.baseUrl() + "/_test/out.mp4\"}}")));
        ark.stubFor(get(urlEqualTo("/_test/out.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4")
                        .withBody("FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8))));

        // An empty list of reference images names no input, so it is a plain request too.
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("model", "seedance-2.0", "prompt", "From text alone.", "seconds", 5),
                Map.of("model", "seedance-2.0", "prompt", "From text alone.", "seconds", 5, "reference_images", List.of()))) {
            ark.resetRequests();
            AsyncJobAcknowledged ack = http.post().uri("/v1/videos/generations")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isAccepted()
                    .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

            JobView view = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                view = http.get().uri("/v1/jobs/{id}", ack.jobId()).header("Authorization", bearer).exchange()
                        .expectStatus().isOk().expectBody(JobView.class).returnResult().getResponseBody();
                if (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED) break;
                Thread.sleep(100);
            }
            assertThat(view.status()).isEqualTo(JobStatus.DONE);
            assertThat(view.inputs()).isEmpty();
            // The provider was sent what it has always been sent, byte for byte.
            assertThat(ark.findAll(postRequestedFor(urlEqualTo("/api/v3/contents/generations/tasks"))).get(0).getBodyAsString())
                    .isEqualTo("{\"model\":\"seedance-2.0\",\"content\":[{\"type\":\"text\",\"text\":\"From text alone.\"}],"
                            + "\"duration\":5,\"ratio\":\"1280x720\"}");
        }
    }
}
