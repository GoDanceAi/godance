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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobChannel;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.GenerationQuotes;
import ai.godance.service.InputService;
import ai.godance.service.OpenAiVideos;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI's video routes ({@code /v1/videos}) as a second view of Godance
 * video jobs, end to end against a provider cassette: create from JSON and
 * from a multipart form, the video object for each job status, owner-only
 * retrieve and list, content, delete, and the routes that are not supported.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class OpenAiVideoIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer ark;
    static WireMockServer openAi;

    @BeforeAll
    static void startMocks() {
        ark = started();
        openAi = started();
    }

    private static WireMockServer started() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopMocks() {
        for (WireMockServer server : new WireMockServer[] {ark, openAi}) {
            if (server != null) server.stop();
        }
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.volcengine.api-base", () -> ark.baseUrl());
        r.add("godance.providers.volcengine.poll-interval", () -> "50ms");
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
        r.add("godance.inputs.enabled", () -> "true");
        r.add("godance.inputs.allow-people", () -> "true");
    }

    static final byte[] CLIP = "FAKE-MP4-BYTES-FOR-THE-OPENAI-VIDEO-SHAPE".getBytes(StandardCharsets.UTF_8);
    static final String ARK_TASKS = "/api/v3/contents/generations/tasks";

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired UsageRecordRepository usage;
    @Autowired AuditLogEntryRepository auditRows;
    @Autowired ObjectStorageClient storage;
    @Autowired InputService inputs;
    @Autowired GenerationQuotes quotes;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        ark.resetAll();
        openAi.resetAll();
    }

    // ===== Create =====================================================

    @Test
    void create_givenJsonWithSecondsAsString_expectsJobAndQueuedVideoObject() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_json");
        long before = Instant.now().getEpochSecond();

        JsonNode video = json.readTree(http.post().uri("/v1/videos")
                .header("Authorization", me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("seedance-2.0", "A paper boat drifts down a gutter after rain.", "seconds", "8"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(String.class).returnResult().getResponseBody());

        // OpenAI's video object, as its SDK reads it.
        assertThat(video.get("object").asString()).isEqualTo("video");
        assertThat(video.get("status").asString()).isEqualTo("queued");
        assertThat(video.get("progress").asInt()).isZero();
        assertThat(video.get("model").asString()).isEqualTo("seedance-2.0");
        assertThat(video.get("seconds").isString()).as("seconds is a string, as OpenAI has it").isTrue();
        assertThat(video.get("seconds").asString()).isEqualTo("8");
        assertThat(video.get("prompt").asString()).isEqualTo("A paper boat drifts down a gutter after rain.");
        assertThat(video.get("created_at").asLong()).isBetween(before - 1, Instant.now().getEpochSecond() + 1);
        assertThat(video.get("completed_at").isNull()).isTrue();
        assertThat(video.get("error").isNull()).isTrue();

        // The video is a job: its id is the job's id, and the job is an ordinary video job of eight seconds.
        UUID id = UUID.fromString(video.get("id").asString());
        GenerationJob job = jobs.findById(id).orElseThrow();
        assertThat(job.getUserId()).isEqualTo(me.id());
        assertThat(job.getModality()).isEqualTo(Modality.VIDEO);
        assertThat(job.getPrompt().get("duration_seconds")).isEqualTo(8);

        // The Godance extensions: what it holds, and where the job is.
        long held = quotes.video("seedance-2.0", 1, 8).costCents();
        assertThat(video.get("cost_cents").asLong()).isEqualTo(held);
        assertThat(video.get("job_url").asString()).isEqualTo("/v1/jobs/" + id);

        awaitEnd(id);
    }

    @Test
    void create_givenJsonWithSecondsAsNumberAndSize_expectsBothOnTheVideoAndSentToTheProvider() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_number");

        JsonNode video = createJson(me, body("seedance-2.0", "Steam rising from a teapot.",
                "seconds", 6, "size", "720x1280"));

        assertThat(video.get("seconds").asString()).isEqualTo("6");
        assertThat(video.get("size").asString()).isEqualTo("720x1280");
        awaitEnd(idOf(video));

        JsonNode sent = onlyPost(ark, ARK_TASKS);
        assertThat(sent.get("model").asString()).isEqualTo("seedance-2.0");
        // The adapter's own request, unchanged by the route the job came through: one text item.
        assertThat(sent.get("content")).hasSize(1);
        assertThat(sent.get("content").get(0).get("text").asString()).isEqualTo("Steam rising from a teapot.");
    }

    @Test
    void create_givenNoSecondsOrSize_expectsTheModelsDefaultsOnTheVideo() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_defaults");

        JsonNode video = createJson(me, body("seedance-2.0", "A kite over a beach."));

        // The length is the adapter's default, which is what is priced; the size is the catalog's default_size.
        assertThat(video.get("seconds").asString()).isEqualTo("5");
        assertThat(video.get("size").asString()).isEqualTo("1280x720");
        assertThat(video.get("cost_cents").asLong()).isEqualTo(quotes.video("seedance-2.0", 1, null).costCents());
        awaitEnd(idOf(video));
    }

    @Test
    void create_givenMultipartFormOfTextFields_expectsQueuedVideo() throws Exception {
        // What OpenAI's SDK sends for client.videos.create(model=…, prompt=…, seconds="8"): a form, no file.
        Account me = account(100_00L);
        stubArkRun("task_form");
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "A lighthouse beam sweeping over waves.");
        form.part("seconds", "8");
        form.part("size", "1280x720");

        JsonNode video = json.readTree(createForm(me.bearer(), form)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(video.get("object").asString()).isEqualTo("video");
        assertThat(video.get("status").asString()).isEqualTo("queued");
        assertThat(video.get("seconds").asString()).isEqualTo("8");
        assertThat(video.get("size").asString()).isEqualTo("1280x720");
        GenerationJob job = awaitEnd(idOf(video));
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(inputs.viewsFor(job.getId())).isEmpty();
    }

    @Test
    void create_givenMultipartWithInputReferenceFile_expectsItStoredAsTheCallersFileAndSentAsFirstFrame() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_file");
        byte[] still = InputFixtures.image("plain.png");
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "The boat drifts away from the bank.");
        form.part("seconds", "5");
        form.part("input_reference", InputFixtures.named(still, "first.png")).contentType(MediaType.IMAGE_PNG);

        JsonNode video = json.readTree(createForm(me.bearer(), form)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        GenerationJob job = awaitEnd(idOf(video));
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);

        // The image went through the upload intake: it is one of the caller's input files now.
        assertThat(inputs.list(me.id(), null, null)).hasSize(1);
        UUID fileId = inputs.list(me.id(), null, null).get(0).getId();

        // The job records it as its first frame, exactly as a request to /v1/videos/generations would.
        JsonNode jobView = getJson(me.bearer(), "/v1/jobs/" + job.getId());
        assertThat(jobView.get("inputs")).hasSize(1);
        assertThat(jobView.get("inputs").get(0).get("role").asString()).isEqualTo("first_frame");
        assertThat(jobView.get("inputs").get(0).get("file_id").asString()).isEqualTo(fileId.toString());

        // And the provider got the frame itself, inline, in the role Ark names.
        JsonNode sent = onlyPost(ark, ARK_TASKS);
        assertThat(sent.get("content")).hasSize(2);
        assertThat(sent.get("content").get(1)).isEqualTo(json.readTree("{\"type\":\"image_url\",\"image_url\":{\"url\":\""
                + dataUrl("image/png", inputs.content(me.id(), fileId).bytes()) + "\"},\"role\":\"first_frame\"}"));
    }

    @Test
    void create_givenInputReferenceFileIdInJson_expectsFirstFrameOnTheJob() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_file_id");
        UUID fileId = upload(me, InputFixtures.image("plain.jpg"), "first.jpg");

        JsonNode video = createJson(me, body("seedance-2.0", "The tide goes out.", "seconds", "5",
                "input_reference", Map.of("file_id", fileId.toString())));
        GenerationJob job = awaitEnd(idOf(video));

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        JsonNode jobInputs = getJson(me.bearer(), "/v1/jobs/" + job.getId()).get("inputs");
        assertThat(jobInputs).hasSize(1);
        assertThat(jobInputs.get(0).get("role").asString()).isEqualTo("first_frame");
        assertThat(jobInputs.get(0).get("file_id").asString()).isEqualTo(fileId.toString());
        assertThat(onlyPost(ark, ARK_TASKS).get("content").get(1).get("role").asString()).isEqualTo("first_frame");
    }

    @Test
    void create_givenInputReferenceFileIdAsTheSdkWritesItInAForm_expectsFirstFrameOnTheJob() throws Exception {
        // OpenAI's SDK flattens input_reference={"file_id": …} into a form field named input_reference[file_id].
        Account me = account(100_00L);
        stubArkRun("task_bracket");
        UUID fileId = upload(me, InputFixtures.image("plain.png"), "first.png");
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "Clouds passing over a field.");
        form.part("input_reference[file_id]", fileId.toString());

        JsonNode video = json.readTree(createForm(me.bearer(), form)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        GenerationJob job = awaitEnd(idOf(video));

        JsonNode jobInputs = getJson(me.bearer(), "/v1/jobs/" + job.getId()).get("inputs");
        assertThat(jobInputs).hasSize(1);
        assertThat(jobInputs.get(0).get("file_id").asString()).isEqualTo(fileId.toString());
        // No second file was stored: the form named one that was already there.
        assertThat(inputs.list(me.id(), null, null)).hasSize(1);
    }

    @Test
    void create_givenInputReferenceJobOutput_expectsThatImageAsFirstFrame() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_job_output");
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("plain.png"));

        JsonNode video = createJson(me, body("seedance-2.0", "The still comes alive.", "input_reference",
                Map.of("job_output", Map.of("job_id", source.getId().toString(), "index", 0))));
        GenerationJob job = awaitEnd(idOf(video));

        JsonNode jobInputs = getJson(me.bearer(), "/v1/jobs/" + job.getId()).get("inputs");
        assertThat(jobInputs).hasSize(1);
        assertThat(jobInputs.get(0).get("role").asString()).isEqualTo("first_frame");
        assertThat(jobInputs.get(0).get("job_output").get("job_id").asString()).isEqualTo(source.getId().toString());
    }

    @Test
    void create_givenInputReferenceFileOnAccountThatHasNotAcceptedTheTerms_expects403AndNothingStoredOrCharged() {
        Account me = account(100_00L);
        User user = users.findById(me.id()).orElseThrow();
        user.setInputsTermsVersion(null);
        user.setInputsTermsAcceptedAt(null);
        users.save(user);
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "The boat drifts away from the bank.");
        form.part("input_reference", InputFixtures.named(InputFixtures.image("plain.png"), "first.png"))
                .contentType(MediaType.IMAGE_PNG);

        JsonNode problem = refusedForm(me, form, 403, "inputs_terms_required");

        // Round 11's policy answered, in its own words, and the SDK can read it.
        assertThat(problem.get("error").get("message").asString()).isEqualTo(problem.get("detail").asString());
        assertThat(problem.get("error").get("type").asString()).isEqualTo("permission_error");
        assertThat(inputs.list(me.id(), null, null)).isEmpty();
    }

    @Test
    void create_givenInputReferenceFileOnAChildsAccount_expects403AndNothingStored() {
        Account me = account(100_00L);
        User user = users.findById(me.id()).orElseThrow();
        user.setAgeStatus(AgeStatus.CONSENT_GRANTED);   // under 16, approved by a parent: not an adult's account
        users.save(user);
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "The boat drifts away from the bank.");
        form.part("input_reference", InputFixtures.named(InputFixtures.image("plain.png"), "first.png"))
                .contentType(MediaType.IMAGE_PNG);

        refusedForm(me, form, 403, "inputs_not_allowed_for_account");
        assertThat(inputs.list(me.id(), null, null)).isEmpty();
    }

    @Test
    void create_givenInputReferenceFileForAModelThatTakesNoFirstFrame_expects400AndNoStoredFile() {
        Account me = account(1000_00L);
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "sora-2");     // its catalog row lists no inputs
        form.part("prompt", "A tiger surfs a wave.");
        form.part("input_reference", InputFixtures.named(InputFixtures.image("plain.png"), "first.png"))
                .contentType(MediaType.IMAGE_PNG);

        refusedForm(me, form, 400, "input_not_supported");

        // Refused before the image was taken in: a request that cannot run leaves no file behind.
        assertThat(inputs.list(me.id(), null, null)).isEmpty();
    }

    @Test
    void create_givenInputReferenceThatIsNotAnImage_expects415FromTheUploadChecks() {
        Account me = account(100_00L);
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "The boat drifts away from the bank.");
        form.part("input_reference", InputFixtures.named("not an image at all".getBytes(StandardCharsets.UTF_8), "first.png"))
                .contentType(MediaType.IMAGE_PNG);

        refusedForm(me, form, 415, "input_type_unsupported");
        assertThat(inputs.list(me.id(), null, null)).isEmpty();
    }

    @Test
    void create_givenInputReferenceImageUrl_expects400ImageUrlNotSupported() {
        Account me = account(100_00L);

        JsonNode problem = refusedJson(me, body("seedance-2.0", "The still comes alive.", "input_reference",
                Map.of("image_url", "https://example.test/still.png")), 400, "image_url_not_supported");
        assertThat(problem.get("error").get("param").asString()).isEqualTo("input_reference");

        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", "seedance-2.0");
        form.part("prompt", "The still comes alive.");
        form.part("input_reference[image_url]", "https://example.test/still.png");
        refusedForm(me, form, 400, "image_url_not_supported");
    }

    @Test
    void create_givenAnotherUsersFileAsInputReference_expects404AndNoJob() {
        Account me = account(100_00L);
        Account other = account(0L);
        UUID theirs = upload(other, InputFixtures.image("plain.png"), "theirs.png");

        refusedJson(me, body("seedance-2.0", "The still comes alive.", "input_reference",
                Map.of("file_id", theirs.toString())), 404, "file_not_found");
    }

    @Test
    void create_givenSecondsThatIsNotAWholeNumber_expects400WithOpenAisErrorObjectBesideTheProblem() {
        Account me = account(100_00L);

        JsonNode problem = refusedJson(me, body("seedance-2.0", "A kite over a beach.", "seconds", "eight"),
                400, "invalid_parameter");

        // Still a problem document…
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("title").asString()).isEqualTo("Bad Request");
        assertThat(problem.get("detail").asString()).contains("seconds must be a whole number");
        assertThat(problem.get("param").asString()).isEqualTo("seconds");
        // …and OpenAI's error object, which is where its SDK reads the message, the code and the field.
        JsonNode error = problem.get("error");
        assertThat(error.get("message").asString()).isEqualTo(problem.get("detail").asString());
        assertThat(error.get("type").asString()).isEqualTo("invalid_request_error");
        assertThat(error.get("code").asString()).isEqualTo("invalid_parameter");
        assertThat(error.get("param").asString()).isEqualTo("seconds");

        refusedJson(me, body("seedance-2.0", "A kite over a beach.", "seconds", 0), 400, "invalid_parameter");
        refusedJson(me, body("seedance-2.0", "A kite over a beach.", "seconds", 2.5), 400, "invalid_parameter");
    }

    @Test
    void create_givenNoModel_expects400NamingTheField() {
        Account me = account(100_00L);
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", "A kite over a beach.");
        body.put("seconds", "8");

        JsonNode problem = refusedJson(me, body, 400, "invalid_parameter");

        // OpenAI defaults to sora-2; Godance has no default video model, and says which field is missing.
        assertThat(problem.get("error").get("param").asString()).isEqualTo("model");
    }

    @Test
    void create_givenUnknownOrNonVideoModel_expectsTheSameAnswersAsGenerations() {
        Account me = account(100_00L);

        JsonNode unknown = refusedJson(me, body("no-such-model", "x"), 404, null);
        assertThat(unknown.get("error").get("message").asString()).isEqualTo("unknown model: no-such-model");
        assertThat(unknown.get("error").get("type").asString()).isEqualTo("not_found_error");
        refusedJson(me, body("gpt-4o", "x"), 400, null);
    }

    @Test
    void create_givenInsufficientCredit_expects402WithCodeAndNoJob() {
        Account me = account(0L);

        JsonNode problem = refusedJson(me, body("seedance-2.0", "A kite over a beach.", "seconds", "8"),
                402, "insufficient_credit");

        assertThat(problem.get("error").get("code").asString()).isEqualTo("insufficient_credit");
        assertThat(problem.get("error").get("message").asString()).contains("insufficient credit");
    }

    @Test
    void create_givenDailySpendingCapReached_expects402BeforeAnythingRuns() {
        Account me = account(100_00L);
        User user = users.findById(me.id()).orElseThrow();
        user.setDailyCreditCapCents(10_00L);
        users.save(user);
        seedUsage(me.id(), 10_00L);     // the whole cap is already spent today

        JsonNode problem = refusedJson(me, body("seedance-2.0", "A kite over a beach.", "seconds", "8"), 402, null);

        // The cap filter answered, as it does for /v1/videos/generations: not a lack of credit.
        assertThat(problem.get("detail").asString()).doesNotContain("insufficient credit");
        assertThat(problem.get("error").get("message").asString()).isEqualTo(problem.get("detail").asString());
        // The same check guards the route this one is a second view of.
        http.post().uri("/v1/videos/generations")
                .header("Authorization", me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("seedance-2.0", "A kite over a beach.", "seconds", 8))
                .exchange()
                .expectStatus().isEqualTo(402);
    }

    @Test
    void create_givenApiKeyAndGalleryRecipe_expectsChannelApiAndTheRecipeSlugOnTheJob() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_origin");
        String key = apiKey(me);

        JsonNode video = json.readTree(http.post().uri("/v1/videos")
                .header("Authorization", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("seedance-2.0", "Koi circling under falling rain.", "seconds", "5",
                        "gallery_recipe", "koi-pond-in-rain"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        GenerationJob job = awaitEnd(idOf(video));
        assertThat(job.getChannel()).isEqualTo(JobChannel.API);
        assertThat(job.getGalleryRecipe()).isEqualTo("koi-pond-in-rain");

        refusedJson(me, body("seedance-2.0", "x", "gallery_recipe", "Not A Slug!"), 400, "invalid_gallery_recipe");
    }

    @Test
    void create_givenRecipeInPlaceOfPrompt_expectsTheRenderedPromptAndTheRecipeOnTheJob() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_recipe");
        Map<?, ?> recipe = http.post().uri("/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Circling", "modality", "VIDEO",
                        "template", "{{subject}} circling under falling rain.",
                        "variables", List.of(Map.of("name", "subject", "default", "Koi")),
                        "model", "seedance-2.0", "params", Map.of("n", 1)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String versionId = (String) ((Map<?, ?>) recipe.get("latest_version")).get("id");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "seedance-2.0");
        body.put("seconds", "5");
        body.put("recipe", Map.of("version_id", versionId, "variables", Map.of("subject", "Paper boats")));
        JsonNode video = createJson(me, body);

        assertThat(video.get("prompt").asString()).isEqualTo("Paper boats circling under falling rain.");
        GenerationJob job = awaitEnd(idOf(video));
        JsonNode jobView = getJson(me.bearer(), "/v1/jobs/" + job.getId());
        assertThat(jobView.get("recipe").get("id").asString()).isEqualTo(recipe.get("id"));
        assertThat(jobView.get("recipe").get("variables").get("subject").asString()).isEqualTo("Paper boats");

        // A prompt and a recipe together are refused here as on /v1/videos/generations.
        body.put("prompt", "and a prompt as well");
        refusedJson(me, body, 400, "prompt_and_recipe");
    }

    // ===== The video object, by job status ============================

    @Test
    void get_givenEachJobStatus_expectsOpenAisStatusProgressCostAndError() {
        Account me = account(0L);
        GenerationJob queued = videoJob(me.id(), JobStatus.QUEUED, 30);
        GenerationJob running = videoJob(me.id(), JobStatus.RUNNING, 25);
        GenerationJob done = videoJob(me.id(), JobStatus.DONE, 20);
        GenerationJob failed = videoJob(me.id(), JobStatus.FAILED, 15);
        GenerationJob cancelled = videoJob(me.id(), JobStatus.CANCELLED, 10);

        JsonNode q = getJson(me.bearer(), "/v1/videos/" + queued.getId());
        assertThat(q.get("status").asString()).isEqualTo("queued");
        assertThat(q.get("progress").asInt()).isZero();
        assertThat(q.get("cost_cents").asLong()).as("held while it waits").isEqualTo(112L);
        assertThat(q.get("completed_at").isNull()).isTrue();
        assertThat(q.get("error").isNull()).isTrue();

        JsonNode r = getJson(me.bearer(), "/v1/videos/" + running.getId());
        assertThat(r.get("status").asString()).isEqualTo("in_progress");
        assertThat(r.get("progress").asInt()).isZero();
        assertThat(r.get("cost_cents").asLong()).as("held while it runs").isEqualTo(112L);

        JsonNode d = getJson(me.bearer(), "/v1/videos/" + done.getId());
        assertThat(d.get("status").asString()).isEqualTo("completed");
        assertThat(d.get("progress").asInt()).isEqualTo(100);
        assertThat(d.get("cost_cents").asLong()).as("what it was charged").isEqualTo(110L);
        assertThat(d.get("completed_at").asLong()).isEqualTo(done.getCompletedAt().getEpochSecond());
        assertThat(d.get("expires_at").asLong())
                .isEqualTo(done.getCreatedAt().plus(java.time.Duration.ofDays(90)).getEpochSecond());
        assertThat(d.get("error").isNull()).isTrue();
        assertThat(d.get("seconds").asString()).isEqualTo("8");
        assertThat(d.get("model").asString()).isEqualTo("seedance-2.0");
        assertThat(d.get("object").asString()).isEqualTo("video");
        assertThat(d.get("remixed_from_video_id").isNull()).isTrue();
        assertThat(d.get("job_url").asString()).isEqualTo("/v1/jobs/" + done.getId());

        JsonNode f = getJson(me.bearer(), "/v1/videos/" + failed.getId());
        assertThat(f.get("status").asString()).isEqualTo("failed");
        assertThat(f.get("progress").asInt()).isZero();
        assertThat(f.get("cost_cents").asLong()).as("the hold was returned").isZero();
        assertThat(f.get("error").get("code").asString()).isEqualTo("generation_failed");
        assertThat(f.get("error").get("message").asString()).isEqualTo("upstream: the provider said no");

        JsonNode c = getJson(me.bearer(), "/v1/videos/" + cancelled.getId());
        assertThat(c.get("status").asString()).as("OpenAI has no cancelled state").isEqualTo("failed");
        assertThat(c.get("error").get("code").asString()).isEqualTo("generation_cancelled");
        assertThat(c.get("cost_cents").asLong()).isZero();
    }

    @Test
    void get_givenVideoThatIsNotFinished_expectsThePollIntervalHeaderOpenAisSdkReads() {
        Account me = account(0L);
        GenerationJob running = videoJob(me.id(), JobStatus.RUNNING, 10);
        GenerationJob done = videoJob(me.id(), JobStatus.DONE, 5);

        http.get().uri("/v1/videos/{id}", running.getId()).header("Authorization", me.bearer()).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("openai-poll-after-ms", "5000");
        http.get().uri("/v1/videos/{id}", done.getId()).header("Authorization", me.bearer()).exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("openai-poll-after-ms");
    }

    @Test
    void createThenPoll_givenProviderSucceeds_expectsCompletedWithTheChargeAndTheSameJobAtJobs() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_done");

        JsonNode created = createJson(me, body("seedance-2.0", "A paper boat on a pond.", "seconds", "8"));
        UUID id = idOf(created);
        awaitEnd(id);

        JsonNode video = getJson(me.bearer(), "/v1/videos/" + id);
        long cost = quotes.video("seedance-2.0", 1, 8).costCents();
        assertThat(video.get("status").asString()).isEqualTo("completed");
        assertThat(video.get("progress").asInt()).isEqualTo(100);
        assertThat(video.get("cost_cents").asLong()).isEqualTo(cost);
        assertThat(video.get("completed_at").asLong()).isGreaterThanOrEqualTo(video.get("created_at").asLong());

        // The same job, unchanged, at the route that was already there.
        JsonNode job = getJson(me.bearer(), "/v1/jobs/" + id);
        assertThat(job.get("status").asString()).isEqualTo("DONE");
        assertThat(job.get("modality").asString()).isEqualTo("VIDEO");
        assertThat(job.get("actualCostCents").asLong()).isEqualTo(cost);
        assertThat(job.get("params").get("seconds").asInt()).isEqualTo(8);
        awaitBalance(me.id(), 100_00L - cost);
    }

    @Test
    void createThenPoll_givenProviderFails_expectsFailedWithErrorAndTheHoldReturned() throws Exception {
        Account me = account(100_00L);
        ark.stubFor(post(urlEqualTo(ARK_TASKS)).willReturn(okJson("{\"id\":\"task_fail\"}")));
        ark.stubFor(get(urlPathMatching(ARK_TASKS + "/task_fail"))
                .willReturn(okJson("{\"id\":\"task_fail\",\"status\":\"failed\"}")));

        UUID id = idOf(createJson(me, body("seedance-2.0", "A clip that never finishes.", "seconds", "8")));
        awaitEnd(id);

        JsonNode video = getJson(me.bearer(), "/v1/videos/" + id);
        assertThat(video.get("status").asString()).isEqualTo("failed");
        assertThat(video.get("error").get("code").asString()).isEqualTo("generation_failed");
        assertThat(video.get("error").get("message").asString()).isNotBlank();
        assertThat(video.get("cost_cents").asLong()).isZero();
        awaitBalance(me.id(), 100_00L);
    }

    // ===== Retrieve and list: the owner only ==========================

    @Test
    void get_givenAnotherUsersVideoAnImageJobOrNoSuchId_expects404VideoNotFound() {
        Account me = account(0L);
        Account other = account(0L);
        GenerationJob theirs = videoJob(other.id(), JobStatus.DONE, 10);
        GenerationJob myImage = doneImageJob(me.id(), InputFixtures.image("plain.png"));

        for (String id : List.of(theirs.getId().toString(), myImage.getId().toString(),
                UUID.randomUUID().toString(), "not-a-uuid")) {
            JsonNode problem = json.readTree(http.get().uri("/v1/videos/{id}", id)
                    .header("Authorization", me.bearer())
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody(String.class).returnResult().getResponseBody());
            assertThat(problem.get("code").asString()).as(id).isEqualTo("video_not_found");
            assertThat(problem.get("error").get("code").asString()).isEqualTo("video_not_found");
            assertThat(problem.get("error").get("type").asString()).isEqualTo("not_found_error");
            assertThat(problem.get("error").get("message").asString()).isEqualTo("video not found");
        }
        // Its owner reads it.
        assertThat(getJson(other.bearer(), "/v1/videos/" + theirs.getId()).get("id").asString())
                .isEqualTo(theirs.getId().toString());
    }

    @Test
    void list_givenVideosOfTwoUsersAndAnImageJob_expectsOnlyTheCallersVideosPagedAsOpenAiPages() {
        Account me = account(0L);
        Account other = account(0L);
        GenerationJob oldest = videoJob(me.id(), JobStatus.DONE, 30);
        GenerationJob middle = videoJob(me.id(), JobStatus.FAILED, 20);
        GenerationJob newest = videoJob(me.id(), JobStatus.RUNNING, 10);
        videoJob(other.id(), JobStatus.DONE, 15);
        doneImageJob(me.id(), InputFixtures.image("plain.png"));

        JsonNode first = getJson(me.bearer(), "/v1/videos?limit=2");
        assertThat(first.get("object").asString()).isEqualTo("list");
        assertThat(ids(first)).containsExactly(newest.getId().toString(), middle.getId().toString());
        assertThat(first.get("has_more").asBoolean()).isTrue();
        assertThat(first.get("first_id").asString()).isEqualTo(newest.getId().toString());
        assertThat(first.get("last_id").asString()).isEqualTo(middle.getId().toString());
        assertThat(first.get("data").get(0).get("object").asString()).isEqualTo("video");
        assertThat(first.get("data").get(0).get("status").asString()).isEqualTo("in_progress");

        // The next page is asked for with after = last_id, as OpenAI's SDK does.
        JsonNode second = getJson(me.bearer(), "/v1/videos?limit=2&after=" + middle.getId());
        assertThat(ids(second)).containsExactly(oldest.getId().toString());
        assertThat(second.get("has_more").asBoolean()).isFalse();

        // Without a limit: a page of up to twenty.
        assertThat(ids(getJson(me.bearer(), "/v1/videos")))
                .containsExactly(newest.getId().toString(), middle.getId().toString(), oldest.getId().toString());

        // order=asc walks the other way.
        JsonNode ascending = getJson(me.bearer(), "/v1/videos?order=asc&limit=2");
        assertThat(ids(ascending)).containsExactly(oldest.getId().toString(), middle.getId().toString());
        assertThat(ascending.get("has_more").asBoolean()).isTrue();
        assertThat(ids(getJson(me.bearer(), "/v1/videos?order=asc&after=" + middle.getId())))
                .containsExactly(newest.getId().toString());

        // The other user sees only theirs.
        assertThat(getJson(other.bearer(), "/v1/videos").get("data")).hasSize(1);
        // An account with none gets an empty page, not an error.
        JsonNode empty = getJson(account(0L).bearer(), "/v1/videos");
        assertThat(empty.get("data")).isEmpty();
        assertThat(empty.get("has_more").asBoolean()).isFalse();
        assertThat(empty.get("last_id").isNull()).isTrue();
    }

    @Test
    void list_givenBadParameters_expects400NamingTheParameter() {
        Account me = account(0L);
        Account other = account(0L);
        GenerationJob theirs = videoJob(other.id(), JobStatus.DONE, 10);

        for (String query : List.of("limit=0", "limit=101", "limit=many", "after=not-a-uuid",
                "after=" + theirs.getId(), "after=" + UUID.randomUUID(), "order=sideways")) {
            JsonNode problem = json.readTree(http.get().uri("/v1/videos?" + query)
                    .header("Authorization", me.bearer())
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody(String.class).returnResult().getResponseBody());
            assertThat(problem.get("code").asString()).as(query).isEqualTo("invalid_parameter");
            assertThat(problem.get("error").get("param").asString()).as(query).isEqualTo(query.split("=")[0]);
        }
    }

    // ===== Content ====================================================

    @Test
    void content_givenCompletedVideo_expectsItsBytesStreamedWithTheStoredTypeAndLength() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_content");
        UUID id = idOf(createJson(me, body("seedance-2.0", "A paper boat on a pond.", "seconds", "5")));
        awaitEnd(id);

        // As OpenAI's SDK asks: Accept: application/binary, and no variant means the video.
        for (String uri : List.of("/v1/videos/" + id + "/content", "/v1/videos/" + id + "/content?variant=video")) {
            http.get().uri(uri)
                    .header("Authorization", me.bearer())
                    .header(HttpHeaders.ACCEPT, "application/binary")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType("video/mp4")
                    .expectHeader().contentLength(CLIP.length)
                    .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store, private")
                    .expectBody(byte[].class).isEqualTo(CLIP);
        }
    }

    @Test
    void content_givenAVideoOfSeveralMegabytes_expectsEveryByteInOrder() {
        Account me = account(0L);
        GenerationJob job = videoJob(me.id(), JobStatus.DONE, 10);
        byte[] large = new byte[3 * 1024 * 1024 + 123];
        new Random(42).nextBytes(large);
        store(job, 0, large, "video/mp4", "mp4");

        Flux<byte[]> chunks = http.get().uri("/v1/videos/{id}/content", job.getId())
                .header("Authorization", me.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentLength(large.length)
                .returnResult(byte[].class).getResponseBody();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        List<byte[]> parts = chunks.collectList().block();
        parts.forEach(received::writeBytes);

        assertThat(parts.size()).as("sent in pieces, not as one buffer").isGreaterThan(1);
        assertThat(received.toByteArray()).isEqualTo(large);
    }

    @Test
    void content_givenThumbnail_expects404WhileTheJobHasNoImageOutputAndTheImageOnceItHasOne() {
        Account me = account(0L);
        GenerationJob job = videoJob(me.id(), JobStatus.DONE, 10);
        store(job, 0, CLIP, "video/mp4", "mp4");

        // No adapter stores a poster today, so this is what every video answers.
        JsonNode problem = json.readTree(http.get().uri("/v1/videos/{id}/content?variant=thumbnail", job.getId())
                .header("Authorization", me.bearer())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(problem.get("code").asString()).isEqualTo("video_content_not_found");
        assertThat(problem.get("error").get("message").asString()).isEqualTo("This video has no thumbnail.");

        // A job that has an image among its outputs serves it as the thumbnail.
        byte[] poster = InputFixtures.image("plain.png");
        store(job, 1, poster, "image/png", "png");
        http.get().uri("/v1/videos/{id}/content?variant=thumbnail", job.getId())
                .header("Authorization", me.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class).isEqualTo(poster);
        // And the video is still the video.
        http.get().uri("/v1/videos/{id}/content", job.getId())
                .header("Authorization", me.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("video/mp4")
                .expectBody(byte[].class).isEqualTo(CLIP);
    }

    @Test
    void content_givenVideoThatIsNotCompletedOrNotTheCallers_expectsAClearRefusal() {
        Account me = account(0L);
        Account other = account(0L);
        GenerationJob running = videoJob(me.id(), JobStatus.RUNNING, 10);
        GenerationJob failed = videoJob(me.id(), JobStatus.FAILED, 9);
        GenerationJob done = videoJob(me.id(), JobStatus.DONE, 8);
        store(done, 0, CLIP, "video/mp4", "mp4");

        assertThat(contentProblem(me, running.getId(), "", 409).get("code").asString()).isEqualTo("video_not_ready");
        assertThat(contentProblem(me, failed.getId(), "", 404).get("code").asString())
                .isEqualTo("video_content_not_found");
        // OpenAI offers a spritesheet; Godance does not, and says so.
        JsonNode sprites = contentProblem(me, done.getId(), "?variant=spritesheet", 400);
        assertThat(sprites.get("code").asString()).isEqualTo("variant_not_supported");
        assertThat(sprites.get("error").get("param").asString()).isEqualTo("variant");
        // Another user's video is not found, whatever state it is in.
        assertThat(contentProblem(other, done.getId(), "", 404).get("code").asString()).isEqualTo("video_not_found");
    }

    @Test
    void content_givenAFileStorageNoLongerHas_expects503AndNotAnEmptyDownload() {
        Account me = account(0L);
        GenerationJob job = videoJob(me.id(), JobStatus.DONE, 10);
        // The job records an output, but the object is not there: storage lost it, or a delete is under way.
        outputRows.save(new GenerationJobOutput(job.getId(), 0, "videos/" + job.getId() + "/0.mp4", "video/mp4", 99, null));

        JsonNode problem = contentProblem(me, job.getId(), "", 503);

        assertThat(problem.get("code").asString()).isEqualTo("video_content_unavailable");
        assertThat(problem.get("error").get("type").asString()).isEqualTo("server_error");
    }

    // ===== Delete =====================================================

    @Test
    void delete_givenCompletedVideo_expectsFilesPurgedNoRefundAndTheJobKeptWithItsCost() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_delete");
        UUID id = idOf(createJson(me, body("seedance-2.0", "A paper boat on a pond.", "seconds", "8")));
        awaitEnd(id);
        long cost = quotes.video("seedance-2.0", 1, 8).costCents();
        awaitBalance(me.id(), 100_00L - cost);
        long ledgerBefore = ledgerCount(me.id());
        assertThat(storage.list("videos/" + id + "/")).hasSize(1);

        http.delete().uri("/v1/videos/{id}", id).header("Authorization", me.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id.toString())
                .jsonPath("$.object").isEqualTo("video.deleted")
                .jsonPath("$.deleted").isEqualTo(true);

        // The files are gone, exactly as after the retention run: objects, output rows, prompt.
        assertThat(storage.list("videos/" + id + "/")).isEmpty();
        assertThat(outputRows.findByJobIdOrderByOutputIndexAsc(id)).isEmpty();
        GenerationJob job = jobs.findById(id).orElseThrow();
        assertThat(job.getPurgedAt()).isNotNull();
        assertThat(job.getPrompt()).isEqualTo(Map.of("purged", true));

        // Nothing was refunded: the balance and the ledger are as they were.
        assertThat(ledger.authoritativeBalance(me.id())).isEqualTo(100_00L - cost);
        assertThat(ledgerCount(me.id())).isEqualTo(ledgerBefore);

        // It is no longer a video…
        http.get().uri("/v1/videos/{id}", id).header("Authorization", me.bearer()).exchange()
                .expectStatus().isNotFound();
        assertThat(getJson(me.bearer(), "/v1/videos").get("data")).isEmpty();
        http.delete().uri("/v1/videos/{id}", id).header("Authorization", me.bearer()).exchange()
                .expectStatus().isNotFound();
        // …and the job is still there, purged, with what it cost.
        JsonNode jobView = getJson(me.bearer(), "/v1/jobs/" + id);
        assertThat(jobView.get("purged").asBoolean()).isTrue();
        assertThat(jobView.get("actualCostCents").asLong()).isEqualTo(cost);
        assertThat(jobView.get("outputs")).isEmpty();

        assertThat(auditRows.findByEventType(OpenAiVideos.EVT_VIDEO_DELETED))
                .anySatisfy(entry -> {
                    assertThat(entry.getUserId()).isEqualTo(me.id());
                    assertThat(entry.getEventDetail()).containsEntry("job_id", id.toString());
                });
    }

    @Test
    void delete_givenAnotherUsersVideo_expects404AndLeavesItUntouched() {
        Account me = account(0L);
        Account other = account(0L);
        GenerationJob theirs = videoJob(other.id(), JobStatus.DONE, 10);
        store(theirs, 0, CLIP, "video/mp4", "mp4");

        JsonNode problem = json.readTree(http.delete().uri("/v1/videos/{id}", theirs.getId())
                .header("Authorization", me.bearer())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(problem.get("code").asString()).isEqualTo("video_not_found");
        assertThat(jobs.findById(theirs.getId()).orElseThrow().getPurgedAt()).isNull();
        assertThat(storage.list("videos/" + theirs.getId() + "/")).hasSize(1);
        assertThat(outputRows.findByJobIdOrderByOutputIndexAsc(theirs.getId())).hasSize(1);
    }

    @Test
    void delete_givenVideoStillInProgress_expects409AndNothingChanges() {
        Account me = account(100_00L);
        GenerationJob running = videoJob(me.id(), JobStatus.RUNNING, 10);
        long ledgerBefore = ledgerCount(me.id());

        JsonNode problem = json.readTree(http.delete().uri("/v1/videos/{id}", running.getId())
                .header("Authorization", me.bearer())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(String.class).returnResult().getResponseBody());

        // Ending a running job would mean returning its hold, and delete never moves money.
        assertThat(problem.get("code").asString()).isEqualTo("video_not_deletable");
        GenerationJob after = jobs.findById(running.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(after.getPurgedAt()).isNull();
        assertThat(ledgerCount(me.id())).isEqualTo(ledgerBefore);
        assertThat(ledger.authoritativeBalance(me.id())).isEqualTo(100_00L);
    }

    @Test
    void delete_givenFailedVideo_expectsItGoneAndTheLedgerUntouched() {
        Account me = account(100_00L);
        GenerationJob failed = videoJob(me.id(), JobStatus.FAILED, 10);
        long ledgerBefore = ledgerCount(me.id());

        http.delete().uri("/v1/videos/{id}", failed.getId()).header("Authorization", me.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.deleted").isEqualTo(true);

        assertThat(jobs.findById(failed.getId()).orElseThrow().getPurgedAt()).isNotNull();
        assertThat(ledgerCount(me.id())).isEqualTo(ledgerBefore);
        assertThat(ledger.authoritativeBalance(me.id())).isEqualTo(100_00L);
    }

    @Test
    void list_givenAfterIsAVideoDeletedSinceItWasListed_expectsThePageAfterIt() {
        // client.videos.list() pages with after = the last id it saw; deleting as it goes must not break it.
        Account me = account(0L);
        GenerationJob oldest = videoJob(me.id(), JobStatus.DONE, 30);
        GenerationJob middle = videoJob(me.id(), JobStatus.DONE, 20);
        videoJob(me.id(), JobStatus.DONE, 10);

        http.delete().uri("/v1/videos/{id}", middle.getId()).header("Authorization", me.bearer()).exchange()
                .expectStatus().isOk();

        JsonNode page = getJson(me.bearer(), "/v1/videos?after=" + middle.getId());
        assertThat(ids(page)).containsExactly(oldest.getId().toString());
        assertThat(ids(getJson(me.bearer(), "/v1/videos"))).doesNotContain(middle.getId().toString()).hasSize(2);
    }

    // ===== What OpenAI offers and Godance does not ====================

    @Test
    void unsupportedRoutes_givenAnyCaller_expect404WithACodeOfTheirOwn() {
        Account me = account(0L);
        String key = apiKey(me);
        UUID any = UUID.randomUUID();

        for (String bearer : List.of(me.bearer(), key)) {
            assertThat(unsupported(bearer, "POST", "/v1/videos/" + any + "/remix")).isEqualTo("video_remix_not_supported");
            assertThat(unsupported(bearer, "POST", "/v1/videos/edits")).isEqualTo("video_edits_not_supported");
            assertThat(unsupported(bearer, "POST", "/v1/videos/extensions")).isEqualTo("video_extensions_not_supported");
            assertThat(unsupported(bearer, "POST", "/v1/videos/characters")).isEqualTo("video_characters_not_supported");
            assertThat(unsupported(bearer, "GET", "/v1/videos/characters/char_123")).isEqualTo("video_characters_not_supported");
        }
    }

    // ===== Who may call, and what an error looks like =================

    @Test
    void everyRoute_givenApiKey_expectsTheSameAccessAsTheSession() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_key");
        String key = apiKey(me);

        JsonNode created = json.readTree(http.post().uri("/v1/videos")
                .header("Authorization", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("seedance-2.0", "A paper boat on a pond.", "seconds", "5"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        UUID id = idOf(created);
        awaitEnd(id);

        assertThat(getJson(key, "/v1/videos/" + id).get("status").asString()).isEqualTo("completed");
        assertThat(ids(getJson(key, "/v1/videos"))).containsExactly(id.toString());
        http.get().uri("/v1/videos/{id}/content", id).header("Authorization", key).exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(CLIP);
        http.delete().uri("/v1/videos/{id}", id).header("Authorization", key).exchange()
                .expectStatus().isOk();
    }

    @Test
    void everyRoute_givenNoCredentials_expects401() {
        UUID any = UUID.randomUUID();
        http.post().uri("/v1/videos").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("seedance-2.0", "x")).exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/videos").exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/videos/{id}", any).exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/videos/{id}/content", any).exchange().expectStatus().isUnauthorized();
        http.delete().uri("/v1/videos/{id}", any).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void errors_givenAnInvalidApiKey_expectOpenAisErrorObjectOnVideoRoutesOnly() {
        String badKey = "Bearer gd_live_this_key_does_not_exist_0000000000";

        JsonNode onVideos = json.readTree(http.get().uri("/v1/videos")
                .header("Authorization", badKey)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(onVideos.get("detail").asString()).isEqualTo("invalid API key");
        assertThat(onVideos.get("error").get("message").asString()).isEqualTo("invalid API key");
        assertThat(onVideos.get("error").get("type").asString()).isEqualTo("authentication_error");
        assertThat(onVideos.get("error").get("code").isNull()).isTrue();

        // Every other route's errors are exactly as they were.
        JsonNode onJobs = json.readTree(http.get().uri("/v1/jobs")
                .header("Authorization", badKey)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(onJobs.get("detail").asString()).isEqualTo("invalid API key");
        assertThat(onJobs.has("error")).isFalse();
    }

    @Test
    void errors_givenTheGenerationsRoute_expectItsProblemsUnchanged() {
        Account me = account(100_00L);

        JsonNode problem = json.readTree(http.post().uri("/v1/videos/generations")
                .header("Authorization", me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("gpt-4o", "x", "seconds", 5))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(problem.has("error")).as("/v1/videos/generations carries no OpenAI error object").isFalse();
        assertThat(problem.get("detail").asString()).isEqualTo("model gpt-4o is not a video model");
    }

    @Test
    void generations_givenAJobMadeThere_expectsItListedAsAVideoToo() throws Exception {
        Account me = account(100_00L);
        stubArkRun("task_generations");

        JsonNode ack = json.readTree(http.post().uri("/v1/videos/generations")
                .header("Authorization", me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("seedance-2.0", "A paper boat on a pond.", "seconds", 5))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody());

        // The route answers as it always has: 202 with the job.
        assertThat(ack.get("status").asString()).isEqualTo("QUEUED");
        assertThat(ack.get("poll_url").asString()).isEqualTo("/v1/jobs/" + ack.get("job_id").asString());
        UUID id = UUID.fromString(ack.get("job_id").asString());
        awaitEnd(id);

        // A video is a job, however it was made.
        assertThat(getJson(me.bearer(), "/v1/videos/" + id).get("status").asString()).isEqualTo("completed");
    }

    // ===== Helpers ====================================================

    /** An adult account that has accepted the input terms, with this balance. */
    private record Account(UUID id, String bearer) { }

    private Account account(long balanceCents) {
        String email = "oai-video-" + UUID.randomUUID() + "@godance.test";
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

    private String apiKey(Account account) {
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "openai-video-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        return "Bearer " + key.plaintext();
    }

    /** A request body: the model, the prompt, and then any further fields in pairs. */
    private static Map<String, Object> body(String model, String prompt, Object... more) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        for (int i = 0; i < more.length; i += 2) body.put((String) more[i], more[i + 1]);
        return body;
    }

    private JsonNode createJson(Account account, Map<String, Object> body) {
        return json.readTree(http.post().uri("/v1/videos")
                .header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
    }

    private WebTestClient.ResponseSpec createForm(String bearer, MultipartBodyBuilder form) {
        return http.post().uri("/v1/videos")
                .header("Authorization", bearer)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .exchange();
    }

    private static UUID idOf(JsonNode video) {
        return UUID.fromString(video.get("id").asString());
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("data").forEach(video -> ids.add(video.get("id").asString()));
        return ids;
    }

    private JsonNode getJson(String bearer, String uri) {
        return json.readTree(http.get().uri(uri)
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
    }

    /**
     * Sends the JSON request and expects it refused with this status and
     * code (null: a problem without one), before anything happened: no
     * provider was called, no job row was written, and the balance and the
     * ledger are as they were. Every refusal carries OpenAI's error object.
     */
    private JsonNode refusedJson(Account account, Map<String, Object> body, int status, String code) {
        return refused(account, status, code, http.post().uri("/v1/videos")
                .header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body));
    }

    /** {@link #refusedJson} for a multipart form. */
    private JsonNode refusedForm(Account account, MultipartBodyBuilder form, int status, String code) {
        return refused(account, status, code, http.post().uri("/v1/videos")
                .header("Authorization", account.bearer())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build())));
    }

    private JsonNode refused(Account account, int status, String code, WebTestClient.RequestHeadersSpec<?> request) {
        long balanceBefore = ledger.authoritativeBalance(account.id());
        long ledgerBefore = ledgerCount(account.id());
        int jobsBefore = jobs.findByUserIdOrderByCreatedAtDesc(account.id()).size();
        List<String> callsBefore = providerCalls();

        JsonNode problem = json.readTree(request.exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody());

        if (code == null) {
            assertThat(problem.has("code")).as("code").isFalse();
        } else {
            assertThat(problem.get("code").asString()).isEqualTo(code);
            assertThat(problem.get("error").get("code").asString()).isEqualTo(code);
        }
        assertThat(problem.get("error").get("message").asString()).as("error.message").isNotBlank();
        assertThat(ledger.authoritativeBalance(account.id())).as("balance").isEqualTo(balanceBefore);
        assertThat(ledgerCount(account.id())).as("ledger rows").isEqualTo(ledgerBefore);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(account.id())).as("jobs").hasSize(jobsBefore);
        assertThat(providerCalls()).as("provider calls").isEqualTo(callsBefore);
        return problem;
    }

    private JsonNode contentProblem(Account account, UUID id, String query, int status) {
        return json.readTree(http.get().uri("/v1/videos/" + id + "/content" + query)
                .header("Authorization", account.bearer())
                .header(HttpHeaders.ACCEPT, "application/binary")
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody());
    }

    /** Calls a route Godance does not offer and returns the code of its 404; the SDK-readable message must be there. */
    private String unsupported(String bearer, String method, String uri) {
        WebTestClient.RequestHeadersSpec<?> request = method.equals("GET")
                ? http.get().uri(uri)
                : http.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("prompt", "x"));
        JsonNode problem = json.readTree(request.header("Authorization", bearer)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(problem.get("error").get("message").asString()).contains("is not supported");
        assertThat(problem.get("error").get("code").asString()).isEqualTo(problem.get("code").asString());
        return problem.get("code").asString();
    }

    private long ledgerCount(UUID userId) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, 1)).getTotalElements();
    }

    /**
     * A video job as a worker leaves it in this status, created this many
     * seconds ago: eight seconds of seedance-2.0, 112 cents held, 110
     * charged when DONE.
     */
    private GenerationJob videoJob(UUID owner, JobStatus status, int secondsAgo) {
        Instant created = Instant.now().minusSeconds(secondsAgo);
        GenerationJob job = new GenerationJob();
        job.setUserId(owner);
        job.setModality(Modality.VIDEO);
        job.setProvider(Provider.VOLCENGINE_ARK);
        job.setModel("seedance-2.0");
        Map<String, Object> prompt = new HashMap<>();
        prompt.put("prompt", "a paper boat on a pond");
        prompt.put("duration_seconds", 8);
        job.setPrompt(prompt);
        job.setStatus(status);
        job.setEstimatedCostCents(112L);
        job.setRequestId("vid_" + UUID.randomUUID());
        job.setCreatedAt(created);
        if (status != JobStatus.QUEUED) job.setStartedAt(created.plusSeconds(1));
        if (status == JobStatus.DONE) job.setActualCostCents(110L);
        if (status == JobStatus.FAILED) job.setErrorMessage("upstream: the provider said no");
        if (status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELLED) {
            job.setCompletedAt(created.plusSeconds(2));
        }
        return jobs.save(job);
    }

    /** Stores a file as output {@code index} of a job, where a worker would: {@code videos/<job>/<index>.<ext>}. */
    private void store(GenerationJob job, int index, byte[] bytes, String contentType, String ext) {
        String key = "videos/" + job.getId() + "/" + index + "." + ext;
        storage.upload(key, bytes, contentType);
        outputRows.save(new GenerationJobOutput(job.getId(), index, key, contentType, bytes.length, null));
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

    /** Uploads an image as the account and returns its file id. */
    private UUID upload(Account account, byte[] bytes, String filename) {
        String body = InputFixtures.upload(http, account.bearer(), bytes, filename, InputFixtures.declaredType(filename))
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private void seedUsage(UUID userId, long costCents) {
        UsageRecord record = new UsageRecord();
        record.setUserId(userId);
        record.setModality(Modality.VIDEO);
        record.setModelName("seedance-2.0");
        record.setProvider(Provider.VOLCENGINE_ARK);
        record.setInputTokens(0);
        record.setOutputTokens(0);
        record.setCostCents(costCents);
        record.setRegion(Region.LOCAL);
        record.setCacheHit(false);
        record.setFallbackUsed(false);
        record.setRequestId("seed-" + UUID.randomUUID());
        record.setRecordedAt(Instant.now());
        usage.save(record);
    }

    /**
     * Waits for a job to end. Every test that starts a job waits for it: a
     * job still running when its test ends would call the provider during
     * the next.
     */
    private GenerationJob awaitEnd(UUID jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        GenerationJob job = jobs.findById(jobId).orElseThrow();
        while (System.currentTimeMillis() < deadline
                && (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING)) {
            Thread.sleep(50);
            job = jobs.findById(jobId).orElseThrow();
        }
        assertThat(job.getStatus()).as("the job ended").isIn(JobStatus.DONE, JobStatus.FAILED);
        return job;
    }

    /** The worker's ledger writes can land just after the job reads as ended. */
    private void awaitBalance(UUID userId, long expectedCents) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && ledger.authoritativeBalance(userId) != expectedCents) {
            Thread.sleep(50);
        }
        assertThat(ledger.authoritativeBalance(userId)).isEqualTo(expectedCents);
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

    private static List<String> providerCalls() {
        List<String> calls = new ArrayList<>();
        for (WireMockServer server : new WireMockServer[] {ark, openAi}) {
            for (LoggedRequest request : server.findAll(anyRequestedFor(anyUrl()))) {
                calls.add(request.getMethod() + " " + request.getUrl());
            }
        }
        return calls;
    }

    /** Ark: the task is accepted, succeeds at its first poll, and its clip can be downloaded. */
    private static void stubArkRun(String taskId) {
        ark.stubFor(post(urlEqualTo(ARK_TASKS)).willReturn(okJson("{\"id\":\"" + taskId + "\"}")));
        ark.stubFor(get(urlPathMatching(ARK_TASKS + "/" + taskId))
                .willReturn(okJson("{\"id\":\"" + taskId + "\",\"status\":\"succeeded\","
                        + "\"content\":{\"video_url\":\"" + ark.baseUrl() + "/_test/out.mp4\"}}")));
        ark.stubFor(get(urlEqualTo("/_test/out.mp4"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4").withBody(CLIP)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }
}
