package ai.godance.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.ModelMetadata;
import ai.godance.routing.RoutingPolicy;
import ai.godance.routing.RoutingPolicyRegistry;
import reactor.core.publisher.Mono;

/**
 * OpenAI-compatible model enumeration. Drop-in for clients that call
 * {@code client.models.list()}. The OpenAI shape is:
 *
 * <pre>{@code
 * { "object": "list",
 *   "data": [ { "id": "gpt-4o", "object": "model", "created": 1700000000,
 *               "owned_by": "openai" }, ... ] }
 * }</pre>
 *
 * <p>Godance extends each entry with {@code godance: {modality, pricing}}
 * — pure OpenAI clients ignore unknown fields. A concrete entry may also
 * carry {@code display_name}, {@code description} and {@code capabilities}
 * (sizes, default size, durations, default length, {@code max_n}); each is
 * left out when the catalog does not know it. {@code pricing} is the
 * provider list price; {@code markup_percent} is the platform fee billed on
 * top of it, resolved by {@link CostCalculator} exactly as at charge time.
 *
 * <p>Virtual routing ids ({@code godance-fast/auto/quality}) are appended
 * after the concrete catalog as purely additive entries (owned_by
 * {@code "godance"}, {@code godance.virtual = true}) so OpenAI clients can
 * discover them via {@code client.models.list()}. They have no
 * {@code pricing} block: a routed call is billed at the concrete model that
 * serves it, so a virtual id has no fixed price. Consumers that read
 * {@code godance.pricing} (the app's chat presets, the marketing catalog)
 * skip {@code godance.virtual} entries.
 */
@RestController
@RequestMapping("/v1")
public class ModelsController {

    private final ModelCatalog catalog;
    private final RoutingPolicyRegistry routing;
    private final CostCalculator costs;

    public ModelsController(ModelCatalog catalog, RoutingPolicyRegistry routing,
                            CostCalculator costs) {
        this.catalog = catalog;
        this.routing = routing;
        this.costs = costs;
    }

    @GetMapping("/models")
    public Mono<ModelListResponse> list() {
        List<ModelEntry> entries = new ArrayList<>();
        catalog.all().forEach(m -> entries.add(toEntry(m)));      // concrete catalog, unchanged
        routing.all().forEach(p -> entries.add(toVirtualEntry(p))); // virtual ids, additive
        return Mono.just(new ModelListResponse("list", entries));
    }

    private static ModelEntry toVirtualEntry(RoutingPolicy p) {
        Map<String, Object> godance = Map.of(
                "virtual",  true,
                "modality", p.modality().name());
        return new ModelEntry(p.id(), "model", "godance", godance);
    }

    private ModelEntry toEntry(ModelInfo m) {
        Map<String, Object> pricing = switch (m.modality()) {
            case TEXT, EMBEDDING, AUDIO_STT, AUDIO_TTS -> Map.of(
                    "input_microcents_per_1k",  m.inputMicrocentsPer1k(),
                    "output_microcents_per_1k", m.outputMicrocentsPer1k());
            case IMAGE -> Map.of("per_image_microcents", m.perImageMicrocents());
            case VIDEO -> Map.of("per_second_microcents", m.perSecondMicrocents());
        };
        Map<String, Object> godance = new LinkedHashMap<>();
        godance.put("modality",       m.modality().name());
        godance.put("requires_async", m.requiresAsync());
        godance.put("pricing",        pricing);
        godance.put("markup_percent", costs.markupPercent(m));
        // V24 metadata: each part only when the catalog has it. Absent
        // capabilities mean unknown, not unlimited; a missing max_n is 1.
        ModelMetadata meta = catalog.metadata(m.modelName());
        if (meta.displayName() != null) godance.put("display_name", meta.displayName());
        if (meta.description() != null) godance.put("description", meta.description());
        if (!meta.capabilities().isEmpty()) godance.put("capabilities", meta.capabilities().toJson());
        return new ModelEntry(m.modelName(), "model",
                ownedBy(m.provider()), godance);
    }

    private static String ownedBy(ai.godance.domain.Provider p) {
        // Matches what OpenAI returns for their own models; for everyone else
        // we surface the upstream provider in lowercase as the owner.
        return p.name().toLowerCase().replace('_', '-');
    }

    public record ModelListResponse(String object, List<ModelEntry> data) { }

    public record ModelEntry(
            String id,
            String object,
            @JsonProperty("owned_by") String ownedBy,
            Map<String, Object> godance
    ) { }
}
