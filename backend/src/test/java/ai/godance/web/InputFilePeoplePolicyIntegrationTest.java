package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.InputFile;
import ai.godance.domain.InputRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.InputPersonCheck;
import ai.godance.service.InputRefusedException;
import ai.godance.service.InputService;
import ai.godance.service.InputService.InputUse;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.InputDtos.InputRef;

/**
 * The two ends of the people rule that {@code InputFileIntegrationTest}
 * cannot reach with its stand-in check:
 * <ul>
 *   <li>people not allowed and <b>no check model set</b>, which is what
 *       switching inputs on gives until the operator names one
 *       ({@code godance.inputs.check-model}): every upload fails closed;</li>
 *   <li>people allowed: the check is not asked, and the file records that
 *       nothing checked it.</li>
 * </ul>
 * One Postgres and one MinIO serve both; each nested class is its own
 * application with its own settings.
 */
@Testcontainers
class InputFilePeoplePolicyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static void containerProps(DynamicPropertyRegistry r, String bucket) {
        r.add("spring.datasource.url",                    POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",               POSTGRES::getUsername);
        r.add("spring.datasource.password",               POSTGRES::getPassword);
        r.add("godance.object-storage.endpoint",          MINIO::getS3URL);
        r.add("godance.object-storage.access-key",        MINIO::getUserName);
        r.add("godance.object-storage.secret-key",        MINIO::getPassword);
        r.add("godance.object-storage.bucket",            () -> bucket);
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
            "godance.inputs.enabled=true",
            "godance.inputs.allow-people=false"})
    @AutoConfigureWebTestClient
    class PeopleNotAllowedAndNoCheckModel {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            containerProps(r, "godance-inputs-no-check-test");
        }

        @Autowired WebTestClient http;
        @Autowired UserRepository users;
        @Autowired InputFileRepository files;
        @Autowired ObjectStorageClient storage;
        @Autowired AuditLogEntryRepository audit;
        @Autowired org.springframework.context.ApplicationContext context;

        @Test
        void upload_givenNoCheckModel_expects503CheckUnavailableAndNothingStored() {
            // The only check is the built-in one, and no model is configured for it.
            assertThat(context.getBeansOfType(InputPersonCheck.class).values()).singleElement()
                    .isInstanceOf(ai.godance.service.VisionPersonCheck.class);
            assertThat(context.getBean(ai.godance.config.InputsProperties.class).hasCheckModel()).isFalse();
            String email = "no-check-" + UUID.randomUUID() + "@godance.test";
            String bearer = InputFixtures.signup(http, email);
            UUID userId = users.findByEmail(email).orElseThrow().getId();
            InputFixtures.acceptTerms(http, bearer, 1).expectStatus().isNoContent();

            for (String fixture : List.of("plain.png", "gps.jpg", "plain.webp")) {
                InputFixtures.upload(http, bearer, fixture)
                        .expectStatus().isEqualTo(503)
                        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_CHECK_UNAVAILABLE);
            }

            assertThat(files.findByUserId(userId)).isEmpty();
            assertThat(storage.list("inputs/")).isEmpty();
            List<AuditLogEntry> refused = audit.findByEventType(AuditLogService.EVT_INPUT_REFUSED).stream()
                    .filter(row -> userId.equals(row.getUserId())).toList();
            assertThat(refused).hasSize(3).allSatisfy(row -> assertThat(row.getEventDetail())
                    .containsEntry("reason", InputRefusedException.CODE_CHECK_UNAVAILABLE));
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
            "godance.inputs.enabled=true",
            "godance.inputs.allow-people=true"})
    @AutoConfigureWebTestClient
    class PeopleAllowed {

        static final AtomicInteger CHECKS = new AtomicInteger();

        @TestConfiguration
        static class CountingCheck {
            @Bean
            @Primary   // in place of the built-in check, which asks a model
            InputPersonCheck inputPersonCheck() {
                return (userId, image, contentType) -> {
                    CHECKS.incrementAndGet();
                    return InputPersonCheck.Verdict.PERSON;
                };
            }
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            containerProps(r, "godance-inputs-people-test");
        }

        @Autowired WebTestClient http;
        @Autowired UserRepository users;
        @Autowired InputFileRepository files;
        @Autowired InputService inputs;

        @Test
        void upload_givenPeopleAllowed_expectsTheCheckSkippedAndContainsPersonNull() {
            String email = "people-" + UUID.randomUUID() + "@godance.test";
            String bearer = InputFixtures.signup(http, email);
            UUID userId = users.findByEmail(email).orElseThrow().getId();
            InputFixtures.acceptTerms(http, bearer, 1).expectStatus().isNoContent();
            http.get().uri("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                    .expectBody().jsonPath("$.inputs.allow_people").isEqualTo(true);

            Map<?, ?> body = InputFixtures.upload(http, bearer, "gps.jpg")
                    .expectStatus().isCreated()
                    .expectBody(Map.class).returnResult().getResponseBody();

            // The check would have said "a person"; it was never asked, and the file says nothing checked it.
            assertThat(CHECKS.get()).isZero();
            assertThat(body.containsKey("contains_person")).isTrue();
            assertThat(body.get("contains_person")).isNull();
            InputFile stored = files.findById(UUID.fromString((String) body.get("id"))).orElseThrow();
            assertThat(stored.getContainsPerson()).isNull();
            // And with people allowed it can be used as it is.
            assertThat(inputs.resolve(userId, List.of(new InputUse(InputRole.IMAGE, InputRef.file(stored.getId())))))
                    .hasSize(1);
            assertThat(CHECKS.get()).isZero();
        }
    }
}
