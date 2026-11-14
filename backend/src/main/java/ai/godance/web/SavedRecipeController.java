package ai.godance.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.security.JwtService;
import ai.godance.service.SavedRecipeService;
import ai.godance.web.dto.SavedRecipeDtos.SavedRecipeView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The gallery recipes a user saved to open in the Studio again. An app
 * feature: every route needs a signed-in session and falls under
 * {@code SecurityConfig}'s deny-by-default rule, so an API key is refused
 * with a 403.
 */
@RestController
@RequestMapping("/v1/saved-recipes")
public class SavedRecipeController {

    private final SavedRecipeService recipes;

    public SavedRecipeController(SavedRecipeService recipes) {
        this.recipes = recipes;
    }

    /** The caller's saved recipes, newest first: a bare array of {@code {slug, savedAt}}. */
    @GetMapping
    public Mono<List<SavedRecipeView>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> recipes.list(userId).stream().map(SavedRecipeView::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Saves a recipe. 204, also when it was already saved. A 400 for a slug
     * that is not a gallery slug; a 409 problem+json with {@code code}
     * {@code saved_recipes_limit_reached} at the cap.
     */
    @PutMapping("/{slug}")
    public Mono<ResponseEntity<Void>> save(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> recipes.save(userId, slug))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /** Un-saves a recipe. 204, also when it was not saved. */
    @DeleteMapping("/{slug}")
    public Mono<ResponseEntity<Void>> unsave(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> recipes.unsave(userId, slug))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
