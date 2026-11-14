package ai.godance.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One version of a {@link Recipe} (V29): the template, its variables, and
 * the model and parameters to run it with. Written once and never changed,
 * so every column is insert-only.
 */
@Entity
@Table(name = "recipe_versions")
public class RecipeVersion {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipe_id", nullable = false, updatable = false)
    private UUID recipeId;

    /** 1, 2, 3… within the recipe. */
    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    /** The prompt, with each variable as a {{name}} marker. */
    @Column(name = "template", columnDefinition = "text", nullable = false, updatable = false)
    private String template;

    /** Each variable as {@code {"name": …, "default": …}}, in order. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", columnDefinition = "jsonb", nullable = false, updatable = false)
    private List<Map<String, String>> variables;

    @Column(name = "model", nullable = false, updatable = false)
    private String model;

    /** Request fields as the API names them: size, n, seconds, … */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> params;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getRecipeId() { return recipeId; }
    public void setRecipeId(UUID recipeId) { this.recipeId = recipeId; }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public List<Map<String, String>> getVariables() { return variables; }
    public void setVariables(List<Map<String, String>> variables) { this.variables = variables; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecipeVersion other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
