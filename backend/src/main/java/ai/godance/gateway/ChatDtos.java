package ai.godance.gateway;

import java.util.List;

/**
 * Provider-agnostic chat completion shapes. Modeled after OpenAI's
 * v1 chat completions so future adapters (Anthropic, Vertex, Volcengine
 * Ark) translate in/out at the edge rather than the controller layer.
 */
public final class ChatDtos {

    private ChatDtos() { }

    public record ChatRequest(
            String model,
            List<ChatMessage> messages,
            Integer maxTokens,
            Double temperature,
            Boolean stream            // null/false in Session 6; SSE arrives Session 7
    ) { }

    /**
     * One message: a role ({@code system}, {@code user} or {@code assistant})
     * and its content as parts, in order. A message that arrived as a plain
     * string is one text part, and adapters send such a message exactly as
     * they always have: as a string. Only a message that carries an image is
     * sent in a provider's parts form.
     */
    public record ChatMessage(String role, List<ContentPart> parts) {

        public ChatMessage {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        /** A message whose content is one string. */
        public ChatMessage(String role, String content) {
            this(role, content == null ? List.<ContentPart>of() : List.<ContentPart>of(new TextPart(content)));
        }

        /**
         * The message's text: the one string it was given, or its text parts
         * joined by a line break. Null for a message with no content at all;
         * empty for one that holds only images.
         */
        public String content() {
            if (parts.isEmpty()) return null;
            if (parts.size() == 1 && parts.get(0) instanceof TextPart only) return only.text();
            StringBuilder text = new StringBuilder();
            for (ContentPart part : parts) {
                if (part instanceof TextPart t && t.text() != null) {
                    if (text.length() > 0) text.append('\n');
                    text.append(t.text());
                }
            }
            return text.toString();
        }

        /** Whether any part is an image. */
        public boolean hasImages() {
            for (ContentPart part : parts) {
                if (part instanceof ImagePart) return true;
            }
            return false;
        }

        /** How many parts are images. */
        public int imageCount() {
            int count = 0;
            for (ContentPart part : parts) {
                if (part instanceof ImagePart) count++;
            }
            return count;
        }
    }

    /** One part of a message's content. */
    public sealed interface ContentPart permits TextPart, ImagePart { }

    public record TextPart(String text) implements ContentPart { }

    /**
     * An image, already fetched or decoded and checked: its bytes, its type
     * ({@code image/png}, {@code image/jpeg} or {@code image/webp}) and the
     * {@code detail} the caller asked for, if any. Adapters send it inline,
     * in whichever form their provider documents; a provider is never handed
     * a URL to fetch.
     */
    public record ImagePart(byte[] data, String mediaType, String detail) implements ContentPart {

        /** The image as base64, without line breaks. */
        public String base64() {
            return java.util.Base64.getEncoder().encodeToString(data);
        }

        /** The image as a {@code data:} URL. */
        public String dataUrl() {
            return "data:" + mediaType + ";base64," + base64();
        }
    }

    public record ChatResponse(
            String id,
            String model,
            List<ChatChoice> choices,
            Usage usage,
            long createdAt            // unix seconds
    ) { }

    public record ChatChoice(int index, ChatMessage message, String finishReason) { }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) { }

    // ===== Streaming =================================================

    /**
     * Incremental chunk emitted while a streamed completion is in
     * flight. {@code usage} is null on every chunk except the terminal
     * one — providers either inline it on the final chunk or emit a
     * trailing usage-only chunk (OpenAI does the latter when
     * {@code stream_options.include_usage = true}).
     */
    public record ChatChunk(
            String id,
            String model,
            long createdAt,
            java.util.List<ChunkChoice> choices,
            Usage usage
    ) { }

    public record ChunkChoice(int index, ChunkDelta delta, String finishReason) { }

    /** {@code role} appears once on the first chunk; subsequent chunks carry only {@code content}. */
    public record ChunkDelta(String role, String content) { }
}
