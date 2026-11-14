package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.InputFile;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.InputPersonCheck;
import ai.godance.service.InputPersonCheck.Verdict;
import ai.godance.service.InputRefusedException;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ImageMetadataTest;
import ai.godance.storage.ObjectStorageClient;

/**
 * Input files with inputs switched on and people not allowed, which is the
 * first stage an operator would open: {@code POST /v1/files} and every
 * refusal in its order, what is stored and what is not, the person check,
 * owner-only reading and deleting, and the input terms.
 *
 * <p>The limits are small here (64 KB, one megapixel, five files) so a test
 * can reach them, and the terms are at version 2 so an older acceptance can
 * be told apart. The person check is a stand-in a test sets the answer of;
 * brief 02 supplies the real one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.inputs.enabled=true",
        "godance.inputs.allow-people=false",
        "godance.inputs.terms-version=" + InputFileIntegrationTest.TERMS,
        "godance.inputs.max-bytes=64KB",
        "godance.inputs.max-megapixels=1",
        "godance.inputs.max-files-per-account=" + InputFileIntegrationTest.FILE_LIMIT,
        "spring.webflux.multipart.max-disk-usage-per-part=128KB"})
@AutoConfigureWebTestClient
@Testcontainers
class InputFileIntegrationTest {

    static final int TERMS = 2;
    static final int FILE_LIMIT = 5;
    private static final String CANARY = "GPSCANARY";

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
        r.add("godance.object-storage.bucket",            () -> "godance-input-files-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    /** What the stand-in check answers, how often it was asked, and the image it was last shown. */
    static final AtomicReference<Verdict> VERDICT = new AtomicReference<>(Verdict.NO_PERSON);
    static final AtomicInteger CHECKS = new AtomicInteger();
    static final AtomicReference<byte[]> CHECKED_IMAGE = new AtomicReference<>();
    static final AtomicReference<RuntimeException> CHECK_FAILURE = new AtomicReference<>();

    @TestConfiguration
    static class StandInCheck {
        @Bean
        InputPersonCheck inputPersonCheck() {
            return (userId, image, contentType) -> {
                CHECKS.incrementAndGet();
                CHECKED_IMAGE.set(image);
                if (CHECK_FAILURE.get() != null) throw CHECK_FAILURE.get();
                return VERDICT.get();
            };
        }
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired InputFileRepository files;
    @Autowired ObjectStorageClient storage;
    @Autowired AuditLogEntryRepository audit;
    @Autowired CreditLedgerRepository ledger;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetCheck() {
        VERDICT.set(Verdict.NO_PERSON);
        CHECKS.set(0);
        CHECKED_IMAGE.set(null);
        CHECK_FAILURE.set(null);
    }

    // ===== What is stored ==============================================

    @Test
    void upload_givenJpegWithGpsExif_storesItWithoutTheMetadata() throws Exception {
        Signed u = ready();
        byte[] original = InputFixtures.image("gps.jpg");
        assertThat(ImageMetadataTest.count(original, CANARY)).isPositive();
        Instant before = Instant.now();

        Map<?, ?> body = InputFixtures.upload(http, u.bearer(), "gps.jpg")
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        // The file as the contract describes it.
        UUID id = UUID.fromString((String) body.get("id"));
        assertThat(body.get("object")).isEqualTo("file");
        assertThat(body.get("purpose")).isEqualTo("generation_input");
        assertThat(body.get("content_type")).isEqualTo("image/jpeg");
        assertThat(body.get("width")).isEqualTo(48);          // stored 64 × 48, turned a quarter
        assertThat(body.get("height")).isEqualTo(64);
        assertThat(body.get("contains_person")).isEqualTo(false);
        long createdAt = ((Number) body.get("created_at")).longValue();
        long expiresAt = ((Number) body.get("expires_at")).longValue();
        assertThat(createdAt).isBetween(before.getEpochSecond(), Instant.now().getEpochSecond());
        assertThat(expiresAt - createdAt).isEqualTo(24 * 3600);

        // What is in storage is the picture without the metadata: no GPS, no device, no comment.
        InputFile row = files.findById(id).orElseThrow();
        byte[] stored = storage.download(row.getStorageKey());
        assertThat(ImageMetadataTest.count(stored, CANARY)).isZero();
        assertThat(stored).isEqualTo(ImageMetadata.strip(original).bytes());
        assertThat(((Number) body.get("bytes")).intValue()).isEqualTo(stored.length).isLessThan(original.length);
        ImageMetadataTest.assertSamePixels(original, stored);
        // Under its own prefix, apart from job outputs, with a name nobody could guess.
        assertThat(row.getStorageKey()).matches("inputs/" + id + "/[0-9a-f]{32}\\.jpg");
        assertThat(storage.list("images/")).isEmpty();
        assertThat(row.getUserId()).isEqualTo(u.id());

        // The check saw the stripped image, once.
        assertThat(CHECKS.get()).isEqualTo(1);
        assertThat(CHECKED_IMAGE.get()).isEqualTo(stored);

        // The audit row says how big and what kind, and carries no image.
        AuditLogEntry uploaded = auditRows(AuditLogService.EVT_INPUT_UPLOADED, u.id()).get(0);
        assertThat(uploaded.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(uploaded.getEventDetail().keySet())
                .containsExactlyInAnyOrder("fileId", "bytes", "contentType", "width", "height", "containsPerson");
        assertThat(uploaded.getEventDetail()).containsEntry("fileId", id.toString())
                .containsEntry("contentType", "image/jpeg");
        assertThat(((Number) uploaded.getEventDetail().get("bytes")).intValue()).isEqualTo(stored.length);
        // Uploading is free.
        assertThat(ledger.sumDeltaForUser(u.id())).isZero();
    }

    @ParameterizedTest
    @CsvSource({"gps.png,image/png,png", "gps.webp,image/webp,webp"})
    void upload_givenPngOrWebpWithGpsMetadata_storesItWithoutTheMetadata(String fixture, String type, String ext) {
        Signed u = ready();
        byte[] original = InputFixtures.image(fixture);

        Map<?, ?> body = InputFixtures.upload(http, u.bearer(), fixture)
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(body.get("content_type")).isEqualTo(type);
        assertThat(body.get("width")).isEqualTo(48);
        assertThat(body.get("height")).isEqualTo(64);
        InputFile row = files.findById(UUID.fromString((String) body.get("id"))).orElseThrow();
        assertThat(row.getStorageKey()).endsWith("." + ext);
        byte[] stored = storage.download(row.getStorageKey());
        assertThat(ImageMetadataTest.count(stored, CANARY)).isZero();
        assertThat(ImageMetadataTest.count(stored, "xmpmeta")).isZero();
        assertThat(stored).isEqualTo(ImageMetadata.strip(original).bytes());
    }

    @Test
    void upload_givenADeclaredTypeThatIsWrong_expectsTheTypeFromTheBytes() {
        Signed u = ready();

        // A JPEG called a PNG is stored as the JPEG it is.
        InputFixtures.upload(http, u.bearer(), InputFixtures.image("plain.jpg"), "holiday.png", MediaType.IMAGE_PNG)
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.content_type").isEqualTo("image/jpeg");
        // A GIF called a PNG is not an image an input may be.
        refused(InputFixtures.upload(http, u.bearer(), InputFixtures.image("picture.gif"), "holiday.png", MediaType.IMAGE_PNG),
                415, InputRefusedException.CODE_TYPE_UNSUPPORTED);
    }

    // ===== Every refusal, in its order =================================

    @Test
    void upload_givenAccountThatIsNotAnAdults_expects403NotAllowedForAccount() {
        Signed u = ready();
        jdbc.update("UPDATE users SET age_status = 'CONSENT_GRANTED' WHERE id = ?", u.id());

        // Second in the order: it answers before the size or the type is looked at.
        refused(InputFixtures.upload(http, u.bearer(), tooManyBytes(), "big.bin", MediaType.APPLICATION_OCTET_STREAM),
                403, InputRefusedException.CODE_NOT_ALLOWED_FOR_ACCOUNT);
        refused(InputFixtures.upload(http, u.bearer(), "plain.png"),
                403, InputRefusedException.CODE_NOT_ALLOWED_FOR_ACCOUNT);

        assertNothingStoredFor(u);
        assertThat(CHECKS.get()).isZero();
        assertThat(refusalReasons(u)).containsOnly(InputRefusedException.CODE_NOT_ALLOWED_FOR_ACCOUNT).hasSize(2);
    }

    @Test
    void upload_givenTermsNotAccepted_expects403TermsRequired() {
        Signed u = signedUp();

        // Third in the order: before the size.
        refused(InputFixtures.upload(http, u.bearer(), tooManyBytes(), "big.bin", MediaType.APPLICATION_OCTET_STREAM),
                403, InputRefusedException.CODE_TERMS_REQUIRED)
                .jsonPath("$.terms_version").isEqualTo(TERMS);

        assertNothingStoredFor(u);
        assertThat(refusalReasons(u)).containsExactly(InputRefusedException.CODE_TERMS_REQUIRED);
    }

    @Test
    void upload_givenAnOlderVersionOfTheTermsAccepted_expects403TermsRequired() {
        Signed u = signedUp();
        jdbc.update("UPDATE users SET inputs_terms_version = ?, inputs_terms_accepted_at = now() WHERE id = ?",
                TERMS - 1, u.id());

        refused(InputFixtures.upload(http, u.bearer(), "plain.png"), 403, InputRefusedException.CODE_TERMS_REQUIRED);
        http.get().uri("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody()
                .jsonPath("$.inputs.accepted").isEqualTo(false)
                .jsonPath("$.inputs.accepted_at").isEmpty()
                .jsonPath("$.inputs.terms_version").isEqualTo(TERMS);

        // Accepting the current version is what counts.
        InputFixtures.acceptTerms(http, u.bearer(), TERMS).expectStatus().isNoContent();
        InputFixtures.upload(http, u.bearer(), "plain.png").expectStatus().isCreated();
    }

    @Test
    void upload_givenMoreBytesThanAllowed_expects413TooLarge() {
        Signed u = ready();

        // Fourth in the order: the bytes are not an image either, and the size answers first.
        refused(InputFixtures.upload(http, u.bearer(), tooManyBytes(), "big.png", MediaType.IMAGE_PNG),
                413, InputRefusedException.CODE_TOO_LARGE);
        // Far past the limit, where the part is cut off while it is still being received.
        refused(InputFixtures.upload(http, u.bearer(), new byte[300_000], "bigger.png", MediaType.IMAGE_PNG),
                413, InputRefusedException.CODE_TOO_LARGE);

        assertNothingStoredFor(u);
        assertThat(CHECKS.get()).isZero();
        assertThat(refusalReasons(u)).containsOnly(InputRefusedException.CODE_TOO_LARGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"picture.gif", "animated.webp", "animated.png"})
    void upload_givenSomethingThatIsNotAStillPngJpegOrWebp_expects415TypeUnsupported(String fixture) {
        Signed u = ready();

        refused(InputFixtures.upload(http, u.bearer(), fixture), 415, InputRefusedException.CODE_TYPE_UNSUPPORTED);

        assertNothingStoredFor(u);
        assertThat(CHECKS.get()).isZero();
        assertThat(refusalReasons(u)).containsExactly(InputRefusedException.CODE_TYPE_UNSUPPORTED);
    }

    @Test
    void upload_givenTextAnEmptyFileOrACutOffImage_expects415TypeUnsupported() {
        Signed u = ready();
        byte[] cutOff = java.util.Arrays.copyOf(InputFixtures.image("gps.png"), 200);

        refused(InputFixtures.upload(http, u.bearer(), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8), "drawing.png", MediaType.IMAGE_PNG),
                415, InputRefusedException.CODE_TYPE_UNSUPPORTED);
        refused(InputFixtures.upload(http, u.bearer(), new byte[0], "empty.png", MediaType.IMAGE_PNG),
                415, InputRefusedException.CODE_TYPE_UNSUPPORTED);
        refused(InputFixtures.upload(http, u.bearer(), cutOff, "cut.png", MediaType.IMAGE_PNG),
                415, InputRefusedException.CODE_TYPE_UNSUPPORTED);

        assertNothingStoredFor(u);
    }

    @Test
    void upload_givenMorePixelsThanAllowed_expects413TooLargeBeforeThePersonCheck() {
        Signed u = ready();
        VERDICT.set(Verdict.PERSON);
        byte[] big = InputFixtures.png(1200, 1000);      // 1.2 megapixels in a few kilobytes
        assertThat(big.length).isLessThan(64 * 1024);

        // Sixth in the order: the check, seventh, is never asked.
        refused(InputFixtures.upload(http, u.bearer(), big, "wide.png", MediaType.IMAGE_PNG),
                413, InputRefusedException.CODE_TOO_LARGE);

        assertThat(CHECKS.get()).isZero();
        assertNothingStoredFor(u);
        // At the limit exactly, it passes.
        VERDICT.set(Verdict.NO_PERSON);
        InputFixtures.upload(http, u.bearer(), InputFixtures.png(1000, 1000), "square.png", MediaType.IMAGE_PNG)
                .expectStatus().isCreated();
    }

    // ===== The person check ============================================

    @Test
    void upload_givenAnImageThatShowsAPerson_expects422PersonNotAllowed() {
        Signed u = ready();
        VERDICT.set(Verdict.PERSON);

        refused(InputFixtures.upload(http, u.bearer(), "plain.jpg"), 422, InputRefusedException.CODE_PERSON_NOT_ALLOWED);

        assertThat(CHECKS.get()).isEqualTo(1);
        assertNothingStoredFor(u);
        assertThat(refusalReasons(u)).containsExactly(InputRefusedException.CODE_PERSON_NOT_ALLOWED);
    }

    @Test
    void upload_givenACheckThatCannotSay_expects503CheckUnavailable() {
        Signed u = ready();

        VERDICT.set(Verdict.UNAVAILABLE);
        refused(InputFixtures.upload(http, u.bearer(), "plain.jpg"), 503, InputRefusedException.CODE_CHECK_UNAVAILABLE);
        // A check that fails, or answers nothing, is a check that cannot say.
        CHECK_FAILURE.set(new IllegalStateException("the check model timed out"));
        refused(InputFixtures.upload(http, u.bearer(), "plain.jpg"), 503, InputRefusedException.CODE_CHECK_UNAVAILABLE);
        CHECK_FAILURE.set(null);
        VERDICT.set(null);
        refused(InputFixtures.upload(http, u.bearer(), "plain.jpg"), 503, InputRefusedException.CODE_CHECK_UNAVAILABLE);

        assertThat(CHECKS.get()).isEqualTo(3);
        assertNothingStoredFor(u);
        assertThat(refusalReasons(u)).containsOnly(InputRefusedException.CODE_CHECK_UNAVAILABLE).hasSize(3);
    }

    // ===== Limits and malformed requests ===============================

    @Test
    void upload_givenAsManyFilesAsAnAccountMayKeep_expects409LimitReached() {
        Signed u = ready();
        for (int i = 0; i < FILE_LIMIT; i++) {
            InputFixtures.upload(http, u.bearer(), "plain.webp").expectStatus().isCreated();
        }

        refused(InputFixtures.upload(http, u.bearer(), "plain.webp"), 409, InputRefusedException.CODE_LIMIT_REACHED)
                .jsonPath("$.limit").isEqualTo(FILE_LIMIT);

        assertThat(files.countLive(u.id())).isEqualTo(FILE_LIMIT);
        // Deleting one makes room for another.
        UUID first = files.findByUserId(u.id()).get(0).getId();
        http.delete().uri("/v1/files/{id}", first).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isNoContent();
        InputFixtures.upload(http, u.bearer(), "plain.webp").expectStatus().isCreated();
    }

    @Test
    void upload_givenNoFilePartAnotherPurposeOrABodyThatIsNotMultipart_expectsARefusalAndNothingStored() {
        Signed u = ready();

        MultipartBodyBuilder noFile = new MultipartBodyBuilder();
        noFile.part("purpose", "generation_input");
        http.post().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(noFile.build()))
                .exchange().expectStatus().isBadRequest();

        MultipartBodyBuilder otherPurpose = new MultipartBodyBuilder();
        otherPurpose.part("purpose", "fine-tune");
        otherPurpose.part("file", InputFixtures.named(InputFixtures.image("plain.png"), "plain.png"))
                .contentType(MediaType.IMAGE_PNG);
        http.post().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(otherPurpose.build()))
                .exchange().expectStatus().isBadRequest();

        http.post().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("file", "anything"))
                .exchange().expectStatus().isEqualTo(415);

        assertNothingStoredFor(u);
    }

    @Test
    void upload_givenNoPurpose_expectsItStoredAsAGenerationInput() {
        Signed u = ready();
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("file", InputFixtures.named(InputFixtures.image("plain.png"), "plain.png")).contentType(MediaType.IMAGE_PNG);

        http.post().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(form.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.purpose").isEqualTo("generation_input");
    }

    // ===== Reading, listing and deleting: the owner only ===============

    @Test
    void read_givenTheOwner_expectsTheFileItsListAndAShortLivedLinkToItsBytes() {
        Signed u = ready();
        UUID id = uploaded(u, "gps.jpg");

        http.get().uri("/v1/files/{id}", id).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.content_type").isEqualTo("image/jpeg")
                .jsonPath("$.expires_at").isNumber();

        String location = http.get().uri("/v1/files/{id}/content", id)
                .header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody().isEmpty()
                .getResponseHeaders().getLocation().toString();
        // The link is signed and lasts five minutes, not the seven days a job's output link does.
        assertThat(location).contains("X-Amz-Signature=").contains("X-Amz-Expires=300");
        HttpResponse<byte[]> fetched = InputFixtures.fetch(location);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(ImageMetadata.strip(InputFixtures.image("gps.jpg")).bytes());
        assertThat(fetched.headers().firstValue("Content-Type")).contains("image/jpeg");
        // Without the signature the object is not readable: it is never public.
        assertThat(InputFixtures.fetch(location.substring(0, location.indexOf('?'))).statusCode()).isEqualTo(403);
    }

    @Test
    void read_givenAnotherUser_expects404FileNotFoundEverywhere() {
        Signed owner = ready();
        Signed other = ready();
        UUID id = uploaded(owner, "plain.png");

        for (String path : List.of("/v1/files/" + id, "/v1/files/" + id + "/content")) {
            http.get().uri(path).header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
        }
        http.delete().uri("/v1/files/{id}", id).header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
        http.get().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isOk().expectBody().json("[]");
        // A cursor that is another user's file is refused, and says nothing about it.
        http.get().uri("/v1/files?before=" + id).header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isBadRequest();

        // Untouched for its owner.
        assertThat(files.findLive(id, owner.id())).isPresent();
        assertThat(storage.list("inputs/" + id + "/")).hasSize(1);
        // An id that is not one answers the same 404, as does one nobody has.
        for (String unknown : List.of("not-a-file-id", UUID.randomUUID().toString())) {
            http.get().uri("/v1/files/" + unknown).header(HttpHeaders.AUTHORIZATION, owner.bearer()).exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
        }
    }

    @Test
    void list_givenSeveralFiles_expectsNewestFirstInPages() {
        Signed u = ready();
        UUID first = uploaded(u, "plain.png");
        jdbc.update("UPDATE input_files SET created_at = now() - interval '2 minutes' WHERE id = ?", first);
        UUID second = uploaded(u, "plain.jpg");
        jdbc.update("UPDATE input_files SET created_at = now() - interval '1 minute' WHERE id = ?", second);
        UUID third = uploaded(u, "plain.webp");

        assertThat(listIds(u, "")).containsExactly(third, second, first);
        assertThat(listIds(u, "?limit=2")).containsExactly(third, second);
        assertThat(listIds(u, "?limit=2&before=" + second)).containsExactly(first);
        for (String bad : List.of("?limit=0", "?limit=101", "?limit=many", "?before=nonsense")) {
            http.get().uri("/v1/files" + bad).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                    .expectStatus().isBadRequest();
        }
    }

    @Test
    void delete_givenTheOwner_expects204AndTheObjectAndRowGone() {
        Signed u = ready();
        UUID id = uploaded(u, "gps.webp");
        assertThat(storage.list("inputs/" + id + "/")).hasSize(1);

        http.delete().uri("/v1/files/{id}", id).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isNoContent().expectBody().isEmpty();

        assertThat(storage.list("inputs/" + id + "/")).isEmpty();
        assertThat(files.findById(id)).isEmpty();
        http.get().uri("/v1/files/{id}", id).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isNotFound();
        assertThat(listIds(u, "")).isEmpty();
        // Deleting what is gone is a 404, like any file that does not exist.
        http.delete().uri("/v1/files/{id}", id).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
        assertThat(auditRows(AuditLogService.EVT_INPUT_DELETED, u.id())).singleElement()
                .satisfies(row -> assertThat(row.getEventDetail()).containsEntry("fileId", id.toString()));
    }

    @Test
    void files_givenAnApiKey_expectsUploadReadAndDeleteToWork() {
        Signed u = ready();
        String key = apiKey(u);

        Map<?, ?> body = InputFixtures.upload(http, key, "plain.png")
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String id = (String) body.get("id");

        http.get().uri("/v1/files").header(HttpHeaders.AUTHORIZATION, key).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo(id);
        http.get().uri("/v1/files/{id}/content", id).header(HttpHeaders.AUTHORIZATION, key).exchange()
                .expectStatus().isFound();
        http.delete().uri("/v1/files/{id}", id).header(HttpHeaders.AUTHORIZATION, key).exchange()
                .expectStatus().isNoContent();
    }

    // ===== The input terms =============================================

    @Test
    void acceptTerms_givenTheCurrentVersion_expects204AndMeSaysSoWithTheTime() {
        Signed u = signedUp();
        http.get().uri("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody()
                .jsonPath("$.inputs.enabled").isEqualTo(true)
                .jsonPath("$.inputs.terms_version").isEqualTo(TERMS)
                .jsonPath("$.inputs.accepted").isEqualTo(false)
                .jsonPath("$.inputs.accepted_at").isEmpty()
                .jsonPath("$.inputs.allow_people").isEqualTo(false);

        InputFixtures.acceptTerms(http, u.bearer(), TERMS).expectStatus().isNoContent().expectBody().isEmpty();

        User saved = users.findById(u.id()).orElseThrow();
        assertThat(saved.getInputsTermsVersion()).isEqualTo(TERMS);
        assertThat(saved.getInputsTermsAcceptedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        http.get().uri("/v1/auth/me").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody()
                .jsonPath("$.inputs.accepted").isEqualTo(true)
                .jsonPath("$.inputs.accepted_at").isNotEmpty();
        assertThat(auditRows(AuditLogService.EVT_INPUTS_TERMS_ACCEPTED, u.id())).singleElement()
                .satisfies(row -> assertThat(row.getEventDetail()).containsEntry("version", TERMS));

        // Accepting again changes nothing: the time stays, and there is no second audit row.
        InputFixtures.acceptTerms(http, u.bearer(), TERMS).expectStatus().isNoContent();
        assertThat(users.findById(u.id()).orElseThrow().getInputsTermsAcceptedAt())
                .isEqualTo(saved.getInputsTermsAcceptedAt());
        assertThat(auditRows(AuditLogService.EVT_INPUTS_TERMS_ACCEPTED, u.id())).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 0})
    void acceptTerms_givenAVersionThatIsNotTheCurrentOne_expects400AndNothingRecorded(int version) {
        Signed u = signedUp();

        InputFixtures.acceptTerms(http, u.bearer(), version)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(InputRefusedException.CODE_TERMS_VERSION)
                .jsonPath("$.terms_version").isEqualTo(TERMS);

        assertThat(users.findById(u.id()).orElseThrow().getInputsTermsVersion()).isNull();
    }

    @Test
    void acceptTerms_givenNoVersionAtAll_expects400() {
        Signed u = signedUp();
        http.post().uri("/v1/inputs/terms").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_TERMS_VERSION);
    }

    @Test
    void acceptTerms_givenAnApiKey_expects403AndNothingRecorded() {
        Signed u = signedUp();

        InputFixtures.acceptTerms(http, apiKey(u), TERMS).expectStatus().isForbidden();

        assertThat(users.findById(u.id()).orElseThrow().getInputsTermsVersion()).isNull();
    }

    @Test
    void acceptTerms_givenAccountThatIsNotAnAdults_expects403NotAllowedForAccount() {
        Signed u = signedUp();
        jdbc.update("UPDATE users SET age_status = 'CONSENT_GRANTED' WHERE id = ?", u.id());

        InputFixtures.acceptTerms(http, u.bearer(), TERMS)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_NOT_ALLOWED_FOR_ACCOUNT);

        assertThat(users.findById(u.id()).orElseThrow().getInputsTermsVersion()).isNull();
    }

    // ===== Helpers =====================================================

    private record Signed(UUID id, String bearer) { }

    /** A new adult account that has not accepted the terms. */
    private Signed signedUp() {
        String email = "input-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        return new Signed(users.findByEmail(email).orElseThrow().getId(), bearer);
    }

    /** A new adult account that has accepted the current terms: one that may upload. */
    private Signed ready() {
        Signed u = signedUp();
        InputFixtures.acceptTerms(http, u.bearer(), TERMS).expectStatus().isNoContent();
        return u;
    }

    private String apiKey(Signed u) {
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "input-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return "Bearer " + key.get("plaintext");
    }

    private UUID uploaded(Signed u, String fixture) {
        Map<?, ?> body = InputFixtures.upload(http, u.bearer(), fixture)
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString((String) body.get("id"));
    }

    private List<UUID> listIds(Signed u, String query) {
        List<Map> page = http.get().uri("/v1/files" + query).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
        return page.stream().map(f -> UUID.fromString((String) f.get("id"))).toList();
    }

    /** The response is this problem, with this code. */
    private static WebTestClient.BodyContentSpec refused(WebTestClient.ResponseSpec response, int status, String code) {
        return response
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(code);
    }

    /** More bytes than the limit, of nothing in particular. */
    private static byte[] tooManyBytes() {
        return new byte[70_000];
    }

    private void assertNothingStoredFor(Signed u) {
        assertThat(files.findByUserId(u.id())).isEmpty();
        assertThat(auditRows(AuditLogService.EVT_INPUT_UPLOADED, u.id())).isEmpty();
        Set<String> owned = Set.copyOf(jdbc.queryForList("SELECT storage_key FROM input_files", String.class));
        assertThat(storage.list("inputs/")).allSatisfy(o -> assertThat(owned).contains(o.key()));
    }

    private List<String> refusalReasons(Signed u) {
        return auditRows(AuditLogService.EVT_INPUT_REFUSED, u.id()).stream()
                .peek(row -> assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE))
                .map(row -> (String) row.getEventDetail().get("reason"))
                .toList();
    }

    private List<AuditLogEntry> auditRows(String eventType, UUID userId) {
        return audit.findByEventType(eventType).stream()
                .filter(row -> userId.equals(row.getUserId()))
                .toList();
    }
}
