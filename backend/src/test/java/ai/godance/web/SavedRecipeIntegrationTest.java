package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.SavedRecipeRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.SavedRecipeService;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.SavedRecipeDtos.SavedRecipeView;

/**
 * Saved gallery recipes (round 9): {@code GET /v1/saved-recipes}, and
 * {@code PUT} and {@code DELETE /v1/saved-recipes/{slug}}. A list of slugs
 * per user, for the signed-in app only, apart from the saved-generations
 * limit. Closing an account is in {@code AccountClosureIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class SavedRecipeIntegrationTest {

    private static final String LANTERNS = "paper-lantern-street-at-dusk";
    private static final String KOI = "koi-pond-in-the-rain";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired SavedRecipeRepository recipes;
    @Autowired TransactionTemplate tx;

    @Test
    void saveListAndUnsave_givenSignedInUser_expectTheListNewestFirst() {
        Signed u = signup();
        assertThat(list(u.bearer())).isEmpty();
        Instant before = Instant.now().minusSeconds(1);

        save(LANTERNS, u.bearer()).expectStatus().isNoContent().expectBody().isEmpty();
        save(KOI, u.bearer()).expectStatus().isNoContent();

        List<SavedRecipeView> saved = list(u.bearer());
        assertThat(saved).extracting(SavedRecipeView::slug).containsExactly(KOI, LANTERNS);
        assertThat(saved).allSatisfy(r -> assertThat(r.savedAt()).isAfter(before));

        unsave(KOI, u.bearer()).expectStatus().isNoContent().expectBody().isEmpty();

        assertThat(list(u.bearer())).extracting(SavedRecipeView::slug).containsExactly(LANTERNS);
    }

    @Test
    void saveAndUnsave_givenRepeats_expect204AndChangeNothing() {
        Signed u = signup();
        save(LANTERNS, u.bearer()).expectStatus().isNoContent();
        Instant savedAt = list(u.bearer()).get(0).savedAt();

        save(LANTERNS, u.bearer()).expectStatus().isNoContent();

        assertThat(list(u.bearer())).singleElement().satisfies(r -> assertThat(r.savedAt()).isEqualTo(savedAt));

        unsave(LANTERNS, u.bearer()).expectStatus().isNoContent();
        unsave(LANTERNS, u.bearer()).expectStatus().isNoContent();
        unsave("never-saved", u.bearer()).expectStatus().isNoContent();

        assertThat(list(u.bearer())).isEmpty();
    }

    @Test
    void list_givenTwoUsers_expectEachTheirOwn() {
        Signed first = signup();
        Signed second = signup();
        save(LANTERNS, first.bearer()).expectStatus().isNoContent();
        save(KOI, second.bearer()).expectStatus().isNoContent();
        save(LANTERNS, second.bearer()).expectStatus().isNoContent();

        // Un-saving one's own never touches the other's.
        unsave(LANTERNS, second.bearer()).expectStatus().isNoContent();

        assertThat(list(first.bearer())).extracting(SavedRecipeView::slug).containsExactly(LANTERNS);
        assertThat(list(second.bearer())).extracting(SavedRecipeView::slug).containsExactly(KOI);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Paper-Lantern", "has_underscore", "with.dot", "with space", "naïve",
            "a-slug-that-is-far-too-long-to-be-a-gallery-slug-because-it-runs-past-eighty-characters"})
    void saveAndUnsave_givenSlugThatIsNotAGallerySlug_expect400ProblemJson(String slug) {
        Signed u = signup();

        save(slug, u.bearer())
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        unsave(slug, u.bearer())
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(recipes.countByUserId(u.id())).isZero();
    }

    @Test
    void save_givenTheCapReached_expects409UntilOneIsRemoved() {
        Signed u = signup();
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < SavedRecipeService.MAX_SAVED; i++) {
                recipes.insertIfAbsent(u.id(), "recipe-" + i, Instant.now());
            }
        });

        save("one-too-many", u.bearer())
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(SavedRecipeService.CODE_LIMIT_REACHED);
        assertThat(recipes.countByUserId(u.id())).isEqualTo(SavedRecipeService.MAX_SAVED);
        // One already in the list still answers 204 at the cap.
        save("recipe-0", u.bearer()).expectStatus().isNoContent();

        unsave("recipe-0", u.bearer()).expectStatus().isNoContent();
        save("one-too-many", u.bearer()).expectStatus().isNoContent();
        assertThat(recipes.countByUserId(u.id())).isEqualTo(SavedRecipeService.MAX_SAVED);
    }

    @Test
    void savedRecipes_givenAny_areNotCountedAsSavedGenerations() {
        Signed u = signup();
        save(LANTERNS, u.bearer()).expectStatus().isNoContent();
        save(KOI, u.bearer()).expectStatus().isNoContent();

        http.get().uri("/v1/jobs/saved").header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.count").isEqualTo(0);
    }

    @Test
    void savedRecipes_givenApiKey_expect403() {
        Signed u = signup();
        String key = "Bearer " + http.post().uri("/v1/keys")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "recipes"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody().plaintext();

        http.get().uri("/v1/saved-recipes").header("Authorization", key).exchange().expectStatus().isForbidden();
        save(LANTERNS, key).expectStatus().isForbidden();
        unsave(LANTERNS, key).expectStatus().isForbidden();
        assertThat(recipes.countByUserId(u.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void savedRecipes_givenNoCredentials_expect401(String method) {
        String uri = "GET".equals(method) ? "/v1/saved-recipes" : "/v1/saved-recipes/" + LANTERNS;

        http.method(HttpMethod.valueOf(method)).uri(uri).exchange().expectStatus().isUnauthorized();
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signup() {
        String email = "recipes-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Recipe Saver"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return new Signed(users.findByEmail(email).orElseThrow().getId(), "Bearer " + resp.accessToken());
    }

    private WebTestClient.ResponseSpec save(String slug, String bearer) {
        return http.put().uri("/v1/saved-recipes/{slug}", slug).header("Authorization", bearer).exchange();
    }

    private WebTestClient.ResponseSpec unsave(String slug, String bearer) {
        return http.delete().uri("/v1/saved-recipes/{slug}", slug).header("Authorization", bearer).exchange();
    }

    private List<SavedRecipeView> list(String bearer) {
        return http.get().uri("/v1/saved-recipes")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<SavedRecipeView>>() { })
                .returnResult().getResponseBody();
    }
}
