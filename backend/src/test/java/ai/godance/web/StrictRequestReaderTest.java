package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The reading the strict routes share ({@code POST /v1/videos},
 * {@code POST /v1/images/edits}): JSON read strictly, with nulls passed
 * over; the form's bracketed fields read back into the value OpenAI's SDK
 * flattened into them; and every refusal made by the route, in its own
 * words. Here the route is a stand-in that says which of its hooks made a
 * refusal. Each real route's fields are in {@link OpenAiVideoRequestsTest}
 * and {@link ImageEditRequestsTest}.
 */
class StrictRequestReaderTest {

    private static final UUID A = UUID.fromString("0b6f6c1e-8c7d-4c58-9d0a-5f2f4f7f3a10");
    private static final UUID JOB = UUID.fromString("7f000101-a1de-1f5a-81a1-def16cd70039");
    private static final UUID VERSION = UUID.fromString("3e9c2b41-5d6f-4a7b-8c9d-0e1f2a3b4c5d");

    /** A request of the kind a strict route reads: text, a count, a flag, input references and a recipe. */
    record Thing(String name, Integer count, Boolean loud, InputRef input, List<InputRef> inputs, RecipeRef recipe) { }

    /** A refusal of the stand-in route, with the hook that made it. */
    static final class Refused extends RuntimeException {
        final String hook;
        final String param;

        Refused(String hook, String param, String detail) {
            super(detail);
            this.hook = hook;
            this.param = param;
        }
    }

    /** A route that refuses in its own words, without overriding how a file in a text field is refused. */
    private static final StrictRequestReader.Route ROUTE = new StrictRequestReader.Route() {
        @Override
        public String name() {
            return "POST /v1/things";
        }

        @Override
        public RuntimeException invalid(String param, String detail) {
            return new Refused("invalid", param, detail);
        }

        @Override
        public RuntimeException unknownField(String field) {
            return new Refused("unknownField", field, "POST /v1/things has no " + field);
        }
    };

    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private final StrictRequestReader reader = new StrictRequestReader(json, ROUTE);

    // ===== JSON =======================================================

    @Test
    void body_givenFieldsTheRecordHas_expectsEachRead() {
        Thing thing = body("""
                {"name": "kite", "count": 2, "loud": true, "input": {"file_id": "%s"},
                 "inputs": [{"job_output": {"job_id": "%s", "index": 1}}],
                 "recipe": {"version_id": "%s", "variables": {"subject": "Koi"}}}
                """.formatted(A, JOB, VERSION));

        assertThat(thing).isEqualTo(new Thing("kite", 2, true, InputRef.file(A), List.of(InputRef.jobOutput(JOB, 1)),
                new RecipeRef(VERSION, Map.of("subject", "Koi"), null)));
    }

    @Test
    void body_givenAFieldTheRecordDoesNotHave_expectsTheRoutesOwnRefusalForIt() {
        Refused refused = refused(() -> body("{\"name\": \"kite\", \"colour\": \"red\"}"));

        assertThat(refused.hook).isEqualTo("unknownField");
        assertThat(refused.param).isEqualTo("colour");
        assertThat(refused.getMessage()).isEqualTo("POST /v1/things has no colour");
    }

    @Test
    void body_givenUnreadFieldsSetToNull_expectsThemPassedOverAtAnyDepth() {
        Thing thing = body("{\"name\": \"kite\", \"colour\": null, \"input\": {\"file_id\": \"" + A + "\", \"note\": null},"
                + " \"recipe\": {\"version_id\": \"" + VERSION + "\", \"variable\": null}}");

        assertThat(thing.name()).isEqualTo("kite");
        assertThat(thing.input()).isEqualTo(InputRef.file(A));
        assertThat(thing.recipe().versionId()).isEqualTo(VERSION);
    }

    @Test
    void body_givenAnUnreadFieldInsideAValue_expectsItsPathAndWhatTheValueHas() {
        Refused recipe = refused(() -> body("{\"recipe\": {\"version_id\": \"" + VERSION + "\", \"variable\": {\"subject\": \"Koi\"}}}"));
        assertThat(recipe.hook).isEqualTo("invalid");
        assertThat(recipe.param).isEqualTo("recipe.variable");
        assertThat(recipe.getMessage())
                .isEqualTo("POST /v1/things does not take recipe.variable: recipe has version_id, variables, inputs and published_id.");

        Refused listed = refused(() -> body("{\"inputs\": [{\"file_id\": \"" + A + "\"}, {\"url\": \"https://example.test/b.png\"}]}"));
        assertThat(listed.param).isEqualTo("inputs[1].url");
        assertThat(listed.getMessage()).endsWith("inputs[1] has file_id and job_output.");
    }

