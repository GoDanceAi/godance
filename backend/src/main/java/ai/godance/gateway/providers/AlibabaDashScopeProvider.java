package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.AlibabaProperties;
import ai.godance.domain.Provider;

/** Alibaba DashScope (Qwen) text adapter ({@code qwen-3.6}, {@code qwen-3.6-coder}). */
@Component
public class AlibabaDashScopeProvider extends OpenAICompatibleChatProvider {

    public AlibabaDashScopeProvider(AlibabaProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/v1/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.ALIBABA_DASHSCOPE;
    }
}
