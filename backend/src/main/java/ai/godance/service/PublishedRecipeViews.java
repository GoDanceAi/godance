package ai.godance.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.CommunityProperties;
import ai.godance.domain.Profile;
import ai.godance.domain.PublishedRecipe;
import ai.godance.domain.PublishedRecipeExample;
import ai.godance.domain.PublishedStatus;
import ai.godance.repository.FollowRepository;
import ai.godance.repository.ProfileRepository;
import ai.godance.repository.PublishedRecipeExampleRepository;
import ai.godance.repository.PublishedRecipeRepository;
import ai.godance.web.dto.CommunityDtos.MadeFrom;
import ai.godance.web.dto.CommunityDtos.PublicAuthor;
import ai.godance.web.dto.CommunityDtos.PublicExample;
import ai.godance.web.dto.CommunityDtos.PublicInputSlot;
import ai.godance.web.dto.CommunityDtos.PublicProfileView;
import ai.godance.web.dto.CommunityDtos.PublicRecipeView;
import ai.godance.web.dto.CommunityDtos.PublicVariable;

/**
 * What the public routes show of published recipes and profiles (round 14
 * brief 01): only {@code PUBLIC} recipes, only the fields the contract
 * lists, and a profile only as a moderator approved it. Every query is
 * paged or bounded and index-backed; a page's examples, counts and authors
 * are read in one query each.
 */
@Service
public class PublishedRecipeViews {

    /** Largest page {@code limit} accepts, and the page when none is asked for. */
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 50;

    /** A cursor above every row: the first page. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID HIGHEST_UUID = new UUID(-1L, -1L);

    private final PublishedRecipeRepository published;
    private final PublishedRecipeExampleRepository examples;
    private final ProfileRepository profiles;
    private final FollowRepository follows;
    private final PublishedExamples copies;
    private final CommunityProperties props;

    public PublishedRecipeViews(PublishedRecipeRepository published, PublishedRecipeExampleRepository examples,
                                ProfileRepository profiles, FollowRepository follows, PublishedExamples copies,
                                CommunityProperties props) {
        this.published = published;
        this.examples = examples;
        this.profiles = profiles;
        this.follows = follows;
        this.copies = copies;
        this.props = props;
    }

    /**
     * Public recipes, newest first. {@code before} is the public id of the
     * last recipe of the previous page; {@code tag} keeps those that carry it.
     */
    @Transactional(readOnly = true)
    public List<PublicRecipeView> list(String apiBase, Integer limit, String before, String tag) {
        requireEnabled();
        int size = limit(limit);
        PublishedRecipe cursor = cursor(before);
        String wanted = tag == null || tag.isBlank() ? null : tag(tag);
        List<PublishedRecipe> page = published.findPublicPage(
                cursor == null ? END_OF_TIME : cursor.getPublishedAt(),
                cursor == null ? HIGHEST_UUID : cursor.getId(), wanted, size);
        return views(apiBase, page);
    }

    /** One public recipe. @throws CommunityRefusedException 404 {@code not_found} for any that is not public */
    @Transactional(readOnly = true)
    public PublicRecipeView get(String apiBase, String publicId) {
        requireEnabled();
        return views(apiBase, List.of(requirePublic(publicId))).get(0);
    }

    /**
     * A profile as a moderator approved it, its follower count and its public
     * recipes. A profile is public only once approved and while its owner has
     * a public recipe.
     */
    @Transactional(readOnly = true)
    public PublicProfileView profile(String apiBase, String handle) {
        requireEnabled();
        String normalised = handle == null ? "" : handle.trim().toLowerCase(Locale.ROOT);
        Profile profile = profiles.findByHandle(normalised).filter(Profile::isApproved)
                .orElseThrow(() -> CommunityRefusedException.notFound("profile"));
        List<PublishedRecipe> recipes = published.findPublicByAuthor(profile.getUserId(), props.maxPublishedPerUser());
        if (recipes.isEmpty()) throw CommunityRefusedException.notFound("profile");
        return new PublicProfileView(profile.getHandle(), profile.getApprovedDisplayName(), profile.getApprovedLink(),
                follows.countByFolloweeId(profile.getUserId()), views(apiBase, recipes));
    }

    /** The public recipes of the accounts the caller follows, newest first. */
    @Transactional(readOnly = true)
    public List<PublicRecipeView> feed(String apiBase, UUID userId, Integer limit, String before) {
        requireEnabled();
        int size = limit(limit);
        PublishedRecipe cursor = cursor(before);
        List<PublishedRecipe> page = published.findFeedPage(userId,
                cursor == null ? END_OF_TIME : cursor.getPublishedAt(),
                cursor == null ? HIGHEST_UUID : cursor.getId(), size);
        return views(apiBase, page);
    }

