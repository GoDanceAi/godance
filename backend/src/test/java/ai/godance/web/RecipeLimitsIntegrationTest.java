package ai.godance.web;

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
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * The two limits on a user's recipes, each set small: two recipes an
 * account, three versions a recipe. Past either, saving answers 409
 * {@code recipe_limit_reached} and writes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.recipes.max-per-account=2",
        "godance.recipes.max-versions=3"})
@AutoConfigureWebTestClient
@Testcontainers
class RecipeLimitsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;

    @Test
    void create_givenAnAccountAtItsLimit_expects409RecipeLimitReachedUntilOneIsDeleted() {
        String bearer = signup();
        String first = create(bearer, "one");
        create(bearer, "two");

        post(bearer, "/v1/recipes", recipe("three"))
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("recipe_limit_reached")
                .jsonPath("$.detail").value(detail -> org.assertj.core.api.Assertions.assertThat((String) detail).contains("2 recipes"));
        http.get().uri("/v1/recipes").header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(2);

        // Deleting one makes room; the limit is the account's own.
        http.delete().uri("/v1/recipes/{id}", first).header(HttpHeaders.AUTHORIZATION, bearer).exchange().expectStatus().isNoContent();
        post(bearer, "/v1/recipes", recipe("three")).expectStatus().isCreated();
        post(signup(), "/v1/recipes", recipe("someone else's first")).expectStatus().isCreated();
    }

    @Test
    void newVersion_givenARecipeAtItsLimit_expects409RecipeLimitReachedAndNothingWritten() {
        String bearer = signup();
        String id = create(bearer, "versions");
        String path = "/v1/recipes/" + id + "/versions";
        post(bearer, path, version("A {{color}} mug, two.")).expectStatus().isCreated();
        post(bearer, path, version("A {{color}} mug, three.")).expectStatus().isCreated();

        post(bearer, path, version("A {{color}} mug, four."))
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("recipe_limit_reached")
                .jsonPath("$.detail").value(detail -> org.assertj.core.api.Assertions.assertThat((String) detail).contains("3 versions"));
        http.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(3).jsonPath("$[0].number").isEqualTo(3);

        // Saving what the latest version already holds is still not a new version, and not refused.
        post(bearer, path, version("A {{color}} mug, three.")).expectStatus().isOk().expectBody().jsonPath("$.number").isEqualTo(3);
        // The limit is the recipe's own: this one does not count against creating recipes.
        post(bearer, "/v1/recipes", recipe("another")).expectStatus().isCreated();
    }

    private String signup() {
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "recipe-limit-" + UUID.randomUUID() + "@godance.test",
                        "password", "correct-horse-battery-staple", "fullName", "Recipe Keeper"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    private static Map<String, Object> recipe(String title) {
        return Map.of("title", title, "modality", "IMAGE", "template", "A {{color}} mug.",
                "variables", List.of(Map.of("name", "color", "default", "red")), "model", "gpt-image-2");
    }

    private static Map<String, Object> version(String template) {
        return Map.of("template", template, "variables", List.of(Map.of("name", "color", "default", "red")), "model", "gpt-image-2");
    }

    private String create(String bearer, String title) {
        Map<?, ?> created = post(bearer, "/v1/recipes", recipe(title))
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return (String) created.get("id");
    }

    private WebTestClient.ResponseSpec post(String bearer, String path, Map<String, Object> body) {
        return http.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }
}
