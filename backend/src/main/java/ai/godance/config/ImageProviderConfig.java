package ai.godance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the {@code @ConfigurationProperties} for the image providers
 * added in the catalog refresh. Kept separate from {@code SecurityConfig}
 * so each modality registers its own provider config independently.
 *
 * <p>Only genuinely new image providers appear here. The OpenAI, BFL,
 * Volcengine Ark (Seedream), Google (Imagen / Nano Banana) and Alibaba
 * DashScope (Qwen-Image) adapters reuse config blocks already registered
 * elsewhere — no entry here.
 */
@Configuration
@EnableConfigurationProperties({
        StabilityProperties.class})
public class ImageProviderConfig {
}
