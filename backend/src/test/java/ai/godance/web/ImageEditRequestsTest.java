package ai.godance.web;

import static ai.godance.web.StrictRequestReaderTest.fields;
import static ai.godance.web.StrictRequestReaderTest.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;

import ai.godance.service.ImageEditRefusedException;
import ai.godance.web.ImageEditRequests.Fields;
import ai.godance.web.dto.ImageEditDtos.ImageEditRequest;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * How {@code POST /v1/images/edits} reads a request: every field read or
 * refused, in JSON and in the multipart form OpenAI's SDK sends for
 * {@code client.images.edit(…)}, where {@code extra_body={"recipe": {…}}}
 * arrives as bracketed fields. Over HTTP, against the providers' cassettes,
 * in {@link ImageEditsIntegrationTest}.
 */
class ImageEditRequestsTest {

    private static final UUID A = UUID.fromString("0b6f6c1e-8c7d-4c58-9d0a-5f2f4f7f3a10");
    private static final UUID B = UUID.fromString("6f1c0b9e-2d4a-4c3b-9a57-0e8d4b2f7c11");
    private static final UUID JOB = UUID.fromString("7f000101-a1de-1f5a-81a1-def16cd70039");
    private static final UUID VERSION = UUID.fromString("3e9c2b41-5d6f-4a7b-8c9d-0e1f2a3b4c5d");

    /** What OpenAI's SDK (3.51.0) takes by name for images.edit, and Godance does not act on. */
    private static final List<String> OPENAI_FIELDS_NOT_READ = List.of("background", "complexity", "frame_count",
            "frame_duration_ms", "input_fidelity", "output_compression", "output_format", "partial_images",
            "safety_identifier", "stream", "user");

    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private final ImageEditRequests requests = new ImageEditRequests(json);

    // ===== JSON =======================================================

    @Test
    void json_givenEveryFieldTheRouteTakes_expectsEachRead() {
        ImageEditRequest request = json("""
                {"model": "gpt-image-2", "prompt": "Put the mug on a marble counter.",
                 "images": [{"file_id": "%s"}, {"job_output": {"job_id": "%s", "index": 1}}],
                 "mask": {"file_id": "%s"}, "n": 2, "size": "1024x1024", "quality": "high",
                 "response_format": "url", "async": true, "webhook_url": "https://example.test/hook",
                 "gallery_recipe": "mug-on-marble", "recipe": null}
                """.formatted(A, JOB, B));

        assertThat(request.images()).containsExactly(InputRef.file(A), InputRef.jobOutput(JOB, 1));
        assertThat(request.mask()).isEqualTo(InputRef.file(B));
        Fields fields = Fields.of(request);
        assertThat(fields).isEqualTo(new Fields("gpt-image-2", "Put the mug on a marble counter.", 2, "1024x1024",
                "high", "url", true, "https://example.test/hook", "mug-on-marble", null));
    }

    @Test
    void json_givenOpenAisOwnFieldsThatGodanceDoesNotActOn_expectsEachRefusedByName() {
        for (String field : withStyle(OPENAI_FIELDS_NOT_READ)) {
            ImageEditRefusedException refused = refused(() -> json(
                    "{\"model\": \"gpt-image-2\", \"prompt\": \"x\", \"images\": [{\"file_id\": \"" + A + "\"}], \""
                            + field + "\": \"auto\"}"));

            assertThat(refused.code()).as(field).isEqualTo(ImageEditRefusedException.CODE_INVALID_PARAMETER);
            assertThat(param(refused)).as(field).isEqualTo(field);
            assertThat(refused.getReason()).as(field).isEqualTo("POST /v1/images/edits does not take " + field
                    + ". It takes model, prompt, images, mask, n, size, quality and response_format, and the Godance "
                    + "extensions async, recipe and webhook_url.");
        }
    }

