package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

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
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.storage.ObjectStorageClient;

/**
 * {@code POST /v1/images/edits} with nothing configured, which is how it
 * ships: inputs are off. Both forms answer {@code inputs_disabled}, whatever
 * the request holds, and nothing is stored, charged or sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ImageEditsDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAi;

    @BeforeAll
    static void startMock() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
    }

    @AfterAll
    static void stopMock() {
        if (openAi != null) openAi.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-image-edits-disabled-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired InputFileRepository files;
    @Autowired InputsProperties inputsProperties;
    @Autowired ObjectStorageClient storage;

    @Test
    void edit_givenInputsDisabled_expects403InputsDisabledOnBothFormsAndNothingWritten() {
        assertThat(inputsProperties.enabled()).isFalse();
        String email = "image-edits-off-" + UUID.randomUUID() + "@godance.test";
        String session = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        Map<?, ?> created = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "edits-off", "image_response", "sync"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String key = "Bearer " + created.get("plaintext");

        for (String bearer : List.of(session, key)) {
            // Multipart, as OpenAI's SDK sends it: well formed, and on a model that would take it.
            for (String model : List.of("gpt-image-2", "imagen-4", "no-such-model")) {
                MultipartBodyBuilder form = new MultipartBodyBuilder();
                form.part("model", model);
                form.part("prompt", "Put the mug on a marble counter.");
                form.part("image", InputFixtures.named(InputFixtures.image("plain.png"), "mug.png")).contentType(MediaType.IMAGE_PNG);
                expectDisabledWithOpenAisError(http.post().uri("/v1/images/edits")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(form.build()))
                        .exchange());
            }
            // JSON, whatever it names; and a body with nothing in it at all.
            for (Map<String, Object> body : List.<Map<String, Object>>of(
                    Map.of("model", "gpt-image-2", "prompt", "x", "images", List.of(Map.of("file_id", UUID.randomUUID().toString()))),
                    Map.of("model", "gpt-image-2", "prompt", "x",
                            "images", List.of(Map.of("job_output", Map.of("job_id", UUID.randomUUID().toString(), "index", 0))),
                            "mask", Map.of("file_id", UUID.randomUUID().toString())),
                    Map.of())) {
                expectDisabledWithOpenAisError(http.post().uri("/v1/images/edits")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchange());
            }
        }

        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        assertThat(files.findByUserId(userId)).isEmpty();
        assertThat(storage.list("inputs/")).isEmpty();
        assertThat(openAi.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void generate_givenInputsDisabledAndARecipeWithInputSlots_expects403InputsDisabledAndNothingCharged() {
        String email = "recipe-slots-off-" + UUID.randomUUID() + "@godance.test";
        String session = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, 100_00L, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        Map<String, Object> aFile = Map.of("file_id", UUID.randomUUID().toString());

        // Keeping slots on a recipe names no image, so it needs no inputs. Running it does.
        for (Map<String, Object> each : List.<Map<String, Object>>of(
                Map.of("modality", "IMAGE", "model", "gpt-image-2", "route", "/v1/images/generations", "slot", "product", "role", "image"),
                Map.of("modality", "IMAGE", "model", "gpt-image-2", "route", "/v1/images/edits", "slot", "product", "role", "image"),
                Map.of("modality", "VIDEO", "model", "seedance-2.0", "route", "/v1/videos/generations", "slot", "opening", "role", "first_frame"))) {
            Map<?, ?> recipe = http.post().uri("/v1/recipes")
                    .header(HttpHeaders.AUTHORIZATION, session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("title", "With a slot " + UUID.randomUUID(), "modality", each.get("modality"),
                            "template", "The product on a counter.", "variables", List.of(), "model", each.get("model"),
                            "inputs", List.of(Map.of("slot", each.get("slot"), "role", each.get("role"), "hint", ""))))
                    .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
            String versionId = (String) ((Map<?, ?>) recipe.get("latest_version")).get("id");

            WebTestClient.ResponseSpec response = http.post().uri((String) each.get("route"))
                    .header(HttpHeaders.AUTHORIZATION, session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("model", each.get("model"),
                            "recipe", Map.of("version_id", versionId, "inputs", Map.of(each.get("slot"), aFile))))
                    .exchange();
            // OpenAI's image routes say it in OpenAI's error object too; Godance's video route does not.
            if (((String) each.get("route")).startsWith("/v1/images/")) expectDisabledWithOpenAisError(response);
            else expectDisabled(response).jsonPath("$.error").doesNotExist();
        }

        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        assertThat(openAi.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void edit_givenNoCredentials_expects401() {
        http.post().uri("/v1/images/edits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "x"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static WebTestClient.BodyContentSpec expectDisabled(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("inputs_disabled")
                .jsonPath("$.detail").isEqualTo("Image inputs are not available.");
    }

    /**
     * {@link #expectDisabled} on one of OpenAI's image routes, where the problem
     * also carries OpenAI's error object: its SDK's exception then reads
     * "Error code: 403 - Image inputs are not available." rather than only the status.
     */
    private static void expectDisabledWithOpenAisError(WebTestClient.ResponseSpec response) {
        expectDisabled(response)
                .jsonPath("$.error.message").isEqualTo("Image inputs are not available.")
                .jsonPath("$.error.type").isEqualTo("permission_error")
                .jsonPath("$.error.code").isEqualTo("inputs_disabled")
                .jsonPath("$.error.param").isEmpty();
    }
}