    @Test
    void body_givenValuesOfTheWrongKind_expectsTheFieldAndWhatItMustBe() {
        assertMistyped("{\"loud\": \"very\"}", "loud", "loud must be true or false.");
        assertMistyped("{\"count\": \"two\"}", "count", "count must be a whole number.");
        assertMistyped("{\"input\": \"a.png\"}", "input", "input must be an input reference: {\"file_id\": …} or "
                + "{\"job_output\": {\"job_id\": …, \"index\": …}}.");
        assertMistyped("{\"input\": {\"file_id\": \"a.png\"}}", "input.file_id", "input.file_id must be an id.");
        assertMistyped("{\"inputs\": {\"file_id\": \"" + A + "\"}}", "inputs", "inputs must be a list.");
        assertMistyped("{\"recipe\": {\"variables\": \"Koi\"}}", "recipe.variables", "recipe.variables must be an object.");
        assertMistyped("{\"name\": [\"kite\"]}", "name", "name must be a string.");
    }

    @Test
    void body_givenABodyThatIsNotAnObject_expectsRefusalOfTheWholeRequest() {
        for (String body : List.of("[{\"name\": \"kite\"}]", "\"kite\"", "null")) {
            Refused refused = refused(() -> reader.body(json.readTree(body), reader.type(Thing.class)));
            assertThat(refused.hook).as(body).isEqualTo("invalid");
            assertThat(refused.param).as(body).isNull();
            assertThat(refused.getMessage()).as(body).isEqualTo("The request body must be a JSON object.");
        }
    }

    // ===== The form: a value in brackets or as JSON text ==============

    @Test
    void recipe_givenTheBracketedFieldsOpenAisSdkWrites_expectsTheRecipeTheyWereWrittenFrom() {
        // extra_body={"recipe": {"version_id": …, "variables": {…}, "inputs": {…}}}, as the SDK flattens it.
        RecipeRef recipe = reader.recipe(fields(
                "recipe[version_id]", VERSION.toString(),
                "recipe[variables][subject]", "Paper boats",
                "recipe[variables][mood]", "",
                "recipe[inputs][opening][file_id]", A.toString(),
                "recipe[inputs][ending][job_output][job_id]", JOB.toString(),
                "recipe[inputs][ending][job_output][index]", "0"));

        assertThat(recipe.versionId()).isEqualTo(VERSION);
        // An empty value is a value: it replaces the variable's default.
        assertThat(recipe.variables()).containsExactly(Map.entry("subject", "Paper boats"), Map.entry("mood", ""));
        assertThat(recipe.inputs()).containsExactly(
                Map.entry("opening", InputRef.file(A)), Map.entry("ending", InputRef.jobOutput(JOB, 0)));
        assertThat(reader.recipe(fields("model", "kite"))).as("a form without one").isNull();
    }

    @Test
    void recipe_givenJsonText_expectsTheSameRecipeReadAsStrictly() {
        assertThat(reader.recipe(fields("recipe", "{\"version_id\": \"" + VERSION + "\", \"variables\": {\"subject\": \"Koi\"}}")))
                .isEqualTo(new RecipeRef(VERSION, Map.of("subject", "Koi"), null));

        Refused strict = refused(() -> reader.recipe(fields("recipe", "{\"version_id\": \"" + VERSION + "\", \"variable\": {}}")));
        assertThat(strict.param).as("named as JSON names it").isEqualTo("recipe.variable");

        Refused notJson = refused(() -> reader.recipe(fields("recipe", "version 3")));
        assertThat(notJson.param).isEqualTo("recipe");
        assertThat(notJson.getMessage()).isEqualTo("recipe must be a JSON object: {\"version_id\": …, \"variables\": {…}}");
    }

    @Test
    void recipe_givenJsonTextAndBracketsBoth_expectsRefusalAsOnlyOneCouldBeRead() {
        Refused refused = refused(() -> reader.recipe(fields(
                "recipe", "{\"version_id\": \"" + VERSION + "\"}", "recipe[version_id]", VERSION.toString())));

        assertThat(refused.param).isEqualTo("recipe");
        assertThat(refused.getMessage()).isEqualTo("send recipe once: as JSON text, or as recipe[…] fields, not both");
    }

