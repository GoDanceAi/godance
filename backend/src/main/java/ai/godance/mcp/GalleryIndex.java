package ai.godance.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import ai.godance.service.GalleryRecipeSlug;
import reactor.core.publisher.Mono;

/**
 * The published gallery, as {@code search_recipes} and {@code get_recipe}
 * read it: the site's own static JSON ({@code /gallery/index.json},
 * {@code /gallery/_search.json} and {@code /gallery/<slug>.json}), so a tool
 * shows exactly what the gallery shows and nothing it does not publish.
 *
 * <p>The index is kept for {@code godance.mcp.gallery-cache-ttl}. While the
 * gallery is switched off the site has no such files: the index is then
 * empty and marked unavailable, and that answer is kept for a minute, so the
 * site is not asked on every call.
 */
@Component
class GalleryIndex {

    private static final Logger log = LoggerFactory.getLogger(GalleryIndex.class);
    private static final Duration UNAVAILABLE_TTL = Duration.ofMinutes(1);
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    /** One fetch of the index: its entries in the gallery's order, and each one's search text. */
    record Snapshot(boolean available, List<Map<String, Object>> recipes, Map<String, String> searchText,
                    Instant expiresAt) { }

    private final WebClient client;
    private final McpProperties props;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>();

    GalleryIndex(WebClient.Builder builder, McpProperties props) {
        String base = props.galleryUrl().toString();
        this.client = builder.clone()
                .baseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BYTES))
                .build();
        this.props = props;
    }

    /** The index, from the cache while it is fresh. */
    Mono<Snapshot> index() {
        Snapshot current = cached.get();
        if (current != null && Instant.now().isBefore(current.expiresAt())) return Mono.just(current);
        return fetch("/index.json")
                .zipWith(fetch("/_search.json").defaultIfEmpty(Map.of()))
                .map(both -> snapshot(both.getT1(), both.getT2()))
                .defaultIfEmpty(new Snapshot(false, List.of(), Map.of(), Instant.now().plus(UNAVAILABLE_TTL)))
                .doOnNext(cached::set);
    }

    /**
     * One published recipe, as {@code /gallery/<slug>.json} has it; empty
     * when there is no such recipe or the gallery is off. A slug that could
     * not be a gallery slug is refused before anything is fetched.
     */
    Mono<Optional<Map<String, Object>>> recipe(String slug) {
        if (!GalleryRecipeSlug.isValid(slug) || slug.startsWith("_") || "index".equals(slug)) {
            return Mono.just(Optional.empty());
        }
        return fetch("/" + slug + ".json").map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** The page of a gallery recipe on the site. */
    String pageUrl(String slug) {
        String base = props.galleryUrl().toString();
        return (base.endsWith("/") ? base : base + "/") + slug + "/";
    }

    private Snapshot snapshot(Map<String, Object> index, Map<String, Object> search) {
        List<Map<String, Object>> recipes = new ArrayList<>();
        if (index.get("recipes") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("slug") instanceof String) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> recipe = (Map<String, Object>) map;
                    recipes.add(recipe);
                }
            }
        }
        Map<String, String> text = new HashMap<>();
        if (search.get("recipes") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("slug") instanceof String slug
                        && map.get("text") instanceof String body) {
                    text.put(slug, body.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new Snapshot(true, List.copyOf(recipes), Map.copyOf(text),
                Instant.now().plus(props.galleryCacheTtl()));
    }

    /** A JSON object from the site; empty when it is not there (404) or cannot be read in time. */
    private Mono<Map<String, Object>> fetch(String path) {
        return client.get().uri(path)
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .timeout(props.galleryTimeout())
                .onErrorResume(WebClientResponseException.class, e -> {
                    if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                        log.warn("gallery {} answered {}", path, e.getStatusCode().value());
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("gallery {} could not be read: {}", path, e.toString());
                    return Mono.empty();
                });
    }
}