    @Test
    void json_givenImageAsTheFormNamesIt_expectsToBeToldHowJsonNamesTheImages() {
        ImageEditRefusedException refused = refused(() -> json(
                "{\"model\": \"gpt-image-2\", \"prompt\": \"x\", \"image\": {\"file_id\": \"" + A + "\"}}"));

        assertThat(param(refused)).isEqualTo("image");
        assertThat(refused.getReason()).startsWith("POST /v1/images/edits does not take image. In JSON the images are "
                + "images, a list of input references");
    }

    @Test
    void json_givenUnreadFieldsSetToNull_expectsThemPassedOverAsTheyCarryNothing() {
        ImageEditRequest request = json("{\"model\": \"gpt-image-2\", \"prompt\": \"x\", \"output_format\": null, "
                + "\"user\": null, \"style\": null, \"images\": [{\"file_id\": \"" + A + "\", \"note\": null}]}");

        assertThat(request.images()).containsExactly(InputRef.file(A));
    }

    @Test
    void json_givenAnUnreadFieldInsideAValue_expectsItsPath() {
        assertThat(param(refused(() -> json("{\"model\": \"gpt-image-2\", \"prompt\": \"x\", \"images\": [{\"file_id\": \""
                + A + "\"}, {\"url\": \"https://example.test/b.png\"}]}")))).isEqualTo("images[1].url");
        assertThat(param(refused(() -> json("{\"model\": \"gpt-image-2\", \"prompt\": \"x\", \"images\": [{\"file_id\": \""
                + A + "\"}], \"mask\": {\"file_id\": \"" + B + "\", \"invert\": true}}")))).isEqualTo("mask.invert");

        ImageEditRefusedException recipe = refused(() -> json("{\"model\": \"gpt-image-2\", \"recipe\": "
                + "{\"version_id\": \"" + VERSION + "\", \"variable\": {\"subject\": \"Koi\"}}}"));
        assertThat(param(recipe)).isEqualTo("recipe.variable");
        assertThat(recipe.getReason())
                .isEqualTo("POST /v1/images/edits does not take recipe.variable: recipe has version_id, variables and inputs.");
    }

    @Test
    void json_givenAValueOfTheWrongKind_expectsItsPathAndWhatItMustBe() {
        assertMistyped("\"async\": \"soon\"", "async", "async must be true or false.");
        assertMistyped("\"n\": \"two\"", "n", "n must be a whole number.");
        assertMistyped("\"images\": {\"file_id\": \"" + A + "\"}", "images", "images must be a list.");
        assertMistyped("\"mask\": \"mask.png\"", "mask", "mask must be an input reference");
        assertMistyped("\"images\": [{\"file_id\": \"a.png\"}]", "images[0].file_id", "images[0].file_id must be an id.");
    }

    @Test
    void json_givenABodyThatIsNotAnObject_expectsRefusal() {
        ImageEditRefusedException refused = refused(() -> requests.json(json.readTree("[{\"model\": \"gpt-image-2\"}]")));

        assertThat(refused.code()).isEqualTo(ImageEditRefusedException.CODE_INVALID_PARAMETER);
        assertThat(param(refused)).isNull();
        assertThat(refused.getReason()).isEqualTo("The request body must be a JSON object.");
    }

    // ===== The form ===================================================

    @Test
    void form_givenOpenAisFieldsThatTheRouteReads_expectsEachReadAndThePartsLeftToTheRoute() {
        MultiValueMap<String, Part> form = fields("model", "gpt-image-2", "prompt", "Put the mug on a marble counter.",
                "n", "2", "size", "1024x1024", "quality", "high", "response_format", "b64_json", "async", "true",
                "webhook_url", "https://example.test/hook", "gallery_recipe", "mug-on-marble");
        form.add("image[]", file("image[]", "a.png"));
        form.add("image[]", file("image[]", "b.png"));
        form.add("mask", file("mask", "mask.png"));

        Fields fields = requests.form(form);

        assertThat(fields).isEqualTo(new Fields("gpt-image-2", "Put the mug on a marble counter.", 2, "1024x1024",
                "high", "b64_json", true, "https://example.test/hook", "mug-on-marble", null));
    }

