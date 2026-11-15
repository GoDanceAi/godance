package ai.godance.gateway.providers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ContentPart;
import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.gateway.ChatDtos.TextPart;

/**
 * A message's content in OpenAI's chat-completions form, which the
 * OpenAI-compatible providers share.
 *
 * <p>A message of text is a plain string, byte for byte what was sent
 * before images existed. A message with an image is OpenAI's array of
 * parts, each image inline as a {@code data:} URL:
 * <pre>
 * [ {"type": "text", "text": "…"},
 *   {"type": "image_url", "image_url": {"url": "data:image/png;base64,…", "detail": "auto"}} ]
 * </pre>
 * {@code detail} is sent only when the caller gave one.
 */
final class OpenAIContent {

    private OpenAIContent() { }

    static Object of(ChatMessage message) {
        if (!message.hasImages()) return message.content();
        List<Map<String, Object>> parts = new ArrayList<>(message.parts().size());
        for (ContentPart part : message.parts()) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (part instanceof TextPart text) {
                out.put("type", "text");
                out.put("text", text.text());
            } else if (part instanceof ImagePart image) {
                Map<String, Object> url = new LinkedHashMap<>();
                url.put("url", image.dataUrl());
                if (image.detail() != null) url.put("detail", image.detail());
                out.put("type", "image_url");
                out.put("image_url", url);
            }
            parts.add(out);
        }
        return parts;
    }
}
