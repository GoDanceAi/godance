package ai.godance.gateway.catalog;

import java.util.List;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.gateway.CatalogContributor;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Text models offered through {@code /v1/chat/completions}. <b>Frozen
 * seed source</b> for the V11 catalog migration — no longer a Spring
 * bean and no longer a runtime read path; see
 * {@link CatalogContributor}.
 *
 * <p>Pricing is microcents per 1000 tokens — {@code (USD per 1M tokens)
 * × 1000}. All entries use the default 5% text markup tier
 * ({@code markupOverride = null}). The list is deliberately curated to
 * a flagship / mid / budget spread per provider rather than mirroring
 * every model a lab ships.
 *
 * <p>Prices marked {@code UNVERIFIED} are aggregator-sourced (see
 * {@code Godance-Model-Catalog-Research-2026-05.md}) and must be
 * confirmed on the provider dashboard before they drive resale pricing.
 */
public class TextCatalog implements CatalogContributor {

    @Override
    public List<ModelInfo> models() {
        return List.of(
                // ===== Legacy generation — retained for existing fallback chains =====
                text("gpt-4o-mini",                 Provider.OPENAI,         150L,    600L,   4096),
                text("gpt-4o",                      Provider.OPENAI,         2_500L,  10_000L, 4096),
                text("gpt-4.1",                     Provider.OPENAI,         2_000L,  8_000L,  4096),
                text("claude-3-5-sonnet-20241022",  Provider.ANTHROPIC,      3_000L,  15_000L, 8192),
                text("claude-3-5-haiku-20241022",   Provider.ANTHROPIC,      800L,    4_000L,  8192),
                text("gemini-1.5-pro",              Provider.GOOGLE_VERTEX,  1_250L,  5_000L,  8192),
                text("gemini-1.5-flash",            Provider.GOOGLE_VERTEX,  75L,     300L,    8192),

                // ===== Smart tier — frontier (May 2026) =====
                text("gpt-5.5",                     Provider.OPENAI,         5_000L,  30_000L, 8192),
                text("gpt-5.4",                     Provider.OPENAI,         2_500L,  15_000L, 8192),
                text("claude-opus-4-7",             Provider.ANTHROPIC,      5_000L,  25_000L, 8192),
                text("claude-sonnet-4-6",           Provider.ANTHROPIC,      3_000L,  15_000L, 8192),
                text("gemini-3.1-pro",              Provider.GOOGLE_VERTEX,  2_000L,  12_000L, 8192),
                text("deepseek-v4-pro",             Provider.DEEPSEEK,       1_740L,  3_480L,  8192),
                text("doubao-1.6-pro",              Provider.VOLCENGINE_ARK, 110L,    1_100L,  8192),  // UNVERIFIED — confirm on Ark/BytePlus console
                text("grok-4",                      Provider.XAI,            3_000L,  15_000L, 8192),  // UNVERIFIED — confirm on x.ai pricing
                text("ernie-5.1",                   Provider.BAIDU,          590L,    2_650L,  8192),  // UNVERIFIED — Qianfan rates are CNY-denominated

                // ===== Cheap / fast tier — target < $0.50/1M input =====
                text("gpt-5.4-nano",                Provider.OPENAI,         200L,    1_250L,  4096),
                text("gemini-3.1-flash-lite",       Provider.GOOGLE_VERTEX,  250L,    1_500L,  4096),
                text("claude-haiku-4-5",            Provider.ANTHROPIC,      1_000L,  5_000L,  4096),
                text("deepseek-v4-flash",           Provider.DEEPSEEK,       140L,    280L,    4096),
                text("kimi-k2.6",                   Provider.MOONSHOT,       950L,    4_000L,  4096),  // UNVERIFIED — not from Moonshot's own page
                text("qwen-3.6",                    Provider.ALIBABA_DASHSCOPE, 325L, 1_950L,  4096),  // verified 2026-05-21 (OpenRouter)
                text("qwen-3.6-coder",              Provider.ALIBABA_DASHSCOPE, 650L, 3_250L,  4096),  // verified 2026-05-21 (OpenRouter)
                text("glm-4.7-flash",               Provider.ZHIPU,          60L,     400L,    4096),  // verified 2026-05-21 (z.ai paid tier)
                text("minimax-m2.7",                Provider.MINIMAX,        300L,    1_200L,  4096)); // UNVERIFIED — confirm on minimax.io
    }

    private static ModelInfo text(String modelName, Provider provider,
                                  long inputMicrocentsPer1k, long outputMicrocentsPer1k,
                                  int defaultMaxOutputTokens) {
        return ModelInfo.text(modelName, provider, Modality.TEXT,
                inputMicrocentsPer1k, outputMicrocentsPer1k, defaultMaxOutputTokens, null);
    }
}