    @Test
    void form_givenRecipeAsOpenAisSdkWritesIt_expectsTheRecipeAndNoPrompt() {
        // client.images.edit(image=[], prompt="", extra_body={"recipe": {"version_id": …, "variables": {…}, "inputs": {…}}})
        Fields fields = requests.form(fields(
                "model", "gpt-image-2",
                "prompt", "",
                "recipe[version_id]", VERSION.toString(),
                "recipe[variables][surface]", "marble counter",
                "recipe[inputs][product][file_id]", A.toString(),
                "recipe[inputs][room][job_output][job_id]", JOB.toString(),
                "recipe[inputs][room][job_output][index]", "0"));

        assertThat(fields.prompt()).as("an empty prompt is no prompt").isNull();
        assertThat(fields.recipe()).isEqualTo(new RecipeRef(VERSION, Map.of("surface", "marble counter"),
                Map.of("product", InputRef.file(A), "room", InputRef.jobOutput(JOB, 0))));

        // json.dumps(…) in extra_body: the same recipe, as JSON text.
        assertThat(requests.form(fields("model", "gpt-image-2", "recipe",
                "{\"version_id\": \"" + VERSION + "\", \"variables\": {\"surface\": \"marble counter\"}}")).recipe())
                .isEqualTo(new RecipeRef(VERSION, Map.of("surface", "marble counter"), null));
    }

    @Test
    void form_givenRecipeFieldsOfAnotherShape_expectsThemNamedAsTheFormWroteThem() {
        ImageEditRefusedException typo = refusedForm("model", "gpt-image-2", "recipe[version_id]", VERSION.toString(),
                "recipe[variable][subject]", "Koi");
        assertThat(param(typo)).isEqualTo("recipe[variable]");
        assertThat(typo.getReason())
                .isEqualTo("POST /v1/images/edits does not take recipe[variable]: recipe has version_id, variables and inputs.");

        ImageEditRefusedException unclosed = refusedForm("model", "gpt-image-2", "recipe[version_id", VERSION.toString());
        assertThat(param(unclosed)).isEqualTo("recipe[version_id");
        assertThat(unclosed.getReason()).isEqualTo("POST /v1/images/edits does not take recipe[version_id. In a form, "
                + "recipe is JSON text, or recipe[version_id] with recipe[variables][name] and "
                + "recipe[inputs][slot][file_id] fields.");
    }

    @Test
    void form_givenOpenAisOwnFieldsThatGodanceDoesNotActOn_expectsEachRefusedByName() {
        for (String field : withStyle(OPENAI_FIELDS_NOT_READ)) {
            ImageEditRefusedException refused = refusedForm("model", "gpt-image-2", "prompt", "x", field, "auto");

            assertThat(param(refused)).as(field).isEqualTo(field);
            assertThat(refused.getReason()).as(field).isEqualTo("POST /v1/images/edits does not take " + field
                    + ". It takes model, prompt, image, mask, n, size, quality and response_format, and the Godance "
                    + "extensions async, recipe and webhook_url.");
        }
        // OpenAI's svg is an object, which the SDK writes as bracketed fields; markup is a file.
        assertThat(param(refusedForm("model", "gpt-image-2", "prompt", "x", "svg[viewbox]", "0 0 10 10")))
                .isEqualTo("svg[viewbox]");
        MultiValueMap<String, Part> markup = fields("model", "gpt-image-2", "prompt", "x");
        markup.add("markup", file("markup", "markup.png"));
        assertThat(param(refused(() -> requests.form(markup)))).isEqualTo("markup");
        // Empty, a field is still one the route would not read.
        assertThat(param(refusedForm("model", "gpt-image-2", "prompt", "x", "user", ""))).isEqualTo("user");
    }