    /**
     * Where an example copy of a public recipe can be read: a signed link.
     *
     * @throws CommunityRefusedException 404 {@code not_found}
     */
    @Transactional(readOnly = true)
    public String exampleLink(String publicId, int position) {
        requireEnabled();
        PublishedRecipe recipe = requirePublic(publicId);
        return examples.findByPublishedRecipeIdOrderByPositionAsc(recipe.getId()).stream()
                .filter(e -> e.getPosition() == position && e.getStorageKey() != null)
                .findFirst()
                .map(copies::signedUrl)
                .orElseThrow(() -> CommunityRefusedException.notFound("example"));
    }

    // ===== Building the views ===========================================

    /** The views of these recipes, in their order: examples, counts, authors and sources read once for the page. */
    List<PublicRecipeView> views(String apiBase, List<PublishedRecipe> page) {
        if (page.isEmpty()) return List.of();
        List<UUID> ids = page.stream().map(PublishedRecipe::getId).toList();
        Map<UUID, List<PublishedRecipeExample>> examplesOf = examples.findByPublishedRecipeIdInOrderByPositionAsc(ids)
                .stream().filter(e -> e.getStorageKey() != null)
                .collect(Collectors.groupingBy(PublishedRecipeExample::getPublishedRecipeId, LinkedHashMap::new,
                        Collectors.toList()));
        Map<UUID, PublishedRecipeRepository.RunCounts> counts = published.runCounts(ids).stream()
                .collect(Collectors.toMap(PublishedRecipeRepository.RunCounts::getPublishedRecipeId, Function.identity()));
        List<UUID> authorIds = page.stream().map(PublishedRecipe::getUserId).distinct().toList();
        Map<UUID, Profile> authors = profiles.findByUserIdIn(authorIds).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        Map<UUID, Long> followers = new HashMap<>();
        follows.followerCounts(authorIds).forEach(c -> followers.put(c.getFolloweeId(), c.getFollowers()));
        Map<UUID, String> sources = madeFromPublicIds(page.stream()
                .map(PublishedRecipe::getMadeFromPublishedId).filter(Objects::nonNull).distinct().toList());

        return page.stream().map(r -> {
            Profile author = authors.get(r.getUserId());
            PublishedRecipeRepository.RunCounts c = counts.get(r.getId());
            List<PublicExample> shown = examplesOf.getOrDefault(r.getId(), List.of()).stream()
                    .map(e -> new PublicExample(copies.publicUrl(apiBase, r, e), e.getContentType()))
                    .toList();
            return new PublicRecipeView(
                    r.getPublicId(),
                    r.getTitle(),
                    r.getModality().name().toLowerCase(Locale.ROOT),
                    r.getTemplate(),
                    r.getVariables().stream().map(v -> new PublicVariable(v.get("name"), v.get("default"))).toList(),
                    r.getInputs().stream().map(s -> new PublicInputSlot(s.get("slot"), s.get("role"), s.get("hint"))).toList(),
                    r.getModel(),
                    r.getParams(),
                    shown,
                    madeFrom(r, sources),
                    c == null ? 0 : c.getRuns(),
                    c == null ? 0 : c.getRunners(),
                    r.getLicence(),
                    new PublicAuthor(author == null ? null : author.getHandle(), followers.getOrDefault(r.getUserId(), 0L)),
                    r.getTags(),
                    r.getPublishedAt());
        }).toList();
    }

    /** The public ids of the source recipes that are still public; one that is not is never named. */
    private Map<UUID, String> madeFromPublicIds(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return published.findByIdIn(ids).stream()
                .filter(s -> s.getStatus() == PublishedStatus.PUBLIC)
                .collect(Collectors.toMap(PublishedRecipe::getId, PublishedRecipe::getPublicId));
    }

    private static MadeFrom madeFrom(PublishedRecipe r, Map<UUID, String> sources) {
        if (r.getMadeFromPublishedId() != null) {
            String source = sources.get(r.getMadeFromPublishedId());
            return source == null ? null : new MadeFrom("published", source);
        }
        return r.getMadeFromGallery() == null ? null : new MadeFrom("gallery", r.getMadeFromGallery());
    }

    // ===== Helpers ======================================================

    PublishedRecipe requirePublic(String publicId) {
        String id = publicId == null ? "" : publicId.trim();
        return published.findByPublicId(id)
                .filter(r -> r.getStatus() == PublishedStatus.PUBLIC)
                .orElseThrow(() -> CommunityRefusedException.notFound("recipe"));
    }

    private PublishedRecipe cursor(String before) {
        if (before == null || before.isBlank()) return null;
        return published.findByPublicId(before.trim())
                .filter(r -> r.getStatus() == PublishedStatus.PUBLIC)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "before must be the public id of a public recipe"));
    }

    private static int limit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static String tag(String raw) {
        String tag = raw.trim().toLowerCase(Locale.ROOT);
        if (!PublishService.TAG.matcher(tag).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tag is 1 to 24 lowercase letters, digits and hyphens");
        }
        return tag;
    }

    private void requireEnabled() {
        if (!props.enabled()) throw CommunityRefusedException.disabled();
    }
}
