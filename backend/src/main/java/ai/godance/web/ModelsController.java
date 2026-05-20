package ai.godance.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
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
 * — pure OpenAI clients ignore unknown fields.
 */
@RestController
@RequestMapping("/v1")
public class ModelsController {

    private final ModelCatalog catalog;

    public ModelsController(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/models")
    public Mono<ModelListResponse> list() {
        List<ModelEntry> entries = catalog.all().stream().map(ModelsController::toEntry).toList();
        return Mono.just(new ModelListResponse("list", entries));
    }

    private static ModelEntry toEntry(ModelInfo m) {
        Map<String, Object> pricing = switch (m.modality()) {
            case TEXT, EMBEDDING, AUDIO_STT, AUDIO_TTS -> Map.of(
                    "input_microcents_per_1k",  m.inputMicrocentsPer1k(),
                    "output_microcents_per_1k", m.outputMicrocentsPer1k());
            case IMAGE -> Map.of("per_image_microcents", m.perImageMicrocents());
            case VIDEO -> Map.of("per_second_microcents", m.perSecondMicrocents());
        };
        Map<String, Object> godance = Map.of(
                "modality",        m.modality().name(),
                "requires_async",  m.requiresAsync(),
                "pricing",         pricing);
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
