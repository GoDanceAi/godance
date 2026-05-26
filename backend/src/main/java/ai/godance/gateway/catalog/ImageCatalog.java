package ai.godance.gateway.catalog;

import java.util.List;

import org.springframework.stereotype.Component;

import ai.godance.domain.Provider;
import ai.godance.gateway.CatalogContributor;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Image models offered through {@code /v1/images/generations}.
 *
 * <p>Per-image pricing is microcents — {@code (USD per image) × 1_000_000}
 * (so $0.04/image → {@code 40_000}). Prices are provider list rates;
 * sources and {@code UNVERIFIED} flags live in
 * {@code Godance-Model-Catalog-Research-2026-05.md}. Providers that bill
 * per output token (OpenAI) or per megapixel (BFL) are priced for a
 * standard 1024² / 1 MP generation — see the per-model notes.
 *
 * <p>Every image model is async ({@code requiresAsync = true}): generation
 * returns a {@code job_id} immediately and the result is fetched via
 * {@code GET /v1/jobs/{id}} or a webhook. Markup is the image tier
 * (10–15%); a flat 12% {@code markupOverride} is applied per model.
 */
@Component
public class ImageCatalog implements CatalogContributor {

    /** Image resale markup — 12%, mid of the 10–15% image tier band. */
    private static final double IMAGE_MARKUP = 12.0;

    @Override
    public List<ModelInfo> models() {
        return List.of(
                // ===== OpenAI =====
                // gpt-image-2 is token-billed (~$0.01–$0.20/img by quality);
                // priced for the standard-quality 1024² tier.
                img("gpt-image-2",        Provider.OPENAI,            53_000L),  // ~$0.053/img (standard 1024²); verified 2026-05-21
                img("gpt-image-1.5",      Provider.OPENAI,            40_000L),  // ~$0.04/img (standard tier); verified 2026-05-21

                // ===== ByteDance / Volcengine Ark — Seedream =====
                img("seedream-5.0",       Provider.VOLCENGINE_ARK,    35_000L),  // ~$0.035/img

                // ===== Alibaba DashScope — Qwen-Image =====
                img("qwen-image-2.0",     Provider.ALIBABA_DASHSCOPE, 35_000L),  // ~$0.035/img
                img("qwen-image-2.0-pro", Provider.ALIBABA_DASHSCOPE, 75_000L),  // ~$0.075/img

                // ===== Black Forest Labs — Flux =====
                // FLUX.2 is per-megapixel (~$0.03 first MP); priced for a 1 MP image.
                img("flux-2-pro",         Provider.BFL,               30_000L),  // ~$0.03/1 MP
                img("flux-1.1-pro",       Provider.BFL,               40_000L),  // ~$0.04/img

                // ===== Google — Gemini image =====
                img("nano-banana",        Provider.GOOGLE_VERTEX,    134_000L),  // Nano Banana Pro, ~$0.134 (1K/2K)
                img("imagen-4",           Provider.GOOGLE_VERTEX,     40_000L),  // Imagen 4 standard tier ~$0.04

                // ===== Stability — SDXL =====
                img("sdxl",               Provider.STABILITY,         50_000L)); // ~$0.05/img (~5 credits)
    }

    /** Async image model at the standard 12% image-tier markup. */
    private static ModelInfo img(String modelName, Provider provider, long perImageMicrocents) {
        return ModelInfo.imageAsync(modelName, provider, perImageMicrocents, IMAGE_MARKUP);
    }
}