    @Test
    void form_givenStoredImagesOrAMaskById_expectsToBeToldToSendJson() {
        for (String field : List.of("images[][file_id]", "images", "mask[file_id]")) {
            ImageEditRefusedException refused = refusedForm("model", "gpt-image-2", "prompt", "x", field, A.toString());

            assertThat(param(refused)).as(field).isEqualTo(field);
            assertThat(refused.getReason()).as(field).isEqualTo("POST /v1/images/edits does not take " + field
                    + ". In a form, the images are file parts named image, and the mask a file part named mask. "
                    + "To edit images that are already stored, send the request as JSON: {\"images\": [{\"file_id\": …}]}.");
        }
        assertThat(refusedForm("model", "gpt-image-2", "prompt", "x", "image[0]", A.toString()).getReason())
                .endsWith("In a form, each image is a file part named image, or image[] as OpenAI's SDK names a list of them.");
    }

    @Test
    void form_givenAFieldTwice_expectsRefusalExceptForImages() {
        ImageEditRefusedException model = refusedForm("model", "gpt-image-2", "model", "flux-2-pro", "prompt", "x");
        assertThat(param(model)).isEqualTo("model");
        assertThat(model.getReason()).isEqualTo("send model once: the form has it 2 times");

        MultiValueMap<String, Part> masks = fields("model", "gpt-image-2", "prompt", "x");
        masks.add("image", file("image", "a.png"));
        masks.add("mask", file("mask", "one.png"));
        masks.add("mask", file("mask", "two.png"));
        assertThat(param(refused(() -> requests.form(masks)))).isEqualTo("mask");

        // An image comes once per image, in a part of either name.
        MultiValueMap<String, Part> images = fields("model", "gpt-image-2", "prompt", "x");
        for (String name : List.of("image", "image", "image[]", "image[]")) images.add(name, file(name, "a.png"));
        assertThat(requests.form(images).model()).isEqualTo("gpt-image-2");
    }

    @Test
    void form_givenACountThatIsNotAWholeNumber_expectsItNamed() {
        ImageEditRefusedException refused = refusedForm("model", "gpt-image-2", "prompt", "x", "n", "many");

        assertThat(refused.code()).isEqualTo(ImageEditRefusedException.CODE_INVALID_PARAMETER);
        assertThat(param(refused)).isEqualTo("n");
        assertThat(refused.getReason()).isEqualTo("n must be a whole number");
    }

    @Test
    void form_givenATextFieldSentAsAFile_expectsRefusal() {
        MultiValueMap<String, Part> form = fields("model", "gpt-image-2");
        form.add("prompt", file("prompt", "prompt.txt"));

        ImageEditRefusedException refused = refused(() -> requests.form(form));

        assertThat(param(refused)).isEqualTo("prompt");
        assertThat(refused.getReason()).isEqualTo("prompt must be a text field");
    }

    // ===== Helpers ====================================================

    private ImageEditRequest json(String body) {
        return requests.json(json.readTree(body));
    }

    private void assertMistyped(String field, String param, String detail) {
        ImageEditRefusedException refused = refused(() -> json("{\"model\": \"gpt-image-2\", \"prompt\": \"x\", " + field + "}"));
        assertThat(refused.code()).isEqualTo(ImageEditRefusedException.CODE_INVALID_PARAMETER);
        assertThat(param(refused)).isEqualTo(param);
        assertThat(refused.getReason()).startsWith(detail);
    }

    private ImageEditRefusedException refusedForm(String... namesAndValues) {
        return refused(() -> requests.form(fields(namesAndValues)));
    }

    private static ImageEditRefusedException refused(Runnable read) {
        Throwable thrown = catchThrowable(read::run);
        assertThat(thrown).isInstanceOf(ImageEditRefusedException.class);
        return (ImageEditRefusedException) thrown;
    }

    private static String param(ImageEditRefusedException refused) {
        Map<String, Object> properties = refused.getBody().getProperties();
        return properties == null ? null : (String) properties.get("param");
    }

    private static List<String> withStyle(List<String> fields) {
        List<String> all = new java.util.ArrayList<>(fields);
        all.add("style");
        return all;
    }
}
