package ai.godance.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ai.godance.domain.Modality;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.service.InputService.ResolvedInput;
import ai.godance.service.RecipeRunService.RunPrompt;
import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;

/**
 * What a generation request runs with once its recipe is rendered and the
 * recipe's input slots are placed: the one place that knows which slot role
 * goes where in a request. The generation routes use it for a single run,
 * and a recipe batch uses it for every row, so a row is checked and run by
 * the same code as a request made one at a time.
 *
 * <p>Two steps, as everywhere a recipe runs. {@link #image} and
 * {@link #video} turn the request into what the job is made from, before
 * anything is priced or charged. {@link #checkImage} and {@link #checkVideo}
 * make the same checks for a run that will start later, without loading an
 * image or creating anything.
 */
@Component
public class RecipeRuns {

    private final RecipeRunService recipes;
    private final ImageEdits edits;
    private final VideoInputs videoInputs;

    public RecipeRuns(RecipeRunService recipes, ImageEdits edits, VideoInputs videoInputs) {
        this.recipes = recipes;
        this.edits = edits;
        this.videoInputs = videoInputs;
    }

    /**
     * An image request ready to become a job: the request with its prompt
     * and its input images, the inputs to record with the job, and where it
     * came from. A recipe with input slots runs as an edit and one without
     * as a generation: the server decides, from the slots.
     */
    public record ImageRun(ImageRequest request, List<ResolvedInput> inputs, JobOrigin origin) { }

    /**
     * @param domain  the request's model and settings; its prompt is replaced
     * @param prompt  the request's own prompt, or null when it names a recipe
     * @param recipe  the recipe the request names, or null for a plain prompt
     */
    public ImageRun image(UUID userId, ImageRequest domain, String prompt, RecipeRef recipe, JobOrigin origin) {
        // A recipe is rendered here, before anything is priced or charged.
        RunPrompt run = recipes.resolve(userId, Modality.IMAGE, prompt, recipe);
        ImageRequest request = domain.withPrompt(run.prompt());
        if (!run.hasInputs()) return new ImageRun(request, List.of(), run.origin(origin));
        // Each slot's image is checked and loaded as an edit's is: inputs on, the model takes them, the caller owns them.
        List<ResolvedInput> inputs = edits.resolve(userId, domain.model(), imageRefs(run));
        return new ImageRun(edits.attach(request, inputs), inputs, run.origin(origin));
    }

    /**
     * {@link #image} for a recipe run that starts later: the recipe renders
     * and every slot's image may be used, and nothing is loaded.
     *
     * @return the rendered run: its prompt, and the value every variable has
     */
    public RunPrompt checkImage(UUID userId, String model, RecipeRef recipe) {
        RunPrompt run = recipes.resolve(userId, Modality.IMAGE, null, recipe);
        if (run.hasInputs()) edits.check(userId, model, imageRefs(run));
        return run;
    }

    private static ImageEdits.Refs imageRefs(RunPrompt run) {
        return new ImageEdits.Refs(run.inputsWithRole("image"), null);
    }

    /**
     * A video request ready to be submitted: the request with its prompt,
     * the inputs it starts from as the caller or the recipe named them, and
     * where it came from. {@code VideoGenerationService.submit} checks and
     * loads the inputs.
     */
    public record VideoRun(VideoRequest request, VideoInputs.Refs inputs, JobOrigin origin) { }

    /**
     * @param domain  the request's model and settings; its prompt is replaced
     * @param prompt  the request's own prompt, or null when it names a recipe
     * @param recipe  the recipe the request names, or null for a plain prompt
     * @param given   the inputs the request itself names, outside any recipe
     * @throws RecipeRefusedException 400 {@code recipe_inputs_conflict} when
     *         a recipe with slots is run with inputs of the request's own
     */
    public VideoRun video(UUID userId, VideoRequest domain, String prompt, RecipeRef recipe,
                          VideoInputs.Refs given, JobOrigin origin) {
        // A recipe is rendered here, before anything is priced or charged.
        RunPrompt run = recipes.resolve(userId, Modality.VIDEO, prompt, recipe);
        // A recipe that names its own inputs runs with those, each placed by its slot's role, and with no others.
        VideoInputs.Refs named = given;
        if (run.hasInputs()) {
            if (!given.isEmpty()) throw RecipeRefusedException.inputsOutsideRecipe();
            named = videoRefs(run);
        }
        VideoRequest request = new VideoRequest(domain.model(), run.prompt(), domain.size(),
                domain.durationSeconds(), domain.n());
        return new VideoRun(request, named, run.origin(origin));
    }

    /**
     * {@link #video} for a recipe run that starts later: the recipe renders
     * and every slot's image may be used with this model, and nothing is
     * loaded.
     */
    public RunPrompt checkVideo(UUID userId, ModelInfo model, RecipeRef recipe) {
        RunPrompt run = recipes.resolve(userId, Modality.VIDEO, null, recipe);
        if (run.hasInputs()) videoInputs.check(userId, model, videoRefs(run));
        return run;
    }

    private static VideoInputs.Refs videoRefs(RunPrompt run) {
        List<InputRef> first = run.inputsWithRole("first_frame");
        List<InputRef> last = run.inputsWithRole("last_frame");
        return new VideoInputs.Refs(first.isEmpty() ? null : first.get(0),
                last.isEmpty() ? null : last.get(0), run.inputsWithRole("reference_image"));
    }

    /**
     * Whether the model takes the inputs a recipe version's slots ask for,
     * told from the slots alone: no input is named yet. The same refusals a
     * run would get, before any row is looked at, so a model that cannot
     * take the recipe's inputs is not reported as a fault of one row.
     *
     * @param slotRoles the role of each of the version's slots, in order;
     *                  empty for a recipe with none, and then nothing is checked
     */
    public void requireModelTakesSlots(Modality modality, ModelInfo model, List<String> slotRoles) {
        if (slotRoles.isEmpty()) return;
        // The checks look at which inputs a request has, never at what they point to.
        InputRef any = InputRef.file(new UUID(0L, 0L));
        if (modality == Modality.IMAGE) {
            long images = slotRoles.stream().filter("image"::equals).count();
            edits.requireSupported(model.modelName(), (int) images, false);
            return;
        }
        long references = slotRoles.stream().filter("reference_image"::equals).count();
        videoInputs.requireSupported(model, new VideoInputs.Refs(
                slotRoles.contains("first_frame") ? any : null,
                slotRoles.contains("last_frame") ? any : null,
                java.util.Collections.nCopies((int) references, any)));
    }
}
