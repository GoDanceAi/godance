package ai.godance.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ai.godance.config.CommunityProperties;
import ai.godance.config.InputsProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.PublishedRecipe;
import ai.godance.domain.PublishedRecipeExample;
import ai.godance.domain.PublishedStatus;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeVersion;
import ai.godance.domain.User;
import ai.godance.repository.GenerationJobInputRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.ProfileRepository;
import ai.godance.repository.PublishedRecipeExampleRepository;
import ai.godance.repository.PublishedRecipeRepository;
import ai.godance.repository.RecipeRepository;
import ai.godance.repository.RecipeVersionRepository;
import ai.godance.repository.ReportRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.CommunityDtos.PublishRequest;
import ai.godance.web.dto.CommunityDtos.PublishView;

/**
 * Publishing a recipe (round 14 brief 01). An adult account with a verified
 * email and a profile submits one of its recipes: a version, a licence, and
 * one to four of its own finished, unpurged jobs of that recipe as
 * examples. The submission is {@code PENDING} until a moderator approves it
 * ({@link ModerationService}); nothing here makes anything public.
 *
 * <p>The title and the version are copied when the author publishes, so a
 * later rename or a new version never reaches the public routes unreviewed.
 */
@Service
@EnableConfigurationProperties(CommunityProperties.class)
public class PublishService {

