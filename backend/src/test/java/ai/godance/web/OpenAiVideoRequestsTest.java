package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
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

import ai.godance.service.OpenAiVideoRefusedException;
import ai.godance.web.OpenAiVideoRequests.Fields;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * How {@code POST /v1/videos} reads a request: every field read or refused,
 * in JSON and in the multipart form OpenAI's SDK sends, where a nested value
 * arrives as bracketed fields. Over HTTP, against a provider cassette, in
 * {@link OpenAiVideoIntegrationTest}.
 */
class OpenAiVideoRequestsTest {

    private static final UUID A = UUID.fromString("0b6f6c1e-8c7d-4c58-9d0a-5f2f4f7f3a10");
    private static final UUID B = UUID.fromString("6f1c0b9e-2d4a-4c3b-9a57-0e8d4b2f7c11");
    private static final UUID JOB = UUID.fromString("7f000101-a1de-1f5a-81a1-def16cd70039");
    private static final UUID VERSION = UUID.fromString("3e9c2b41-5d6f-4a7b-8c9d-0e1f2a3b4c5d");

    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private final OpenAiVideoRequests requests = new OpenAiVideoRequests(json);

    // ===== JSON =======================================================

    @Test
    void json_givenEveryFieldTheRouteTakes_expectsEachRead() {
        Fields fields = json("""
                {"model": "seedance-2.0", "prompt": "The tide goes out.", "seconds": "8", "size": "1280x720",
                 "input_reference": {"file_id": "%s"},
                 "last_frame": {"file_id": "%s"},
                 "reference_images": [{"job_output": {"job_id": "%s", "index": 1}}],
                 "webhook_url": "https://example.test/hook", "gallery_recipe": "koi-pond-in-rain",
                 "recipe": {"version_id": "%s", "variables": {"subject": "Koi"}}}
                """.formatted(A, B, JOB, VERSION));

        assertThat(fields.model()).isEqualTo("seedance-2.0");
        assertThat(fields.seconds()).isEqualTo(8);
        assertThat(fields.inputReference().fileId()).isEqualTo(A.toString());
        assertThat(fields.lastFrame()).isEqualTo(InputRef.file(B));
        assertThat(fields.referenceImages()).containsExactly(InputRef.jobOutput(JOB, 1));
        assertThat(fields.webhookUrl()).isEqualTo("https://example.test/hook");
        assertThat(fields.galleryRecipe()).isEqualTo("koi-pond-in-rain");
        assertThat(fields.recipe()).isEqualTo(new RecipeRef(VERSION, Map.of("subject", "Koi"), null));
    }

    @Test
    void json_givenAFieldTheRouteDoesNotRead_expectsInvalidParameterNamingIt() {
        OpenAiVideoRefusedException refused = refusedJson("{\"model\": \"seedance-2.0\", \"prompt\": \"x\", \"colour\": \"red\"}");

        assertThat(refused.code()).isEqualTo(OpenAiVideoRefusedException.CODE_INVALID_PARAMETER);
        assertThat(param(refused)).isEqualTo("colour");
        assertThat(refused.getReason()).isEqualTo("POST /v1/videos does not take colour. It takes model, prompt, seconds, "
                + "size and input_reference, and the Godance extensions last_frame, reference_images, recipe and "
                + "webhook_url.");
    }

    @Test
    void json_givenNOrFirstFrame_expectsTheRouteThatTakesItNamed() {
        OpenAiVideoRefusedException n = refusedJson("{\"model\": \"seedance-2.0\", \"prompt\": \"x\", \"n\": 2}");
        assertThat(param(n)).isEqualTo("n");
        assertThat(n.getReason()).contains("takes no n").contains("POST /v1/videos/generations");

        // Even a count of one: a field that is not read is refused, whatever its value.
        assertThat(param(refusedJson("{\"model\": \"seedance-2.0\", \"prompt\": \"x\", \"n\": 1}"))).isEqualTo("n");

        OpenAiVideoRefusedException first = refusedJson(
                "{\"model\": \"seedance-2.0\", \"prompt\": \"x\", \"first_frame\": {\"file_id\": \"" + A + "\"}}");
        assertThat(param(first)).isEqualTo("first_frame");
        assertThat(first.getReason()).contains("send input_reference in place of first_frame");
    }

    @Test
    void json_givenAnUnreadFieldSetToNull_expectsItPassedOverAsItCarriesNothing() {
        Fields fields = json("{\"model\": \"seedance-2.0\", \"prompt\": \"x\", \"n\": null, \"first_frame\": null,"
                + " \"recipe\": null, \"last_frame\": {\"file_id\": \"" + A + "\", \"note\": null}}");

        assertThat(fields.model()).isEqualTo("seedance-2.0");
        assertThat(fields.lastFrame()).isEqualTo(InputRef.file(A));
    }

