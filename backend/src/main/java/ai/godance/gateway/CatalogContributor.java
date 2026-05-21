package ai.godance.gateway;

import java.util.List;

import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Supplies a slice of the model catalog. Each modality (text, image,
 * video) ships its own {@code @Component} implementation under
 * {@link ai.godance.gateway.catalog}; {@link ModelCatalog} autowires
 * every contributor and merges their models into one registry.
 *
 * <p>This keeps modality catalogs independently editable — adding a
 * provider in one modality never touches another's file.
 */
public interface CatalogContributor {

    /** Models this contributor registers. Model names must be globally unique. */
    List<ModelInfo> models();
}
