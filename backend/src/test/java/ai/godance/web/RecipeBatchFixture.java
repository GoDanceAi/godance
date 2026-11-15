package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import ai.godance.domain.CreditSource;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * What every recipe batch test needs: an account with credit, a recipe, a
 * batch, and a way to run the batch runner by hand. The runner's cron never
 * fires under the test profile, so a test advances a batch one pass at a
 * time and decides what happens between passes.
 */
final class RecipeBatchFixture {

    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    /** What gpt-image-2 charges for one image in the test catalog. */
    static final long IMAGE_CENTS = 6;

    record Signed(UUID id, String bearer) { }

    record Recipe(String id, String versionId) { }

    private final WebTestClient http;
    private final UserRepository users;
    private final CreditLedgerService ledger;
    private final RecipeBatchRunner runner;

    RecipeBatchFixture(WebTestClient http, UserRepository users, CreditLedgerService ledger,
                       RecipeBatchRunner runner) {
        this.http = http;
        this.users = users;
        this.ledger = ledger;
        this.runner = runner;
    }

    Signed signupWithBalance(long cents) {
        String email = "batch-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Batch Runner"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    /** A new API key of the account: its id, and the header that authenticates with it. */
    record Key(UUID id, String bearer) { }

    Key apiKey(Signed u) {
        return apiKey(u, Map.of("name", "batch-key"));
    }

    Key apiKey(Signed u, Map<String, Object> body) {
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Key(UUID.fromString((String) key.get("id")), "Bearer " + key.get("plaintext"));
    }

    static Map<String, String> variable(String name, String fallback) {
        return Map.of("name", name, "default", fallback);
    }

    Recipe createRecipe(Signed u, String modality, String template, List<Map<String, String>> variables,
                        String model, Map<String, Object> params) {
        return createRecipe(u, modality, template, variables, model, params, List.of());
    }

    Recipe createRecipe(Signed u, String modality, String template, List<Map<String, String>> variables,
                        String model, Map<String, Object> params, List<Map<String, String>> slots) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "A batch recipe");
        body.put("modality", modality);
        body.put("template", template);
        body.put("variables", variables);
        body.put("model", model);
        body.put("params", params);
        if (!slots.isEmpty()) body.put("inputs", slots);
        Map<?, ?> created = http.post().uri("/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Recipe((String) created.get("id"), (String) ((Map<?, ?>) created.get("latest_version")).get("id"));
    }

    /** One row of a batch request: values for the recipe's variables. */
    static Map<String, Object> row(String... nameThenValue) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (int i = 0; i < nameThenValue.length; i += 2) variables.put(nameThenValue[i], nameThenValue[i + 1]);
        return Map.of("variables", variables);
    }

    /** {@code count} rows, each with {@code name} set to {@code prefix} and its index. */
    static List<Map<String, Object>> rows(int count, String name, String prefix) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) rows.add(row(name, prefix + " " + i));
        return rows;
    }

    static Map<String, Object> batchBody(String versionId, List<Map<String, Object>> rows, Integer maxConcurrency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version_id", versionId);
        body.put("rows", rows);
        if (maxConcurrency != null) body.put("max_concurrency", maxConcurrency);
        return body;
    }

    /** Creates a batch and returns its id. */
    String createBatch(String bearer, Map<String, Object> body) {
        Map<?, ?> batch = http.post().uri("/v1/recipe-batches")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) batch.get("id");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> batch(String bearer, String id) {
        return http.get().uri("/v1/recipe-batches/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> batchRows(String bearer, String id) {
        return (List<Map<String, Object>>) (List<?>) http.get().uri("/v1/recipe-batches/{id}/rows?limit=100", id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody();
    }

    /** One pass of the runner. */
    void tick() {
        runner.tick();
    }

    /**
     * Runs the runner until the batch reads {@code status}, a pass every
     * 50 ms, and returns the batch as last read. Fails after 20 seconds.
     */
    Map<String, Object> runUntil(String bearer, String id, String status) throws InterruptedException {
        Map<String, Object> view = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            runner.tick();
            view = batch(bearer, id);
            if (status.equals(view.get("status")) && settled(view, status)) return view;
            Thread.sleep(50);
        }
        assertThat(view).as("the batch never reached %s; last read: %s", status, view).isNull();
        return view;
    }

    /** A DONE or CANCELLED batch is only over once the time its last row ended is set. */
    private static boolean settled(Map<String, Object> view, String status) {
        boolean ends = "DONE".equals(status) || "CANCELLED".equals(status);
        return !ends || view.get("completed_at") != null;
    }

    /** Every image request the provider gets answers with one tiny PNG. */
    static void stubImage(WireMockServer openAiMock) {
        stubImage(openAiMock, 0);
    }

    /** {@link #stubImage(WireMockServer)}, answering after {@code delayMs}. */
    static void stubImage(WireMockServer openAiMock, int delayMs) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(delayMs)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }
}
