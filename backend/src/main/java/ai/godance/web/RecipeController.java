package ai.godance.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.RecipeRefusedException;
import ai.godance.service.RecipeService;
import ai.godance.web.dto.RecipeDtos.CreateRecipeRequest;
import ai.godance.web.dto.RecipeDtos.NewVersionRequest;
import ai.godance.web.dto.RecipeDtos.RecipeVersionView;
import ai.godance.web.dto.RecipeDtos.RecipeView;
import ai.godance.web.dto.RecipeDtos.RenameRecipeRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A user's own recipes: {@code /v1/recipes}. A signed-in session or an API
 * key, like the job routes. Every recipe is private to its owner: one that
 * is not the caller's answers 404 {@code recipe_not_found}, exactly as one
 * that does not exist.
 *
 * <p>{@code GET /v1/recipes} is a bare array, newest first, with optional
 * {@code limit} (1–100) and {@code before} (a recipe id: only older ones),
 * as {@code GET /v1/jobs} is.
 */
@RestController
@RequestMapping("/v1/recipes")
public class RecipeController {

    private final RecipeService recipes;

    public RecipeController(RecipeService recipes) {
        this.recipes = recipes;
    }

    @PostMapping
    public Mono<ResponseEntity<RecipeView>> create(@RequestBody CreateRecipeRequest req,
                                                   @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> recipes.create(userId, req, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .map(recipe -> ResponseEntity.status(HttpStatus.CREATED).body(recipe));
    }

    @GetMapping
    public Mono<List<RecipeView>> list(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) String limit,
                                       @RequestParam(required = false) String before) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer pageSize = parseLimit(limit);
        UUID cursor = before == null ? null : parseId(before, "before must be a recipe id");
        return Mono.fromCallable(() -> recipes.list(userId, pageSize, cursor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<RecipeView> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        return Mono.fromCallable(() -> recipes.get(userId, recipeId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Renames the recipe: {@code {"title": "…"}}. */
    @PatchMapping("/{id}")
    public Mono<RecipeView> rename(@PathVariable String id, @RequestBody RenameRecipeRequest req,
                                   @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        return Mono.fromCallable(() -> recipes.rename(userId, recipeId, req == null ? null : req.title()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Deletes the recipe and its versions. Its jobs are kept. */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        String ip = clientIp(exchange);
        return Mono.fromRunnable(() -> recipes.delete(userId, recipeId, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/{id}/versions")
    public Mono<List<RecipeVersionView>> versions(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        return Mono.fromCallable(() -> recipes.versions(userId, recipeId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 201 with the new version, or 200 with the latest one when nothing had changed. */
    @PostMapping("/{id}/versions")
    public Mono<ResponseEntity<RecipeVersionView>> newVersion(@PathVariable String id, @RequestBody NewVersionRequest req,
                                                              @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        return Mono.fromCallable(() -> recipes.newVersion(userId, recipeId, req))
                .subscribeOn(Schedulers.boundedElastic())
                .map(saved -> ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(saved.version()));
    }

    /** A recipe id from the path. Anything that is not an id names no recipe: the same 404. */
    private static UUID recipeId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw RecipeRefusedException.notFound();
        }
    }

    private static UUID parseId(String raw, String detail) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
        }
    }

    private static Integer parseLimit(String raw) {
        if (raw == null) return null;
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= RecipeService.MAX_LIMIT) return limit;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be an integer between 1 and " + RecipeService.MAX_LIMIT);
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
