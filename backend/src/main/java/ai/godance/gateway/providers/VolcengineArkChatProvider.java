package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import ai.godance.config.VolcengineProperties;
import ai.godance.domain.Provider;

/**
 * Volcengine Ark text adapter — Doubao ({@code doubao-1.6-pro}).
 *
 * <p>Reuses the shared {@link VolcengineProperties} block (Ark also
 * hosts Seedream/Seedance). That config has no dedicated chat timeout,
 * so the video {@code poll-timeout} doubles as a generous request cap;
 * {@code ChatCompletionService} applies the real 60s block budget.
 */
@Component
public class VolcengineArkChatProvider extends OpenAICompatibleChatProvider {

    public VolcengineArkChatProvider(VolcengineProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.pollTimeout(), "/api/v3/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.VOLCENGINE_ARK;
    }
}
