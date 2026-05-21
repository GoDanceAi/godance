package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Provider;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import reactor.core.publisher.Mono;

/**
 * Regional-allowlist enforcement for the video gateway. CLAUDE.md hard
 * constraint #1: a Western provider (Sora/Veo/Runway) dispatched from
 * {@code REGION=alicloud} MUST fail closed with 503. Kling is
 * China-hosted (Kuaishou) and is the one new video provider allowed
 * from Alicloud.
 */
class VideoProviderGatewayTest {

    private static final ModelInfo KLING = ModelInfo.video("kling-3.0", Provider.KLING, 84_000L, 15.0);
    private static final ModelInfo SORA = ModelInfo.video("sora-2", Provider.OPENAI, 100_000L, 12.0);
    private static final ModelInfo VEO = ModelInfo.video("veo-3.1", Provider.GOOGLE_VERTEX, 400_000L, 10.0);
    private static final ModelInfo RUNWAY = ModelInfo.video("runway-gen4", Provider.RUNWAY, 100_000L, 12.0);

    private static VideoProviderAdapter stubAdapter(Provider provider) {
        return new VideoProviderAdapter() {
            @Override public Provider type() { return provider; }
            @Override public Mono<List<GeneratedVideo>> generate(VideoRequest request) {
                return Mono.error(new UnsupportedOperationException("stub"));
            }
        };
    }

    private static VideoProviderGateway gatewayInRegion(String region, VideoProviderAdapter... adapters) {
        return new VideoProviderGateway(List.of(adapters), region);
    }

    // ===== HARD STOP — Western providers blocked from Alicloud ========

    @Test
    void dispatch_soraFromAlicloud_returns503() {
        VideoProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.OPENAI));
        assertThatThrownBy(() -> gw.dispatch(SORA))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void dispatch_veoFromAlicloud_returns503() {
        VideoProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.GOOGLE_VERTEX));
        assertThatThrownBy(() -> gw.dispatch(VEO))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void dispatch_runwayFromAlicloud_returns503() {
        VideoProviderGateway gw = gatewayInRegion("alicloud", stubAdapter(Provider.RUNWAY));
        assertThatThrownBy(() -> gw.dispatch(RUNWAY))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ===== Positive — Kling is China-hosted, Alicloud-allowed =========

    @Test
    void dispatch_klingFromAlicloud_returnsAdapter() {
        VideoProviderAdapter kling = stubAdapter(Provider.KLING);
        VideoProviderGateway gw = gatewayInRegion("alicloud", kling);
        assertThat(gw.dispatch(KLING)).isSameAs(kling);
    }

    @Test
    void dispatch_soraFromDigitalocean_returnsAdapter() {
        VideoProviderAdapter sora = stubAdapter(Provider.OPENAI);
        VideoProviderGateway gw = gatewayInRegion("digitalocean", sora);
        assertThat(gw.dispatch(SORA)).isSameAs(sora);
    }
}
