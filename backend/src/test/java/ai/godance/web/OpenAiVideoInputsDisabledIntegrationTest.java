package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.InputsProperties;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.storage.ObjectStorageClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/videos} as the service ships: inputs switched off. A
 * video from text works, in the multipart form OpenAI's SDK always sends;
 * an {@code input_reference} answers {@code inputs_disabled} and does
 * nothing else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class OpenAiVideoInputsDisabledIntegrationTest {

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
        r.add("godance.object-storage.bucket", () -> "godance-openai-video-inputs-off-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired InputsProperties inputsProperties;
    @Autowired ObjectStorageClient storage;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        ark.resetAll();
    }

    @Test
    void create_givenInputsDisabledAndAFormOfTextFields_expectsTheVideoToRun() throws Exception {
        assertThat(inputsProperties.enabled()).isFalse();
        Signed me = signedUp();
        ark.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks")).willReturn(okJson("{\"id\":\"task_off\"}")));
        ark.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_off"))
                .willReturn(okJson("{\"id\":\"task_off\",\"status\":\"succeeded\","
                        + "\"content\":{\"video_url\":\"" + ark.baseUrl() + "/_test/out.mp4\"}}")));
        ark.stubFor(get(urlEqualTo("/_test/out.mp4")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "video/mp4").withBody("CLIP".getBytes(StandardCharsets.UTF_8))));
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "A lighthouse beam sweeping over waves.");
        form.part("seconds", "8");

        JsonNode video = json.readTree(http.post().uri("/v1/videos")
                .header("Authorization", me.bearer())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(video.get("status").asString()).isEqualTo("queued");
        UUID id = UUID.fromString(video.get("id").asString());
        long deadline = System.currentTimeMillis() + 10_000;
        GenerationJob job = jobs.findById(id).orElseThrow();
        while (System.currentTimeMillis() < deadline
                && (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING)) {
            Thread.sleep(50);
            job = jobs.findById(id).orElseThrow();
        }
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void create_givenInputsDisabledAndAnInputReference_expects403InputsDisabledAndNothingElse() {
        assertThat(inputsProperties.enabled()).isFalse();
        Signed me = signedUp();
        Map<String, Object> aFile = Map.of("file_id", UUID.randomUUID().toString());
        Map<String, Object> anOutput = Map.of("job_output", Map.of("job_id", UUID.randomUUID().toString(), "index", 0));

        // As JSON, in either form of reference; on a model that takes a first frame, one that does not,
        // and one that does not exist.
        for (String model : List.of("seedance-2.0", "sora-2", "no-such-model")) {
            for (Map<String, Object> reference : List.of(aFile, anOutput)) {
                Map<String, Object> body = new LinkedHashMap<>(Map.of("model", model, "prompt", "From an image."));
                body.put("input_reference", reference);
                refused(http.post().uri("/v1/videos")
                        .header("Authorization", me.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body));
            }

            // As a form: the reference the way OpenAI's SDK writes it, and the image itself as a part.
            MultipartBodyBuilder named = new MultipartBodyBuilder();
            named.part("model", model);
            named.part("prompt", "From an image.");
            named.part("input_reference[file_id]", UUID.randomUUID().toString());
            refused(http.post().uri("/v1/videos")
                    .header("Authorization", me.bearer())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(named.build())));

            MultipartBodyBuilder sent = new MultipartBodyBuilder();
            sent.part("model", model);
            sent.part("prompt", "From an image.");
            sent.part("input_reference", InputFixtures.named(InputFixtures.image("plain.png"), "first.png"))
                    .contentType(MediaType.IMAGE_PNG);
            refused(http.post().uri("/v1/videos")
                    .header("Authorization", me.bearer())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(sent.build())));
        }

        assertThat(users.findById(me.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(me.id())).isEmpty();
        assertThat(ark.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        // No part of any form was stored.
        assertThat(storage.list("inputs/")).isEmpty();
    }

    @Test
    void create_givenInputsDisabledAndALastFrameOrReferenceImages_expects403InputsDisabledAndNothingElse() {
        assertThat(inputsProperties.enabled()).isFalse();
        Signed me = signedUp();
        Map<String, Object> aFile = Map.of("file_id", UUID.randomUUID().toString());

        for (String field : List.of("last_frame", "reference_images")) {
            // As JSON, on a model that takes the field and on one that does not.
            for (String model : List.of("seedance-2.0", "sora-2")) {
                Map<String, Object> body = new LinkedHashMap<>(Map.of("model", model, "prompt", "From an image."));
                body.put(field, field.equals("last_frame") ? aFile : List.of(aFile));
                refused(http.post().uri("/v1/videos")
                        .header("Authorization", me.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body));
            }

            // As OpenAI's SDK writes it into a form, from extra_body.
            MultipartBodyBuilder form = new MultipartBodyBuilder();
            form.part("model", "seedance-2.0");
            form.part("prompt", "From an image.");
            form.part(field.equals("last_frame") ? "last_frame[file_id]" : "reference_images[][file_id]",
                    UUID.randomUUID().toString());
            refused(http.post().uri("/v1/videos")
                    .header("Authorization", me.bearer())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(form.build())));
        }

        assertThat(users.findById(me.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(me.id())).isEmpty();
        assertThat(ark.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    private void refused(WebTestClient.RequestHeadersSpec<?> request) {
        request.exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("inputs_disabled")
                .jsonPath("$.detail").isEqualTo("Image inputs are not available.")
                .jsonPath("$.error.code").isEqualTo("inputs_disabled")
                .jsonPath("$.error.message").isEqualTo("Image inputs are not available.")
                .jsonPath("$.error.type").isEqualTo("permission_error");
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signedUp() {
        String email = "oai-video-off-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, bearer);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }
}
