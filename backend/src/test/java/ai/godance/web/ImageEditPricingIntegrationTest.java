package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
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

import ai.godance.domain.AgeStatus;
import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.Modality;
import ai.godance.domain.PriceField;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.domain.Provider;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.repository.ModelPriceHistoryRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.GenerationQuotes;
import ai.godance.service.admin.AdminCatalogService;
import ai.godance.service.pricing.ProposalApprovalService;
import ai.godance.web.dto.ImageDtos.JobView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The price of an edit's input images: in the estimate, in the pre-debit and
 * in the charge, the same figure each time, with and without inputs, for
 * every model V33 seeds. And the two ways the price is seen and set: the
 * model list and the admin catalog show it, and a pricing proposal sets it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class ImageEditPricingIntegrationTest {

    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    /** The models V33 says take input images. */
    static final List<String> SEEDED = List.of("gpt-image-2", "gpt-image-1.5", "seedream-5.0", "flux-2-pro");

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
    @Autowired GenerationJobRepository jobs;
    @Autowired UsageRecordRepository usage;
    @Autowired GenerationQuotes quotes;
    @Autowired ModelCatalog catalog;
    @Autowired ModelCatalogEntryRepository catalogRows;
    @Autowired ModelChangeProposalRepository proposals;
    @Autowired ModelPriceHistoryRepository priceHistory;
    @Autowired ProposalApprovalService approval;
    @Autowired AdminCatalogService adminCatalog;
    @Autowired ObjectMapper json;

    @BeforeEach
    void stubEveryProvider() {
        for (WireMockServer server : new WireMockServer[] {openAi, ark, bfl}) server.resetAll();
        String images = "{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(TINY_PNG) + "\"}]}";
        for (String path : List.of("/v1/images/edits", "/v1/images/generations")) {
            openAi.stubFor(post(urlEqualTo(path)).willReturn(okJson(images)));
        }
        ark.stubFor(post(urlEqualTo("/api/v3/images/generations")).willReturn(okJson(images)));
        bfl.stubFor(post(urlEqualTo("/v1/flux-2-pro")).willReturn(okJson("{\"id\":\"flux_job\"}")));
        bfl.stubFor(get(urlPathEqualTo("/v1/get_result")).willReturn(okJson(
                "{\"id\":\"flux_job\",\"status\":\"Ready\",\"result\":{\"sample\":\"" + bfl.baseUrl() + "/_test/sample.jpg\"}}")));
        bfl.stubFor(get(urlEqualTo("/_test/sample.jpg")).willReturn(
                aResponse().withStatus(200).withHeader("content-type", "image/jpeg").withBody(InputFixtures.image("plain.jpg"))));
    }

    @Test
    void estimate_givenEverySeededModel_expectsTheFigureThePreDebitTakesWithAndWithoutInputs() throws Exception {
        Account me = account(1000_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        for (String model : SEEDED) {
            // Without inputs: the estimate is the pre-debit of a generation, as it always was.
            long plain = estimate(me, model, null);
            assertThat(plain).as(model).isEqualTo(quotes.image(model, null).costCents());
            UUID generation = jobIdOf(http.post().uri("/v1/images/generations")
                    .header(HttpHeaders.AUTHORIZATION, me.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("model", model, "prompt", "A lighthouse."))
                    .exchange().expectStatus().isAccepted().expectBody(String.class).returnResult().getResponseBody());
            assertThat(jobs.findById(generation).orElseThrow().getEstimatedCostCents()).as(model).isEqualTo(plain);
            assertThat(pollJob(me, generation).actualCostCents()).as(model).isEqualTo(plain);

            // With inputs: the estimate for that many input images is what the edit holds, and what it is charged.
            for (int inputs : List.of(1, 3)) {
                long withInputs = estimate(me, model, inputs);
                assertThat(withInputs).as(model + " with " + inputs).isEqualTo(quotes.image(model, null, inputs).costCents());
                long before = balance(me);
                List<Object> refs = new ArrayList<>();
                for (int i = 0; i < inputs; i++) refs.add(Map.of("file_id", image.toString()));
                UUID edit = jobIdOf(http.post().uri("/v1/images/edits")
                        .header(HttpHeaders.AUTHORIZATION, me.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("model", model, "prompt", "The same, at dusk.", "images", refs))
                        .exchange().expectStatus().isAccepted().expectBody(String.class).returnResult().getResponseBody());
                assertThat(jobs.findById(edit).orElseThrow().getEstimatedCostCents()).as(model + " with " + inputs).isEqualTo(withInputs);
                assertThat(balance(me)).as("held up front").isEqualTo(before - withInputs);
                JobView done = pollJob(me, edit);
                assertThat(done.status()).as(model).isEqualTo(JobStatus.DONE);
                assertThat(done.actualCostCents()).as(model + " with " + inputs).isEqualTo(withInputs);
                assertThat(balance(me)).as("charged").isEqualTo(before - withInputs);
            }
        }
    }

    @Test
    void estimate_givenFlux_expectsTheDocumentedReferenceImagePriceOnTop() {
        Account me = account(100_00L);

        // flux-2-pro: 30 000 µ¢ an image, 60 000 µ¢ an input image, 12% on the sum.
        assertThat(estimate(me, "flux-2-pro", null)).isEqualTo(3L);     //  33 600 µ¢
        assertThat(estimate(me, "flux-2-pro", 0)).isEqualTo(3L);
        assertThat(estimate(me, "flux-2-pro", 1)).isEqualTo(10L);       // 100 800 µ¢
        assertThat(estimate(me, "flux-2-pro", 2)).isEqualTo(17L);       // 168 000 µ¢
        assertThat(estimate(me, "flux-2-pro", 8)).isEqualTo(57L);       // 571 200 µ¢
        // A model whose provider charges nothing for inputs, or publishes no per-image figure: inputs add nothing.
        for (String model : List.of("gpt-image-2", "gpt-image-1.5", "seedream-5.0")) {
            assertThat(estimate(me, model, 4)).as(model).isEqualTo(estimate(me, model, null));
        }

        // The count is echoed when it was sent, and absent when it was not.
        estimateSpec(me, Map.of("model", "flux-2-pro", "input_images", 2)).expectStatus().isOk()
                .expectBody().jsonPath("$.input_images").isEqualTo(2).jsonPath("$.estimated_cost_cents").isEqualTo(17);
        estimateSpec(me, Map.of("model", "flux-2-pro")).expectStatus().isOk()
                .expectBody().jsonPath("$.input_images").doesNotExist();
        // A video model charges nothing for its inputs, so the count changes nothing there.
        assertThat(estimate(me, "seedance-2.0", 3)).isEqualTo(estimate(me, "seedance-2.0", null));
        estimateSpec(me, Map.of("model", "flux-2-pro", "input_images", -1)).expectStatus().isBadRequest();
    }

    @Test
    void edit_givenFluxWithInputs_expectsTheUsageRowToCarryWhatTheProviderBills() throws Exception {
        Account me = account(100_00L);
        UUID image = upload(me, InputFixtures.image("plain.png"), "a.png");

        UUID edit = jobIdOf(http.post().uri("/v1/images/edits")
                .header(HttpHeaders.AUTHORIZATION, me.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "flux-2-pro", "prompt", "Together.",
                        "images", List.of(Map.of("file_id", image.toString()), Map.of("file_id", image.toString()))))
                .exchange().expectStatus().isAccepted().expectBody(String.class).returnResult().getResponseBody());
        assertThat(pollJob(me, edit).status()).isEqualTo(JobStatus.DONE);

        UsageRecord row = usage.findAll().stream().filter(r -> me.id().equals(r.getUserId())).findFirst().orElseThrow();
        assertThat(row.getCostCents()).isEqualTo(17L);
        // One image at 30 000 µ¢ and two input images at 60 000 µ¢ each, before the markup.
        assertThat(row.getProviderCostMicrocents()).isEqualTo(150_000L);
        assertThat(row.getProviderCostCents()).isEqualTo(15L);
    }

    @Test
    void models_givenTheSeededImageModels_expectTheirInputsAndTheInputPriceWhereThereIsOne() throws Exception {
        JsonNode models = json.readTree(http.get().uri("/v1/models").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody()).get("data");
        Map<String, JsonNode> godance = new LinkedHashMap<>();
        for (JsonNode model : models) godance.put(model.get("id").asString(), model.get("godance"));

        assertThat(godance.get("gpt-image-2").get("capabilities").get("inputs"))
                .isEqualTo(json.readTree("{\"images\":{\"max\":16,\"mask\":true}}"));
        assertThat(godance.get("gpt-image-1.5").get("capabilities").get("inputs"))
                .isEqualTo(json.readTree("{\"images\":{\"max\":16,\"mask\":true}}"));
        assertThat(godance.get("seedream-5.0").get("capabilities").get("inputs")).isEqualTo(json.readTree("{\"images\":{\"max\":14}}"));
        assertThat(godance.get("flux-2-pro").get("capabilities").get("inputs")).isEqualTo(json.readTree("{\"images\":{\"max\":8}}"));
        for (String model : List.of("flux-1.1-pro", "sdxl", "imagen-4", "nano-banana", "qwen-image-2.0", "qwen-image-2.0-pro")) {
            JsonNode capabilities = godance.get(model).get("capabilities");
            assertThat(capabilities == null || capabilities.isNull() || !capabilities.has("inputs")).as(model).isTrue();
        }

        // The input price is published where the provider charges one, and absent everywhere else.
        assertThat(godance.get("flux-2-pro").get("pricing"))
                .isEqualTo(json.readTree("{\"per_image_microcents\":30000,\"per_input_image_microcents\":60000}"));
        for (String model : List.of("gpt-image-2", "gpt-image-1.5", "seedream-5.0", "sdxl")) {
            assertThat(godance.get(model).get("pricing").has("per_input_image_microcents")).as(model).isFalse();
        }
    }

    @Test
    void proposal_givenAPerInputImagePrice_expectsItSetShownAndCharged() {
        Account me = account(100_00L);
        String model = "gpt-image-1.5";
        long before = estimate(me, model, 2);
        assertThat(view(model).perInputImageMicrocents()).isNull();
        assertThat(view("flux-2-pro").perInputImageMicrocents()).isEqualTo(60_000L);

        // The operator's way to set it: a pricing proposal on the field, approved.
        ModelChangeProposal proposal = new ModelChangeProposal();
        proposal.setType(ProposalType.PRICE_CHANGE);
        proposal.setModelName(model);
        proposal.setProvider(Provider.OPENAI);
        proposal.setModality(Modality.IMAGE);
        proposal.setPayload(Map.of("field", PriceField.PER_INPUT_IMAGE.asColumnKey(), "new_value", 20_000));
        proposal.setSource("operator");
        proposal.setDetectedAt(Instant.now());
        proposal.setStatus(ProposalStatus.PENDING);
        proposal = proposals.save(proposal);
        try {
            approval.approve(proposal.getId(), "test@godance.local");
            catalog.refresh();

            ModelCatalogEntry row = catalogRows.findByModelName(model).orElseThrow();
            assertThat(row.getPerInputImageMicrocents()).isEqualTo(20_000L);
            assertThat(view(model).perInputImageMicrocents()).isEqualTo(20_000L);
            assertThat(priceHistory.findByModelNameOrderByObservedAtDesc(model))
                    .anySatisfy(entry -> {
                        assertThat(entry.getField()).isEqualTo(PriceField.PER_INPUT_IMAGE);
                        assertThat(entry.getNewValue()).isEqualTo(20_000L);
                    });
            // gpt-image-1.5: 40 000 µ¢ an image; two inputs at 20 000 µ¢ now add 40 000 µ¢ before the 12%.
            assertThat(estimate(me, model, 2)).isEqualTo(9L).isGreaterThan(before);
            assertThat(estimate(me, model, null)).isEqualTo(4L);
        } finally {
            // The other tests here read this model as seeded: put the row back.
            ModelCatalogEntry row = catalogRows.findByModelName(model).orElseThrow();
            row.setPerInputImageMicrocents(null);
            catalogRows.save(row);
            catalog.refresh();
        }
    }

    // ===== Helpers ====================================================

    private record Account(UUID id, String bearer) { }

    private Account account(long balanceCents) {
        String email = "image-edit-pricing-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        User user = users.findByEmail(email).orElseThrow();
        user.setAgeStatus(AgeStatus.ADULT);
        user.setInputsTermsVersion(1);
        user.setInputsTermsAcceptedAt(Instant.now());
        users.save(user);
        ledger.topUp(user.getId(), balanceCents, CreditSource.PROMO, "seed-" + user.getId(), "seed-" + user.getId(), "test seed");
        return new Account(user.getId(), bearer);
    }

    private long balance(Account account) {
        return users.findById(account.id()).orElseThrow().getCreditBalanceCents();
    }

    private AdminCatalogService.CatalogModelView view(String model) {
        return adminCatalog.listModels().stream().filter(v -> model.equals(v.modelName())).findFirst().orElseThrow();
    }

    private WebTestClient.ResponseSpec estimateSpec(Account account, Map<String, Object> body) {
        return http.post().uri("/v1/estimates")
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** {@code POST /v1/estimates} for one image on this model, with this many input images, or none named. */
    private long estimate(Account account, String model, Integer inputImages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (inputImages != null) body.put("input_images", inputImages);
        String response = estimateSpec(account, body).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return json.readTree(response).get("estimated_cost_cents").asLong();
    }

    private UUID upload(Account account, byte[] bytes, String filename) {
        String body = InputFixtures.upload(http, account.bearer(), bytes, filename, InputFixtures.declaredType(filename))
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private UUID jobIdOf(String body) {
        return UUID.fromString(json.readTree(body).get("job_id").asString());
    }

    private JobView pollJob(Account account, UUID jobId) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header(HttpHeaders.AUTHORIZATION, account.bearer())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) return view;
            Thread.sleep(100);
        }
        return view;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }
}
