package ai.godance.gateway.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.ProviderAdapter;
import ai.godance.gateway.ProviderGateway;
import reactor.core.publisher.Mono;

/**
 * Verifies the text catalog content and that text models route
 * correctly through the regional allowlist — China-hosted providers are
 * dispatchable from Alicloud, Western frontier providers are not.
 */
class TextCatalogTest {

    private static final Map<String, ModelInfo> MODELS = new TextCatalog().models().stream()
            .collect(Collectors.toMap(ModelInfo::modelName, Function.identity()));

    @Test
    void models_everyEntry_isTextModalityOnDefaultMarkupTier() {
        for (ModelInfo m : MODELS.values()) {
            assertThat(m.modality())
                    .as("model %s must be TEXT", m.modelName())
                    .isEqualTo(Modality.TEXT);
            assertThat(m.markupOverride())
                    .as("model %s should use the default text markup tier", m.modelName())
                    .isNull();
        }
    }

    @Test
    void models_modelNames_areUnique() {
        List<ModelInfo> all = new TextCatalog().models();
        assertThat(all.stream().map(ModelInfo::modelName).distinct().count())
                .isEqualTo(all.size());
    }

    @Test
    void models_retainLegacyEntries_forExistingFallbackChains() {
        // application.yml fallback chains still reference these — dropping them would break routing.
        assertThat(MODELS).containsKeys("gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet-20241022");
    }

    @Test
    void models_frontierPricing_matchesUsdPerMillionConversion() {
        // inputMicrocentsPer1k = USD-per-1M × 1000
        assertThat(MODELS.get("gpt-5.5").inputMicrocentsPer1k()).isEqualTo(5_000L);    // $5.00/1M
        assertThat(MODELS.get("gpt-5.5").outputMicrocentsPer1k()).isEqualTo(30_000L);  // $30.00/1M
        assertThat(MODELS.get("claude-opus-4-7").inputMicrocentsPer1k()).isEqualTo(5_000L);
        assertThat(MODELS.get("claude-opus-4-7").outputMicrocentsPer1k()).isEqualTo(25_000L);
        assertThat(MODELS.get("deepseek-v4-flash").inputMicrocentsPer1k()).isEqualTo(140L);   // $0.14/1M
        assertThat(MODELS.get("deepseek-v4-flash").outputMicrocentsPer1k()).isEqualTo(280L);  // $0.28/1M
    }

    @Test
    void models_newProviders_routeToTheirProviderEnum() {
        assertThat(MODELS.get("deepseek-v4-pro").provider()).isEqualTo(Provider.DEEPSEEK);
        assertThat(MODELS.get("kimi-k2.6").provider()).isEqualTo(Provider.MOONSHOT);
        assertThat(MODELS.get("qwen-3.6").provider()).isEqualTo(Provider.ALIBABA_DASHSCOPE);
        assertThat(MODELS.get("qwen-3.6-coder").provider()).isEqualTo(Provider.ALIBABA_DASHSCOPE);
        assertThat(MODELS.get("glm-4.7-flash").provider()).isEqualTo(Provider.ZHIPU);
        assertThat(MODELS.get("minimax-m2.7").provider()).isEqualTo(Provider.MINIMAX);
        assertThat(MODELS.get("doubao-1.6-pro").provider()).isEqualTo(Provider.VOLCENGINE_ARK);
        assertThat(MODELS.get("grok-4").provider()).isEqualTo(Provider.XAI);
        assertThat(MODELS.get("ernie-5.1").provider()).isEqualTo(Provider.BAIDU);
    }

    // ===== Regional allowlist routing =================================

    private static ProviderAdapter stubAdapter(Provider provider) {
        return new ProviderAdapter() {
            @Override public Provider type() { return provider; }
            @Override public Mono<ChatResponse> chat(ChatRequest request) {
                return Mono.error(new UnsupportedOperationException("stub"));
            }
        };
    }

    @Test
    void dispatch_chineseTextModelFromAlicloud_isAllowed() {
        ProviderGateway gw = new ProviderGateway(
                List.of(stubAdapter(Provider.DEEPSEEK), stubAdapter(Provider.VOLCENGINE_ARK)),
                "alicloud");
        assertThat(gw.dispatch(MODELS.get("deepseek-v4-pro"))).isNotNull();
        assertThat(gw.dispatch(MODELS.get("doubao-1.6-pro"))).isNotNull();
    }

    @Test
    void dispatch_westernTextModelFromAlicloud_returns503() {
        ProviderGateway gw = new ProviderGateway(
                List.of(stubAdapter(Provider.OPENAI), stubAdapter(Provider.XAI)), "alicloud");
        assertThatThrownBy(() -> gw.dispatch(MODELS.get("gpt-5.5")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> gw.dispatch(MODELS.get("grok-4")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