    @Test
    void json_givenAnUnreadFieldInsideAValue_expectsItsPathAndWhatIsTakenThere() {
        OpenAiVideoRefusedException recipe = refusedJson("{\"model\": \"seedance-2.0\", \"recipe\": "
                + "{\"version_id\": \"" + VERSION + "\", \"variable\": {\"subject\": \"Koi\"}}}");
        assertThat(param(recipe)).isEqualTo("recipe.variable");
        assertThat(recipe.getReason())
                .isEqualTo("POST /v1/videos does not take recipe.variable: recipe has version_id, variables and inputs.");

        OpenAiVideoRefusedException slot = refusedJson("{\"model\": \"seedance-2.0\", \"recipe\": {\"version_id\": \""
                + VERSION + "\", \"inputs\": {\"product photo\": {\"url\": \"https://example.test/a.png\"}}}}");
        assertThat(param(slot)).isEqualTo("recipe.inputs.product photo.url");
        assertThat(slot.getReason()).endsWith("recipe.inputs.product photo has file_id and job_output.");

        OpenAiVideoRefusedException listed = refusedJson("{\"model\": \"seedance-2.0\", \"prompt\": \"x\", "
                + "\"reference_images\": [{\"file_id\": \"" + A + "\"}, {\"image_url\": \"https://example.test/b.png\"}]}");
        assertThat(param(listed)).isEqualTo("reference_images[1].image_url");

        OpenAiVideoRefusedException reference = refusedJson(
                "{\"model\": \"seedance-2.0\", \"prompt\": \"x\", \"input_reference\": {\"file_id\": \"" + A + "\", \"fit\": \"crop\"}}");
        assertThat(param(reference)).isEqualTo("input_reference.fit");
    }

    @Test
    void json_givenAValueOfTheWrongKind_expectsItsPathAndWhatItMustBe() {
        assertMistyped("\"last_frame\": \"first.png\"", "last_frame", "last_frame must be an input reference");
        assertMistyped("\"last_frame\": {\"file_id\": \"first.png\"}", "last_frame.file_id", "last_frame.file_id must be an id.");
        assertMistyped("\"reference_images\": {\"file_id\": \"" + A + "\"}", "reference_images", "reference_images must be a list.");
        assertMistyped("\"reference_images\": [{\"job_output\": {\"job_id\": \"" + JOB + "\", \"index\": \"first\"}}]",
                "reference_images[0].job_output.index", "reference_images[0].job_output.index must be a whole number.");
        assertMistyped("\"recipe\": {\"version_id\": \"" + VERSION + "\", \"variables\": \"Koi\"}",
                "recipe.variables", "recipe.variables must be an object.");
        assertMistyped("\"model\": [\"seedance-2.0\"]", "model", "model must be a string.");
    }

    @Test
    void json_givenABodyThatIsNotAnObject_expectsRefusal() {
        OpenAiVideoRefusedException refused = refused(() -> requests.json(json.readTree("[{\"model\": \"seedance-2.0\"}]")));

        assertThat(refused.code()).isEqualTo(OpenAiVideoRefusedException.CODE_INVALID_PARAMETER);
        assertThat(refused.getReason()).isEqualTo("The request body must be a JSON object.");
    }

    // ===== The form: recipe ===========================================

    @Test
    void form_givenRecipeAsOpenAisSdkWritesIt_expectsTheRecipe() {
        // client.videos.create(..., extra_body={"recipe": {"version_id": …, "variables": {…}, "inputs": {…}}})
        Fields fields = form(
                "model", "seedance-2.0",
                "prompt", "",
                "recipe[version_id]", VERSION.toString(),
                "recipe[variables][subject]", "Paper boats",
                "recipe[variables][mood]", "",
                "recipe[inputs][opening][file_id]", A.toString(),
                "recipe[inputs][ending][job_output][job_id]", JOB.toString(),
                "recipe[inputs][ending][job_output][index]", "0");

        assertThat(fields.prompt()).as("an empty prompt is no prompt").isNull();
        assertThat(fields.recipe().versionId()).isEqualTo(VERSION);
        // An empty value is a value: it replaces the variable's default.
        assertThat(fields.recipe().variables()).containsExactly(Map.entry("subject", "Paper boats"), Map.entry("mood", ""));
        assertThat(fields.recipe().inputs()).containsExactly(
                Map.entry("opening", InputRef.file(A)), Map.entry("ending", InputRef.jobOutput(JOB, 0)));
    }

