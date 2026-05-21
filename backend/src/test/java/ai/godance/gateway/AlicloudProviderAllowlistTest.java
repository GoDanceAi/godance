package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import ai.godance.domain.Provider;

/**
 * Negative-case coverage is the point of this test: the CLAUDE.md hard
 * constraint #1 forbids Anthropic/OpenAI/Vertex/etc. from ever being
 * dispatched from the Alicloud region. The allowlist's job is to make
 * those calls fail closed.
 */
class AlicloudProviderAllowlistTest {

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = {
            "DEEPSEEK", "MOONSHOT", "ALIBABA_DASHSCOPE", "ZHIPU",
            "MINIMAX", "VOLCENGINE_ARK", "BAIDU", "KLING"
    })
    void isAllowed_givenChineseProvider_returnsTrue(Provider provider) {
        assertThat(AlicloudProviderAllowlist.isAllowed(provider)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, mode = Mode.EXCLUDE, names = {
            "DEEPSEEK", "MOONSHOT", "ALIBABA_DASHSCOPE", "ZHIPU",
            "MINIMAX", "VOLCENGINE_ARK", "BAIDU", "KLING"
    })
    void isAllowed_givenWesternOrUnlistedProvider_returnsFalse(Provider provider) {
        assertThat(AlicloudProviderAllowlist.isAllowed(provider))
                .as("provider %s must not be Alicloud-allowed", provider)
                .isFalse();
    }

    @Test
    void isAllowed_givenNull_returnsFalse() {
        assertThat(AlicloudProviderAllowlist.isAllowed(null)).isFalse();
    }
}
