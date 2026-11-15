package ai.godance.gateway;

import java.util.Map;

import ai.godance.domain.Provider;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * How many input tokens to hold for one image in a chat request, before the
 * provider says what it actually counted (round 11 brief 02).
 *
 * <p>Settlement uses the provider's reported usage, which includes image
 * tokens, so the bill is right either way. This is only the pre-debit, and
 * a pre-debit must not under-hold: each figure is the LARGEST count the
 * provider documents for one image on that model, whatever {@code detail}
 * the request asks for. A model the table does not name gets its
 * provider's largest figure, and a provider with no documented rule gets
 * the largest figure of all.
 *
 * <p>From each provider's documentation, read on 2026-11-14:
 * <ul>
 *   <li><b>OpenAI</b>, https://developers.openai.com/api/docs/guides/images-vision
 *     <ul>
 *       <li>gpt-4o, gpt-4.1: 85 base tokens plus 170 per 512 px tile, after
 *           fitting in 2048 px and scaling the short side to 768 px, which is
 *           at most 8 tiles: 85 + 8 × 170 = 1,445.</li>
 *       <li>gpt-4o-mini: the same rule with 2,833 base and 5,667 per tile:
 *           2,833 + 8 × 5,667 = 48,169.</li>
 *       <li>gpt-5.5, gpt-5.4, gpt-5.4-nano: 32 px patches times 1.2, with a
 *           budget of up to 10,000 patches at {@code detail: original}:
 *           12,000.</li>
 *     </ul></li>
 *   <li><b>Anthropic</b>, https://platform.claude.com/docs/en/build-with-claude/vision:
 *       ⌈width / 28⌉ × ⌈height / 28⌉ after downscaling; at most 4,784 on
 *       Claude 4.7 and later (claude-opus-4-7), 1,568 on all other models.</li>
 *   <li><b>Google</b>, https://aistudio.google.com/docs/media-resolution: on
 *       Gemini 3 models an image is about 1,120 tokens at the default
 *       resolution and 2,240 at the highest.</li>
 * </ul>
 * The OpenAI maxima are worked out from the documented rule; the
 * documentation gives the rule, not the maximum.
 */
public final class ChatImageTokens {

    private ChatImageTokens() { }

    /** A provider with no documented rule: the largest documented figure of any. */
    static final int UNDOCUMENTED = 48_169;

    private static final Map<String, Integer> BY_MODEL = Map.of(
            "gpt-4o", 1_445,
            "gpt-4.1", 1_445,
            "gpt-4o-mini", 48_169,
            "gpt-5.5", 12_000,
            "gpt-5.4", 12_000,
            "gpt-5.4-nano", 12_000,
            "claude-opus-4-7", 4_784,
            "claude-sonnet-4-6", 1_568,
            "claude-haiku-4-5", 1_568,
            "gemini-3.1-flash-lite", 2_240);

    private static final Map<Provider, Integer> LARGEST_BY_PROVIDER = Map.of(
            Provider.OPENAI, 48_169,
            Provider.ANTHROPIC, 4_784,
            Provider.GOOGLE_VERTEX, 2_240);

    /** The tokens to hold for one image sent to this model. */
    public static int perImage(ModelInfo model) {
        Integer documented = BY_MODEL.get(model.modelName());
        if (documented != null) return documented;
        return LARGEST_BY_PROVIDER.getOrDefault(model.provider(), UNDOCUMENTED);
    }
}