    @Test
    void form_givenRecipeAsJsonText_expectsTheSameRecipe() {
        String text = "{\"version_id\": \"" + VERSION + "\", \"variables\": {\"subject\": \"Paper boats\"}}";

        Fields fields = form("model", "seedance-2.0", "prompt", "", "recipe", text);

        assertThat(fields.recipe()).isEqualTo(new RecipeRef(VERSION, Map.of("subject", "Paper boats"), null));
        // Read as strictly as in JSON.
        OpenAiVideoRefusedException refused = refusedForm("model", "seedance-2.0", "recipe",
                "{\"version_id\": \"" + VERSION + "\", \"variable\": {\"subject\": \"Paper boats\"}}");
        assertThat(param(refused)).isEqualTo("recipe.variable");
        assertThat(refusedForm("model", "seedance-2.0", "recipe", "version 3").getReason())
                .isEqualTo("recipe must be a JSON object: {\"version_id\": …, \"variables\": {…}}");
    }

    @Test
    void form_givenRecipeBothAsTextAndInBrackets_expectsRefusal() {
        OpenAiVideoRefusedException refused = refusedForm("model", "seedance-2.0",
                "recipe", "{\"version_id\": \"" + VERSION + "\"}",
                "recipe[version_id]", VERSION.toString());

        assertThat(param(refused)).isEqualTo("recipe");
        assertThat(refused.getReason()).isEqualTo("send recipe once: as JSON text, or as recipe[…] fields, not both");
    }

    @Test
    void form_givenABracketedRecipeFieldTheRouteDoesNotRead_expectsItNamedAsTheFormWroteIt() {
        OpenAiVideoRefusedException refused = refusedForm("model", "seedance-2.0",
                "recipe[version_id]", VERSION.toString(), "recipe[variable][subject]", "Koi");
        assertThat(param(refused)).isEqualTo("recipe[variable]");
        assertThat(refused.getReason())
                .isEqualTo("POST /v1/videos does not take recipe[variable]: recipe has version_id, variables and inputs.");

        assertThat(param(refusedForm("model", "seedance-2.0", "recipe[version_id]", VERSION.toString(),
                "recipe[inputs][opening][url]", "https://example.test/a.png"))).isEqualTo("recipe[inputs][opening][url]");
        assertThat(param(refusedForm("model", "seedance-2.0", "recipe[version_id]", "version 3")))
                .isEqualTo("recipe[version_id]");
        assertThat(refusedForm("model", "seedance-2.0", "recipe[version_id]", "version 3").getReason())
                .isEqualTo("recipe[version_id] must be an id.");
    }

    @Test
    void form_givenBracketsOfAnotherShape_expectsRefusal() {
        // Brackets that do not close, a list where the value has none, and a value with fields inside it.
        assertThat(param(refusedForm("model", "seedance-2.0", "recipe[version_id", VERSION.toString())))
                .isEqualTo("recipe[version_id");
        assertThat(param(refusedForm("model", "seedance-2.0", "recipe[][version_id]", VERSION.toString())))
                .isEqualTo("recipe[][version_id]");
        OpenAiVideoRefusedException both = refusedForm("model", "seedance-2.0", "recipe[version_id]", VERSION.toString(),
                "recipe[variables]", "Koi", "recipe[variables][subject]", "Koi");
        assertThat(param(both)).isEqualTo("recipe[variables]");
        assertThat(both.getReason()).contains("as a value and as fields inside it");
    }

    // ===== The form: last_frame and reference_images ==================

    @Test
    void form_givenLastFrameInBrackets_expectsTheInputReference() {
        assertThat(form("model", "seedance-2.0", "prompt", "x", "last_frame[file_id]", A.toString()).lastFrame())
                .isEqualTo(InputRef.file(A));
        assertThat(form("model", "seedance-2.0", "prompt", "x", "last_frame[job_output][job_id]", JOB.toString(),
                "last_frame[job_output][index]", "2").lastFrame()).isEqualTo(InputRef.jobOutput(JOB, 2));
        assertThat(form("model", "seedance-2.0", "prompt", "x", "last_frame", "{\"file_id\": \"" + A + "\"}").lastFrame())
                .isEqualTo(InputRef.file(A));
        assertThat(param(refusedForm("model", "seedance-2.0", "last_frame[url]", "https://example.test/a.png")))
                .isEqualTo("last_frame[url]");
    }

