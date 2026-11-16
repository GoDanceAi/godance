package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.InputPersonCheck;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The upload limit per account, on {@code POST /v1/files} and on the images
 * of a {@code POST /v1/images/edits}, with limits switched back on for this one
 * suite (the test profile turns them off) against the shared Redis. The
 * account is new each run, so no counter outlives a test. The per-address
 * limit is left far above what a run can reach, because every suite shares
 * one address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.rate-limit.enabled=true",
        "godance.inputs.enabled=true",
        "godance.inputs.upload-limit-per-account=" + InputUploadRateLimitIntegrationTest.LIMIT,
        "godance.inputs.upload-limit-per-ip=100000"})
@AutoConfigureWebTestClient
@Testcontainers
class InputUploadRateLimitIntegrationTest {

    static final int LIMIT = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.object-storage.endpoint",          MINIO::getS3URL);
        r.add("godance.object-storage.access-key",        MINIO::getUserName);
        r.add("godance.object-storage.secret-key",        MINIO::getPassword);
        r.add("godance.object-storage.bucket",            () -> "godance-inputs-rate-limit-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @TestConfiguration
    static class StandInCheck {
        @Bean
        @Primary   // in place of the built-in check, which asks a model
        InputPersonCheck inputPersonCheck() {
            return (userId, image, contentType) -> InputPersonCheck.Verdict.NO_PERSON;
        }
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired InputFileRepository files;
    @Autowired ObjectMapper json;

    @Test
    void upload_givenMoreThanTheAccountsLimitInAnHour_expects429WithRetryAfter() {
        String email = "upload-rl-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        InputFixtures.acceptTerms(http, bearer, 1).expectStatus().isNoContent();

        for (int i = 0; i < LIMIT; i++) {
            InputFixtures.upload(http, bearer, "plain.png").expectStatus().isCreated();
        }
        InputFixtures.upload(http, bearer, "plain.png")
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);

        // The upload over the limit stored nothing.
        assertThat(files.findByUserId(userId)).hasSize(LIMIT);
        // Another account is not held back by this one's limit.
        String otherBearer = InputFixtures.signup(http, "upload-rl-" + UUID.randomUUID() + "@godance.test");
        InputFixtures.acceptTerms(http, otherBearer, 1).expectStatus().isNoContent();
        InputFixtures.upload(http, otherBearer, "plain.png").expectStatus().isCreated();
    }

    @Test
    void edit_givenAnAccountAtItsUploadLimit_expects429WithOpenAisErrorObjectWhereFilesHasNone() {
        String email = "upload-rl-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        InputFixtures.acceptTerms(http, bearer, 1).expectStatus().isNoContent();
        for (int i = 0; i < LIMIT; i++) {
            InputFixtures.upload(http, bearer, "plain.png").expectStatus().isCreated();
        }

        // An edit's image is an upload as well, so the same limit holds it back…
        MultipartBodyBuilder edit = new MultipartBodyBuilder();
        edit.part("model", "gpt-image-2");
        edit.part("prompt", "Put the mug on a marble counter.");
        edit.part("image", InputFixtures.named(InputFixtures.image("plain.png"), "mug.png")).contentType(MediaType.IMAGE_PNG);
        JsonNode problem = json.readTree(http.post().uri("/v1/images/edits")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(edit.build()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody());

        // …and on that route says so in OpenAI's error object too, as a rate limit, which its SDK reads.
        assertThat(problem.get("detail").asString()).startsWith("too many requests, try again in ");
        assertThat(problem.get("error").get("message").asString()).isEqualTo(problem.get("detail").asString());
        assertThat(problem.get("error").get("type").asString()).isEqualTo("rate_limit_error");
        assertThat(problem.get("error").get("code").isNull()).isTrue();
        assertThat(files.findByUserId(userId)).hasSize(LIMIT);

        // POST /v1/files answers its own 429 as it always has.
        JsonNode upload = json.readTree(InputFixtures.upload(http, bearer, "plain.png")
                .expectStatus().isEqualTo(429)
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(upload.get("detail").asString()).startsWith("too many requests, try again in ");
        assertThat(upload.has("error")).as("POST /v1/files carries no OpenAI error object").isFalse();
    }
}
