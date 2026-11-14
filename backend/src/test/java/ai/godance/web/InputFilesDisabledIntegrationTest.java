package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.config.InputsProperties;
import ai.godance.domain.InputRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.InputRefusedException;
import ai.godance.service.InputService;
import ai.godance.service.InputService.InputUse;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.InputDtos.InputRef;

/**
 * Inputs as they ship: switched off. Nothing here sets a property, so this
 * is the configuration a deployment has until its operator changes it.
 * Every way of handing in an input answers 403 {@code inputs_disabled} and
 * leaves nothing behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class InputFilesDisabledIntegrationTest {

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
        r.add("godance.object-storage.bucket",            () -> "godance-inputs-disabled-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired InputFileRepository files;
    @Autowired InputService inputs;
    @Autowired InputsProperties props;
    @Autowired ObjectStorageClient storage;
    @Autowired AuditLogEntryRepository audit;
    @Autowired JdbcTemplate jdbc;

    @Test
    void defaults_givenNothingConfigured_expectsEverythingOff() {
        assertThat(props.enabled()).isFalse();
        assertThat(props.allowPeople()).isFalse();
        assertThat(props.checkModel()).isEmpty();
        assertThat(props.hasCheckModel()).isFalse();
        assertThat(props.termsVersion()).isEqualTo(1);
        assertThat(props.unusedTtlHours()).isEqualTo(24);
        assertThat(props.maxBytes().toMegabytes()).isEqualTo(20);
        assertThat(props.maxMegapixels()).isEqualTo(40);
    }

    @Test
    void upload_givenInputsDisabled_expects403InputsDisabledAndNothingStored() {
        String bearer = InputFixtures.signup(http, email());

        InputFixtures.upload(http, bearer, "gps.jpg")
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_DISABLED);

        assertNothingStored();
    }

    @Test
    void upload_givenInputsDisabledAndAnApiKey_expects403InputsDisabled() {
        String bearer = InputFixtures.signup(http, email());
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "inputs-off"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        InputFixtures.upload(http, "Bearer " + key.get("plaintext"), "gps.png")
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_DISABLED);

        assertNothingStored();
    }

    @Test
    void upload_givenInputsDisabledAndABodyThatIsNotMultipart_expectsStill403InputsDisabled() {
        String bearer = InputFixtures.signup(http, email());

        http.post().uri("/v1/files")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("file", "anything"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_DISABLED);
    }

    @Test
    void upload_givenNoCredentials_expects401() {
        http.post().uri("/v1/files").contentType(MediaType.MULTIPART_FORM_DATA).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void acceptTerms_givenInputsDisabled_expects403InputsDisabledAndNothingRecorded() {
        String email = email();
        String bearer = InputFixtures.signup(http, email);

        InputFixtures.acceptTerms(http, bearer, 1)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_DISABLED);

        assertThat(users.findByEmail(email).orElseThrow().getInputsTermsVersion()).isNull();
        assertThat(audit.findByEventType(AuditLogService.EVT_INPUTS_TERMS_ACCEPTED)).isEmpty();
    }

    @Test
    void me_givenInputsDisabled_expectsInputsOff() {
        String bearer = InputFixtures.signup(http, email());

        http.get().uri("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.inputs.enabled").isEqualTo(false)
                .jsonPath("$.inputs.terms_version").isEqualTo(1)
                .jsonPath("$.inputs.accepted").isEqualTo(false)
                .jsonPath("$.inputs.accepted_at").isEmpty()
                .jsonPath("$.inputs.allow_people").isEqualTo(false);
    }

    @Test
    void resolve_givenInputsDisabled_expectsInputsDisabledForEitherKindOfReference() {
        String email = email();
        InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();

        assertThatThrownBy(() -> inputs.resolve(userId, List.of(new InputUse(InputRole.IMAGE, InputRef.file(UUID.randomUUID())))))
                .isInstanceOfSatisfying(InputRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo(InputRefusedException.CODE_DISABLED));
        assertThatThrownBy(() -> inputs.resolve(userId, List.of(new InputUse(InputRole.FIRST_FRAME,
                InputRef.jobOutput(UUID.randomUUID(), 0)))))
                .isInstanceOfSatisfying(InputRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo(InputRefusedException.CODE_DISABLED));
        assertThatThrownBy(() -> inputs.content(userId, UUID.randomUUID()))
                .isInstanceOfSatisfying(InputRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo(InputRefusedException.CODE_DISABLED));
        // A request that names no input is not looked at: it behaves as it always did.
        assertThat(inputs.resolve(userId, List.of())).isEmpty();
        assertThat(inputs.resolve(userId, null)).isEmpty();
    }

    @Test
    void read_givenInputsDisabled_expectsAnEmptyListAndFileNotFound() {
        String bearer = InputFixtures.signup(http, email());

        http.get().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");
        for (String path : List.of("/v1/files/" + UUID.randomUUID(), "/v1/files/" + UUID.randomUUID() + "/content")) {
            http.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
        }
        http.delete().uri("/v1/files/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
    }

    /**
     * Switching inputs off never strands what was stored while they were on:
     * its owner can still read and delete it, and the nightly run still
     * deletes it once nothing uses it.
     */
    @Test
    void storedFiles_givenInputsSwitchedOffAfterwards_expectsReadDeleteAndExpiryToStillWork() {
        String email = email();
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        UUID kept = storedWhileInputsWereOn(userId, java.time.Instant.now().plusSeconds(3600));
        UUID expired = storedWhileInputsWereOn(userId, java.time.Instant.now().minusSeconds(60));

        http.get().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2);
        http.get().uri("/v1/files/{id}/content", kept).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isFound();

        InputService.ExpiryCount run = inputs.deleteExpired(java.time.Instant.now());

        assertThat(run.deleted()).isEqualTo(1);
        assertThat(files.findById(expired)).isEmpty();
        assertThat(storage.list("inputs/" + expired + "/")).isEmpty();

        http.delete().uri("/v1/files/{id}", kept).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectStatus().isNoContent();
        assertThat(files.findById(kept)).isEmpty();
        assertThat(storage.list("inputs/" + kept + "/")).isEmpty();
        // But it could not have been used in a request, nor a new one uploaded.
        InputFixtures.upload(http, bearer, "plain.png").expectStatus().isForbidden();
    }

    /** A file row and its object, as an upload left them before inputs were switched off. */
    private UUID storedWhileInputsWereOn(UUID userId, java.time.Instant expiresAt) {
        byte[] image = InputFixtures.image("plain.png");
        UUID id = UUID.randomUUID();
        String key = "inputs/" + id + "/0123456789abcdef0123456789abcdef.png";
        storage.upload(key, image, "image/png");
        files.save(new ai.godance.domain.InputFile(id, userId, key, "image/png", image.length, 64, 48, false,
                java.time.Instant.now(), expiresAt));
        return id;
    }

    private void assertNothingStored() {
        assertThat(files.count()).isZero();
        assertThat(storage.list("inputs/")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job_inputs", Long.class)).isZero();
        assertThat(audit.findByEventType(AuditLogService.EVT_INPUT_UPLOADED)).isEmpty();
        assertThat(audit.findByEventType(AuditLogService.EVT_INPUT_REFUSED)).isEmpty();
    }

    private static String email() {
        return "inputs-off-" + UUID.randomUUID() + "@godance.test";
    }
}
