package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import ai.godance.config.XaiProperties;
import ai.godance.domain.Provider;

/** xAI (Grok) text adapter ({@code grok-4}). Western provider — never Alicloud-routed. */
@Component
public class XaiProvider extends OpenAICompatibleChatProvider {

    public XaiProvider(XaiProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/v1/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.XAI;
    }
}
