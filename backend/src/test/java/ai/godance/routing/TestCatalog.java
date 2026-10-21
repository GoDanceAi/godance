package ai.godance.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Hand-written {@link ModelCatalog} stub for routing unit tests. Avoids
 * a mocking framework (repo convention) and the DB repository entirely:
 * {@code find}/{@code all} are overridden to read a fixed in-memory map,
 * so the {@code null} repository is never touched.
 */
final class TestCatalog {

    private TestCatalog() { }

    static ModelCatalog of(ModelInfo... models) {
        Map<String, ModelInfo> map = new LinkedHashMap<>();
        for (ModelInfo m : models) map.put(m.modelName(), m);
        return new ModelCatalog(null) {
            @Override
            public Optional<ModelInfo> find(String modelName) {
                return Optional.ofNullable(map.get(modelName));
            }

            @Override
            public List<ModelInfo> all() {
                return List.copyOf(map.values());
            }
        };
    }
}