    @Test
    void recipe_givenABracketedFieldARecipeDoesNotHave_expectsItNamedAsTheFormWroteIt() {
        Refused typo = refused(() -> reader.recipe(fields("recipe[version_id]", VERSION.toString(), "recipe[variable][subject]", "Koi")));
        assertThat(typo.param).isEqualTo("recipe[variable]");
        assertThat(typo.getMessage())
                .isEqualTo("POST /v1/things does not take recipe[variable]: recipe has version_id, variables, inputs and published_id.");

        Refused slot = refused(() -> reader.recipe(fields("recipe[version_id]", VERSION.toString(),
                "recipe[inputs][opening][url]", "https://example.test/a.png")));
        assertThat(slot.param).isEqualTo("recipe[inputs][opening][url]");

        Refused id = refused(() -> reader.recipe(fields("recipe[version_id]", "version 3")));
        assertThat(id.param).isEqualTo("recipe[version_id]");
        assertThat(id.getMessage()).isEqualTo("recipe[version_id] must be an id.");
    }

    @Test
    void recipe_givenBracketsOfAnotherShape_expectsEachRefused() {
        // Brackets that do not close, and a list where a recipe has none: no field of the route.
        for (String field : List.of("recipe[version_id", "recipe[][version_id]", "recipe[variables]x")) {
            Refused refused = refused(() -> reader.recipe(fields(field, VERSION.toString())));
            assertThat(refused.hook).as(field).isEqualTo("unknownField");
            assertThat(refused.param).as(field).isEqualTo(field);
        }
        // A value with fields inside it as well.
        Refused both = refused(() -> reader.recipe(fields("recipe[version_id]", VERSION.toString(),
                "recipe[variables]", "Koi", "recipe[variables][subject]", "Koi")));
        assertThat(both.param).isEqualTo("recipe[variables]");
        assertThat(both.getMessage()).contains("as a value and as fields inside it");
    }

    @Test
    void value_givenAListTheRouteRebuiltFromItsBrackets_expectsItReadAndNamedAsTheFormWroteIt() {
        // A route rebuilds a list itself, as the SDK writes one field per element: inputs[][file_id].
        ArrayNode bracketed = json.createArrayNode();
        bracketed.addObject().put("file_id", A.toString());
        bracketed.addObject().put("file_id", JOB.toString());
        List<InputRef> inputs = reader.value(fields("name", "kite"), "inputs", bracketed, "inputs[][…]",
                reader.listOf(InputRef.class), "a JSON list");
        assertThat(inputs).containsExactly(InputRef.file(A), InputRef.file(JOB));

        ArrayNode malformed = json.createArrayNode();
        malformed.addObject().put("file_id", "a.png");
        Refused refused = refused(() -> reader.value(fields("name", "kite"), "inputs", malformed, "inputs[][…]",
                reader.listOf(InputRef.class), "a JSON list"));
        assertThat(refused.param).isEqualTo("inputs[][file_id]");
        assertThat(refused.getMessage()).isEqualTo("inputs[][file_id] must be an id.");

        Refused both = refused(() -> reader.value(fields("inputs", "[]"), "inputs", bracketed, "inputs[][…]",
                reader.listOf(InputRef.class), "a JSON list"));
        assertThat(both.getMessage()).isEqualTo("send inputs once: as JSON text, or as inputs[][…] fields, not both");
    }

    // ===== The form: which fields, how often, of what kind ============

    @Test
    void requireKnown_givenAFieldTheRouteDoesNotRead_expectsTheRoutesOwnRefusal() {
        Refused refused = refused(() -> reader.requireKnown(fields("name", "kite", "colour", "red"),
                name -> name.equals("name"), name -> false));

        assertThat(refused.hook).isEqualTo("unknownField");
        assertThat(refused.param).isEqualTo("colour");
    }

    @Test
    void requireKnown_givenAFieldTwice_expectsRefusalUnlessItIsOneThatRepeats() {
        MultiValueMap<String, Part> form = fields("name", "kite", "name", "box", "input[]", "a", "input[]", "b");

        Refused twice = refused(() -> reader.requireKnown(form, name -> true, name -> name.equals("input[]")));
        assertThat(twice.param).isEqualTo("name");
        assertThat(twice.getMessage()).isEqualTo("send name once: the form has it 2 times");

        // A field that comes once per element of a list may repeat.
        reader.requireKnown(fields("input[]", "a", "input[]", "b"), name -> true, name -> name.equals("input[]"));
    }

