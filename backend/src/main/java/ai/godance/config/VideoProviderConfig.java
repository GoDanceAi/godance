package ai.godance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the {@code @ConfigurationProperties} for video providers added
 * in the catalog refresh. Mirrors {@code TextProviderConfig} — each
 * modality registers its own provider config independently.
 *
 * <p>Only genuinely new providers appear here. Sora reuses
 * {@code OpenAIProperties}, Veo reuses {@code GoogleProperties}, Seedance
 * reuses {@code VolcengineProperties}, and MiniMax Hailuo reuses
 * {@code MiniMaxProperties} — all registered with other modalities.
 */
@Configuration
@EnableConfigurationProperties({
        KlingProperties.class,
        RunwayProperties.class})
public class VideoProviderConfig {
}
