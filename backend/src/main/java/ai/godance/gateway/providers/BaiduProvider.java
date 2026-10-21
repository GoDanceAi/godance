package ai.godance.gateway.providers;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import ai.godance.config.BaiduProperties;
import ai.godance.domain.Provider;

/** Baidu (ERNIE) text adapter ({@code ernie-5.1}) via the Qianfan v2 platform. */
@Component
public class BaiduProvider extends OpenAICompatibleChatProvider {

    public BaiduProvider(BaiduProperties props, ObjectMapper json) {
        super(bearerClient(props.apiBase(), props.apiKey()), json,
                props.timeout(), "/chat/completions");
    }

    @Override
    public Provider type() {
        return Provider.BAIDU;
    }
}
