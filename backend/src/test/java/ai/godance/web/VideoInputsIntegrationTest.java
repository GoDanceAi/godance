package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.service.GenerationQuotes;
import ai.godance.service.InputService;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Video inputs end to end, with inputs switched on: a first frame, a last
 * frame and reference images, each an upload or an output of one of the
 * caller's image jobs, through {@code POST /v1/videos/generations} to each
 * provider's cassette. Every refusal is checked to come before any charge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class VideoInputsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer ark;
    static WireMockServer kling;
    static WireMockServer minimax;
    static WireMockServer openAi;

    @BeforeAll
    static void startMocks() {
        ark = started();
        kling = started();
        minimax = started();
        openAi = started();
    }

    private static WireMockServer started() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopMocks() {
        for (WireMockServer server : new WireMockServer[] {ark, kling, minimax, openAi}) {
            if (server != null) server.stop();
        }
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.volcengine.api-base", () -> ark.baseUrl());
        r.add("godance.providers.volcengine.poll-interval", () -> "50ms");
        r.add("godance.providers.kling.api-base", () -> kling.baseUrl());
        r.add("godance.providers.kling.poll-interval", () -> "50ms");
        r.add("godance.providers.minimax.api-base", () -> minimax.baseUrl());
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
        r.add("godance.inputs.enabled", () -> "true");
        r.add("godance.inputs.allow-people", () -> "true");
    }

    static final byte[] CLIP = "FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8);

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired UsageRecordRepository usage;
    @Autowired ObjectStorageClient storage;
    @Autowired InputService inputs;
    @Autowired GenerationQuotes quotes;
    @Autowired DataRetentionService retention;
    @Autowired ModelCatalog catalog;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        for (WireMockServer server : new WireMockServer[] {ark, kling, minimax, openAi}) server.resetAll();
    }

    // ===== Seedance ===================================================

    @Test
    void generate_givenFirstFrameAsJobOutputOnSeedance_expectsFrameInProviderRequest() throws Exception {
        Account me = account(100_00L);
        byte[] still = InputFixtures.image("plain.png");
        GenerationJob source = doneImageJob(me.id(), still);
        stubArkRun("task_first");

        AsyncJobAcknowledged ack = submit(me, body("seedance-2.0", "The boat drifts away from the bank.",
                "first_frame", jobOutput(source.getId(), 0)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        JobView done = pollJob(me, ack.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);

        // The provider got the prompt, then the frame itself, inline, in the role Ark names.
        JsonNode sent = onlyPost(ark, "/api/v3/contents/generations/tasks");
        assertThat(sent.get("model").asString()).isEqualTo("seedance-2.0");
        assertThat(sent.get("content")).hasSize(2);
        assertThat(sent.get("content").get(0)).isEqualTo(json.readTree(
                "{\"type\":\"text\",\"text\":\"The boat drifts away from the bank.\"}"));
        assertThat(sent.get("content").get(1)).isEqualTo(json.readTree("{\"type\":\"image_url\",\"image_url\":{\"url\":\""
                + dataUrl("image/png", still) + "\"},\"role\":\"first_frame\"}"));

        // The job records what it started from, and shows it to its owner.
        assertThat(done.inputs()).hasSize(1);
        assertThat(done.inputs().get(0).role()).isEqualTo("first_frame");
        assertThat(done.inputs().get(0).jobOutput().jobId()).isEqualTo(source.getId());
        assertThat(done.inputs().get(0).jobOutput().index()).isZero();
        assertThat(InputFixtures.fetch(done.inputs().get(0).url()).body()).isEqualTo(still);
    }

    @Test
    void generate_givenFirstAndLastFrameAsUploadsOnSeedance_expectsBothFramesInOrderAndRecorded() throws Exception {
        Account me = account(100_00L);
        byte[] first = InputFixtures.image("plain.jpg");
        byte[] last = InputFixtures.image("plain.webp");
        UUID firstId = upload(me, first, "first.jpg");
        UUID lastId = upload(me, last, "last.webp");
        stubArkRun("task_both");

        AsyncJobAcknowledged ack = submit(me, body("seedance-2.0", "The tide goes out.",
                "first_frame", file(firstId), "last_frame", file(lastId)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        JobView done = pollJob(me, ack.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);

        JsonNode content = onlyPost(ark, "/api/v3/contents/generations/tasks").get("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(1).get("role").asString()).isEqualTo("first_frame");
        assertThat(content.get(1).get("image_url").get("url").asString()).isEqualTo(dataUrl("image/jpeg", stored(me, firstId)));
        assertThat(content.get(2).get("role").asString()).isEqualTo("last_frame");
        assertThat(content.get(2).get("image_url").get("url").asString()).isEqualTo(dataUrl("image/webp", stored(me, lastId)));

        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("first_frame", "last_frame");
        assertThat(done.inputs()).extracting(input -> input.fileId()).containsExactly(firstId, lastId);
    }

    @Test
    void generate_givenReferenceImagesOnSeedance_expectsThemInTheOrderGiven() throws Exception {
        Account me = account(100_00L);
        byte[] generated = InputFixtures.image("progressive.jpg");
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("plain.png"), generated);
        UUID uploadA = upload(me, InputFixtures.image("plain.png"), "a.png");
        UUID uploadB = upload(me, InputFixtures.image("plain.webp"), "b.webp");
        stubArkRun("task_refs");

        AsyncJobAcknowledged ack = submit(me, body("seedance-2.0", "Image 1 walks through the place in Image 2, in the style of Image 3.",
                "reference_images", List.of(file(uploadB), jobOutput(source.getId(), 1), file(uploadA))))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        JobView done = pollJob(me, ack.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);

        // "Image n" in the prompt is the nth image item: the request's order is the order sent. The prompt is not rewritten.
        JsonNode content = onlyPost(ark, "/api/v3/contents/generations/tasks").get("content");
        assertThat(content).hasSize(4);
        assertThat(content.get(0).get("text").asString())
                .isEqualTo("Image 1 walks through the place in Image 2, in the style of Image 3.");
        List<String> urls = new ArrayList<>();
        for (int i = 1; i < content.size(); i++) {
            assertThat(content.get(i).get("type").asString()).isEqualTo("image_url");
            assertThat(content.get(i).get("role").asString()).isEqualTo("reference_image");
            urls.add(content.get(i).get("image_url").get("url").asString());
        }
        assertThat(urls).containsExactly(dataUrl("image/webp", stored(me, uploadB)), dataUrl("image/jpeg", generated),
                dataUrl("image/png", stored(me, uploadA)));

        // Recorded in the same order, each as a reference image.
        assertThat(done.inputs()).extracting(input -> input.role())
                .containsExactly("reference_image", "reference_image", "reference_image");
        assertThat(done.inputs().get(0).fileId()).isEqualTo(uploadB);
        assertThat(done.inputs().get(1).jobOutput().jobId()).isEqualTo(source.getId());
        assertThat(done.inputs().get(1).jobOutput().index()).isEqualTo(1);
        assertThat(done.inputs().get(2).fileId()).isEqualTo(uploadA);
    }

    @Test
    void generate_givenTenReferenceImagesOnSeedance_expectsInputNotSupportedBeforeCharge() throws Exception {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");
        List<Object> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(file(image));

        JsonNode problem = refused(me, body("seedance-2.0", "Too many.", "reference_images", ten), 400, "input_not_supported");

        assertThat(problem.get("max").asInt()).isEqualTo(9);
        assertThat(problem.get("field").asString()).isEqualTo("reference_images");
        assertThat(problem.get("detail").asString()).contains("at most 9 reference images").contains("the request has 10");
        // Nine is the most it takes, and nine go through.
        stubArkRun("task_nine");
        assertThat(runToTheEnd(me, body("seedance-2.0", "Nine.", "reference_images", ten.subList(0, 9))).status())
                .isEqualTo(JobStatus.DONE);
    }

    @Test
    void generate_givenFramesTogetherWithReferenceImagesOnSeedance_expectsInputNotSupportedBeforeCharge() {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        JsonNode problem = refused(me, body("seedance-2.0", "Both.",
                "first_frame", file(image), "reference_images", List.of(file(image))), 400, "input_not_supported");

        assertThat(problem.get("detail").asString()).contains("but not both in one request");
    }

    @Test
    void generate_givenLastFrameWithoutFirstFrame_expectsInputNotSupportedBeforeCharge() {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        for (String model : List.of("seedance-2.0", "kling-3.0")) {
            JsonNode problem = refused(me, body(model, "Ends here.", "last_frame", file(image)), 400, "input_not_supported");
            assertThat(problem.get("detail").asString()).isEqualTo("The model " + model + " takes last_frame only together with first_frame.");
            assertThat(problem.get("field").asString()).isEqualTo("last_frame");
        }
    }

    @Test
    void generate_givenFrameOutsideTheProvidersAspectRange_expectsInputAspectMismatchNamingBothBeforeCharge() throws Exception {
        Account me = account(100_00L);
        UUID wide = upload(me, InputFixtures.png(300, 100), "wide.png");       // 3:1, wider than 2.5
        UUID tall = upload(me, InputFixtures.png(100, 300), "tall.png");       // 1:3, narrower than 0.4
        GenerationJob source = doneImageJob(me.id(), InputFixtures.png(260, 100));   // 2.6

        JsonNode problem = refused(me, body("seedance-2.0", "Wide.", "first_frame", file(wide)), 400, "input_aspect_mismatch");
        assertThat(problem.get("detail").asString()).isEqualTo("first_frame is 300×100, a width-to-height ratio of 3.00. "
                + "The model seedance-2.0 takes images whose ratio is between 0.40 and 2.50. "
                + "Crop the image and send it again; it is not cropped for you.");
        assertThat(problem.get("field").asString()).isEqualTo("first_frame");
        assertThat(problem.get("width").asInt()).isEqualTo(300);
        assertThat(problem.get("height").asInt()).isEqualTo(100);
        assertThat(problem.get("min_aspect").asDouble()).isEqualTo(0.4);
        assertThat(problem.get("max_aspect").asDouble()).isEqualTo(2.5);

        // The same rule for every input and on every provider that takes one, whether an upload or a job's output.
        assertThat(refused(me, body("seedance-2.0", "Tall.", "reference_images", List.of(file(tall))), 400, "input_aspect_mismatch")
                .get("field").asString()).isEqualTo("reference_images[0]");
        refused(me, body("kling-3.0", "Wide.", "first_frame", jobOutput(source.getId(), 0)), 400, "input_aspect_mismatch");
        refused(me, body("minimax-hailuo-2.3", "Tall.", "first_frame", file(tall)), 400, "input_aspect_mismatch");

        // A shape at the edge of the range is taken: 250×100 is exactly 2.5.
        stubArkRun("task_edge");
        assertThat(runToTheEnd(me, body("seedance-2.0", "Edge.", "first_frame",
                file(upload(me, InputFixtures.png(250, 100), "edge.png")))).status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void generate_givenJobOutputOfAnotherUsersJob_expects404AndNothingCharged() {
        Account me = account(100_00L);
        Account other = account(100_00L);
        GenerationJob theirs = doneImageJob(other.id(), InputFixtures.image("plain.png"));

        JsonNode problem = refused(me, body("seedance-2.0", "Theirs.", "first_frame", jobOutput(theirs.getId(), 0)), 404, "job_output_not_found");

        // The same answer as for a job that does not exist: nothing says the job is someone's.
        JsonNode missing = refused(me, body("seedance-2.0", "None.", "first_frame", jobOutput(UUID.randomUUID(), 0)), 404, "job_output_not_found");
        assertThat(problem.get("detail")).isEqualTo(missing.get("detail"));
        assertThat(balance(other)).isEqualTo(100_00L);
    }

    @Test
    void generate_givenOutputOfAPurgedJob_expects404AndNothingCharged() {
        Account me = account(100_00L);
        GenerationJob purged = doneImageJob(me.id(), InputFixtures.image("plain.png"));
        // Purged as the retention job purges it: its files deleted, the row kept.
        assertThat(retention.purgeJob(purged.getId(), Instant.now())).isTrue();

        refused(me, body("seedance-2.0", "Gone.", "first_frame", jobOutput(purged.getId(), 0)), 404, "job_output_not_found");
        refused(me, body("seedance-2.0", "Gone.", "reference_images", List.of(jobOutput(purged.getId(), 0))), 404, "job_output_not_found");
    }

    @Test
    void generate_givenAnotherUsersFile_expects404AndNothingCharged() {
        Account me = account(100_00L);
        Account other = account(100_00L);
        UUID theirs = upload(other, InputFixtures.image("plain.png"), "theirs.png");

        refused(me, body("seedance-2.0", "Theirs.", "first_frame", file(theirs)), 404, "file_not_found");
        refused(me, body("seedance-2.0", "Theirs.", "reference_images", List.of(file(theirs))), 404, "file_not_found");
    }

    @Test
    void generate_givenAnInputThatNamesBothOrNeitherForm_expectsInvalidInputReference() {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("plain.png"));
        Map<String, Object> both = new LinkedHashMap<>(file(image));
        both.putAll(jobOutput(source.getId(), 0));

        refused(me, body("seedance-2.0", "Both.", "first_frame", both), 400, "invalid_input_reference");
        refused(me, body("seedance-2.0", "Neither.", "first_frame", Map.of()), 400, "invalid_input_reference");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        refused(me, body("seedance-2.0", "Nothing.", "reference_images", withNull), 400, "invalid_input_reference");
    }

    @Test
    void generate_givenAccountThatMayNotUseInputs_expectsBrief01sRefusalBeforeCharge() {
        Account me = account(100_00L);
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("plain.png"));
        Map<String, Object> request = body("seedance-2.0", "Mine.", "first_frame", jobOutput(source.getId(), 0));

        // The terms not accepted: even an output of the caller's own job is an input.
        User user = users.findById(me.id()).orElseThrow();
        user.setInputsTermsVersion(null);
        user.setInputsTermsAcceptedAt(null);
        users.save(user);
        assertThat(refused(me, request, 403, "inputs_terms_required").get("terms_version").asInt()).isEqualTo(1);

        // Not an adult's account.
        user = users.findById(me.id()).orElseThrow();
        user.setInputsTermsVersion(1);
        user.setInputsTermsAcceptedAt(Instant.now());
        user.setAgeStatus(AgeStatus.CONSENT_PENDING);
        users.save(user);
        refused(me, request, 403, "inputs_not_allowed_for_account");
    }

    @Test
    void generate_givenAnEmptyListOfReferenceImages_expectsAPlainRequest() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_plain");

        AsyncJobAcknowledged ack = submit(me, body("seedance-2.0", "From text alone.", "reference_images", List.of()))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        JobView done = pollJob(me, ack.jobId());

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.inputs()).isEmpty();
        assertThat(onlyPost(ark, "/api/v3/contents/generations/tasks").get("content")).hasSize(1);
    }

    @Test
    void generate_givenInputs_expectsThePreDebitToEqualTheEstimateAndThePriceWithoutInputs() throws Exception {
        Account me = account(100_00L);
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("plain.png"));
        stubArkRun("task_price");

        JsonNode estimate = json.readTree(http.post().uri("/v1/estimates")
                .header("Authorization", me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "seedance-2.0", "seconds", 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        long estimated = estimate.get("estimated_cost_cents").asLong();
        assertThat(estimated).isEqualTo(quotes.video("seedance-2.0", null, 5).costCents()).isPositive();

        // The hold is taken with the request, before the worker runs: read it from the job row.
        AsyncJobAcknowledged withFrame = submit(me, body("seedance-2.0", "With a frame.", "first_frame", jobOutput(source.getId(), 0)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        assertThat(jobs.findById(withFrame.jobId()).orElseThrow().getEstimatedCostCents()).isEqualTo(estimated);
        JobView done = pollJob(me, withFrame.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.actualCostCents()).isEqualTo(estimated);
        assertThat(balance(me)).isEqualTo(100_00L - estimated);

        // A request from text alone holds and costs the same: an input image is not charged for.
        AsyncJobAcknowledged plain = submit(me, body("seedance-2.0", "From text alone."))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        assertThat(jobs.findById(plain.jobId()).orElseThrow().getEstimatedCostCents()).isEqualTo(estimated);
        assertThat(pollJob(me, plain.jobId()).actualCostCents()).isEqualTo(estimated);
        assertThat(balance(me)).isEqualTo(100_00L - 2 * estimated);
    }

    @Test
    void generate_givenProviderFailureOnARequestWithInputs_expectsFullRefundAndTheInputsStillRecorded() throws Exception {
        Account me = account(100_00L);
        UUID frame = upload(me, InputFixtures.image("plain.png"), "frame.png");
        ark.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks")).willReturn(okJson("{\"id\":\"task_bad\"}")));
        ark.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_bad"))
                .willReturn(okJson("{\"id\":\"task_bad\",\"status\":\"failed\"}")));

        AsyncJobAcknowledged ack = submit(me, body("seedance-2.0", "This one fails.", "first_frame", file(frame)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        JobView failed = pollJob(me, ack.jobId());

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(balance(me)).isEqualTo(100_00L);
        assertThat(usage.findAll().stream().filter(row -> me.id().equals(row.getUserId()))).isEmpty();
        // The job still shows what it was asked to start from.
        assertThat(failed.inputs()).hasSize(1);
        assertThat(failed.inputs().get(0).fileId()).isEqualTo(frame);
    }

    // ===== Kling ======================================================

    @Test
    void generate_givenFirstFrameOnKling_expectsImageToVideoWithTheFrameAsPlainBase64() throws Exception {
        Account me = account(100_00L);
        byte[] still = InputFixtures.image("plain.png");
        GenerationJob source = doneImageJob(me.id(), still);
        stubKlingImageRun("k_first");

        AsyncJobAcknowledged ack = submit(me, body("kling-3.0", "The kettle starts to steam.", "first_frame", jobOutput(source.getId(), 0)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        assertThat(pollJob(me, ack.jobId()).status()).isEqualTo(JobStatus.DONE);

        // Image to video is its own endpoint, with its own task list; nothing went to text to video.
        JsonNode sent = onlyPost(kling, "/v1/videos/image2video");
        assertThat(sent).isEqualTo(json.readTree("{\"model_name\":\"kling-3.0\",\"prompt\":\"The kettle starts to steam.\","
                + "\"duration\":5,\"image\":\"" + Base64.getEncoder().encodeToString(still) + "\"}"));
        assertThat(everyRequestTo(kling)).noneMatch(request -> request.contains("text2video"));
        assertThat(everyRequestTo(kling)).contains("GET /v1/videos/image2video/k_first");
    }

    @Test
    void generate_givenFirstAndLastFrameOnKling_expectsImageTail() throws Exception {
        Account me = account(100_00L);
        UUID first = upload(me, InputFixtures.image("plain.jpg"), "first.jpg");
        UUID last = upload(me, InputFixtures.image("plain.png"), "last.png");
        stubKlingImageRun("k_both");

        AsyncJobAcknowledged ack = submit(me, body("kling-3.0", "Day turns to night.", "first_frame", file(first), "last_frame", file(last)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        JobView done = pollJob(me, ack.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);

        JsonNode sent = onlyPost(kling, "/v1/videos/image2video");
        assertThat(sent.get("image").asString()).isEqualTo(Base64.getEncoder().encodeToString(stored(me, first)));
        assertThat(sent.get("image_tail").asString()).isEqualTo(Base64.getEncoder().encodeToString(stored(me, last)));
        assertThat(sent.has("aspect_ratio")).isFalse();
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("first_frame", "last_frame");
    }

    @Test
    void generate_givenReferenceImagesOnKling_expectsInputNotSupportedBeforeCharge() {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        JsonNode problem = refused(me, body("kling-3.0", "Follow this.", "reference_images", List.of(file(image))), 400, "input_not_supported");

        assertThat(problem.get("field").asString()).isEqualTo("reference_images");
        assertThat(problem.get("detail").asString()).startsWith("The model kling-3.0 does not take reference_images.");
    }

    @Test
    void generate_givenAWebpFrameOnKling_expectsInputTypeUnsupportedBeforeCharge() {
        Account me = account(100_00L);
        UUID webp = upload(me, InputFixtures.image("plain.webp"), "frame.webp");

        JsonNode problem = refused(me, body("kling-3.0", "From a WebP.", "first_frame", file(webp)), 415, "input_type_unsupported");

        assertThat(problem.get("detail").asString()).isEqualTo("first_frame is a WebP. The model kling-3.0 takes JPEG or PNG images.");
    }

    // ===== MiniMax ====================================================

    @Test
    void generate_givenFirstFrameOnHailuo_expectsFirstFrameImageAsADataUrl() throws Exception {
        Account me = account(100_00L);
        UUID frame = upload(me, InputFixtures.image("plain.webp"), "frame.webp");
        stubMiniMaxRun("m_first");

        AsyncJobAcknowledged ack = submit(me, body("minimax-hailuo-2.3", "The curtain lifts.", "first_frame", file(frame)))
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        JobView done = pollJob(me, ack.jobId());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);

        JsonNode sent = onlyPost(minimax, "/v1/video_generation");
        assertThat(sent).isEqualTo(json.readTree("{\"model\":\"minimax-hailuo-2.3\",\"prompt\":\"The curtain lifts.\",\"duration\":5,"
                + "\"first_frame_image\":\"" + dataUrl("image/webp", stored(me, frame)) + "\"}"));
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("first_frame");
    }

    @Test
    void generate_givenLastFrameOrReferenceImagesOnHailuo_expectsInputNotSupportedBeforeCharge() {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        assertThat(refused(me, body("minimax-hailuo-2.3", "Ends here.", "first_frame", file(image), "last_frame", file(image)),
                400, "input_not_supported").get("field").asString()).isEqualTo("last_frame");
        assertThat(refused(me, body("minimax-hailuo-2.3", "Follow this.", "reference_images", List.of(file(image))),
                400, "input_not_supported").get("field").asString()).isEqualTo("reference_images");
    }

    // ===== Models that take no input ==================================

    @Test
    void generate_givenAnyInputOnAModelWithoutTheCapability_expectsInputNotSupportedBeforeCharge() {
        Account me = account(1000_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        for (String model : List.of("sora-2", "veo-3.1", "runway-gen4")) {
            assertThat(catalog.metadata(model).capabilities().inputs().isEmpty()).as(model).isTrue();
            assertThat(refused(me, body(model, "From a frame.", "first_frame", file(image)), 400, "input_not_supported")
                    .get("detail").asString()).startsWith("The model " + model + " does not take first_frame.");
            assertThat(refused(me, body(model, "To a frame.", "last_frame", file(image)), 400, "input_not_supported")
                    .get("field").asString()).isEqualTo("last_frame");
            assertThat(refused(me, body(model, "Follow this.", "reference_images", List.of(file(image))), 400, "input_not_supported")
                    .get("field").asString()).isEqualTo("reference_images");
        }
        assertThat(everyRequestTo(openAi)).isEmpty();
    }

    @Test
    void models_givenTheSeededVideoModels_expectTheirInputsInTheContractsShape() throws Exception {
        JsonNode models = json.readTree(http.get().uri("/v1/models").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody()).get("data");
        Map<String, JsonNode> inputsOf = new LinkedHashMap<>();
        for (JsonNode model : models) {
            JsonNode capabilities = model.get("godance").get("capabilities");
            inputsOf.put(model.get("id").asString(), capabilities == null || capabilities.isNull() ? null : capabilities.get("inputs"));
        }

        assertThat(inputsOf.get("seedance-2.0")).isEqualTo(json.readTree(
                "{\"first_frame\":true,\"last_frame\":true,\"reference_images\":{\"max\":9}}"));
        assertThat(inputsOf.get("kling-3.0")).isEqualTo(json.readTree("{\"first_frame\":true,\"last_frame\":true}"));
        assertThat(inputsOf.get("minimax-hailuo-2.3")).isEqualTo(json.readTree("{\"first_frame\":true,\"last_frame\":false}"));
        for (String model : List.of("sora-2", "sora-2-pro", "sora-2-pro-hd", "veo-3.1", "veo-3.1-fast", "runway-gen4")) {
            assertThat(inputsOf.get(model)).as(model).isNull();
        }
    }

    // ===== Helpers ====================================================

    /** An adult account that has accepted the input terms, with this balance. */
    private record Account(UUID id, String bearer) { }

    private Account account(long balanceCents) {
        String email = "video-inputs-" + UUID.randomUUID() + "@godance.test";
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

    /** A finished image job with these stored outputs, as a worker leaves it. */
    private GenerationJob doneImageJob(UUID owner, byte[]... images) {
        GenerationJob job = new GenerationJob();
        job.setUserId(owner);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "a paper boat on a pond", "n", images.length));
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

    private static Map<String, Object> jobOutput(UUID jobId, int index) {
        return Map.of("job_output", Map.of("job_id", jobId.toString(), "index", index));
    }

    private static Map<String, Object> file(UUID fileId) {
        return Map.of("file_id", fileId.toString());
    }

    /** A request body: the model, the prompt, five seconds, and then any further fields in pairs. */
    private static Map<String, Object> body(String model, String prompt, Object... more) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("seconds", 5);
        for (int i = 0; i < more.length; i += 2) body.put((String) more[i], more[i + 1]);
        return body;
    }

    private WebTestClient.ResponseSpec submit(Account account, Map<String, Object> body) {
        return http.post().uri("/v1/videos/generations")
                .header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /**
     * Submits a request that is expected to be accepted, and waits for its
     * job to end. Every test that starts a job waits for it: a job still
     * running when its test ends would call the provider during the next.
     */
    private JobView runToTheEnd(Account account, Map<String, Object> body) throws InterruptedException {
        AsyncJobAcknowledged ack = submit(account, body)
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
        return pollJob(account, ack.jobId());
    }

    private JobView pollJob(Account account, UUID jobId) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header("Authorization", account.bearer())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) return view;
            Thread.sleep(100);
        }
        return view;
    }

    private static String dataUrl(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** The body of the one POST this server received at this path. */
    private JsonNode onlyPost(WireMockServer server, String path) {
        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).hasSize(1);
        return json.readTree(requests.get(0).getBodyAsString());
    }

    private static List<String> everyRequestTo(WireMockServer server) {
        List<String> seen = new ArrayList<>();
        for (LoggedRequest request : server.findAll(anyRequestedFor(anyUrl()))) seen.add(request.getMethod() + " " + request.getUrl());
        return seen;
    }

    /** Ark: the task is accepted, succeeds at its first poll, and its clip can be downloaded. */
    private static void stubArkRun(String taskId) {
        ark.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks"))
                .willReturn(okJson("{\"id\":\"" + taskId + "\"}")));
        ark.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/" + taskId))
                .willReturn(okJson("{\"id\":\"" + taskId + "\",\"status\":\"succeeded\","
                        + "\"content\":{\"video_url\":\"" + ark.baseUrl() + "/_test/out.mp4\"}}")));
        ark.stubFor(get(urlEqualTo("/_test/out.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4").withBody(CLIP)));
    }

    /** Uploads an image as the account and returns its file id. */
    private UUID upload(Account account, byte[] bytes, String filename) {
        String body = InputFixtures.upload(http, account.bearer(), bytes, filename, InputFixtures.declaredType(filename))
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    /** The file as it is stored, which is what a provider is sent: the upload with its metadata stripped. */
    private byte[] stored(Account account, UUID fileId) {
        return inputs.content(account.id(), fileId).bytes();
    }

    /**
     * Sends the request and expects it refused with this status and code,
     * before anything happened: no provider was called, no job row was
     * written, and the balance and the ledger are as they were.
     */
    private JsonNode refused(Account account, Map<String, Object> body, int status, String code) {
        long balanceBefore = balance(account);
        long ledgerBefore = ledgerRows.findByUserIdOrderByOccurredAtDesc(account.id(), PageRequest.of(0, 1)).getTotalElements();
        int videoJobsBefore = videoJobs(account);
        List<String> calls = providerCalls();

        String response = submit(account, body)
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode problem = json.readTree(response);

        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(balance(account)).as("balance").isEqualTo(balanceBefore);
        assertThat(ledgerRows.findByUserIdOrderByOccurredAtDesc(account.id(), PageRequest.of(0, 1)).getTotalElements())
                .as("ledger rows").isEqualTo(ledgerBefore);
        assertThat(videoJobs(account)).as("video jobs").isEqualTo(videoJobsBefore);
        assertThat(providerCalls()).as("provider calls").isEqualTo(calls);
        return problem;
    }

    private int videoJobs(Account account) {
        return (int) jobs.findByUserIdOrderByCreatedAtDesc(account.id()).stream()
                .filter(job -> job.getModality() == Modality.VIDEO).count();
    }

    private static List<String> providerCalls() {
        List<String> calls = new ArrayList<>();
        for (WireMockServer server : new WireMockServer[] {ark, kling, minimax, openAi}) calls.addAll(everyRequestTo(server));
        return calls;
    }

    /** Kling image to video: accepted, done at its first poll, and its clip can be downloaded. */
    private static void stubKlingImageRun(String taskId) {
        kling.stubFor(post(urlEqualTo("/v1/videos/image2video"))
                .willReturn(okJson("{\"code\":0,\"message\":\"SUCCEED\",\"data\":{\"task_id\":\"" + taskId + "\",\"task_status\":\"submitted\"}}")));
        kling.stubFor(get(urlEqualTo("/v1/videos/image2video/" + taskId))
                .willReturn(okJson("{\"code\":0,\"message\":\"SUCCEED\",\"data\":{\"task_id\":\"" + taskId + "\",\"task_status\":\"succeed\","
                        + "\"task_result\":{\"videos\":[{\"url\":\"" + kling.baseUrl() + "/_test/out.mp4\"}]}}}")));
        kling.stubFor(get(urlEqualTo("/_test/out.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4").withBody(CLIP)));
    }

    /** MiniMax: accepted, a success at its first poll, a file to retrieve, and its clip. */
    private static void stubMiniMaxRun(String taskId) {
        minimax.stubFor(post(urlEqualTo("/v1/video_generation")).willReturn(okJson("{\"task_id\":\"" + taskId + "\"}")));
        minimax.stubFor(get(urlPathEqualTo("/v1/query/video_generation"))
                .willReturn(okJson("{\"task_id\":\"" + taskId + "\",\"status\":\"Success\",\"file_id\":\"file_1\"}")));
        minimax.stubFor(get(urlPathEqualTo("/v1/files/retrieve"))
                .willReturn(okJson("{\"file\":{\"download_url\":\"" + minimax.baseUrl() + "/_test/out.mp4\"}}")));
        minimax.stubFor(get(urlEqualTo("/_test/out.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4").withBody(CLIP)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }
}