    @Test
    void text_givenAFilePartWhereTextIsRead_expectsTheRoutesRefusalOrItsDefault() {
        MultiValueMap<String, Part> form = fields("model", "kite");
        form.add("recipe", file("recipe", "recipe.json"));

        Refused byDefault = refused(() -> reader.recipe(form));
        assertThat(byDefault.param).isEqualTo("recipe");
        assertThat(byDefault.getMessage()).isEqualTo("recipe must be a text field");

        // A route may say more, as POST /v1/videos does for an image sent where an id is read.
        StrictRequestReader.Route saysMore = new StrictRequestReader.Route() {
            @Override public String name() { return ROUTE.name(); }
            @Override public RuntimeException invalid(String param, String detail) { return ROUTE.invalid(param, detail); }
            @Override public RuntimeException unknownField(String field) { return ROUTE.unknownField(field); }
            @Override public RuntimeException notText(String field) { return new Refused("notText", field, field + " is a file"); }
        };
        Refused said = refused(() -> new StrictRequestReader(json, saysMore).recipe(form));
        assertThat(said.hook).isEqualTo("notText");
        assertThat(said.getMessage()).isEqualTo("recipe is a file");
    }

    @Test
    void text_givenAnEmptyField_expectsNoValueWhileTheRawTextKeepsIt() {
        MultiValueMap<String, Part> form = fields("prompt", "", "input[]", "a", "input[]", "");

        assertThat(reader.text(form, "prompt")).as("an empty prompt is no prompt").isNull();
        assertThat(reader.rawText(form, "prompt")).isEmpty();
        assertThat(reader.rawTexts(form, "input[]")).containsExactly("a", "");
        assertThat(reader.text(form, "missing")).isNull();
    }

    @Test
    void root_givenAFieldWithBrackets_expectsTheNameBeforeThem() {
        assertThat(StrictRequestReader.root("recipe[inputs][slot][file_id]")).isEqualTo("recipe");
        assertThat(StrictRequestReader.root("image[]")).isEqualTo("image");
        assertThat(StrictRequestReader.root("model")).isEqualTo("model");
        assertThat(StrictRequestReader.isRecipe("recipe")).isTrue();
        assertThat(StrictRequestReader.isRecipe("recipe[version_id]")).isTrue();
        assertThat(StrictRequestReader.isRecipe("recipes")).isFalse();
    }

    // ===== Helpers ====================================================

    private Thing body(String text) {
        return reader.body(json.readTree(text), reader.type(Thing.class));
    }

    private void assertMistyped(String body, String param, String detail) {
        Refused refused = refused(() -> body(body));
        assertThat(refused.hook).as(body).isEqualTo("invalid");
        assertThat(refused.param).as(body).isEqualTo(param);
        assertThat(refused.getMessage()).as(body).isEqualTo(detail);
    }

    private static Refused refused(Runnable read) {
        Throwable thrown = catchThrowable(read::run);
        assertThat(thrown).isInstanceOf(Refused.class);
        return (Refused) thrown;
    }

    /** A form of text fields, in the order given; a name given twice is sent twice, as the SDK sends a list. */
    static MultiValueMap<String, Part> fields(String... namesAndValues) {
        MultiValueMap<String, Part> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            form.add(namesAndValues[i], text(namesAndValues[i], namesAndValues[i + 1]));
        }
        return form;
    }

    static FormFieldPart text(String name, String value) {
        return new FormFieldPart() {
            @Override public String value() { return value; }
            @Override public String name() { return name; }
            @Override public HttpHeaders headers() { return new HttpHeaders(); }
            @Override public Flux<DataBuffer> content() { return Flux.empty(); }
        };
    }

    static FilePart file(String name, String filename) {
        return new FilePart() {
            @Override public String filename() { return filename; }
            @Override public Mono<Void> transferTo(Path dest) { return Mono.empty(); }
            @Override public String name() { return name; }
            @Override public HttpHeaders headers() { return new HttpHeaders(); }
            @Override public Flux<DataBuffer> content() { return Flux.empty(); }
        };
    }
}
