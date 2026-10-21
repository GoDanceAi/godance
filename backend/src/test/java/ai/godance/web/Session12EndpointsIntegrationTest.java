package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.config.SecurityProperties;
import ai.godance.service.ProviderHealthAggregator;
import ai.godance.web.UsageController.UsageView;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ConversationDtos.AppendMessageRequest;
import ai.godance.web.dto.ConversationDtos.ConversationView;
import ai.godance.web.dto.ConversationDtos.CreateConversationRequest;
import ai.godance.web.dto.ConversationDtos.MessageView;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * End-to-end coverage of the surfaces added in Session 12:
 *   /v1/models, /v1/status, /v1/usage, /v1/jobs, /v1/conversations,
 *   and the CORS preflight policy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class Session12EndpointsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired GenerationJobRepository jobs;
    @Autowired ProviderHealthAggregator aggregator;
    @Autowired SecurityProperties securityProps;
    @Autowired org.springframework.web.cors.reactive.CorsConfigurationSource corsSource;

    // Bean-level coverage: the CORS source resolves the right config for an
    // /v1/** request from an allowed dev origin, and allows it. WebTestClient
    // doesn't reliably drive the CORS filter chain end-to-end under MOCK env,
    // so browser-level CORS gets validated in the smoke step instead.
    @Test
    void cors_configurationAllowsLocalDevOrigins() {
        var req = org.springframework.mock.http.server.reactive.MockServerHttpRequest
                .get("/v1/models")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173").build();
        var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(req);
        var cfg = corsSource.getCorsConfiguration(exchange);
        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowedOrigins()).contains("http://localhost:5173", "http://localhost:4173");
        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cfg.checkOrigin("https://evil.example.com")).isNull();
        assertThat(securityProps.corsAllowedOrigins()).contains("http://localhost:5173");
    }

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    private AuthResponse signup(String email) {
        return http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Session 12 Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
    }

    // ---------- /v1/models (public) -----------------------------------

    @Test
    void models_givenNoAuth_returnsOpenAIShapedCatalog() {
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class)
                .returnResult().getResponseBody();

        assertThat(body.object()).isEqualTo("list");
        assertThat(body.data()).isNotEmpty();
        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("gpt-4o-mini");
            assertThat(m.object()).isEqualTo("model");
            assertThat(m.ownedBy()).isEqualTo("openai");
            assertThat(m.godance()).containsEntry("modality", "TEXT");
            assertThat(m.godance()).containsKey("pricing");
        });
    }

    @Test
    void models_includesNewImageAndVideoEntries_withVerifiedPricing() {
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class)
                .returnResult().getResponseBody();

        // gpt-image-1.5 — separate image entry at 40,000 microcents/img
        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("gpt-image-1.5");
            assertThat(m.godance()).containsEntry("modality", "IMAGE");
            assertThat(perImageMicrocents(m)).isEqualTo(40_000L);
        });
        // gpt-image-2 — re-priced to 53,000 microcents/img
        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("gpt-image-2");
            assertThat(perImageMicrocents(m)).isEqualTo(53_000L);
        });
        // sora-2-pro-hd — separate video entry at 500,000 microcents/s (1024p HD)
        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("sora-2-pro-hd");
            assertThat(m.godance()).containsEntry("modality", "VIDEO");
            assertThat(perSecondMicrocents(m)).isEqualTo(500_000L);
        });
        // sora-2-pro — unchanged at 300,000 microcents/s (720p)
        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("sora-2-pro");
            assertThat(perSecondMicrocents(m)).isEqualTo(300_000L);
        });
    }

    @SuppressWarnings("unchecked")
    private static long perImageMicrocents(ModelsController.ModelEntry m) {
        Map<String, Object> pricing = (Map<String, Object>) m.godance().get("pricing");
        return ((Number) pricing.get("per_image_microcents")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static long perSecondMicrocents(ModelsController.ModelEntry m) {
        Map<String, Object> pricing = (Map<String, Object>) m.godance().get("pricing");
        return ((Number) pricing.get("per_second_microcents")).longValue();
    }

    // ---------- /v1/status (public) -----------------------------------

    @Test
    void status_givenAggregatedRow_returnsLatestPerProviderModel() {
        // Seed a UsageRecord owned by a fresh user, then run the fold by hand.
        User u = users.findByEmail(signupEmail()).orElseThrow();
        seedUsage(u.getId(), Provider.OPENAI, "gpt-4o-mini");

        Instant end   = floor5min(Instant.now()).plus(5, ChronoUnit.MINUTES);
        Instant start = end.minus(5, ChronoUnit.MINUTES);
        aggregator.fold(start, end);

        StatusController.StatusResponse body = http.get().uri("/v1/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusController.StatusResponse.class)
                .returnResult().getResponseBody();

        assertThat(body.providers()).anySatisfy(p -> {
            assertThat(p.provider()).isEqualTo(Provider.OPENAI);
            assertThat(p.model()).isEqualTo("gpt-4o-mini");
            assertThat(p.requestCount()).isGreaterThanOrEqualTo(1);
            assertThat(p.state()).isEqualTo("ok");
        });
    }

    private String signupEmail() {
        String email = uniqueEmail("status-seed");
        signup(email);
        return email;
    }

    // ---------- /v1/usage (auth) --------------------------------------

    @Test
    void usage_givenSeededRecords_returnsCallerOwnedOnly_newestFirst() {
        String emailA = uniqueEmail("usage-a");
        String bearerA = "Bearer " + signup(emailA).accessToken();
        UUID idA = users.findByEmail(emailA).orElseThrow().getId();

        String emailB = uniqueEmail("usage-b");
        signup(emailB);
        UUID idB = users.findByEmail(emailB).orElseThrow().getId();

        seedUsage(idA, Provider.OPENAI, "gpt-4o-mini");
        seedUsage(idA, Provider.ANTHROPIC, "claude-3-5-haiku-20241022");
        seedUsage(idB, Provider.OPENAI, "gpt-4o");  // must NOT appear for A

        List<UsageView> rows = http.get().uri("/v1/usage?limit=10")
                .header("Authorization", bearerA)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UsageView.class).returnResult().getResponseBody();

        assertThat(rows).hasSize(2);
        // Newest first by recordedAt.
        assertThat(rows.get(0).recordedAt())
                .isAfterOrEqualTo(rows.get(1).recordedAt());
        assertThat(rows).allSatisfy(r -> assertThat(r.model())
                .isIn("gpt-4o-mini", "claude-3-5-haiku-20241022"));
    }

    @Test
    void usage_givenNoAuth_returns401() {
        http.get().uri("/v1/usage")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- /v1/jobs (auth) ---------------------------------------

    @Test
    void jobs_list_returnsCallerOwnedOnly() {
        String emailA = uniqueEmail("jobs-a");
        String bearerA = "Bearer " + signup(emailA).accessToken();
        UUID idA = users.findByEmail(emailA).orElseThrow().getId();

        String emailB = uniqueEmail("jobs-b");
        signup(emailB);
        UUID idB = users.findByEmail(emailB).orElseThrow().getId();

        UUID jobAId = seedJob(idA).getId();
        seedJob(idB);

        List<JobView> rows = http.get().uri("/v1/jobs")
                .header("Authorization", bearerA)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(JobView.class).returnResult().getResponseBody();

        assertThat(rows).singleElement().satisfies(j ->
                assertThat(j.id()).isEqualTo(jobAId));
    }

    // ---------- /v1/conversations (auth) ------------------------------

    @Test
    void conversations_fullLifecycle_create_append_list_archive() {
        String bearer = "Bearer " + signup(uniqueEmail("convo")).accessToken();

        // Create
        ConversationView created = http.post().uri("/v1/conversations")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateConversationRequest("Trip planning"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ConversationView.class).returnResult().getResponseBody();
        assertThat(created.title()).isEqualTo("Trip planning");

        // Append a message
        MessageView msg = http.post().uri("/v1/conversations/{id}/messages", created.id())
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AppendMessageRequest("user", "Where should I fly?",
                        "gpt-4o-mini", 12, 0, 1L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(MessageView.class).returnResult().getResponseBody();
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.content()).isEqualTo("Where should I fly?");

        // List messages
        List<MessageView> msgs = http.get()
                .uri("/v1/conversations/{id}/messages", created.id())
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(MessageView.class).returnResult().getResponseBody();
        assertThat(msgs).hasSize(1);

        // List conversations
        List<ConversationView> listed = http.get().uri("/v1/conversations")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ConversationView.class).returnResult().getResponseBody();
        assertThat(listed).singleElement()
                .extracting(ConversationView::id).isEqualTo(created.id());

        // Archive
        http.delete().uri("/v1/conversations/{id}", created.id())
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isNoContent();

        // List again — empty (archived hidden)
        http.get().uri("/v1/conversations")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ConversationView.class).hasSize(0);
    }

    @Test
    void conversations_crossUserRead_returns404_doesNotLeak() {
        String bearerA = "Bearer " + signup(uniqueEmail("convo-a")).accessToken();
        String bearerB = "Bearer " + signup(uniqueEmail("convo-b")).accessToken();

        ConversationView aConvo = http.post().uri("/v1/conversations")
                .header("Authorization", bearerA)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateConversationRequest("secret"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ConversationView.class).returnResult().getResponseBody();

        http.get().uri("/v1/conversations/{id}", aConvo.id())
                .header("Authorization", bearerB)
                .exchange()
                .expectStatus().isNotFound();   // not 403 — leak-prevention pattern
    }

    // ---------- helpers ------------------------------------------------

    private void seedUsage(UUID userId, Provider provider, String model) {
        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setModality(Modality.TEXT);
        r.setModelName(model);
        r.setProvider(provider);
        r.setInputTokens(100);
        r.setOutputTokens(50);
        r.setCostCents(5);
        r.setProviderCostCents(4L);
        r.setRegion(Region.LOCAL);
        r.setRequestId("req-" + UUID.randomUUID());
        usage.save(r);
    }

    private GenerationJob seedJob(UUID userId) {
        GenerationJob j = new GenerationJob();
        j.setUserId(userId);
        j.setModality(Modality.IMAGE);
        j.setProvider(Provider.OPENAI);
        j.setModel("gpt-image-1");
        j.setPrompt(Map.of("text", "a tiger"));
        j.setStatus(JobStatus.QUEUED);
        j.setEstimatedCostCents(40L);
        return jobs.save(j);
    }

    private static Instant floor5min(Instant t) {
        long ms = t.toEpochMilli();
        long step = java.time.Duration.ofMinutes(5).toMillis();
        return Instant.ofEpochMilli(ms - (ms % step));
    }
}
