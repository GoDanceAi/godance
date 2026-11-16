package ai.godance.web;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.config.CommunityProperties;
import ai.godance.service.CommunityRefusedException;
import ai.godance.service.PublishedRecipeViews;
import ai.godance.web.dto.CommunityDtos.PublicProfileView;
import ai.godance.web.dto.CommunityDtos.PublicRecipeView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The public community routes (round 14 brief 01): {@code /v1/public/**},
 * readable without signing in. Only recipes a moderator approved, and only
 * the fields the contract lists. Every answer may be cached for
 * {@code godance.community.public-cache-ttl}.
 */
@RestController
@RequestMapping("/v1/public")
public class PublicCommunityController {

    /** A redirect to a signed link is cached for less than the link lasts (15 minutes). */
    private static final Duration REDIRECT_MAX_AGE = Duration.ofMinutes(10);

    private final PublishedRecipeViews views;
    private final CommunityProperties props;

    public PublicCommunityController(PublishedRecipeViews views, CommunityProperties props) {
        this.views = views;
        this.props = props;
    }

    /**
     * Public recipes, newest first: a bare array. {@code limit} 1–100
     * (default 50); {@code before} the public id of the last recipe of the
     * previous page; {@code tag} keeps those with that tag.
     */
    @GetMapping("/recipes")
    public Mono<ResponseEntity<List<PublicRecipeView>>> recipes(@RequestParam(required = false) String limit,
                                                                @RequestParam(required = false) String before,
                                                                @RequestParam(required = false) String tag,
                                                                ServerWebExchange exchange) {
        Integer size = Requests.limit(limit, PublishedRecipeViews.MAX_LIMIT);
        String base = Requests.apiBase(exchange);
        return Mono.fromCallable(() -> views.list(base, size, before, tag))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::cached);
    }

    @GetMapping("/recipes/{publicId}")
    public Mono<ResponseEntity<PublicRecipeView>> recipe(@PathVariable String publicId, ServerWebExchange exchange) {
        String base = Requests.apiBase(exchange);
        return Mono.fromCallable(() -> views.get(base, publicId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::cached);
    }

    /** An example copy: 302 to a short-lived signed link. */
    @GetMapping("/recipes/{publicId}/examples/{position}")
    public Mono<ResponseEntity<Void>> example(@PathVariable String publicId, @PathVariable String position) {
        int n = position(position);
        return Mono.fromCallable(() -> views.exampleLink(publicId, n))
                .subscribeOn(Schedulers.boundedElastic())
                .map(link -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(link))
                        .cacheControl(CacheControl.maxAge(min(REDIRECT_MAX_AGE, props.publicCacheTtl())).cachePublic())
                        .<Void>build());
    }

    @GetMapping("/profiles/{handle}")
    public Mono<ResponseEntity<PublicProfileView>> profile(@PathVariable String handle, ServerWebExchange exchange) {
        String base = Requests.apiBase(exchange);
        return Mono.fromCallable(() -> views.profile(base, handle))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::cached);
    }

    private <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(props.publicCacheTtl()).cachePublic()).body(body);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static int position(String raw) {
        try {
            int n = Integer.parseInt(raw.trim());
            if (n >= 1) return n;
        } catch (NumberFormatException ignored) {
            // falls through to the 404
        }
        throw CommunityRefusedException.notFound("example");
    }
}
