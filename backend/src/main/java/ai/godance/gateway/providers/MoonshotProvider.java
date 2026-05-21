package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.MoonshotProperties;
import ai.godance.domain.Provider;

/** Moonshot AI (Kimi) text adapter ({@code kimi-k2.6}). */
@Component
public class MoonshotProvider extends OpenAICompatibleChatProvider {

    public MoonshotProvider(MoonshotProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/v1/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.MOONSHOT;
    }
}
