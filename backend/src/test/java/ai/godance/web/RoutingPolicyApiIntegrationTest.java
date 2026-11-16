package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.routing.RoutingPolicyEntity;
import ai.godance.routing.RoutingPolicyRepository;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * {@code /v1/routing/policies} (round 4's Phase 3, round 14 brief 04) with
 * user policies on: create, read, replace and delete, and every refusal of
 * the validator (design §6), in one 400 that lists them all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RoutingPolicyApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.routing.user-policies.enabled", () -> "true");
        r.add("godance.routing.user-policies.max-per-user", () -> "3");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired RoutingPolicyRepository policies;

    /** A valid policy: code goes to gpt-4o first, everything else to deepseek-v4-flash. */
    private static Map<String, Object> coder() {
        return Map.of(
                "default_model", "deepseek-v4-flash",
                "rules", List.of(Map.of(
                        "name", "code",
                        "when", Map.of("task_in", List.of("code")),
                        "targets", List.of("gpt-4o", "deepseek-v4-pro"))),
                "limits", Map.of("excluded_providers", List.of("zhipu")));
    }

    // ===== Create, read, replace, delete ================================

    @Test
    void put_givenAValidPolicy_expects201AndItIsReadableAndListedAfterThePresets() {
        Signed u = signUp();

        put(u, "my-coder", coder()).expectStatus().isCreated().expectBody()
                .jsonPath("$.id").isEqualTo("my-coder")
                .jsonPath("$.built_in").isEqualTo(false)
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.modality").isEqualTo("TEXT")
                .jsonPath("$.default_model").isEqualTo("deepseek-v4-flash")
                // Normalized: tasks and providers in their one spelling, defaults filled in.
                .jsonPath("$.rules[0].when.task_in[0]").isEqualTo("CODE")
                .jsonPath("$.rules[0].targets[0]").isEqualTo("gpt-4o")
                .jsonPath("$.limits.max_candidates").isEqualTo(4)
                .jsonPath("$.limits.excluded_providers[0]").isEqualTo("ZHIPU")
                .jsonPath("$.fallbacks.length()").isEqualTo(0)
                .jsonPath("$.created_at").isNotEmpty();

        get(u, "/v1/routing/policies/my-coder").expectStatus().isOk().expectBody()
                .jsonPath("$.id").isEqualTo("my-coder")
                .jsonPath("$.rules[0].name").isEqualTo("code");

        get(u, "/v1/routing/policies").expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(4)
                .jsonPath("$.data[0].id").isEqualTo("godance-auto")
                .jsonPath("$.data[0].built_in").isEqualTo(true)
                .jsonPath("$.data[1].id").isEqualTo("godance-fast")
                .jsonPath("$.data[2].id").isEqualTo("godance-quality")
                .jsonPath("$.data[3].id").isEqualTo("my-coder")
                .jsonPath("$.data[3].built_in").isEqualTo(false);

        // Stored for its owner under usr_<user>:<name>, the id usage records name.
        assertThat(policies.findById(RoutingPolicyEntity.idFor(u.id(), "my-coder"))).isPresent();
    }

    @Test
    void put_givenAnIdThatExists_expects200AndTheNewPolicy() {
        Signed u = signUp();
        put(u, "swap", coder()).expectStatus().isCreated();

        put(u, "swap", Map.of("default_model", "gpt-4o-mini", "enabled", false)).expectStatus().isOk().expectBody()
                .jsonPath("$.default_model").isEqualTo("gpt-4o-mini")
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.rules.length()").isEqualTo(0);
    }

    @Test
    void delete_givenOwnPolicy_expects204AndThenNotFound() {
        Signed u = signUp();
        put(u, "short-lived", coder()).expectStatus().isCreated();

        delete(u, "short-lived").expectStatus().isNoContent();

        get(u, "/v1/routing/policies/short-lived").expectStatus().isNotFound().expectBody()
                .jsonPath("$.code").isEqualTo("routing_policy_not_found");
        delete(u, "short-lived").expectStatus().isNotFound();
    }

    @Test
    void delete_givenAPreset_expects404AndThePresetStays() {
        Signed u = signUp();

        delete(u, "godance-auto").expectStatus().isNotFound().expectBody()
                .jsonPath("$.code").isEqualTo("routing_policy_not_found");
        get(u, "/v1/routing/policies/godance-auto").expectStatus().isOk().expectBody()
                .jsonPath("$.built_in").isEqualTo(true)
                .jsonPath("$.rules[0].name").isEqualTo("hard-task");
    }

    @Test
    void get_givenAnotherAccountsPolicy_expects404() {
        Signed owner = signUp();
        Signed other = signUp();
        put(owner, "private-one", coder()).expectStatus().isCreated();

        get(other, "/v1/routing/policies/private-one").expectStatus().isNotFound();
        get(other, "/v1/routing/policies").expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(3);
    }

    @Test
    void put_givenAnApiKey_expectsTheSameAsASession() {
        Signed u = signUp();
        String key = "Bearer " + http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "routing"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody().plaintext();

        http.put().uri("/v1/routing/policies/from-a-script")
                .header(HttpHeaders.AUTHORIZATION, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(coder())
                .exchange()
                .expectStatus().isCreated();
        get(u, "/v1/routing/policies/from-a-script").expectStatus().isOk();
    }

    @Test
    void put_givenTheAccountsLastAllowedPolicy_expects409ForTheNext() {
        Signed u = signUp();
        for (String name : List.of("one", "two", "three")) put(u, name, coder()).expectStatus().isCreated();

        put(u, "four", coder()).expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("routing_policy_limit_reached");
        // Replacing one is not a new one.
        put(u, "two", coder()).expectStatus().isOk();
    }

    // ===== The validator ================================================

    @Test
    void put_givenEveryKindOfMistake_expects400ListingEachProblem() {
        Signed u = signUp();
        Map<String, Object> bad = Map.of(
                "modality", "IMAGE",
                "default_model", "no-such-model",
                "rules", List.of(
                        Map.of("name", "pics", "targets", List.of("gpt-image-2")),
                        Map.of("name", "pics", "when", Map.of("task_in", List.of("POETRY"),
                                "min_input_tokens", 500, "max_input_tokens", 100), "targets", List.of()),
                        Map.of("name", "default", "targets", List.of("deepseek-v4-flash"))),
                "limits", Map.of("max_candidates", 9, "allowed_providers", List.of("OPENAI", "ACME"),
                        "excluded_providers", List.of("OPENAI")));

        put(u, "broken", bad).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("routing_policy_invalid")
                .jsonPath("$.errors").value(errors -> assertThat(errors).asList()
                        .anySatisfy(e -> assertThat(e).asString().contains("modality must be TEXT"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'no-such-model' is not a model"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'gpt-image-2' is a IMAGE model"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'pics' is used by another rule"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'POETRY' is not a task"))
                        .anySatisfy(e -> assertThat(e).asString().contains("never matches"))
                        .anySatisfy(e -> assertThat(e).asString().contains("must name at least one model"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'default' is reserved"))
                        .anySatisfy(e -> assertThat(e).asString().contains("max_candidates must be between 1 and 6"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'ACME' is not a provider"))
                        .anySatisfy(e -> assertThat(e).asString().contains("OPENAI is both allowed and excluded"))
                        // deepseek is outside allowed_providers: a target the policy itself would drop.
                        .anySatisfy(e -> assertThat(e).asString().contains("which this policy does not allow")));

        assertThat(policies.findById(RoutingPolicyEntity.idFor(u.id(), "broken"))).isEmpty();
    }

    @Test
    void put_givenNoDefaultModel_expects400() {
        Signed u = signUp();
        put(u, "no-default", Map.of("rules", List.of())).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors[0]").isEqualTo("default_model is required");
    }

    @Test
    void put_givenMoreRulesThanTheCap_expects400() {
        Signed u = signUp();
        List<Map<String, Object>> rules = new java.util.ArrayList<>();
        for (int i = 0; i < 17; i++) rules.add(Map.of("name", "r" + i, "targets", List.of("gpt-4o")));

        put(u, "many", Map.of("default_model", "gpt-4o", "rules", rules)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0]").isEqualTo("a policy has at most 16 rules; this one has 17");
    }

    @Test
    void put_givenMoreTargetsThanMaxCandidates_expects400() {
        Signed u = signUp();
        put(u, "wide", Map.of("default_model", "gpt-4o",
                "limits", Map.of("max_candidates", 2),
                "rules", List.of(Map.of("name", "all", "targets",
                        List.of("gpt-4o", "gpt-4o-mini", "deepseek-v4-flash")))))
                .expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors[0]").isEqualTo("rules[0].targets has 3 models; limits.max_candidates allows 2");
    }

    @Test
    void put_givenAPresetsOrAModelsName_expects400() {
        Signed u = signUp();
        put(u, "godance-auto", coder()).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors[0]").isEqualTo("'godance-auto' is reserved for Godance's built-in policies");
        put(u, "godance-mine", coder()).expectStatus().isBadRequest();
        put(u, "gpt-4o", coder()).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors[0]").isEqualTo("'gpt-4o' is a model; a policy needs a name of its own");
        put(u, "Has Spaces", coder()).expectStatus().isBadRequest();
    }

    @Test
    void put_givenAnExcludedProvidersModelAsATarget_expects400() {
        Signed u = signUp();
        put(u, "self-contradicting", Map.of("default_model", "deepseek-v4-flash",
                "rules", List.of(Map.of("name", "code", "targets", List.of("gpt-4o"))),
                "limits", Map.of("excluded_providers", List.of("OPENAI"))))
                .expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors[0]").isEqualTo(
                        "rules[0].targets: 'gpt-4o' is from OPENAI, which this policy excludes");
    }

    // ===== Fallbacks ====================================================

    @Test
    void put_givenValidFallbacks_expectsThemStoredNormalized() {
        Signed u = signUp();
        Map<String, Object> body = Map.of("default_model", "gpt-4o-mini", "fallbacks", List.of(Map.of(
                "primary", "gpt-image-2",
                "alternatives", List.of(
                        Map.of("model", "gpt-image-1.5", "on", List.of("PROVIDER_ERROR", "timeout")),
                        Map.of("model", "flux-2-pro", "on", List.of("health_below"), "health_below", 0.5)))));

        put(u, "images", body).expectStatus().isCreated().expectBody()
                .jsonPath("$.fallbacks[0].primary").isEqualTo("gpt-image-2")
                .jsonPath("$.fallbacks[0].alternatives[0].on[0]").isEqualTo("provider_error")
                .jsonPath("$.fallbacks[0].alternatives[1].health_below").isEqualTo(0.5);
    }

    @Test
    void put_givenAContentRefusalTriggerOrAMixedModality_expects400() {
        Signed u = signUp();
        Map<String, Object> body = Map.of("default_model", "gpt-4o-mini", "fallbacks", List.of(
                Map.of("primary", "gpt-image-2", "alternatives", List.of(
                        Map.of("model", "gpt-image-1.5", "on", List.of("content_refusal")),
                        Map.of("model", "veo-3.1", "on", List.of("timeout")),
                        Map.of("model", "flux-2-pro", "on", List.of("health_below")))),
                Map.of("primary", "gpt-4o", "alternatives", List.of(
                        Map.of("model", "gpt-4o-mini", "on", List.of("timeout"))))));

        put(u, "bad-fallbacks", body).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors").value(errors -> assertThat(errors).asList()
                        .anySatisfy(e -> assertThat(e).asString()
                                .contains("'content_refusal' is not one of provider_error, timeout, health_below"))
                        .anySatisfy(e -> assertThat(e).asString().contains("'veo-3.1' is a VIDEO model"))
                        .anySatisfy(e -> assertThat(e).asString().contains("health_below must be more than 0"))
                        .anySatisfy(e -> assertThat(e).asString()
                                .contains("'gpt-4o' is a TEXT model; fallbacks are for image and video models")));
    }

    @Test
    void put_givenAPrimaryAnotherOfYourPoliciesCovers_expects400() {
        Signed u = signUp();
        Map<String, Object> body = Map.of("default_model", "gpt-4o-mini", "fallbacks", List.of(Map.of(
                "primary", "gpt-image-2",
                "alternatives", List.of(Map.of("model", "gpt-image-1.5", "on", List.of("timeout"))))));
        put(u, "first", body).expectStatus().isCreated();

        put(u, "second", body).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.errors[0]").isEqualTo(
                        "fallbacks[0].primary: 'gpt-image-2' already has fallbacks in your policy 'first'");
        // The policy that has them may still be replaced.
        put(u, "first", body).expectStatus().isOk();
    }

    // ===== Helpers ======================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signUp() {
        String email = "policies-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Policy Writer"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return new Signed(users.findByEmail(email).orElseThrow().getId(), "Bearer " + resp.accessToken());
    }

    private WebTestClient.ResponseSpec put(Signed u, String id, Map<String, Object> body) {
        return http.put().uri("/v1/routing/policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec get(Signed u, String path) {
        return http.get().uri(path).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange();
    }

    private WebTestClient.ResponseSpec delete(Signed u, String id) {
        return http.delete().uri("/v1/routing/policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange();
    }
}
