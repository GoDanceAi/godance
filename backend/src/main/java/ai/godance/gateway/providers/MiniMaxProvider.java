package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.MiniMaxProperties;
import ai.godance.domain.Provider;

/** MiniMax text adapter ({@code minimax-m2.7}). */
@Component
public class MiniMaxProvider extends OpenAICompatibleChatProvider {

    public MiniMaxProvider(MiniMaxProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/v1/text/chatcompletion_v2");
    }

    @Override
    public Provider type() {
        return Provider.MINIMAX;
    }
}
