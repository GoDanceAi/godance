package ai.godance.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.godance.domain.CatalogStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.Provider;

class ModelAliasResolverTest {

    private static final Map<String, Map<String, String>> ALIASES = Map.of(
            "openrouter", Map.of("anthropic/claude-opus-4-7", "claude-opus-4-7"),
            "litellm",    Map.of());

    private static final List<ModelCatalogEntry> SNAPSHOT = List.of(
            entry("claude-opus-4-7",     Provider.ANTHROPIC, Modality.TEXT),
            entry("gpt-5.5",             Provider.OPENAI,    Modality.TEXT),
            entry("gpt-5.4-nano",        Provider.OPENAI,    Modality.TEXT),
            entry("deepseek-v4-pro",     Provider.DEEPSEEK,  Modality.TEXT),
            entry("seedance-2.0",        Provider.VOLCENGINE_ARK, Modality.VIDEO));

    @Test
    void resolve_aliasedId_returnsKnown() {
        ModelAliasResolver r = new ModelAliasResolver(ALIASES, SNAPSHOT);
        var res = r.resolve("openrouter", "anthropic/claude-opus-4-7");
        assertThat(res.kind()).isEqualTo(ModelAliasResolver.Resolution.Kind.KNOWN);
        assertThat(res.godanceModelName()).isEqualTo("claude-opus-4-7");
        assertThat(res.provider()).isEqualTo(Provider.ANTHROPIC);
    }

    @Test
    void resolve_bareIdMatchesCatalog_returnsKnown() {
        ModelAliasResolver r = new ModelAliasResolver(ALIASES, SNAPSHOT);
        var res = r.resolve("litellm", "claude-opus-4-7");
        assertThat(res.kind()).isEqualTo(ModelAliasResolver.Resolution.Kind.KNOWN);
    }

    @Test
    void resolve_newVersionOfKnownFamily_returnsNewVersion() {
        ModelAliasResolver r = new ModelAliasResolver(ALIASES, SNAPSHOT);
        var res = r.resolve("openrouter", "anthropic/claude-opus-4-8");
        assertThat(res.kind()).isEqualTo(ModelAliasResolver.Resolution.Kind.NEW_VERSION);
        assertThat(res.godanceModelName()).isEqualTo("claude-opus-4-8");
        assertThat(res.provider()).isEqualTo(Provider.ANTHROPIC);
    }

    @Test
    void resolve_brandNewId_returnsNewModel() {
        ModelAliasResolver r = new ModelAliasResolver(ALIASES, SNAPSHOT);
        var res = r.resolve("openrouter", "xai/grok-mythos-5-0");
        assertThat(res.kind()).isEqualTo(ModelAliasResolver.Resolution.Kind.NEW_MODEL);
        assertThat(res.provider()).isEqualTo(Provider.XAI);
    }

    @Test
    void resolve_familyMatchOnlyAcrossSameProvider() {
        // Hypothetical xAI "gpt-5" would NOT be a NEW_VERSION of OpenAI's gpt family.
        ModelAliasResolver r = new ModelAliasResolver(ALIASES, SNAPSHOT);
        var res = r.resolve("openrouter", "xai/gpt-5-7");
        assertThat(res.kind()).isEqualTo(ModelAliasResolver.Resolution.Kind.NEW_MODEL);
    }

    private static ModelCatalogEntry entry(String name, Provider p, Modality m) {
        ModelCatalogEntry e = new ModelCatalogEntry();
        e.setModelName(name);
        e.setProvider(p);
        e.setModality(m);
        e.setStatus(CatalogStatus.ACTIVE);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }
}
