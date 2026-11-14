package ai.godance.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A gallery recipe a user saved to come back to (V27). Only the recipe's
 * slug is kept; the recipe itself lives in the gallery on the marketing
 * site. Rows are written by {@code SavedRecipeRepository.insertIfAbsent}.
 */
@Entity
@Table(name = "saved_recipes")
public class SavedRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;

    protected SavedRecipe() { }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getSlug() { return slug; }
    public Instant getSavedAt() { return savedAt; }
}
