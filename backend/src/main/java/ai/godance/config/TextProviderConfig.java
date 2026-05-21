package ai.godance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the {@code @ConfigurationProperties} for the text providers
 * added in the catalog refresh. Kept separate from {@code SecurityConfig}
 * so each modality registers its own provider config independently.
 *
 * <p>Volcengine Ark's Doubao text adapter reuses the existing
 * {@code VolcengineProperties} (registered with the video block) — no
 * entry here.
 */
@Configuration
@EnableConfigurationProperties({
        DeepSeekProperties.class,
        MoonshotProperties.class,
        AlibabaProperties.class,
        ZhipuProperties.class,
        MiniMaxProperties.class,
        XaiProperties.class,
        BaiduProperties.class})
public class TextProviderConfig {
}
