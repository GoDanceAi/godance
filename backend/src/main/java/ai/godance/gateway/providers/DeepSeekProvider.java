package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import ai.godance.config.DeepSeekProperties;
import ai.godance.domain.Provider;

/** DeepSeek text adapter ({@code deepseek-v4-pro}, {@code deepseek-v4-flash}). */
@Component
public class DeepSeekProvider extends OpenAICompatibleChatProvider {

    public DeepSeekProvider(DeepSeekProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/v1/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.DEEPSEEK;
    }
}
