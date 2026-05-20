package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import reactor.core.publisher.Mono;

/**
 * HANDOFF Hard Stop #3: the gateway MUST refuse to dispatch a forbidden
 * provider when {@code REGION=alicloud}, with a unit test for the
 * negative case. This file is that test.
 */
class ProviderGatewayTest {

    private static final ModelInfo OPENAI_MODEL = ModelInfo.text(
            "gpt-4o-mini", Provider.OPENAI, Modality.TEXT, 150, 600, 4096, null);
    private static final ModelInfo ARK_MODEL = ModelInfo.text(
            "doubao-1.5-pro", Provider.VOLCENGINE_ARK, Modality.TEXT, 200, 800, 4096, null);
    private static final ModelInfo ANTHROPIC_MODEL = ModelInfo.text(
            "claude-3-5-sonnet-20241022", Provider.ANTHROPIC, Modality.TEXT, 3000, 15000, 4096, null);

    private static ProviderAdapter stubAdapter(Provider provider) {
        return new ProviderAdapter() {
            @Override public Provider type() { return provider; }
            @Override public Mono<ChatResponse> chat(ChatRequest request) {
                return Mono.error(new UnsupportedOperationException("stub"));
            }
        };
    }

    private static ProviderGateway gatewayInRegion(String region, ProviderAdapter... adapters) {
        return new ProviderGateway(List.of(adapters), region);
    }

    // ===== HARD STOP — negative case ==================================

    @Test
    void dispatch_openAiFromAlicloud_returns503() {
        ProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.OPENAI));
        assertThatThrownBy(() -> gw.dispatch(OPENAI_MODEL))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void dispatch_anthropicFromAlicloud_returns503() {
        ProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.ANTHROPIC));
        assertThatThrownBy(() -> gw.dispatch(ANTHROPIC_MODEL))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void dispatch_googleVertexFromAlicloud_returns503() {
        ProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.GOOGLE_VERTEX));
        ModelInfo m = ModelInfo.text("gemini-1.5-pro", Provider.GOOGLE_VERTEX, Modality.TEXT,
                1250, 5000, 4096, null);
        assertThatThrownBy(() -> gw.dispatch(m))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ===== Positive cases =============================================

    @Test
    void dispatch_volcengineArkFromAlicloud_returnsAdapter() {
        ProviderAdapter ark = stubAdapter(Provider.VOLCENGINE_ARK);
        ProviderGateway gw = gatewayInRegion("alicloud", ark);
        assertThat(gw.dispatch(ARK_MODEL)).isSameAs(ark);
    }

    @Test
    void dispatch_openAiFromDigitalocean_returnsAdapter() {
        ProviderAdapter openai = stubAdapter(Provider.OPENAI);
        ProviderGateway gw = gatewayInRegion("digitalocean", openai);
        assertThat(gw.dispatch(OPENAI_MODEL)).isSameAs(openai);
    }

    @Test
    void dispatch_openAiFromLocal_returnsAdapter() {
        ProviderAdapter openai = stubAdapter(Provider.OPENAI);
        ProviderGateway gw = gatewayInRegion("local", openai);
        assertThat(gw.dispatch(OPENAI_MODEL)).isSameAs(openai);
    }

    // ===== Misconfiguration ===========================================

    @Test
    void dispatch_noAdapterRegisteredForProvider_returns502() {
        ProviderGateway gw = gatewayInRegion("local");   // empty
        assertThatThrownBy(() -> gw.dispatch(OPENAI_MODEL))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void construct_duplicateAdaptersForSameProvider_throws() {
        ProviderAdapter a = stubAdapter(Provider.OPENAI);
        ProviderAdapter b = stubAdapter(Provider.OPENAI);
        assertThatThrownBy(() -> new ProviderGateway(List.of(a, b), "local"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate ProviderAdapter for OPENAI");
    }

    @Test
    void construct_unknownRegion_throws() {
        assertThatThrownBy(() -> new ProviderGateway(List.of(), "mars"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("godance.region must be one of");
    }

    @Test
    void region_defaultsToLocalWhenBlank() {
        ProviderGateway gw = new ProviderGateway(List.of(), "");
        assertThat(gw.region()).isEqualTo(Region.LOCAL);
    }
}
