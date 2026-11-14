package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * {@code /v1/recipes} (round 10 brief 03): a user's own recipes, with their
 * versions. Each route is the owner's only; another user's recipe answers
 * as one that does not exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RecipeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audit;
    @Autowired JdbcTemplate jdbc;

    // ===== Create =====================================================

    @Test
    void create_givenARecipe_expects201WithItsFirstVersion() {
        Signed u = signup();

        send(HttpMethod.POST, "/v1/recipes", u.bearer(), mug())
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.title").isEqualTo("Mug on a kitchen shelf")
                .jsonPath("$.modality").isEqualTo("IMAGE")
                .jsonPath("$.source_gallery_recipe").isEqualTo("paper-lantern-street-at-dusk")
                .jsonPath("$.created_at").exists()
                .jsonPath("$.updated_at").exists()
                .jsonPath("$.latest_version.id").exists()
                .jsonPath("$.latest_version.number").isEqualTo(1)
                .jsonPath("$.latest_version.template").isEqualTo("A {{color}} mug on a shelf.")
                .jsonPath("$.latest_version.variables.length()").isEqualTo(1)
                .jsonPath("$.latest_version.variables[0].name").isEqualTo("color")
                .jsonPath("$.latest_version.variables[0].default").isEqualTo("red")
                .jsonPath("$.latest_version.model").isEqualTo("gpt-image-2")
                .jsonPath("$.latest_version.params.size").isEqualTo("1024x1024")
                .jsonPath("$.latest_version.params.n").isEqualTo(1)
                .jsonPath("$.latest_version.created_at").exists()
                .jsonPath("$.attempts").isEqualTo(0)
                .jsonPath("$.spent_cents").isEqualTo(0);
    }

    @Test
    void create_givenNoVariablesNoParamsAndNoSource_expectsAPlainPromptKeptAsATemplate() {
        Signed u = signup();

        send(HttpMethod.POST, "/v1/recipes", u.bearer(), Map.of(
                "title", "  A plain one  ", "modality", "video", "template", "Koi under falling rain. }}", "model", "sora-2"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.title").isEqualTo("A plain one")
                .jsonPath("$.modality").isEqualTo("VIDEO")
                .jsonPath("$.latest_version.variables.length()").isEqualTo(0)
                .jsonPath("$.latest_version.params").isMap()
                // Present, and null: not left out.
                .jsonPath("$").value(body -> assertThat(((Map<?, ?>) body).containsKey("source_gallery_recipe")).isTrue())
                .jsonPath("$.source_gallery_recipe").value(source -> assertThat(source).isNull());
    }

    @Test
    void create_givenARecipe_expectsAnAuditRowAndSoDoesDeletingIt() {
        Signed u = signup();
        String id = create(u, mug());

        assertThat(audit.findByEventType(AuditLogService.EVT_RECIPE_CREATED).stream().filter(row -> u.id().equals(row.getUserId())))
                .singleElement().satisfies(row -> assertThat(row.getEventDetail()).containsEntry("recipeId", id));
        send(HttpMethod.DELETE, "/v1/recipes/" + id, u.bearer(), null).expectStatus().isNoContent();
        assertThat(audit.findByEventType(AuditLogService.EVT_RECIPE_DELETED).stream().filter(row -> u.id().equals(row.getUserId())))
                .singleElement().satisfies(row -> assertThat(row.getEventDetail())
                        .containsEntry("recipeId", id).containsEntry("title", "Mug on a kitchen shelf"));
    }

    /** [what is wrong, the field, its value]: each answers 400 invalid_recipe and writes nothing. */
    static List<Object[]> invalid() {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {"no title", "title", null});
        cases.add(new Object[] {"a blank title", "title", "   "});
        cases.add(new Object[] {"a title of 201 characters", "title", "t".repeat(201)});
        cases.add(new Object[] {"no modality", "modality", null});
        cases.add(new Object[] {"a modality that is neither", "modality", "AUDIO"});
        cases.add(new Object[] {"no template", "template", null});
        cases.add(new Object[] {"a blank template", "template", "  "});
        cases.add(new Object[] {"a template of 32,001 characters", "template", "{{color}}" + "x".repeat(31_992)});
        cases.add(new Object[] {"a stray {{", "template", "A {{color}} mug {{ on a shelf."});
        cases.add(new Object[] {"an undeclared variable", "template", "A {{color}} mug on a {{surface}}."});
        cases.add(new Object[] {"a declared variable never used", "template", "A mug on a shelf."});
        cases.add(new Object[] {"a variable name that breaks the rule", "variables", List.of(Map.of("name", "color!", "default", "red"))});
        cases.add(new Object[] {"a variable with no default", "variables", List.of(Map.of("name", "color"))});
        cases.add(new Object[] {"a default of 2,001 characters", "variables", List.of(Map.of("name", "color", "default", "r".repeat(2001)))});
        cases.add(new Object[] {"a variable declared twice", "variables",
                List.of(Map.of("name", "color", "default", "red"), Map.of("name", "color", "default", "blue"))});
        cases.add(new Object[] {"no model", "model", null});
        cases.add(new Object[] {"a model id of 129 characters", "model", "m".repeat(129)});
        cases.add(new Object[] {"params too large", "params", Map.of("note", "p".repeat(2000))});
        return cases;
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("invalid")
    void create_givenSomethingUnacceptable_expects400InvalidRecipeAndNothingWritten(String what, String field, Object value) {
        Signed u = signup();
        Map<String, Object> body = mug();
        if (value == null) body.remove(field);
        else body.put(field, value);

        send(HttpMethod.POST, "/v1/recipes", u.bearer(), body)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_recipe");

        assertThat(recipeCount(u)).isZero();
    }

    @Test
    void create_givenTwentyOneVariables_expects400AndTwentyAreFine() {
        Signed u = signup();
        Map<String, Object> body = mug();
        List<Map<String, String>> variables = new ArrayList<>();
        StringBuilder template = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            variables.add(Map.of("name", "v" + i, "default", "x"));
            template.append("{{v").append(i).append("}} ");
        }
        body.put("variables", variables);
        body.put("template", template.toString());
        send(HttpMethod.POST, "/v1/recipes", u.bearer(), body).expectStatus().isCreated();

        variables.add(Map.of("name", "v21", "default", "x"));
        body.put("template", template + "{{v21}}");
        send(HttpMethod.POST, "/v1/recipes", u.bearer(), body)
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_recipe");
    }

    @Test
    void create_givenASourceThatIsNotASlug_expects400InvalidGalleryRecipe() {
        Signed u = signup();
        Map<String, Object> body = mug();
        body.put("source_gallery_recipe", "Not A Slug");

        send(HttpMethod.POST, "/v1/recipes", u.bearer(), body)
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_gallery_recipe");
        assertThat(recipeCount(u)).isZero();
    }

    // ===== Read =======================================================

    @Test
    void list_givenSeveralRecipes_expectsABareArrayNewestFirstAndPaged() {
        Signed u = signup();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> body = mug();
            body.put("title", "Recipe " + i);
            ids.add(create(u, body));
        }

        send(HttpMethod.GET, "/v1/recipes", u.bearer(), null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(5)
                .jsonPath("$[0].title").isEqualTo("Recipe 5")
                .jsonPath("$[4].title").isEqualTo("Recipe 1")
                .jsonPath("$[0].latest_version.number").isEqualTo(1);
        send(HttpMethod.GET, "/v1/recipes?limit=2", u.bearer(), null)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(ids.get(4))
                .jsonPath("$[1].id").isEqualTo(ids.get(3));
        send(HttpMethod.GET, "/v1/recipes?limit=2&before=" + ids.get(3), u.bearer(), null)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(ids.get(2))
                .jsonPath("$[1].id").isEqualTo(ids.get(1));
        send(HttpMethod.GET, "/v1/recipes?limit=2&before=" + ids.get(0), u.bearer(), null)
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=many", "before=not-an-id"})
    void list_givenABadPage_expects400(String query) {
        Signed u = signup();

        send(HttpMethod.GET, "/v1/recipes?" + query, u.bearer(), null).expectStatus().isBadRequest();
    }

    @Test
    void list_givenAnotherUsersRecipes_expectsOnlyTheCallersOwn() {
        Signed owner = signup();
        String theirs = create(owner, mug());
        Signed other = signup();

        send(HttpMethod.GET, "/v1/recipes", other.bearer(), null)
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
        // Another user's recipe is not a cursor either.
        send(HttpMethod.GET, "/v1/recipes?before=" + theirs, other.bearer(), null).expectStatus().isBadRequest();
    }

    @Test
    void get_givenOwnRecipe_expectsIt() {
        Signed u = signup();
        String id = create(u, mug());

        send(HttpMethod.GET, "/v1/recipes/" + id, u.bearer(), null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.latest_version.template").isEqualTo("A {{color}} mug on a shelf.");
    }

    // ===== Owner only =================================================

    /** Every route that names a recipe, tried by someone who does not own it. */
    @Test
    void everyRoute_givenAnotherUsersRecipe_expects404RecipeNotFoundAndNothingChanged() {
        Signed owner = signup();
        String id = create(owner, mug());
        Signed other = signup();
        Map<String, Object> version = Map.of("template", "A {{color}} teapot.",
                "variables", List.of(Map.of("name", "color", "default", "green")), "model", "gpt-image-2");

        for (String target : List.of(id, UUID.randomUUID().toString(), "not-an-id")) {
            notFound(send(HttpMethod.GET, "/v1/recipes/" + target, other.bearer(), null));
            notFound(send(HttpMethod.PATCH, "/v1/recipes/" + target, other.bearer(), Map.of("title", "Taken over")));
            notFound(send(HttpMethod.DELETE, "/v1/recipes/" + target, other.bearer(), null));
            notFound(send(HttpMethod.GET, "/v1/recipes/" + target + "/versions", other.bearer(), null));
            notFound(send(HttpMethod.POST, "/v1/recipes/" + target + "/versions", other.bearer(), version));
        }

        // The owner's recipe is exactly as it was.
        send(HttpMethod.GET, "/v1/recipes/" + id, owner.bearer(), null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Mug on a kitchen shelf")
                .jsonPath("$.latest_version.number").isEqualTo(1);
    }

    @Test
    void everyRoute_givenAnApiKeyOfTheOwner_expectsItToWork() {
        Signed u = signup();
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "recipes-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String bearer = "Bearer " + key.get("plaintext");

        Map<?, ?> created = send(HttpMethod.POST, "/v1/recipes", bearer, mug())
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String id = (String) created.get("id");
        send(HttpMethod.GET, "/v1/recipes", bearer, null).expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);
        send(HttpMethod.GET, "/v1/recipes/" + id, bearer, null).expectStatus().isOk();
        send(HttpMethod.PATCH, "/v1/recipes/" + id, bearer, Map.of("title", "Renamed by a script"))
                .expectStatus().isOk().expectBody().jsonPath("$.title").isEqualTo("Renamed by a script");
        send(HttpMethod.GET, "/v1/recipes/" + id + "/versions", bearer, null).expectStatus().isOk();
        send(HttpMethod.POST, "/v1/recipes/" + id + "/versions", bearer, Map.of("template", "A {{color}} teapot.",
                "variables", List.of(Map.of("name", "color", "default", "green")), "model", "gpt-image-2"))
                .expectStatus().isCreated();
        send(HttpMethod.DELETE, "/v1/recipes/" + id, bearer, null).expectStatus().isNoContent();
    }

    @Test
    void everyRoute_givenNoCredentials_expects401() {
        http.get().uri("/v1/recipes").exchange().expectStatus().isUnauthorized();
        http.post().uri("/v1/recipes").contentType(MediaType.APPLICATION_JSON).bodyValue(mug()).exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/recipes/{id}/versions", UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
        http.delete().uri("/v1/recipes/{id}", UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
    }

    // ===== Rename and delete ==========================================

    @Test
    void rename_givenANewTitle_expectsItAndNoNewVersion() {
        Signed u = signup();
        String id = create(u, mug());

        send(HttpMethod.PATCH, "/v1/recipes/" + id, u.bearer(), Map.of("title", "  Mug, studio light  "))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Mug, studio light")
                .jsonPath("$.latest_version.number").isEqualTo(1);
        send(HttpMethod.GET, "/v1/recipes/" + id + "/versions", u.bearer(), null)
                .expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":\"\"}", "{\"title\":\"   \"}", "{\"title\":null}"})
    void rename_givenNoTitle_expects400AndTheTitleKept(String body) {
        Signed u = signup();
        String id = create(u, mug());

        http.patch().uri("/v1/recipes/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_recipe");
        send(HttpMethod.GET, "/v1/recipes/" + id, u.bearer(), null)
                .expectBody().jsonPath("$.title").isEqualTo("Mug on a kitchen shelf");
    }

    @Test
    void delete_givenOwnRecipe_expects204AndItAndItsVersionsGone() {
        Signed u = signup();
        String id = create(u, mug());
        String kept = create(u, mug());

        send(HttpMethod.DELETE, "/v1/recipes/" + id, u.bearer(), null).expectStatus().isNoContent().expectBody().isEmpty();

        notFound(send(HttpMethod.GET, "/v1/recipes/" + id, u.bearer(), null));
        notFound(send(HttpMethod.DELETE, "/v1/recipes/" + id, u.bearer(), null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_versions WHERE recipe_id = ?::uuid", Integer.class, id)).isZero();
        send(HttpMethod.GET, "/v1/recipes", u.bearer(), null)
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].id").isEqualTo(kept);
    }

    // ===== Versions ===================================================

    @Test
    void newVersion_givenAChange_expects201AndTheNextNumber() {
        Signed u = signup();
        String id = create(u, mug());

        send(HttpMethod.POST, "/v1/recipes/" + id + "/versions", u.bearer(), version("A {{color}} mug, studio light.", "red", "gpt-image-2", "1024x1024"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.number").isEqualTo(2)
                .jsonPath("$.template").isEqualTo("A {{color}} mug, studio light.");
        send(HttpMethod.GET, "/v1/recipes/" + id, u.bearer(), null)
                .expectBody().jsonPath("$.latest_version.number").isEqualTo(2);
        // Newest first, and the first version is exactly as it was written.
        send(HttpMethod.GET, "/v1/recipes/" + id + "/versions", u.bearer(), null)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].number").isEqualTo(2)
                .jsonPath("$[1].number").isEqualTo(1)
                .jsonPath("$[1].template").isEqualTo("A {{color}} mug on a shelf.");
    }

    @Test
    void newVersion_givenNothingChanged_expects200WithTheLatestAndNoNewVersion() {
        Signed u = signup();
        String id = create(u, mug());
        Map<?, ?> first = send(HttpMethod.GET, "/v1/recipes/" + id, u.bearer(), null)
                .expectBody(Map.class).returnResult().getResponseBody();
        String firstVersionId = (String) ((Map<?, ?>) first.get("latest_version")).get("id");

        // The same content, its params given in another order.
        Map<String, Object> same = new HashMap<>(version("A {{color}} mug on a shelf.", "red", "gpt-image-2", "1024x1024"));
        same.put("params", new java.util.TreeMap<>(Map.of("size", "1024x1024", "n", 1)).descendingMap());
        send(HttpMethod.POST, "/v1/recipes/" + id + "/versions", u.bearer(), same)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(firstVersionId)
                .jsonPath("$.number").isEqualTo(1);

        send(HttpMethod.GET, "/v1/recipes/" + id + "/versions", u.bearer(), null)
                .expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    void newVersion_givenEachKindOfChange_expectsAVersionForEach() {
        Signed u = signup();
        String id = create(u, mug());
        String path = "/v1/recipes/" + id + "/versions";

        // The template, a default, the model, a parameter: each is a change.
        send(HttpMethod.POST, path, u.bearer(), version("A {{color}} mug on a table.", "red", "gpt-image-2", "1024x1024")).expectStatus().isCreated();
        send(HttpMethod.POST, path, u.bearer(), version("A {{color}} mug on a table.", "blue", "gpt-image-2", "1024x1024")).expectStatus().isCreated();
        send(HttpMethod.POST, path, u.bearer(), version("A {{color}} mug on a table.", "blue", "seedream-5.0", "1024x1024")).expectStatus().isCreated();
        send(HttpMethod.POST, path, u.bearer(), version("A {{color}} mug on a table.", "blue", "seedream-5.0", "1536x1024"))
                .expectStatus().isCreated().expectBody().jsonPath("$.number").isEqualTo(5);
        // And going back to an earlier version's content is a change from the latest.
        send(HttpMethod.POST, path, u.bearer(), version("A {{color}} mug on a shelf.", "red", "gpt-image-2", "1024x1024"))
                .expectStatus().isCreated().expectBody().jsonPath("$.number").isEqualTo(6);
    }

    @Test
    void newVersion_givenAnInvalidTemplate_expects400AndNoNewVersion() {
        Signed u = signup();
        String id = create(u, mug());

        send(HttpMethod.POST, "/v1/recipes/" + id + "/versions", u.bearer(), version("A {{colour}} mug.", "red", "gpt-image-2", "1024x1024"))
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_recipe");
        send(HttpMethod.GET, "/v1/recipes/" + id + "/versions", u.bearer(), null)
                .expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signup() {
        String email = "recipes-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Recipe Keeper"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return new Signed(users.findByEmail(email).orElseThrow().getId(), "Bearer " + resp.accessToken());
    }

    /** The recipe of the contract's example. */
    private static Map<String, Object> mug() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Mug on a kitchen shelf");
        body.put("modality", "IMAGE");
        body.put("template", "A {{color}} mug on a shelf.");
        body.put("variables", List.of(Map.of("name", "color", "default", "red")));
        body.put("model", "gpt-image-2");
        body.put("params", Map.of("size", "1024x1024", "n", 1));
        body.put("source_gallery_recipe", "paper-lantern-street-at-dusk");
        return body;
    }

    private static Map<String, Object> version(String template, String fallback, String model, String size) {
        return Map.of("template", template, "variables", List.of(Map.of("name", "color", "default", fallback)),
                "model", model, "params", Map.of("size", size, "n", 1));
    }

    private String create(Signed u, Map<String, Object> body) {
        Map<?, ?> created = send(HttpMethod.POST, "/v1/recipes", u.bearer(), body)
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) created.get("id");
    }

    private WebTestClient.ResponseSpec send(HttpMethod method, String path, String bearer, Object body) {
        WebTestClient.RequestBodySpec request = http.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, bearer);
        return body == null ? request.exchange() : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static void notFound(WebTestClient.ResponseSpec response) {
        response.expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("recipe_not_found");
    }

    private int recipeCount(Signed u) {
        return jdbc.queryForObject("SELECT count(*) FROM recipes WHERE user_id = ?", Integer.class, u.id());
    }
}
