package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import reactor.core.publisher.Mono;

/**
 * Regional allowlist enforcement for image dispatch — the image-side
 * mirror of {@link ProviderGatewayTest}. Western image providers
 * (OpenAI, BFL, Google, Stability) MUST be refused from the Alicloud
 * region; the China-hosted ones (Volcengine Ark Seedream, Alibaba
 * DashScope Qwen-Image) are permitted.
 */
class ImageProviderGatewayTest {

    private static ModelInfo imageModel(Provider provider) {
        return ModelInfo.imageAsync("test-image-model", provider, 40_000L, 12.0);
    }

    private static ImageProviderAdapter stubAdapter(Provider provider) {
        return new ImageProviderAdapter() {
            @Override public Provider type() { return provider; }
            @Override public Mono<List<GeneratedImage>> generate(ImageRequest request) {
                return Mono.error(new UnsupportedOperationException("stub"));
            }
        };
    }

    private static ImageProviderGateway gatewayInRegion(String region, ImageProviderAdapter... adapters) {
        return new ImageProviderGateway(List.of(adapters), region);
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(expected);
    }

    // ===== Alicloud — Western image providers refused (HARD POLICY) =====

    @Test
    void dispatch_openAiImageFromAlicloud_returns503() {
        ImageProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.OPENAI));
        assertThatThrownBy(() -> gw.dispatch(imageModel(Provider.OPENAI)))
                .satisfies(t -> assertStatus(t, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void dispatch_bflImageFromAlicloud_returns503() {
        ImageProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.BFL));
        assertThatThrownBy(() -> gw.dispatch(imageModel(Provider.BFL)))
                .satisfies(t -> assertStatus(t, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void dispatch_googleImageFromAlicloud_returns503() {
        ImageProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.GOOGLE_VERTEX));
        assertThatThrownBy(() -> gw.dispatch(imageModel(Provider.GOOGLE_VERTEX)))
                .satisfies(t -> assertStatus(t, HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void dispatch_stabilityImageFromAlicloud_returns503() {
        ImageProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.STABILITY));
        assertThatThrownBy(() -> gw.dispatch(imageModel(Provider.STABILITY)))
                .satisfies(t -> assertStatus(t, HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ===== Alicloud — China-hosted image providers permitted ============

    @Test
    void dispatch_seedreamFromAlicloud_returnsAdapter() {
        ImageProviderAdapter ark = stubAdapter(Provider.VOLCENGINE_ARK);
        ImageProviderGateway gw = gatewayInRegion("alicloud", ark);
        assertThat(gw.dispatch(imageModel(Provider.VOLCENGINE_ARK))).isSameAs(ark);
    }

    @Test
    void dispatch_qwenImageFromAlicloud_returnsAdapter() {
        ImageProviderAdapter qwen = stubAdapter(Provider.ALIBABA_DASHSCOPE);
        ImageProviderGateway gw = gatewayInRegion("alicloud", qwen);
        assertThat(gw.dispatch(imageModel(Provider.ALIBABA_DASHSCOPE))).isSameAs(qwen);
    }

    // ===== Non-Alicloud — Western providers allowed =====================

    @Test
    void dispatch_openAiImageFromLocal_returnsAdapter() {
        ImageProviderAdapter openai = stubAdapter(Provider.OPENAI);
        ImageProviderGateway gw = gatewayInRegion("local", openai);
        assertThat(gw.dispatch(imageModel(Provider.OPENAI))).isSameAs(openai);
    }

    // ===== Misconfiguration =============================================

    @Test
    void dispatch_noAdapterRegisteredForProvider_returns502() {
        ImageProviderGateway gw = gatewayInRegion("local");   // no adapters
        assertThatThrownBy(() -> gw.dispatch(imageModel(Provider.STABILITY)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_GATEWAY));
    }
}
