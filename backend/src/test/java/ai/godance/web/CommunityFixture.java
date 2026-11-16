package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * What the community tests (round 14 brief 01) share: an author who may
 * publish, a moderator, a recipe with a finished run, and the publish and
 * moderation calls, all made on the wire with the names the contract gives.
 */
final class CommunityFixture {

    /** A 1×1 transparent PNG: what the image upstream returns. */
    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    static final String PASSWORD = "correct-horse-battery-staple";

    /** An account: its id, its email, and a bearer for its session. */
    record Signed(UUID id, String email, String bearer) { }

    /** A recipe of an account's, its first version, and one finished run of it. */
    record Ran(String recipeId, String versionId, UUID jobId) { }

    private final WebTestClient http;
    private final UserRepository users;
    private final UserTwoFactorRepository twoFactors;
    private final CreditLedgerService ledger;

    CommunityFixture(WebTestClient http, UserRepository users, UserTwoFactorRepository twoFactors,
                     CreditLedgerService ledger) {
        this.http = http;
        this.users = users;
        this.twoFactors = twoFactors;
        this.ledger = ledger;
    }

    // ===== Accounts =====================================================

    /** An adult account with a verified email and credit: one that may set a profile and publish. */
    Signed author(long cents) {
        return account("author", cents, true);
    }

    /** An account with credit; {@code verified} says whether its email is verified. */
    Signed account(String label, long cents, boolean verified) {
        String email = label + "-" + UUID.randomUUID() + "@godance.test";
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Community Tester"))
                .exchange()
                .expectStatus().isCreated();
        User u = users.findByEmail(email).orElseThrow();
        u.setEmailVerified(verified);
        users.save(u);
        if (cents > 0) ledger.topUp(u.getId(), cents, CreditSource.PROMO, "seed-" + u.getId(), "seed-" + u.getId(), "test seed");
        return new Signed(u.getId(), email, logIn(email));
    }

    /** A staff account with this role and 2FA on, signed in. */
    Signed staff(UserRole role) {
        String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@godance.test";
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Community Staff"))
                .exchange()
                .expectStatus().isCreated();
        User u = users.findByEmail(email).orElseThrow();
        u.setRole(role);
        u.setEmailVerified(true);
        users.save(u);
        // Signed in by password first; with 2FA on, a sign-in would need the code.
        String bearer = logIn(email);
        TwoFactorFixture.turnOn(twoFactors, u.getId());
        return new Signed(u.getId(), email, bearer);
    }

    private String logIn(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    /** A handle no other test has used: tests share one database per class. */
    static String freshHandle(String stem) {
        return stem + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ===== Profiles =====================================================

    WebTestClient.ResponseSpec putProfile(Signed u, String handle, String displayName, String link) {
        Map<String, Object> body = new HashMap<>();
        body.put("handle", handle);
        body.put("display_name", displayName);
        body.put("link", link);
        return http.put().uri("/v1/profile")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** Sets a profile that the rules accept. */
    String profile(Signed u, String stem) {
        String handle = freshHandle(stem);
        putProfile(u, handle, "Display " + stem, "https://example.test/" + stem).expectStatus().isOk();
        return handle;
    }

    // ===== Recipes and runs =============================================

    /** An image recipe with one variable, as {@code POST /v1/recipes} makes it. */
    Map<String, Object> createRecipe(Signed u, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("modality", "image");
        body.put("template", "A {{thing}} on a wooden table, soft window light.");
        body.put("variables", List.of(Map.of("name", "thing", "default", "teapot")));
        body.put("model", "gpt-image-2");
        body.put("params", Map.of("size", "1024x1024"));
        return http.post().uri("/v1/recipes")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
    }

    /** A recipe and one finished run of its first version. The upstream must be stubbed. */
    Ran recipeWithRun(Signed u, String title) {
        Map<String, Object> recipe = createRecipe(u, title);
        @SuppressWarnings("unchecked")
        String versionId = (String) ((Map<String, Object>) recipe.get("latest_version")).get("id");
        UUID jobId = runVersion(u, versionId, Map.of("thing", "teapot"));
        return new Ran((String) recipe.get("id"), versionId, jobId);
    }

    /** One finished run of a version of the caller's own recipe. */
    UUID runVersion(Signed u, String versionId, Map<String, String> variables) {
        UUID jobId = submit(u.bearer(), Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", versionId, "variables", variables)));
        awaitStatus(jobId, u.bearer(), JobStatus.DONE);
        return jobId;
    }

    /** One finished run of a published recipe, by its public id. */
    UUID runPublished(Signed u, String publicId, Map<String, String> variables) {
        UUID jobId = submit(u.bearer(), Map.of("model", "gpt-image-2",
                "recipe", Map.of("published_id", publicId, "variables", variables)));
        awaitStatus(jobId, u.bearer(), JobStatus.DONE);
        return jobId;
    }

    UUID submit(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/images/generations")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
    }

    JobView awaitStatus(UUID jobId, String bearer, JobStatus wanted) {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header("Authorization", bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo(wanted);
        return view;
    }

    static void stubImage(WireMockServer openAiMock) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    // ===== Publishing and moderation ====================================

    static Map<String, Object> publishBody(int version, String licence, List<UUID> jobIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("licence", licence);
        body.put("job_ids", new ArrayList<>(jobIds.stream().map(UUID::toString).toList()));
        return body;
    }

    WebTestClient.ResponseSpec postPublish(Signed u, String recipeId, Map<String, Object> body) {
        return http.post().uri("/v1/recipes/{id}/publish", recipeId)
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** Publishes version 1 with these examples under CC-BY-4.0; returns the public id. */
    String publish(Signed u, Ran ran, List<UUID> jobIds) {
        Map<String, Object> view = postPublish(u, ran.recipeId(), publishBody(1, "CC-BY-4.0", jobIds))
                .expectStatus().isAccepted()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
        assertThat(view).containsEntry("status", "PENDING");
        return (String) view.get("public_id");
    }

    WebTestClient.ResponseSpec moderate(Signed staff, String publicId, String action, String reason) {
        WebTestClient.RequestBodySpec spec = http.post()
                .uri("/v1/admin/community/recipes/{id}/" + action, publicId)
                .header("Authorization", staff.bearer());
        return reason == null
                ? spec.exchange()
                : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", reason)).exchange();
    }

    void approve(Signed staff, String publicId) {
        moderate(staff, publicId, "approve", null).expectStatus().isOk();
    }
}
