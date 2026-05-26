package ai.godance.gateway;

import java.util.List;

import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Historical contributor interface — kept as the source-of-record for the
 * V11 catalog seed so the SQL {@code INSERT}s are reviewable side-by-side
 * with the original code values at commit e10be63.
 *
 * <p><b>No longer a runtime read path.</b> {@link ModelCatalog} now loads
 * from the {@code model_catalog} table; the three implementations under
 * {@link ai.godance.gateway.catalog} are plain classes (not {@code
 * @Component} beans) referenced only by their unit tests and by V11's
 * comment header. Plan is to delete them entirely once the V11 seed has
 * one production cycle behind it.
 */
public interface CatalogContributor {

    /** Models this contributor registers. Model names must be globally unique. */
    List<ModelInfo> models();
}