    @Test
    void form_givenLastFrameOrAReferenceImageAsAFile_expectsRefusalPointingToAnUpload() {
        MultiValueMap<String, Part> form = fields("model", "seedance-2.0", "prompt", "x");
        form.add("last_frame", file("last_frame", "end.png"));

        OpenAiVideoRefusedException refused = refused(() -> requests.form(form));

        assertThat(param(refused)).isEqualTo("last_frame");
        assertThat(refused.getReason()).isEqualTo("last_frame cannot be a file. Upload the image with POST /v1/files "
                + "and send its id: last_frame[file_id].");

        MultiValueMap<String, Part> references = fields("model", "seedance-2.0", "prompt", "x");
        references.add("reference_images", file("reference_images", "a.png"));
        assertThat(refused(() -> requests.form(references)).getReason()).endsWith("reference_images[][file_id].");
    }

    @Test
    void form_givenReferenceImagesOfOneKindInBrackets_expectsThemInTheOrderSent() {
        assertThat(form("model", "seedance-2.0", "prompt", "x",
                "reference_images[][file_id]", B.toString(),
                "reference_images[][file_id]", A.toString()).referenceImages())
                .containsExactly(InputRef.file(B), InputRef.file(A));

        assertThat(form("model", "seedance-2.0", "prompt", "x",
                "reference_images[][job_output][job_id]", JOB.toString(),
                "reference_images[][job_output][index]", "1",
                "reference_images[][job_output][job_id]", JOB.toString(),
                "reference_images[][job_output][index]", "0").referenceImages())
                .containsExactly(InputRef.jobOutput(JOB, 1), InputRef.jobOutput(JOB, 0));
    }

    @Test
    void form_givenReferenceImagesMixingFilesAndJobOutputsInBrackets_expectsRefusalAndJsonTextTaken() {
        // What OpenAI's SDK sends for [{"file_id": A}, {"job_output": …}, {"file_id": B}]: grouped by field.
        OpenAiVideoRefusedException mixed = refusedForm("model", "seedance-2.0", "prompt", "x",
                "reference_images[][file_id]", A.toString(),
                "reference_images[][file_id]", B.toString(),
                "reference_images[][job_output][job_id]", JOB.toString(),
                "reference_images[][job_output][index]", "0");
        assertThat(param(mixed)).isEqualTo("reference_images");
        assertThat(mixed.getReason()).contains("arrive out of order").contains("as JSON text");

        // As JSON text the list keeps its order, whatever it mixes.
        String text = "[{\"file_id\": \"" + A + "\"}, {\"job_output\": {\"job_id\": \"" + JOB + "\", \"index\": 0}}, "
                + "{\"file_id\": \"" + B + "\"}]";
        assertThat(form("model", "seedance-2.0", "prompt", "x", "reference_images", text).referenceImages())
                .containsExactly(InputRef.file(A), InputRef.jobOutput(JOB, 0), InputRef.file(B));
    }

    @Test
    void form_givenJobOutputsWithoutAnIndexEach_expectsRefusal() {
        OpenAiVideoRefusedException refused = refusedForm("model", "seedance-2.0", "prompt", "x",
                "reference_images[][job_output][job_id]", JOB.toString(),
                "reference_images[][job_output][job_id]", JOB.toString(),
                "reference_images[][job_output][index]", "0");

        assertThat(param(refused)).isEqualTo("reference_images");
        assertThat(refused.getReason()).contains("the form has 2 job_id and 1 index fields");
    }

    @Test
    void form_givenReferenceImagesInAnotherBracketShape_expectsRefusalNamingTheShapesTaken() {
        // A list of plain ids, as the SDK writes ["…", "…"], and indexed elements.
        OpenAiVideoRefusedException ids = refusedForm("model", "seedance-2.0", "prompt", "x",
                "reference_images[]", A.toString(), "reference_images[]", B.toString());
        assertThat(param(ids)).isEqualTo("reference_images[]");
        assertThat(ids.getReason()).contains("reference_images[][file_id] field per image");

        assertThat(param(refusedForm("model", "seedance-2.0", "prompt", "x", "reference_images[0][file_id]", A.toString())))
                .isEqualTo("reference_images[0][file_id]");
    }

    @Test
    void form_givenAMalformedIdInBrackets_expectsTheFieldNamedAsSent() {
        OpenAiVideoRefusedException refused = refusedForm("model", "seedance-2.0", "prompt", "x",
                "reference_images[][file_id]", "a.png");

        assertThat(param(refused)).isEqualTo("reference_images[][file_id]");
        assertThat(refused.getReason()).isEqualTo("reference_images[][file_id] must be an id.");
    }

    // ===== The form: everything else ==================================

