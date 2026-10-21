package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import ai.godance.config.ZhipuProperties;
import ai.godance.domain.Provider;

/** Zhipu AI (GLM) text adapter ({@code glm-4.7-flash}). */
@Component
public class ZhipuProvider extends OpenAICompatibleChatProvider {

    public ZhipuProvider(ZhipuProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.ZHIPU;
    }
}
