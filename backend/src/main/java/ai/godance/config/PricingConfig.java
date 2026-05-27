package ai.godance.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import ai.godance.service.pricing.LiteLLMFeedClient;
import ai.godance.service.pricing.LlmFallbackExtractor;
import ai.godance.service.pricing.NoopLlmFallbackExtractor;
import ai.godance.service.pricing.OpenRouterFeedClient;
import ai.godance.service.pricing.SanityGuard;

/**
 * Spring beans for the daily pricing-refresh stack. The {@link
 * ai.godance.service.pricing.PricingRefreshService} itself is a {@code
 * @Service}, but the small WebClient-wrapping feed clients and the
 * stateless {@link SanityGuard} need explicit bean wiring here.
 *
 * <p>{@link PricingProperties} is registered as a
 * {@code @ConfigurationProperties} bean by the {@code @EnableConfigurationProperties}
 * declaration on {@link SecurityConfig} (where every other properties
 * class lives, by convention).
 */
@Configuration
public class PricingConfig {

    @Bean
    OpenRouterFeedClient openRouterFeedClient(WebClient.Builder builder,
                                              PricingProperties props,
                                              ObjectMapper json) {
        return new OpenRouterFeedClient(builder, props, json);
    }

    @Bean
    LiteLLMFeedClient liteLlmFeedClient(WebClient.Builder builder,
                                        PricingProperties props,
                                        ObjectMapper json) {
        return new LiteLLMFeedClient(builder, props, json);
    }

    @Bean
    SanityGuard pricingSanityGuard(PricingProperties props) {
        return new SanityGuard(props);
    }

    /**
     * Default {@link LlmFallbackExtractor} — emits no observations.
     * Tests or production override by registering their own competing
     * bean; {@link ConditionalOnMissingBean} ensures the override wins.
     */
    @Bean
    @ConditionalOnMissingBean(LlmFallbackExtractor.class)
    LlmFallbackExtractor noopLlmFallbackExtractor() {
        return new NoopLlmFallbackExtractor();
    }
}