    static final int EXAMPLES_MAX = 4;
    static final int TAGS_MAX = 5;
    static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,23}$");

    /** First key of the per-account advisory lock; the second is the account id's hash. */
    static final int PUBLISH_LOCK_SPACE = 7_000_039;

    static final String AUDIT_PUBLISHED = "community.published";
    static final String AUDIT_APPROVED = "community.approved";
    static final String AUDIT_REJECTED = "community.rejected";
    static final String AUDIT_REMOVED = "community.removed";
    static final String AUDIT_REPORTED = "community.reported";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final List<PublishedStatus> ACTIVE = List.of(PublishedStatus.PENDING, PublishedStatus.PUBLIC);

    private final PublishedRecipeRepository published;
    private final PublishedRecipeExampleRepository examples;
    private final RecipeRepository recipes;
    private final RecipeVersionRepository versions;
    private final GenerationJobRepository jobs;
    private final GenerationJobOutputRepository outputs;
    private final GenerationJobInputRepository jobInputs;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final PublishedExamples copies;
    private final ReportRepository reports;
    private final AuditLogService audit;
    private final CommunityProperties props;
    private final InputsProperties inputs;

    public PublishService(PublishedRecipeRepository published, PublishedRecipeExampleRepository examples,
                          RecipeRepository recipes, RecipeVersionRepository versions, GenerationJobRepository jobs,
                          GenerationJobOutputRepository outputs, GenerationJobInputRepository jobInputs,
                          UserRepository users, ProfileRepository profiles, PublishedExamples copies,
                          ReportRepository reports, AuditLogService audit, CommunityProperties props,
                          InputsProperties inputs) {
        this.reports = reports;
        this.published = published;
        this.examples = examples;
        this.recipes = recipes;
        this.versions = versions;
        this.jobs = jobs;
        this.outputs = outputs;
        this.jobInputs = jobInputs;
        this.users = users;
        this.profiles = profiles;
        this.copies = copies;
        this.audit = audit;
        this.props = props;
        this.inputs = inputs;
    }

    // ===== Publish ======================================================

    /**
     * Submits the caller's recipe for review: 202 and a {@code PENDING}
     * submission.
     *
     * @throws CommunityRefusedException 404 {@code community_disabled}; 403
     *         {@code community_not_allowed}; 409 {@code profile_required},
     *         {@code already_published} or {@code publish_limit_reached}
     *         (pending and public at the limit); 429 {@code publish_limit_reached}
     *         (today's submissions at the limit); 400 {@code invalid_publish},
     *         {@code invalid_example}, {@code example_has_inputs} or
     *         {@code published_inputs_not_allowed}
     * @throws RecipeRefusedException 404 {@code recipe_not_found}
     */
    @Transactional
    public PublishView publish(UUID userId, UUID recipeId, PublishRequest req, String ip) {
        requireEnabled();
        User user = users.findById(userId).orElseThrow(RecipeRefusedException::notFound);
        ProfileService.requireMayTakePart(user);
        if (!profiles.existsById(userId)) {
            throw CommunityRefusedException.of(HttpStatus.CONFLICT, CommunityRefusedException.CODE_PROFILE_REQUIRED,
                    "set up your profile before you publish: PUT /v1/profile");
        }
        Recipe recipe = recipes.findById(recipeId).filter(r -> r.getUserId().equals(userId))
                .orElseThrow(RecipeRefusedException::notFound);
        if (req == null) throw invalid("a submission names a version, a licence and its examples");
        RecipeVersion version = version(recipe, req.version());
        String licence = licence(req.licence());
        List<String> tags = tags(req.tags());
        if (!version.getInputs().isEmpty() && !inputs.allowPeople()) {
            throw CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_INPUTS_NOT_ALLOWED,
                    "a recipe with input slots cannot be published while uploads may not show people");
        }
        List<GenerationJob> chosen = examples(userId, recipe, req.jobIds());

        // Counted and written one request at a time, so two cannot both take the last place.
        published.lockPublishingOf(PUBLISH_LOCK_SPACE, userId.hashCode());
        if (!published.findByRecipeIdAndStatusIn(recipeId, ACTIVE).isEmpty()) {
            throw CommunityRefusedException.of(HttpStatus.CONFLICT, CommunityRefusedException.CODE_ALREADY_PUBLISHED,
                    "this recipe is already published or waiting for review; unpublish it first");
        }
        if (published.countByUserIdAndSubmittedAtAfter(userId, Instant.now().minus(Duration.ofDays(1)))
                >= props.maxPendingPerDay()) {
            throw CommunityRefusedException.of(HttpStatus.TOO_MANY_REQUESTS, CommunityRefusedException.CODE_LIMIT_REACHED,
                    "you have submitted " + props.maxPendingPerDay() + " recipes in the last day; try again tomorrow");
        }
        if (published.countByUserIdAndStatusIn(userId, ACTIVE) >= props.maxPublishedPerUser()) {
            throw CommunityRefusedException.of(HttpStatus.CONFLICT, CommunityRefusedException.CODE_LIMIT_REACHED,
                    "you have " + props.maxPublishedPerUser() + " recipes published or waiting for review, the most an account can");
        }

        PublishedRecipe row = new PublishedRecipe();
        row.setPublicId(newPublicId());
        row.setUserId(userId);
        row.setRecipeId(recipeId);
        row.setVersionId(version.getId());
        row.setVersionNumber(version.getNumber());
        row.setTitle(recipe.getTitle());
        row.setModality(recipe.getModality());
        row.setTemplate(version.getTemplate());
        row.setVariables(copyList(version.getVariables()));
        row.setInputs(copyList(version.getInputs()));
        row.setModel(version.getModel());
        row.setParams(new LinkedHashMap<>(version.getParams()));
        row.setLicence(licence);
        row.setTags(tags);
        if (recipe.getSourcePublishedRecipeId() != null) {
            row.setMadeFromPublishedId(recipe.getSourcePublishedRecipeId());
        } else {
            row.setMadeFromGallery(recipe.getSourceGalleryRecipe());
        }
        row.setStatus(PublishedStatus.PENDING);
        try {
            published.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            throw CommunityRefusedException.of(HttpStatus.CONFLICT, CommunityRefusedException.CODE_ALREADY_PUBLISHED,
                    "this recipe is already published or waiting for review; unpublish it first");
        }

        int position = 1;
        for (GenerationJob job : chosen) {
            for (GenerationJobOutput output : outputs.findByJobIdOrderByOutputIndexAsc(job.getId())) {
                PublishedRecipeExample example = new PublishedRecipeExample();
                example.setPublishedRecipeId(row.getId());
                example.setPosition(position++);
                example.setSourceJobId(job.getId());
                example.setSourceOutputIndex(output.getOutputIndex());
                example.setContentType(output.getContentType());
                example.setSizeBytes(output.getSizeBytes());
                examples.save(example);
            }
        }

        audit.record(AUDIT_PUBLISHED, AuditOutcome.SUCCESS, userId, ip, Map.of(
                "publicId", row.getPublicId(), "recipeId", recipeId.toString(), "version", version.getNumber()));
        return view(row);
    }

    /**
     * The recipe's latest submission as its author sees it.
     *
     * @throws CommunityRefusedException 404 {@code not_published} when it was never submitted
     */
    @Transactional(readOnly = true)
    public PublishView view(UUID userId, UUID recipeId) {
        requireEnabled();
        recipes.findById(recipeId).filter(r -> r.getUserId().equals(userId)).orElseThrow(RecipeRefusedException::notFound);
        PublishedRecipe row = published.findFirstByRecipeIdOrderBySubmittedAtDesc(recipeId)
                .orElseThrow(PublishService::notPublished);
        return view(row);
    }

    // ===== Unpublish ====================================================

    /**
     * Takes the recipe's pending or public submission down at once: it is
     * {@code REMOVED}, kept for the record, and its example copies are deleted.
     *
     * @throws CommunityRefusedException 404 {@code not_published}
     */
    @Transactional
    public void unpublish(UUID userId, UUID recipeId, String ip) {
        requireEnabled();
        recipes.findById(recipeId).filter(r -> r.getUserId().equals(userId)).orElseThrow(RecipeRefusedException::notFound);
        List<PublishedRecipe> active = published.findByRecipeIdAndStatusIn(recipeId, ACTIVE);
        if (active.isEmpty()) throw notPublished();
        for (PublishedRecipe row : active) close(row, null, "author", userId, ip);
    }

    /**
     * Unpublishes the recipe's pending or public submission because its
     * author is deleting the recipe. Runs inside the caller's transaction,
     * whatever {@code godance.community.enabled} says.
     */
    @Transactional
    public void unpublishForDeletedRecipe(UUID userId, UUID recipeId, String ip) {
        for (PublishedRecipe row : published.findByRecipeIdAndStatusIn(recipeId, ACTIVE)) {
            close(row, null, "recipe_deleted", userId, ip);
        }
    }

    /**
     * Unpublishes every pending or public submission of an account that is
     * being closed. Runs inside the caller's transaction.
     *
     * @return how many were unpublished
     */
    @Transactional
    public int unpublishForClosedAccount(UUID userId, UUID actorId, String ip) {
        List<PublishedRecipe> active = published.findByUserIdAndStatusIn(userId, ACTIVE);
        for (PublishedRecipe row : active) close(row, null, "account_closed", actorId, ip);
        // A rejected or removed submission's copies are already gone or being retried; any left go too.
        for (PublishedRecipe row : published.findByUserIdAndStatusIn(userId,
                List.of(PublishedStatus.REJECTED, PublishedStatus.REMOVED))) {
            afterCommit(() -> copies.delete(row));
        }
        return active.size();
    }

    /**
     * Marks the submission {@code REMOVED} and, once the transaction
     * commits, deletes its example copies. {@code reason} is a moderator's;
     * null when the author or the system took it down.
     */
    void close(PublishedRecipe row, String reason, String by, UUID actorId, String ip) {
        Instant now = Instant.now();
        row.setStatus(PublishedStatus.REMOVED);
        row.setRemovedAt(now);
        row.setReason(reason);
        published.save(row);
        // Reports on it are answered: it is gone.
        reports.resolveForRecipe(row.getId(), actorId, now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("publicId", row.getPublicId());
        detail.put("by", by);
        if (actorId != null && !actorId.equals(row.getUserId())) detail.put("actorId", actorId.toString());
        audit.record(AUDIT_REMOVED, AuditOutcome.SUCCESS, row.getUserId(), ip, detail);
        afterCommit(() -> copies.delete(row));
    }

    /** Runs {@code action} once the current transaction commits; at once when there is none. */
    static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // ===== Running ======================================================

    /**
     * The public recipe a generation request names by {@code published_id}.
     * One that is not public, or does not exist, answers as a recipe that
     * does not exist.
     *
     * @throws RecipeRefusedException 404 {@code recipe_not_found}
     */
    @Transactional(readOnly = true)
    public PublishedRecipe runnable(String publicId) {
        if (!props.enabled() || publicId == null) throw RecipeRefusedException.notFound();
        return published.findByPublicId(publicId.trim())
                .filter(r -> r.getStatus() == PublishedStatus.PUBLIC)
                .orElseThrow(RecipeRefusedException::notFound);
    }

    /**
     * The public recipe a new recipe is started from, by public id
     * ({@code source_published_recipe} on {@code POST /v1/recipes}).
     *
     * @throws RecipeRefusedException 400 {@code invalid_recipe} when no public recipe has that id
     */
    @Transactional(readOnly = true)
    public UUID sourceOf(String publicId) {
        if (!props.enabled()) throw RecipeRefusedException.invalid("source_published_recipe names no public recipe");
        return published.findByPublicId(publicId.trim())
                .filter(r -> r.getStatus() == PublishedStatus.PUBLIC)
                .map(PublishedRecipe::getId)
                .orElseThrow(() -> RecipeRefusedException.invalid("source_published_recipe names no public recipe"));
    }

    // ===== Validation ===================================================

    private RecipeVersion version(Recipe recipe, Integer number) {
        if (number == null || number < 1) throw invalid("version is the number of one of the recipe's versions");
        return versions.findByRecipeIdAndNumber(recipe.getId(), number)
                .orElseThrow(() -> invalid("the recipe has no version " + number));
    }

    private String licence(String requested) {
        String licence = props.licence(requested);
        if (licence == null) {
            CommunityRefusedException e = invalid("licence must be one of " + String.join(", ", props.licences()));
            e.getBody().setProperty("licences", props.licences());
            throw e;
        }
        return licence;
    }

    static List<String> tags(List<String> requested) {
        if (requested == null) return new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        for (String raw : requested) {
            String tag = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!TAG.matcher(tag).matches() || tag.contains("--")) {
                throw invalid("a tag has 1 to 24 lowercase letters, digits and hyphens, starting with a letter or digit");
            }
            tags.add(tag);
        }
        if (tags.size() > TAGS_MAX) throw invalid("a recipe has at most " + TAGS_MAX + " tags");
        return new ArrayList<>(tags);
    }

    /**
     * The jobs chosen as examples: one to four, each the caller's own
     * finished, unpurged run of a version of this recipe, with at least one
     * output and no input file.
     */
    private List<GenerationJob> examples(UUID userId, Recipe recipe, List<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty() || jobIds.size() > EXAMPLES_MAX) {
            throw invalid("choose one to " + EXAMPLES_MAX + " of the recipe's finished jobs as examples");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : jobIds) {
            try {
                ids.add(UUID.fromString(raw == null ? "" : raw.trim()));
            } catch (IllegalArgumentException e) {
                throw invalidExample(raw, "is not a job id");
            }
        }
        if (ids.size() != jobIds.size()) throw invalid("each example job is named once");
        Set<UUID> versionIds = new LinkedHashSet<>();
        versions.findByRecipeIdOrderByNumberDesc(recipe.getId()).forEach(v -> versionIds.add(v.getId()));
        Map<UUID, GenerationJob> found = new LinkedHashMap<>();
        jobs.findAllById(ids).forEach(j -> found.put(j.getId(), j));
        List<GenerationJob> chosen = new ArrayList<>();
        for (UUID id : ids) {
            GenerationJob job = found.get(id);
            // Another account's job answers as one that is not a run of this recipe.
            if (job == null || !job.getUserId().equals(userId) || job.getRecipeVersionId() == null
                    || !versionIds.contains(job.getRecipeVersionId())) {
                throw invalidExample(id.toString(), "is not one of your runs of this recipe");
            }
            if (job.getStatus() != JobStatus.DONE) throw invalidExample(id.toString(), "has not finished");
            if (job.getPurgedAt() != null) throw invalidExample(id.toString(), "has had its files deleted");
            if (job.getModality() != recipe.getModality()) throw invalidExample(id.toString(), "is not a run of this recipe");
            chosen.add(job);
        }
        if (!jobInputs.findByJobIdInOrderByJobIdAscPositionAsc(ids).isEmpty()) {
            throw CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_EXAMPLE_HAS_INPUTS,
                    "an example used an input image; examples show no upload");
        }
        if (outputs.findByJobIdInOrderByJobIdAscOutputIndexAsc(ids).stream()
                .map(GenerationJobOutput::getJobId).distinct().count() != ids.size()) {
            throw invalid("every example job has a stored output");
        }
        return chosen;
    }

    // ===== Helpers ======================================================

    void requireEnabled() {
        if (!props.enabled()) throw CommunityRefusedException.disabled();
    }

    /** Twelve random lowercase letters and digits, not yet used. */
    String newPublicId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            char[] id = new char[12];
            for (int i = 0; i < id.length; i++) id[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            String candidate = new String(id);
            if (!published.existsByPublicId(candidate)) return candidate;
        }
        throw new IllegalStateException("could not find an unused public id");
    }

    PublishView view(PublishedRecipe row) {
        List<UUID> jobIds = examples.findByPublishedRecipeIdOrderByPositionAsc(row.getId()).stream()
                .map(PublishedRecipeExample::getSourceJobId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        return new PublishView(row.getPublicId(), row.getStatus().name(), row.getVersionNumber(), row.getLicence(),
                row.getTags(), jobIds, row.getReason(), row.getSubmittedAt(), row.getReviewedAt(), row.getPublishedAt());
    }

    private static List<Map<String, String>> copyList(List<Map<String, String>> from) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> item : from) out.add(new LinkedHashMap<>(item));
        return out;
    }

    private static CommunityRefusedException invalid(String detail) {
        return CommunityRefusedException.of(HttpStatus.BAD_REQUEST, CommunityRefusedException.CODE_INVALID_PUBLISH, detail);
    }

    private static CommunityRefusedException invalidExample(String jobId, String what) {
        CommunityRefusedException e = CommunityRefusedException.of(HttpStatus.BAD_REQUEST,
                CommunityRefusedException.CODE_INVALID_EXAMPLE, "the job " + jobId + " " + what);
        e.getBody().setProperty("job_id", jobId);
        return e;
    }

    static CommunityRefusedException notPublished() {
        return CommunityRefusedException.of(HttpStatus.NOT_FOUND, CommunityRefusedException.CODE_NOT_PUBLISHED,
                "this recipe is not published");
    }
}
