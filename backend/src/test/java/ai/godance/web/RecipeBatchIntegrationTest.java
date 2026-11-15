package ai.godance.web;

import static ai.godance.web.RecipeBatchFixture.IMAGE_CENTS;
import static ai.godance.web.RecipeBatchFixture.batchBody;
import static ai.godance.web.RecipeBatchFixture.row;
import static ai.godance.web.RecipeBatchFixture.stubImage;
import static ai.godance.web.RecipeBatchFixture.variable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditLedger;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.RecipeBatchFixture.Recipe;
import ai.godance.web.RecipeBatchFixture.Signed;

/**
 * Recipe batches (round 12 brief 01): one version of a recipe run over many
 * rows, each row an ordinary job. What is pinned here first is the money: a
 * batch charges nothing itself, so what its rows' jobs took from the ledger
 * is exactly what the batch says it spent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RecipeBatchIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base",  () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint",    MINIO::getS3URL);
        r.add("godance.object-storage.access-key",  MINIO::getUserName);
        r.add("godance.object-storage.secret-key",  MINIO::getPassword);
        r.add("godance.object-storage.bucket",      () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired RecipeBatchRunner runner;
    @Autowired JdbcTemplate jdbc;

    RecipeBatchFixture fx;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        fx = new RecipeBatchFixture(http, users, ledger, runner);
    }

    // ===== The opening test: three rows, one at a time ==================

    @Test
    void tick_givenThreeRowsAndMaxConcurrencyOne_expectsThreeJobsInOrderAndTheLedgerSummingToSpentCents()
            throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Recipe recipe = fx.createRecipe(u, "IMAGE", "A {{color}} mug on a shelf.",
                List.of(variable("color", "red")), "gpt-image-2", Map.of("n", 1));

        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(),
                List.of(row("color", "blue"), row("color", "green"), row("color", "yellow")), 1));

        // Creating a batch charges nothing and starts nothing.
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L);

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        // Three rows, three jobs, one job each.
        List<Map<String, Object>> rows = fx.batchRows(u.bearer(), batchId);
        assertThat(rows).extracting(r -> r.get("index")).containsExactly(0, 1, 2);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("DONE");
        List<GenerationJob> made = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            made.add(jobs.findById(UUID.fromString((String) r.get("job_id"))).orElseThrow());
        }
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(3).containsExactlyInAnyOrderElementsOf(made);

        // In order, and one at a time: a row's job ended before the next row's job was made.
        assertThat(made).extracting(j -> j.getPrompt().get("prompt")).containsExactly(
                "A blue mug on a shelf.", "A green mug on a shelf.", "A yellow mug on a shelf.");
        for (int i = 0; i < made.size() - 1; i++) {
            assertThat(made.get(i).getStatus()).isEqualTo(JobStatus.DONE);
            assertThat(made.get(i).getCompletedAt()).isBeforeOrEqualTo(made.get(i + 1).getCreatedAt());
        }

        // The money. The batch spent what its rows' jobs were charged...
        long spent = ((Number) done.get("spent_cents")).longValue();
        assertThat(spent).isEqualTo(3 * IMAGE_CENTS);
        assertThat(rows).extracting(r -> ((Number) r.get("cost_cents")).longValue()).containsOnly(IMAGE_CENTS);
        assertThat(done.get("estimated_total_cents")).isEqualTo((int) (3 * IMAGE_CENTS));

        // ...and the ledger holds those jobs' entries and nothing else: every row
        // of it after the seed belongs to one of the three jobs, and they sum to
        // exactly what the batch says it spent.
        Set<String> requestIds = made.stream().map(GenerationJob::getRequestId).collect(Collectors.toSet());
        List<CreditLedger> entries = ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 100))
                .getContent().stream().filter(e -> !e.getSourceRef().startsWith("seed-")).toList();
        assertThat(entries).isNotEmpty().allSatisfy(e -> assertThat(requestIds).contains(e.getSourceRef()));
        assertThat(entries.stream().mapToLong(CreditLedger::getDeltaCents).sum()).isEqualTo(-spent);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - spent);

        // Each job says which batch and row it was made for, and how it was made.
        assertThat(made).extracting(j -> j.getChannel().name()).containsOnly("BATCH");
        http.get().uri("/v1/jobs/{id}", made.get(1).getId())
                .header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channel").isEqualTo("BATCH")
                .jsonPath("$.batch.id").isEqualTo(batchId)
                .jsonPath("$.batch.row").isEqualTo(1);
    }
}
