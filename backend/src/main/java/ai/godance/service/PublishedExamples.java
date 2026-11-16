package ai.godance.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ai.godance.config.CommunityProperties;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.PublishedRecipe;
import ai.godance.domain.PublishedRecipeExample;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.PublishedRecipeExampleRepository;
import ai.godance.repository.PublishedRecipeRepository;
import ai.godance.storage.ObjectStorageClient;

/**
 * The example copies of published recipes (round 14 brief 01). Approving a
 * recipe copies each chosen output to {@code community/<public_id>/}, which
 * the 90-day purge of job media ({@code images/}, {@code videos/}) never
 * touches; unpublishing, rejecting or removing it deletes them. The public
 * routes serve the copies, never the source job's files.
 */
@Component
public class PublishedExamples {

    private static final Logger log = LoggerFactory.getLogger(PublishedExamples.class);

    /** Where every example copy lives. */
    public static final String PREFIX = "community/";

    private final PublishedRecipeRepository published;
    private final PublishedRecipeExampleRepository examples;
    private final GenerationJobOutputRepository outputs;
    private final ObjectStorageClient storage;
    private final CommunityProperties props;
    private final TransactionTemplate tx;

    public PublishedExamples(PublishedRecipeRepository published, PublishedRecipeExampleRepository examples,
                             GenerationJobOutputRepository outputs, ObjectStorageClient storage,
                             CommunityProperties props, PlatformTransactionManager txManager) {
        this.published = published;
        this.examples = examples;
        this.outputs = outputs;
        this.storage = storage;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** The prefix holding one published recipe's copies. */
    static String prefixOf(PublishedRecipe recipe) {
        return PREFIX + recipe.getPublicId() + "/";
    }

    /**
     * Copies every example of the recipe that has no copy yet and returns the
     * examples, each with its copy's key. Storage is outside any transaction:
     * the caller records the keys. A copy made before a failure is deleted
     * again, and nothing is recorded.
     *
     * @throws CommunityRefusedException 409 {@code example_unavailable} when
     *         a source output is gone (its job was purged) or cannot be copied
     */
    public List<Copied> copy(PublishedRecipe recipe) {
        List<PublishedRecipeExample> rows = examples.findByPublishedRecipeIdOrderByPositionAsc(recipe.getId());
        List<UUID> jobIds = rows.stream().map(PublishedRecipeExample::getSourceJobId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, GenerationJobOutput> byJobAndIndex = jobIds.isEmpty() ? Map.of()
                : outputs.findByJobIdInOrderByJobIdAscOutputIndexAsc(jobIds).stream()
                        .collect(Collectors.toMap(o -> o.getJobId() + "#" + o.getOutputIndex(), Function.identity()));
        List<Copied> copied = new ArrayList<>();
        try {
            for (PublishedRecipeExample row : rows) {
                if (row.getStorageKey() != null) {
                    copied.add(new Copied(row.getId(), row.getStorageKey(), row.getContentType(), row.getSizeBytes()));
                    continue;
                }
                GenerationJobOutput source = row.getSourceJobId() == null ? null
                        : byJobAndIndex.get(row.getSourceJobId() + "#" + row.getSourceOutputIndex());
                if (source == null) {
                    throw CommunityRefusedException.of(org.springframework.http.HttpStatus.CONFLICT,
                            CommunityRefusedException.CODE_EXAMPLE_UNAVAILABLE,
                            "example " + row.getPosition() + " is no longer available: its job's files were deleted");
                }
                String target = prefixOf(recipe) + row.getPosition() + extensionOf(source.getStorageKey());
                storage.copy(source.getStorageKey(), target);
                copied.add(new Copied(row.getId(), target, source.getContentType(), source.getSizeBytes()));
            }
            return copied;
        } catch (CommunityRefusedException e) {
            discard(recipe);
            throw e;
        } catch (RuntimeException e) {
            discard(recipe);
            log.warn("community: could not copy the examples of {}: {}", recipe.getPublicId(), e.getMessage());
            throw CommunityRefusedException.of(org.springframework.http.HttpStatus.CONFLICT,
                    CommunityRefusedException.CODE_EXAMPLE_UNAVAILABLE,
                    "the examples could not be copied; try again, or reject the submission");
        }
    }

    /** An example copied to public storage: its row, its key, its type and size. */
    public record Copied(UUID exampleId, String key, String contentType, Long sizeBytes) { }

    /** Records the copies' keys on their rows. Runs inside the caller's transaction. */
    public void record(List<Copied> copied) {
        for (Copied c : copied) {
            examples.findById(c.exampleId()).ifPresent(row -> {
                row.setStorageKey(c.key());
                row.setContentType(c.contentType());
                row.setSizeBytes(c.sizeBytes());
                examples.save(row);
            });
        }
    }

    /**
     * Deletes the recipe's copies, then its example rows. A storage failure
     * leaves the rows, so the nightly run ({@link #sweep}) tries again; until
     * then nothing serves them, since the recipe is no longer public.
     *
     * @return whether everything was deleted
     */
    public boolean delete(PublishedRecipe recipe) {
        try {
            storage.deletePrefix(prefixOf(recipe));
            tx.executeWithoutResult(status -> examples.deleteByPublishedRecipeId(recipe.getId()));
            return true;
        } catch (RuntimeException e) {
            log.warn("community: could not delete the example copies of {}: {}", recipe.getPublicId(), e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the copies and example rows of submissions that are no longer
     * public and still have some: what a failed delete left, retried by the
     * nightly retention run. Returns how many submissions were cleared.
     */
    public int sweepClosed(int limit) {
        int cleared = 0;
        for (PublishedRecipe recipe : published.findClosedWithExamples(limit)) {
            if (delete(recipe)) cleared++;
        }
        return cleared;
    }

    /**
     * Deletes the recipe's copies in storage and leaves its example rows: for
     * copies made for an approval that did not happen. Logs a failure.
     */
    void discard(PublishedRecipe recipe) {
        try {
            storage.deletePrefix(prefixOf(recipe));
        } catch (RuntimeException e) {
            log.warn("community: could not clear the partial copies of {}: {}", recipe.getPublicId(), e.getMessage());
        }
    }

    /**
     * The URL a public route gives for an example copy: under the operator's
     * media base when one is set, else the API's own redirect route.
     */
    public String publicUrl(String apiBase, PublishedRecipe recipe, PublishedRecipeExample example) {
        if (props.hasMediaBase()) {
            return props.mediaBaseUrl() + example.getStorageKey().substring(PREFIX.length());
        }
        return apiBase + "/v1/public/recipes/" + recipe.getPublicId() + "/examples/" + example.getPosition();
    }

    /** A short-lived signed link to an example copy: where the API's redirect route sends a reader. */
    public String signedUrl(PublishedRecipeExample example) {
        return storage.presignedUrl(example.getStorageKey(), java.time.Duration.ofMinutes(15));
    }

    /** A signed link to an output of the source job: what a moderator reviews before approval. */
    public String reviewUrl(String storageKey) {
        return storage.presignedUrl(storageKey, java.time.Duration.ofMinutes(15));
    }

    private static String extensionOf(String key) {
        int slash = key.lastIndexOf('/');
        int dot = key.lastIndexOf('.');
        if (dot <= slash || key.length() - dot > 6) return "";
        return key.substring(dot).toLowerCase(Locale.ROOT);
    }
}