    @Test
    void form_givenAFieldTheRouteDoesNotRead_expectsInvalidParameterNamingIt() {
        assertThat(param(refusedForm("model", "seedance-2.0", "prompt", "x", "colour", "red"))).isEqualTo("colour");
        assertThat(refusedForm("model", "seedance-2.0", "prompt", "x", "n", "2").getReason()).contains("takes no n");
        assertThat(refusedForm("model", "seedance-2.0", "prompt", "x", "first_frame[file_id]", A.toString()).getReason())
                .contains("does not take first_frame[file_id]");
        assertThat(param(refusedForm("model", "seedance-2.0", "prompt", "x", "input_reference[url]", "https://example.test/a.png")))
                .isEqualTo("input_reference[url]");
        // Empty, it is still a field the route would not read.
        assertThat(param(refusedForm("model", "seedance-2.0", "prompt", "x", "colour", ""))).isEqualTo("colour");
    }

    @Test
    void form_givenAFieldTwice_expectsRefusalAsOnlyOneCouldBeRead() {
        OpenAiVideoRefusedException model = refusedForm("model", "seedance-2.0", "model", "kling-3.0", "prompt", "x");
        assertThat(param(model)).isEqualTo("model");
        assertThat(model.getReason()).isEqualTo("send model once: the form has it 2 times");

        assertThat(param(refusedForm("model", "seedance-2.0", "prompt", "x",
                "last_frame[file_id]", A.toString(), "last_frame[file_id]", B.toString()))).isEqualTo("last_frame[file_id]");
    }

    @Test
    void form_givenOpenAisFieldsAndTheInputReferenceObject_expectsThemReadAsBefore() {
        Fields fields = form("model", "seedance-2.0", "prompt", "Clouds passing over a field.", "seconds", "8",
                "size", "1280x720", "input_reference[job_output][job_id]", JOB.toString(),
                "input_reference[job_output][index]", "0", "webhook_url", "https://example.test/hook");

        assertThat(fields.seconds()).isEqualTo(8);
        assertThat(fields.size()).isEqualTo("1280x720");
        assertThat(fields.inputReference().jobOutput().jobId()).isEqualTo(JOB);
        assertThat(fields.lastFrame()).isNull();
        assertThat(fields.referenceImages()).isNull();
        assertThat(fields.recipe()).isNull();
    }

    // ===== Helpers ====================================================

    private Fields json(String body) {
        return requests.json(json.readTree(body));
    }

    private OpenAiVideoRefusedException refusedJson(String body) {
        return refused(() -> json(body));
    }

    private void assertMistyped(String field, String param, String detail) {
        OpenAiVideoRefusedException refused = refusedJson("{\"model\": \"seedance-2.0\", \"prompt\": \"x\", " + field + "}");
        assertThat(refused.code()).isEqualTo(OpenAiVideoRefusedException.CODE_INVALID_PARAMETER);
        assertThat(param(refused)).isEqualTo(param);
        assertThat(refused.getReason()).startsWith(detail);
    }

    private Fields form(String... namesAndValues) {
        return requests.form(fields(namesAndValues));
    }

    private OpenAiVideoRefusedException refusedForm(String... namesAndValues) {
        return refused(() -> form(namesAndValues));
    }

    private static OpenAiVideoRefusedException refused(Runnable read) {
        Throwable thrown = catchThrowable(read::run);
        assertThat(thrown).isInstanceOf(OpenAiVideoRefusedException.class);
        return (OpenAiVideoRefusedException) thrown;
    }

    private static String param(OpenAiVideoRefusedException refused) {
        Map<String, Object> properties = refused.getBody().getProperties();
        return properties == null ? null : (String) properties.get("param");
    }

    /** A form of text fields, in the order given; a name given twice is sent twice, as the SDK sends a list. */
    private static MultiValueMap<String, Part> fields(String... namesAndValues) {
        MultiValueMap<String, Part> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            form.add(namesAndValues[i], text(namesAndValues[i], namesAndValues[i + 1]));
        }
        return form;
    }

    private static FormFieldPart text(String name, String value) {
        return new FormFieldPart() {
            @Override public String value() { return value; }
            @Override public String name() { return name; }
            @Override public HttpHeaders headers() { return new HttpHeaders(); }
            @Override public Flux<DataBuffer> content() { return Flux.empty(); }
        };
    }

    private static FilePart file(String name, String filename) {
        return new FilePart() {
            @Override public String filename() { return filename; }
            @Override public Mono<Void> transferTo(Path dest) { return Mono.empty(); }
            @Override public String name() { return name; }
            @Override public HttpHeaders headers() { return new HttpHeaders(); }
            @Override public Flux<DataBuffer> content() { return Flux.empty(); }
        };
    }
}
