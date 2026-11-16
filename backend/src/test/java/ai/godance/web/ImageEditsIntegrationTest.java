package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
import org.springframework.data.domain.PageRequest;
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
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.OpenJobsGuard;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ImageDtos.JobView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/images/edits} end to end, with inputs switched on: OpenAI's
 * multipart form and the JSON form, through the job path to each provider's
 * cassette. Every refusal is checked to come before any charge, and a
 * multipart request that is refused for its model or its fields to leave no
 * file behind. How each field is read or refused, unit by unit, is in
 * {@link ImageEditRequestsTest} and {@link StrictRequestReaderTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.images.sync-poll-interval=50ms"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class ImageEditsIntegrationTest {

    /** A 1×1 transparent PNG: what the upstream returns, and what is stored as the output. */
    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAi;
    static WireMockServer ark;
    static WireMockServer bfl;

    @BeforeAll
    static void startMocks() {
        openAi = started();
        ark = started();
        bfl = started();
    }

    private static WireMockServer started() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopMocks() {
        for (WireMockServer server : new WireMockServer[] {openAi, ark, bfl}) {
            if (server != null) server.stop();
        }
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.providers.volcengine.api-base", () -> ark.baseUrl());
        r.add("godance.providers.bfl.api-base", () -> bfl.baseUrl());
        r.add("godance.providers.bfl.poll-interval", () -> "50ms");
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
        r.add("godance.inputs.enabled", () -> "true");
        r.add("godance.inputs.allow-people", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired InputFileRepository files;
    @Autowired UsageRecordRepository usage;
    @Autowired ObjectStorageClient storage;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        for (WireMockServer server : new WireMockServer[] {openAi, ark, bfl}) server.resetAll();
    }

    // ===== Multipart, OpenAI's form ===================================

    @Test
    void edit_givenMultipartWithOneImage_expectsAJobWithOneImageInput() throws Exception {
        Account me = account(100_00L);
        byte[] photo = InputFixtures.image("gps.jpg");
        stubOpenAiEdit(1);

        String body = multipart(me.bearer(), form("gpt-image-2", "Put the mug on a marble counter.")
                .image("image", photo, "mug.jpg"))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        UUID jobId = UUID.fromString(json.readTree(body).get("job_id").asString());

        JobView done = pollJob(me.bearer(), jobId);
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        // The job records the image it worked from: one input, an image, a file of the caller's.
        assertThat(done.inputs()).hasSize(1);
        assertThat(done.inputs().get(0).role()).isEqualTo("image");
        UUID fileId = done.inputs().get(0).fileId();
        assertThat(files.findById(fileId).orElseThrow().getUserId()).isEqualTo(me.id());

        // The provider got OpenAI's own form: the image itself in a part named image, with its metadata gone.
        LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
        assertThat(sent.getHeader("Content-Type")).startsWith("multipart/form-data");
        assertThat(text(sent, "model")).isEqualTo("gpt-image-2");
        assertThat(text(sent, "prompt")).isEqualTo("Put the mug on a marble counter.");
        assertThat(text(sent, "n")).isEqualTo("1");
        List<Request.Part> images = parts(sent, "image");
        assertThat(images).hasSize(1);
        byte[] stored = InputFixtures.fetch(done.inputs().get(0).url()).body();
        assertThat(images.get(0).getBody().asBytes()).isEqualTo(stored);
        assertThat(images.get(0).getHeader("Content-Type").firstValue()).isEqualTo("image/jpeg");
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("GPSCANARY");
        assertThat(new String(photo, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("GPSCANARY");
        assertThat(parts(sent, "image[]")).isEmpty();
        assertThat(parts(sent, "mask")).isEmpty();
    }

    @Test
    void edit_givenMultipartWithSeveralImages_expectsThemInOrderAsOpenAiSendsThem() throws Exception {
        Account me = account(100_00L);
        byte[] first = InputFixtures.image("plain.png");
        byte[] second = InputFixtures.image("plain.jpg");
        byte[] third = InputFixtures.image("plain.webp");

        // Repeated parts named image, and parts named image[]: both spellings OpenAI's clients use.
        for (String partName : List.of("image", "image[]")) {
            openAi.resetAll();
            stubOpenAiEdit(1);
            String body = multipart(me.bearer(), form("gpt-image-2", "Combine these.")
                    .image(partName, first, "a.png").image(partName, second, "b.jpg").image(partName, third, "c.webp")
                    .field("size", "1536x1024").field("quality", "high"))
                    .expectStatus().isAccepted()
                    .expectBody(String.class).returnResult().getResponseBody();
            JobView done = pollJob(me.bearer(), UUID.fromString(json.readTree(body).get("job_id").asString()));
            assertThat(done.status()).as(partName).isEqualTo(JobStatus.DONE);

            LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
            List<Request.Part> images = parts(sent, "image[]");
            assertThat(images).as(partName).hasSize(3);
            assertThat(images).extracting(part -> part.getHeader("Content-Type").firstValue())
                    .containsExactly("image/png", "image/jpeg", "image/webp");
            assertThat(parts(sent, "image")).isEmpty();
            assertThat(text(sent, "size")).isEqualTo("1536x1024");
            assertThat(text(sent, "quality")).isEqualTo("high");
            assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("image", "image", "image");
            for (int i = 0; i < 3; i++) {
                assertThat(images.get(i).getBody().asBytes())
                        .isEqualTo(InputFixtures.fetch(done.inputs().get(i).url()).body());
            }
        }
    }

    @Test
    void edit_givenMultipartWithAMask_expectsTheMaskSentAndRecordedAsAMask() throws Exception {
        Account me = account(100_00L);
        byte[] photo = InputFixtures.image("plain.png");
        byte[] mask = InputFixtures.image("alpha.webp");
        stubOpenAiEdit(1);

        String body = multipart(me.bearer(), form("gpt-image-1.5", "Replace the sky.")
                .image("image", photo, "photo.png").image("mask", mask, "mask.webp"))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        JobView done = pollJob(me.bearer(), UUID.fromString(json.readTree(body).get("job_id").asString()));
        assertThat(done.status()).isEqualTo(JobStatus.DONE);

        LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
        assertThat(parts(sent, "image")).hasSize(1);
        assertThat(parts(sent, "mask")).hasSize(1);
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("image", "mask");
        assertThat(parts(sent, "mask").get(0).getBody().asBytes())
                .isEqualTo(InputFixtures.fetch(done.inputs().get(1).url()).body());
    }

    // ===== JSON, pointing at stored inputs ============================

    @Test
    void edit_givenJsonWithAFileId_expectsTheStoredFileSentAndRecorded() throws Exception {
        Account me = account(100_00L);
        UUID fileId = upload(me, InputFixtures.image("plain.png"), "product.png");
        stubOpenAiEdit(1);

        String body = jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "On a marble counter.",
                "images", List.of(Map.of("file_id", fileId.toString())), "gallery_recipe", "product-on-marble"))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        UUID jobId = UUID.fromString(json.readTree(body).get("job_id").asString());
        JobView done = pollJob(me.bearer(), jobId);

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.inputs()).hasSize(1);
        assertThat(done.inputs().get(0).fileId()).isEqualTo(fileId);
        assertThat(parts(onlyPost(openAi, "/v1/images/edits"), "image").get(0).getBody().asBytes())
                .isEqualTo(InputFixtures.fetch(done.inputs().get(0).url()).body());
        // The recipe it is a run of is recorded, as on a generation.
        assertThat(jobs.findById(jobId).orElseThrow().getGalleryRecipe()).isEqualTo("product-on-marble");
        // No second file was made: the request named one that was already stored.
        assertThat(files.findByUserId(me.id())).hasSize(1);
    }

    @Test
    void edit_givenJsonWithAJobOutputAndAMask_expectsBothResolvedWithoutAnUpload() throws Exception {
        Account me = account(100_00L);
        byte[] generated = InputFixtures.image("progressive.jpg");
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("plain.png"), generated);
        UUID maskId = upload(me, InputFixtures.image("alpha.webp"), "mask.webp");
        stubOpenAiEdit(1);

        String body = jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "The same, at dusk.",
                "images", List.of(Map.of("job_output", Map.of("job_id", source.getId().toString(), "index", 1))),
                "mask", Map.of("file_id", maskId.toString())))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        JobView done = pollJob(me.bearer(), UUID.fromString(json.readTree(body).get("job_id").asString()));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
        assertThat(parts(sent, "image").get(0).getBody().asBytes()).isEqualTo(generated);
        assertThat(parts(sent, "mask")).hasSize(1);
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("image", "mask");
        assertThat(done.inputs().get(0).jobOutput().jobId()).isEqualTo(source.getId());
        assertThat(done.inputs().get(0).jobOutput().index()).isEqualTo(1);
        assertThat(done.inputs().get(1).fileId()).isEqualTo(maskId);
    }

    // ===== Each provider's form =======================================

    @Test
    void edit_givenSeedream_expectsTheImagesAsDataUrlsInTheImageField() throws Exception {
        Account me = account(100_00L);
        UUID a = upload(me, InputFixtures.image("plain.png"), "a.png");
        UUID b = upload(me, InputFixtures.image("plain.jpg"), "b.jpg");
        stubJsonImages(ark, "/api/v3/images/generations");

        // One image is a string; several are a list, in the order given.
        String one = jsonEdit(me.bearer(), Map.of("model", "seedream-5.0", "prompt", "One.",
                "images", List.of(Map.of("file_id", a.toString()))))
                .expectStatus().isAccepted().expectBody(String.class).returnResult().getResponseBody();
        assertThat(pollJob(me.bearer(), jobIdOf(one)).status()).isEqualTo(JobStatus.DONE);
        JsonNode sentOne = json.readTree(onlyPost(ark, "/api/v3/images/generations").getBodyAsString());
        assertThat(sentOne.get("image").asString()).isEqualTo(dataUrl("image/png", stored(me, a)));

        ark.resetRequests();
        String two = jsonEdit(me.bearer(), Map.of("model", "seedream-5.0", "prompt", "Two.",
                "images", List.of(Map.of("file_id", b.toString()), Map.of("file_id", a.toString()))))
                .expectStatus().isAccepted().expectBody(String.class).returnResult().getResponseBody();
        assertThat(pollJob(me.bearer(), jobIdOf(two)).status()).isEqualTo(JobStatus.DONE);
        JsonNode sentTwo = json.readTree(onlyPost(ark, "/api/v3/images/generations").getBodyAsString());
        assertThat(sentTwo.get("image")).hasSize(2);
        assertThat(sentTwo.get("image").get(0).asString()).isEqualTo(dataUrl("image/jpeg", stored(me, b)));
        assertThat(sentTwo.get("image").get(1).asString()).isEqualTo(dataUrl("image/png", stored(me, a)));
        assertThat(sentTwo.get("prompt").asString()).isEqualTo("Two.");
    }

    @Test
    void edit_givenFlux_expectsShortLivedSignedAddressesOfTheStoredInputs() throws Exception {
        Account me = account(100_00L);
        UUID a = upload(me, InputFixtures.image("plain.png"), "a.png");
        UUID b = upload(me, InputFixtures.image("plain.jpg"), "b.jpg");
        stubFluxRun();

        String body = jsonEdit(me.bearer(), Map.of("model", "flux-2-pro", "prompt", "The object from image 1 in the room from image 2.",
                "size", "1024x768", "images", List.of(Map.of("file_id", a.toString()), Map.of("file_id", b.toString()))))
                .expectStatus().isAccepted().expectBody(String.class).returnResult().getResponseBody();
        assertThat(pollJob(me.bearer(), jobIdOf(body)).status()).isEqualTo(JobStatus.DONE);

        JsonNode sent = json.readTree(onlyPost(bfl, "/v1/flux-2-pro").getBodyAsString());
        assertThat(sent.get("prompt").asString()).isEqualTo("The object from image 1 in the room from image 2.");
        assertThat(sent.get("width").asInt()).isEqualTo(1024);
        assertThat(sent.get("height").asInt()).isEqualTo(768);
        assertThat(sent.has("input_image_3")).isFalse();
        // BFL is given an address for each input: signed, expiring, and of the stored file. Never a public one.
        String first = sent.get("input_image").asString();
        String second = sent.get("input_image_2").asString();
        assertThat(first).startsWith(MINIO.getS3URL()).contains("X-Amz-Signature=").contains("X-Amz-Expires=3600");
        assertThat(InputFixtures.fetch(first).body()).isEqualTo(stored(me, a));
        assertThat(InputFixtures.fetch(second).body()).isEqualTo(stored(me, b));
        // Without its signature the address opens nothing.
        assertThat(InputFixtures.fetch(first.substring(0, first.indexOf('?'))).statusCode()).isEqualTo(403);
    }

    // ===== Refusals, each before any charge ===========================

    @Test
    void edit_givenAModelWithoutTheCapability_expectsInputNotSupportedAndNoFileStored() {
        Account me = account(100_00L);
        UUID existing = upload(me, InputFixtures.image("plain.png"), "a.png");

        for (String model : List.of("imagen-4", "flux-1.1-pro", "sdxl", "nano-banana", "qwen-image-2.0")) {
            JsonNode multipart = refused(me, () -> multipart(me.bearer(), form(model, "Edit this.")
                    .image("image", InputFixtures.image("plain.jpg"), "a.jpg")), 400, "input_not_supported");
            assertThat(multipart.get("detail").asString()).startsWith("The model " + model + " does not take input images.");
            assertThat(multipart.get("field").asString()).isEqualTo("image");
            refused(me, () -> jsonEdit(me.bearer(), Map.of("model", model, "prompt", "Edit this.",
                    "images", List.of(Map.of("file_id", existing.toString())))), 400, "input_not_supported");
        }
        // The multipart requests were refused before a part was stored: the account still has the one file.
        assertThat(files.findByUserId(me.id())).hasSize(1);
    }

    @Test
    void edit_givenAMaskOnAModelWithoutMasks_expectsInputNotSupportedAndNoFileStored() {
        Account me = account(100_00L);

        for (String model : List.of("seedream-5.0", "flux-2-pro")) {
            JsonNode problem = refused(me, () -> multipart(me.bearer(), form(model, "Only here.")
                    .image("image", InputFixtures.image("plain.png"), "a.png")
                    .image("mask", InputFixtures.image("alpha.webp"), "mask.webp")), 400, "input_not_supported");
            assertThat(problem.get("detail").asString()).isEqualTo("The model " + model + " does not take a mask.");
            assertThat(problem.get("field").asString()).isEqualTo("mask");
        }
        assertThat(files.findByUserId(me.id())).isEmpty();
    }

    @Test
    void edit_givenMoreImagesThanTheModelTakes_expectsInputNotSupportedWithTheMax() {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");
        List<Object> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(Map.of("file_id", image.toString()));

        JsonNode problem = refused(me, () -> jsonEdit(me.bearer(),
                Map.of("model", "flux-2-pro", "prompt", "Too many.", "images", nine)), 400, "input_not_supported");

        assertThat(problem.get("max").asInt()).isEqualTo(8);
        assertThat(problem.get("detail").asString()).isEqualTo("The model flux-2-pro takes at most 8 input images; the request has 9.");
    }

    @Test
    void edit_givenNoImageAtAll_expects400() {
        Account me = account(100_00L);

        refusedPlain(me, () -> multipart(me.bearer(), form("gpt-image-2", "Nothing to edit.")), 400)
                .jsonPath("$.detail").isEqualTo("an edit needs at least one image");
        refusedPlain(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "Nothing to edit.")), 400)
                .jsonPath("$.detail").isEqualTo("an edit needs at least one image");
        refusedPlain(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "Nothing.", "images", List.of())), 400);
    }

    @Test
    void edit_givenPolicyRefusalsOnMultipart_expectsBrief01sAnswersAndNothingStoredOrCharged() {
        Account me = account(100_00L);
        MultipartForm edit = form("gpt-image-2", "Edit this.").image("image", InputFixtures.image("plain.png"), "a.png");

        // Not a still image the API accepts, whatever it is called: refused as an upload is.
        refused(me, () -> multipart(me.bearer(), form("gpt-image-2", "Edit this.")
                .image("image", InputFixtures.image("picture.gif"), "photo.png")), 415, "input_type_unsupported");
        refused(me, () -> multipart(me.bearer(), form("gpt-image-2", "Edit this.")
                .image("image", InputFixtures.image("animated.webp"), "photo.webp")), 415, "input_type_unsupported");

        // The terms not accepted.
        User user = users.findById(me.id()).orElseThrow();
        user.setInputsTermsVersion(null);
        user.setInputsTermsAcceptedAt(null);
        users.save(user);
        refused(me, () -> multipart(me.bearer(), edit), 403, "inputs_terms_required");

        // Not an adult's account.
        user = users.findById(me.id()).orElseThrow();
        user.setInputsTermsVersion(1);
        user.setInputsTermsAcceptedAt(Instant.now());
        user.setAgeStatus(AgeStatus.CONSENT_PENDING);
        users.save(user);
        refused(me, () -> multipart(me.bearer(), edit), 403, "inputs_not_allowed_for_account");

        assertThat(files.findByUserId(me.id())).isEmpty();
    }

    @Test
    void edit_givenAnotherUsersFileOrJob_expects404AndNothingCharged() {
        Account me = account(100_00L);
        Account other = account(100_00L);
        UUID theirFile = upload(other, InputFixtures.image("plain.png"), "theirs.png");
        GenerationJob theirJob = doneImageJob(other.id(), InputFixtures.image("plain.png"));

        refused(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "Theirs.",
                "images", List.of(Map.of("file_id", theirFile.toString())))), 404, "file_not_found");
        refused(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "Theirs.",
                "images", List.of(Map.of("job_output", Map.of("job_id", theirJob.getId().toString(), "index", 0))))),
                404, "job_output_not_found");
        refused(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "Neither.",
                "images", List.of(Map.of()))), 400, "invalid_input_reference");
    }

    @Test
    void edit_givenRequestsThatAreWrongBeforeTheirInputs_expects400AndNoFileStored() {
        Account me = account(100_00L);
        byte[] photo = InputFixtures.image("plain.png");

        refusedPlain(me, () -> multipart(me.bearer(), form("no-such-model", "x").image("image", photo, "a.png")), 404);
        refusedPlain(me, () -> multipart(me.bearer(), form("gpt-4o", "x").image("image", photo, "a.png")), 400);
        refusedPlain(me, () -> multipart(me.bearer(), form("gpt-image-2", "x").image("image", photo, "a.png").field("n", "many")), 400);
        refusedPlain(me, () -> multipart(me.bearer(), form("gpt-image-2", "x").image("image", photo, "a.png").field("n", "0")), 400);
        refusedPlain(me, () -> multipart(me.bearer(), form(null, "x").image("image", photo, "a.png")), 400);
        assertThat(files.findByUserId(me.id())).isEmpty();
    }

    // ===== OpenAI's SDK form: recipe, and fields the route does not read ===

    @Test
    void edit_givenARecipeAsOpenAisSdkWritesIt_expectsItsPromptWithTheFormsImage() throws Exception {
        // client.images.edit(image=…, prompt="", extra_body={"recipe": {"version_id": …, "variables": {"object": "mug"}}})
        // sends the recipe as bracketed fields; json.dumps(…) in extra_body sends it as JSON text. Both are read.
        Account me = account(100_00L);
        String version = versionId(createRecipe(me, "Put the {{object}} on a marble counter.", List.of()));
        byte[] photo = InputFixtures.image("plain.png");
        Map<String, MultipartForm> forms = new LinkedHashMap<>();
        forms.put("brackets", form("gpt-image-2", "").image("image", photo, "mug.png")
                .field("recipe[version_id]", version).field("recipe[variables][object]", "mug"));
        forms.put("JSON text", form("gpt-image-2", "").image("image", photo, "mug.png")
                .field("recipe", "{\"version_id\": \"" + version + "\", \"variables\": {\"object\": \"mug\"}}"));

        for (Map.Entry<String, MultipartForm> each : forms.entrySet()) {
            openAi.resetAll();
            stubOpenAiEdit(1);
            String body = multipart(me.bearer(), each.getValue()).expectStatus().isAccepted()
                    .expectBody(String.class).returnResult().getResponseBody();
            JobView done = pollJob(me.bearer(), jobIdOf(body));

            assertThat(done.status()).as(each.getKey()).isEqualTo(JobStatus.DONE);
            assertThat(done.inputs()).as(each.getKey()).singleElement()
                    .satisfies(input -> assertThat(input.role()).isEqualTo("image"));
            // The provider got the recipe's prompt, rendered with the form's value, beside the form's image.
            LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
            assertThat(text(sent, "prompt")).as(each.getKey()).isEqualTo("Put the mug on a marble counter.");
            assertThat(parts(sent, "image")).as(each.getKey()).hasSize(1);
            assertThat(jobs.findById(done.id()).orElseThrow().getRecipeVersionId()).isEqualTo(UUID.fromString(version));
        }
    }

    @Test
    void edit_givenARecipeWithSlotsAsOpenAisSdkWritesIt_expectsTheSlotsImagesAndNoUpload() throws Exception {
        // client.images.edit(image=[], prompt="", extra_body={"recipe": {"version_id": …, "inputs": {…}}}):
        // the SDK sends no image part for an empty list, and the recipe's slots name the images.
        Account me = account(100_00L);
        UUID product = upload(me, InputFixtures.image("plain.png"), "product.png");
        GenerationJob room = doneImageJob(me.id(), InputFixtures.image("plain.jpg"));
        String version = versionId(createRecipe(me, "The product in the room.", List.of(slot("product"), slot("room"))));
        stubOpenAiEdit(1);

        String body = multipart(me.bearer(), form("gpt-image-2", "")
                .field("recipe[version_id]", version)
                .field("recipe[inputs][room][job_output][job_id]", room.getId().toString())
                .field("recipe[inputs][room][job_output][index]", "0")
                .field("recipe[inputs][product][file_id]", product.toString()))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        JobView done = pollJob(me.bearer(), jobIdOf(body));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        // The recipe's order, whatever order the form had: the product, then the room.
        assertThat(done.inputs()).hasSize(2);
        assertThat(done.inputs().get(0).fileId()).isEqualTo(product);
        assertThat(done.inputs().get(1).jobOutput().jobId()).isEqualTo(room.getId());
        LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
        assertThat(text(sent, "prompt")).isEqualTo("The product in the room.");
        assertThat(parts(sent, "image[]")).hasSize(2);
        // Nothing was uploaded with the edit: the account still has only the product photo.
        assertThat(files.findByUserId(me.id())).hasSize(1);
    }

    @Test
    void edit_givenFieldsTheRouteDoesNotRead_expectsInvalidParameterNamingEachAndNothingStoredOrCharged() {
        Account me = account(100_00L);
        UUID existing = upload(me, InputFixtures.image("plain.png"), "a.png");
        byte[] photo = InputFixtures.image("plain.png");
        String version = UUID.randomUUID().toString();

        // In the form OpenAI's SDK sends: its own fields that Godance does not act on, a generation's style,
        // a file part it does not read, a recipe's typo, stored images by id, and a field sent twice.
        Map<String, MultipartForm> forms = new LinkedHashMap<>();
        forms.put("output_format", form("gpt-image-2", "x").image("image", photo, "a.png").field("output_format", "webp"));
        forms.put("background", form("gpt-image-2", "x").image("image", photo, "a.png").field("background", "transparent"));
        forms.put("user", form("gpt-image-2", "x").image("image", photo, "a.png").field("user", "end-user-7"));
        forms.put("style", form("gpt-image-2", "x").image("image", photo, "a.png").field("style", "vivid"));
        forms.put("markup", form("gpt-image-2", "x").image("image", photo, "a.png").image("markup", photo, "m.png"));
        forms.put("recipe[variable]", form("gpt-image-2", "").image("image", photo, "a.png")
                .field("recipe[version_id]", version).field("recipe[variable][subject]", "Koi"));
        forms.put("images[][file_id]", form("gpt-image-2", "x").field("images[][file_id]", existing.toString()));
        forms.put("mask", form("gpt-image-2", "x").image("image", photo, "a.png")
                .image("mask", photo, "m1.png").image("mask", photo, "m2.png"));
        forms.put("model", form("gpt-image-2", "x").field("model", "flux-2-pro").image("image", photo, "a.png"));
        for (Map.Entry<String, MultipartForm> each : forms.entrySet()) {
            JsonNode problem = refused(me, () -> multipart(me.bearer(), each.getValue()), 400, "invalid_parameter");
            assertThat(problem.get("param").asString()).as(each.getKey()).isEqualTo(each.getKey());
        }
        JsonNode outputFormat = refused(me, () -> multipart(me.bearer(), forms.get("output_format")), 400, "invalid_parameter");
        assertThat(outputFormat.get("detail").asString()).isEqualTo("POST /v1/images/edits does not take output_format. "
                + "It takes model, prompt, image, mask, n, size, quality and response_format, and the Godance extensions "
                + "async, recipe and webhook_url.");
        assertThat(refused(me, () -> multipart(me.bearer(), forms.get("images[][file_id]")), 400, "invalid_parameter")
                .get("detail").asString()).contains("send the request as JSON");

        // The same in JSON, at any depth.
        Map<String, Object> ref = Map.of("file_id", existing.toString());
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        bodies.put("output_format", Map.of("model", "gpt-image-2", "prompt", "x", "images", List.of(ref), "output_format", "webp"));
        bodies.put("style", Map.of("model", "gpt-image-2", "prompt", "x", "images", List.of(ref), "style", "vivid"));
        bodies.put("image", Map.of("model", "gpt-image-2", "prompt", "x", "image", ref));
        bodies.put("images[1].url", Map.of("model", "gpt-image-2", "prompt", "x",
                "images", List.of(ref, Map.of("url", "https://example.test/b.png"))));
        bodies.put("mask.url", Map.of("model", "gpt-image-2", "prompt", "x", "images", List.of(ref),
                "mask", Map.of("url", "https://example.test/m.png")));
        bodies.put("recipe.variable", Map.of("model", "gpt-image-2", "images", List.of(ref),
                "recipe", Map.of("version_id", version, "variable", Map.of("subject", "Koi"))));
        for (Map.Entry<String, Map<String, Object>> each : bodies.entrySet()) {
            JsonNode problem = refused(me, () -> jsonEdit(me.bearer(), each.getValue()), 400, "invalid_parameter");
            assertThat(problem.get("param").asString()).as(each.getKey()).isEqualTo(each.getKey());
        }
        assertThat(refused(me, () -> jsonEdit(me.bearer(), bodies.get("recipe.variable")), 400, "invalid_parameter")
                .get("detail").asString())
                .isEqualTo("POST /v1/images/edits does not take recipe.variable: recipe has version_id, variables, inputs and published_id.");

        // Every refusal came before a part was stored: the account still has the one upload made above.
        assertThat(files.findByUserId(me.id())).hasSize(1);
    }

    @Test
    void edit_givenJsonWithFieldsItDoesNotReadSetToNull_expectsThemPassedOverAsTheyCarryNothing() throws Exception {
        Account me = account(100_00L);
        UUID fileId = upload(me, InputFixtures.image("plain.png"), "a.png");
        stubOpenAiEdit(1);

        String body = http.post().uri("/v1/images/edits")
                .header(HttpHeaders.AUTHORIZATION, me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\": \"gpt-image-2\", \"prompt\": \"Again.\", \"images\": [{\"file_id\": \"" + fileId
                        + "\", \"note\": null}], \"output_format\": null, \"user\": null, \"style\": null}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(pollJob(me.bearer(), jobIdOf(body)).status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void edit_givenAFormOfMorePartsThanItMayHave_expects400SayingHowToSendFewerAndNothingStored() {
        // A recipe of many variables, as the SDK writes it: one field per value, past the form's 24 parts.
        Account me = account(100_00L);
        MultipartForm edit = form("gpt-image-2", "").image("image", InputFixtures.image("plain.png"), "a.png")
                .field("recipe[version_id]", UUID.randomUUID().toString());
        for (int i = 0; i < 30; i++) edit.field("recipe[variables][v" + i + "]", "a value");

        JsonNode problem = refused(me, () -> multipart(me.bearer(), edit), 400, "invalid_parameter");

        // Spring's own words say which limit; the rest says what to send instead.
        assertThat(problem.get("detail").asString())
                .startsWith("The form could not be read (Too many parts")
                .endsWith("Send recipe as JSON text, in one field, or upload the images with POST /v1/files and send "
                        + "the request as JSON.");
        assertThat(problem.has("param")).isFalse();
        assertThat(files.findByUserId(me.id())).isEmpty();
    }

    // ===== Errors: the problem, with OpenAI's error object beside it ===

    @Test
    void errors_givenAFieldTheRouteDoesNotRead_expectOpenAisErrorObjectBesideEveryProblemMember() {
        Account me = account(100_00L);
        byte[] photo = InputFixtures.image("plain.png");

        // output_format as OpenAI's SDK sends it: a field of the form.
        JsonNode problem = refused(me, () -> multipart(me.bearer(), form("gpt-image-2", "x")
                .image("image", photo, "a.png").field("output_format", "webp")), 400, "invalid_parameter");

        // Still the problem document it was…
        assertThat(problem.get("title").asString()).isEqualTo("Bad Request");
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("instance").asString()).isEqualTo("/v1/images/edits");
        assertThat(problem.get("detail").asString()).startsWith("POST /v1/images/edits does not take output_format.");
        assertThat(problem.get("param").asString()).isEqualTo("output_format");
        // …and OpenAI's error object, which is where its SDK reads the message, the code and the field.
        JsonNode error = problem.get("error");
        assertThat(error.get("message").asString()).isEqualTo(problem.get("detail").asString());
        assertThat(error.get("type").asString()).isEqualTo("invalid_request_error");
        assertThat(error.get("param").asString()).isEqualTo("output_format");
        assertThat(error.get("code").asString()).isEqualTo("invalid_parameter");
        assertThat(error.propertyNames()).containsExactly("message", "type", "param", "code");

        // In JSON a field at any depth: param is its path, in both places.
        UUID file = upload(me, photo, "a.png");
        JsonNode deep = refused(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "x",
                "images", List.of(Map.of("file_id", file.toString()), Map.of("url", "https://example.test/b.png")))),
                400, "invalid_parameter");
        assertThat(deep.get("param").asString()).isEqualTo("images[1].url");
        assertThat(deep.get("error").get("param").asString()).isEqualTo("images[1].url");
        assertThat(deep.get("error").get("message").asString()).isEqualTo(deep.get("detail").asString());
    }

    @Test
    void errors_givenRefusalsOfOtherKinds_expectOpenAisErrorObjectWithTheirTypeAndCode() {
        Account me = account(100_00L);
        Account other = account(0L);
        UUID theirs = upload(other, InputFixtures.image("plain.png"), "theirs.png");
        byte[] photo = InputFixtures.image("plain.png");

        // 404 with a code: another account's file.
        JsonNode notFound = refused(me, () -> jsonEdit(me.bearer(), Map.of("model", "gpt-image-2", "prompt", "Theirs.",
                "images", List.of(Map.of("file_id", theirs.toString())))), 404, "file_not_found");
        assertThat(notFound.get("error").get("message").asString()).isEqualTo("file not found");
        assertThat(notFound.get("error").get("type").asString()).isEqualTo("not_found_error");
        assertThat(notFound.get("error").get("code").asString()).isEqualTo("file_not_found");
        assertThat(notFound.get("error").get("param").isNull()).isTrue();

        // 404 without a code: an unknown model. The object's code is null, as the problem has none.
        JsonNode unknownModel = json.readTree(refusedPlain(me, () -> multipart(me.bearer(),
                form("no-such-model", "x").image("image", photo, "a.png")), 404)
                .returnResult().getResponseBody());
        assertThat(unknownModel.has("code")).isFalse();
        assertThat(unknownModel.get("error").get("message").asString()).isEqualTo("unknown model: no-such-model");
        assertThat(unknownModel.get("error").get("type").asString()).isEqualTo("not_found_error");
        assertThat(unknownModel.get("error").get("code").isNull()).isTrue();

        // input_not_supported names the input in field, which is the problem's own member, not OpenAI's param.
        JsonNode mask = refused(me, () -> multipart(me.bearer(), form("seedream-5.0", "Only here.")
                .image("image", photo, "a.png").image("mask", InputFixtures.image("alpha.webp"), "mask.webp")),
                400, "input_not_supported");
        assertThat(mask.get("field").asString()).isEqualTo("mask");
        assertThat(mask.get("error").get("code").asString()).isEqualTo("input_not_supported");
        assertThat(mask.get("error").get("param").isNull()).isTrue();

        // A part refused as an upload is: 415, which OpenAI has no type of its own for.
        JsonNode notAnImage = refused(me, () -> multipart(me.bearer(), form("gpt-image-2", "Edit this.")
                .image("image", InputFixtures.image("picture.gif"), "photo.png")), 415, "input_type_unsupported");
        assertThat(notAnImage.get("error").get("type").asString()).isEqualTo("invalid_request_error");
        assertThat(notAnImage.get("error").get("code").asString()).isEqualTo("input_type_unsupported");

        // The same refusal from POST /v1/files, the route beside it, is as it was: no error object.
        JsonNode upload = json.readTree(InputFixtures.upload(http, me.bearer(), InputFixtures.image("picture.gif"),
                        "photo.png", MediaType.IMAGE_PNG)
                .expectStatus().isEqualTo(415)
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(upload.get("code").asString()).isEqualTo("input_type_unsupported");
        assertThat(upload.has("error")).as("POST /v1/files carries no OpenAI error object").isFalse();
    }

    @Test
    void errors_givenAnInvalidApiKey_expectOpenAisErrorObjectFromTheFilterThatRefusesIt() {
        String badKey = "Bearer gd_live_this_key_does_not_exist_0000000000";

        // Refused by the API key filter, before the route reads anything: on both forms.
        for (WebTestClient.ResponseSpec response : List.of(
                multipart(badKey, form("gpt-image-2", "x").image("image", InputFixtures.image("plain.png"), "a.png")),
                jsonEdit(badKey, Map.of("model", "gpt-image-2", "prompt", "x")))) {
            JsonNode problem = json.readTree(response
                    .expectStatus().isUnauthorized()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody(String.class).returnResult().getResponseBody());
            assertThat(problem.get("status").asInt()).isEqualTo(401);
            assertThat(problem.get("detail").asString()).isEqualTo("invalid API key");
            assertThat(problem.get("error").get("message").asString()).isEqualTo("invalid API key");
            assertThat(problem.get("error").get("type").asString()).isEqualTo("authentication_error");
            assertThat(problem.get("error").get("code").isNull()).isTrue();
            assertThat(problem.get("error").get("param").isNull()).isTrue();
        }
    }

    // ===== The key's answer: sync and async ===========================

    @Test
    void edit_givenASyncKey_expects200InOpenAisShape() {
        Account me = account(100_00L);
        String key = createKey(me, "sync");
        stubOpenAiEdit(2);

        multipart(key, form("gpt-image-2", "Two versions.").image("image", InputFixtures.image("plain.png"), "a.png").field("n", "2"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.created").isNumber()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].b64_json").isEqualTo(Base64.getEncoder().encodeToString(TINY_PNG))
                // Round 10's additions to OpenAI's body, as on a generation: the job, and what it cost.
                .jsonPath("$.job_id").exists()
                .jsonPath("$.cost_cents").isNumber();

        // And the JSON form answers the same way, with a link when one is asked for.
        UUID fileId = upload(me, InputFixtures.image("plain.png"), "b.png");
        openAi.resetAll();
        stubOpenAiEdit(1);
        jsonEdit(key, Map.of("model", "gpt-image-2", "prompt", "Again.", "response_format", "url",
                "images", List.of(Map.of("file_id", fileId.toString()))))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].url").value(url -> assertThat((String) url).startsWith(MINIO.getS3URL()))
                .jsonPath("$.data[0].b64_json").doesNotExist();
    }

    @Test
    void edit_givenAnAsyncKeyOrASessionOrAsyncTrue_expects202WithTheJob() throws Exception {
        Account me = account(100_00L);
        MultipartForm edit = form("gpt-image-2", "Edit this.").image("image", InputFixtures.image("plain.png"), "a.png");
        stubOpenAiEdit(1);

        // Every job started here is waited for: one still running when the test ends would call the provider during the next.
        for (String bearer : List.of(createKey(me, "async"), createKey(me, null), me.bearer())) {
            String body = multipart(bearer, edit).expectStatus().isAccepted()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(json.readTree(body).has("data")).isFalse();
            assertThat(pollJob(me.bearer(), jobIdOf(body)).status()).isEqualTo(JobStatus.DONE);
        }
        // A sync key that asks for the job gets the job.
        String sync = createKey(me, "sync");
        String asked = multipart(sync, form("gpt-image-2", "Edit this.").image("image", InputFixtures.image("plain.png"), "a.png").field("async", "true"))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(pollJob(me.bearer(), jobIdOf(asked)).status()).isEqualTo(JobStatus.DONE);
        String preferred = http.post().uri("/v1/images/edits")
                .header(HttpHeaders.AUTHORIZATION, sync)
                .header("Prefer", "respond-async")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(edit.build()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(pollJob(me.bearer(), jobIdOf(preferred)).status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void edit_givenProviderFailure_expectsFullRefundAndTheInputsStillRecorded() throws Exception {
        Account me = account(100_00L);
        openAi.stubFor(post(urlEqualTo("/v1/images/edits")).willReturn(aResponse().withStatus(500)
                .withHeader("content-type", "application/json").withBody("{\"error\":{\"message\":\"upstream is down\"}}")));

        String body = multipart(me.bearer(), form("gpt-image-2", "This one fails.").image("image", InputFixtures.image("plain.png"), "a.png"))
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        JobView failed = pollJob(me.bearer(), jobIdOf(body));

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(balance(me)).isEqualTo(100_00L);
        assertThat(usage.findAll().stream().filter(row -> me.id().equals(row.getUserId()))).isEmpty();
        assertThat(failed.inputs()).hasSize(1);

        // A sync key is told so, with the charge returned.
        String key = createKey(me, "sync");
        multipart(key, form("gpt-image-2", "This one fails.").image("image", InputFixtures.image("plain.png"), "a.png"))
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("generation_failed");
        assertThat(balance(me)).isEqualTo(100_00L);
    }

    @Test
    void generate_givenAPlainGenerationRequest_expectsItToRunAsBefore() throws Exception {
        Account me = account(100_00L);
        stubJsonImages(openAi, "/v1/images/generations");

        String body = http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "A lighthouse.", "size", "1024x1024"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        JobView done = pollJob(me.bearer(), jobIdOf(body));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.inputs()).isEmpty();
        // Byte for byte what the adapter has always sent; nothing went to the edits endpoint.
        assertThat(onlyPost(openAi, "/v1/images/generations").getBodyAsString())
                .isEqualTo("{\"model\":\"gpt-image-2\",\"prompt\":\"A lighthouse.\",\"n\":1,\"size\":\"1024x1024\"}");
        assertThat(openAi.findAll(postRequestedFor(urlEqualTo("/v1/images/edits")))).isEmpty();
    }

    // ===== Helpers ====================================================

    private record Account(UUID id, String bearer) { }

    /** An adult account that has accepted the input terms, with this balance. */
    private Account account(long balanceCents) {
        String email = "image-edits-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        User user = users.findByEmail(email).orElseThrow();
        user.setAgeStatus(AgeStatus.ADULT);
        user.setInputsTermsVersion(1);
        user.setInputsTermsAcceptedAt(Instant.now());
        users.save(user);
        if (balanceCents > 0) {
            ledger.topUp(user.getId(), balanceCents, CreditSource.PROMO, "seed-" + user.getId(), "seed-" + user.getId(), "test seed");
        }
        return new Account(user.getId(), bearer);
    }

    private long balance(Account account) {
        return users.findById(account.id()).orElseThrow().getCreditBalanceCents();
    }

    private String createKey(Account account, String imageResponse) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "edits-" + UUID.randomUUID());
        if (imageResponse != null) body.put("image_response", imageResponse);
        Map<?, ?> created = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return "Bearer " + created.get("plaintext");
    }

    /** A multipart form in the making: text fields and image parts, in the order added. */
    private static final class MultipartForm {
        private final List<Object[]> entries = new ArrayList<>();

        MultipartForm field(String name, String value) {
            entries.add(new Object[] {name, value});
            return this;
        }

        MultipartForm image(String name, byte[] bytes, String filename) {
            entries.add(new Object[] {name, bytes, filename});
            return this;
        }

        org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> build() {
            MultipartBodyBuilder form = new MultipartBodyBuilder();
            for (Object[] entry : entries) {
                if (entry.length == 2) {
                    form.part((String) entry[0], entry[1]);
                } else {
                    String filename = (String) entry[2];
                    form.part((String) entry[0], InputFixtures.named((byte[]) entry[1], filename))
                            .contentType(InputFixtures.declaredType(filename));
                }
            }
            return form.build();
        }
    }

    private static MultipartForm form(String model, String prompt) {
        MultipartForm form = new MultipartForm();
        if (model != null) form.field("model", model);
        if (prompt != null) form.field("prompt", prompt);
        return form;
    }

    private WebTestClient.ResponseSpec multipart(String bearer, MultipartForm form) {
        return http.post().uri("/v1/images/edits")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .exchange();
    }

    private WebTestClient.ResponseSpec jsonEdit(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/images/edits")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private UUID jobIdOf(String body) {
        return UUID.fromString(json.readTree(body).get("job_id").asString());
    }

    /** One of the caller's image recipes on gpt-image-2, with a variable for each {{name}} in the template. */
    private JsonNode createRecipe(Account account, String template, List<Map<String, Object>> slots) {
        List<Map<String, String>> variables = new ArrayList<>();
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile("\\{\\{([^{}]+)\\}\\}").matcher(template);
        while (marker.find()) variables.add(Map.of("name", marker.group(1), "default", "a " + marker.group(1)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Edit recipe " + UUID.randomUUID());
        body.put("modality", "IMAGE");
        body.put("template", template);
        body.put("variables", variables);
        body.put("model", "gpt-image-2");
        body.put("inputs", slots);
        String created = http.post().uri("/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return json.readTree(created);
    }

    private static String versionId(JsonNode recipe) {
        return recipe.get("latest_version").get("id").asString();
    }

    /** An input slot of an image recipe. */
    private static Map<String, Object> slot(String name) {
        return Map.of("slot", name, "role", "image", "hint", "");
    }

    private UUID upload(Account account, byte[] bytes, String filename) {
        String body = InputFixtures.upload(http, account.bearer(), bytes, filename, InputFixtures.declaredType(filename))
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    /** The file as it is stored: the upload with its metadata stripped. */
    private byte[] stored(Account account, UUID fileId) {
        return storage.download(files.findById(fileId).orElseThrow().getStorageKey());
    }

    private GenerationJob doneImageJob(UUID owner, byte[]... images) {
        GenerationJob job = new GenerationJob();
        job.setUserId(owner);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "a lighthouse at dusk", "n", images.length));
        job.setStatus(JobStatus.DONE);
        job.setEstimatedCostCents(6L);
        job.setActualCostCents(6L);
        job.setRequestId("img_" + UUID.randomUUID());
        job = jobs.save(job);
        for (int i = 0; i < images.length; i++) {
            ImageMetadata.Format format = ImageMetadata.sniff(images[i]).orElseThrow();
            String key = "images/" + job.getId() + "/" + i + "." + format.extension();
            storage.upload(key, images[i], format.contentType());
            outputRows.save(new GenerationJobOutput(job.getId(), i, key, format.contentType(), images[i].length, null));
        }
        return job;
    }

    private JobView pollJob(String bearer, UUID jobId) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) return view;
            Thread.sleep(100);
        }
        return view;
    }

    /**
     * Makes the request and expects it refused with this status and code,
     * before anything happened: no provider was called, no job was written,
     * and the balance and the ledger are as they were.
     */
    private JsonNode refused(Account account, java.util.function.Supplier<WebTestClient.ResponseSpec> request, int status, String code) {
        long balanceBefore = balance(account);
        long ledgerBefore = ledgerRows.findByUserIdOrderByOccurredAtDesc(account.id(), PageRequest.of(0, 1)).getTotalElements();
        int jobsBefore = jobs.findByUserIdOrderByCreatedAtDesc(account.id()).size();
        List<String> calls = providerCalls();

        String response = request.get()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode problem = json.readTree(response);

        if (code != null) assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(balance(account)).as("balance").isEqualTo(balanceBefore);
        assertThat(ledgerRows.findByUserIdOrderByOccurredAtDesc(account.id(), PageRequest.of(0, 1)).getTotalElements())
                .as("ledger rows").isEqualTo(ledgerBefore);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(account.id())).as("jobs").hasSize(jobsBefore);
        assertThat(providerCalls()).as("provider calls").isEqualTo(calls);
        return problem;
    }

    /** {@link #refused} for a refusal that carries no {@code code}; returns the body to assert on. */
    private WebTestClient.BodyContentSpec refusedPlain(Account account, java.util.function.Supplier<WebTestClient.ResponseSpec> request, int status) {
        long balanceBefore = balance(account);
        int jobsBefore = jobs.findByUserIdOrderByCreatedAtDesc(account.id()).size();
        List<String> calls = providerCalls();
        WebTestClient.BodyContentSpec body = request.get().expectStatus().isEqualTo(status).expectBody();
        assertThat(balance(account)).as("balance").isEqualTo(balanceBefore);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(account.id())).as("jobs").hasSize(jobsBefore);
        assertThat(providerCalls()).as("provider calls").isEqualTo(calls);
        return body;
    }

    private static List<String> providerCalls() {
        List<String> calls = new ArrayList<>();
        for (WireMockServer server : new WireMockServer[] {openAi, ark, bfl}) {
            for (LoggedRequest request : server.findAll(anyRequestedFor(anyUrl()))) calls.add(request.getMethod() + " " + request.getUrl());
        }
        return calls;
    }

    private static LoggedRequest onlyPost(WireMockServer server, String path) {
        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static List<Request.Part> parts(LoggedRequest request, String name) {
        List<Request.Part> found = new ArrayList<>();
        for (Request.Part part : request.getParts()) {
            if (name.equals(part.getName())) found.add(part);
        }
        return found;
    }

    private static String text(LoggedRequest request, String name) {
        List<Request.Part> found = parts(request, name);
        assertThat(found).as(name).hasSize(1);
        return found.get(0).getBody().asString();
    }

    private static String dataUrl(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** OpenAI's edits endpoint answering with {@code n} images. */
    private static void stubOpenAiEdit(int n) {
        String one = "{\"b64_json\":\"" + Base64.getEncoder().encodeToString(TINY_PNG) + "\"}";
        openAi.stubFor(post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[" + String.join(",", java.util.Collections.nCopies(n, one)) + "]}")));
    }

    /** An OpenAI-shaped JSON image endpoint answering with one image. */
    private static void stubJsonImages(WireMockServer server, String path) {
        server.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\""
                                + Base64.getEncoder().encodeToString(TINY_PNG) + "\"}]}")));
    }

    /** BFL: the job is accepted, ready at its first poll, and its sample can be downloaded. */
    private static void stubFluxRun() {
        bfl.stubFor(post(urlEqualTo("/v1/flux-2-pro"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"flux_job\",\"polling_url\":\"" + bfl.baseUrl() + "/v1/get_result?id=flux_job\"}")));
        bfl.stubFor(get(urlPathEqualTo("/v1/get_result"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"flux_job\",\"status\":\"Ready\",\"result\":{\"sample\":\"" + bfl.baseUrl() + "/_test/sample.jpg\"}}")));
        bfl.stubFor(get(urlEqualTo("/_test/sample.jpg"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "image/jpeg")
                        .withBody(InputFixtures.image("plain.jpg"))));
    }
}
