package ai.godance.gateway.catalog;

import java.util.List;

import ai.godance.domain.Provider;
import ai.godance.gateway.CatalogContributor;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Curated launch catalog for {@code /v1/videos/generations}. <b>Frozen
 * seed source</b> for the V11 catalog migration — no longer a Spring
 * bean and no longer a runtime read path; see
 * {@link CatalogContributor}.
 *
 * <p>Every video model is async ({@code requiresAsync = true}) and
 * priced per second of output — {@code perSecondMicrocents =
 * (USD per second) × 1_000_000}. Markup is set per model via
 * {@code markupOverride} (a percent) and lands in the 10–15% video
 * tier band: thinner on premium models to stay competitive, fatter on
 * the cheap China-hosted tier where the absolute dollar cost is small.
 *
 * <p>Provider list prices below are sourced from
 * {@code Godance-Model-Catalog-Research-2026-05.md}; several upstreams
 * bill in credits/units/tokens and are converted to a per-second
 * equivalent. Numbers flagged UNVERIFIED in that doc must be confirmed
 * on the provider dashboard before they drive live resale pricing.
 */
public class VideoCatalog implements CatalogContributor {

    @Override
    public List<ModelInfo> models() {
        return List.of(
                // Volcengine Ark Seedance — China-hosted. ~$0.26 per 720p 5s
                // clip w/ audio → ~$0.052/s. Seedance 2.0 API availability is
                // UNVERIFIED in the research doc; confirm before launch.
                ModelInfo.video("seedance-2.0",       Provider.VOLCENGINE_ARK, 140_000L, 15.0),  // ~$0.14/s standard; API live 2026-04; verified 2026-05-21

                // OpenAI Sora 2 — $0.10/s (720p); Sora 2 Pro — ~$0.30/s (720p).
                ModelInfo.video("sora-2",             Provider.OPENAI,         100_000L, 12.0),
                ModelInfo.video("sora-2-pro",         Provider.OPENAI,         300_000L, 10.0),  // 720p
                ModelInfo.video("sora-2-pro-hd",      Provider.OPENAI,         500_000L, 10.0),  // 1024p HD ~$0.50/s; verified 2026-05-21

                // Google Veo 3.1 — $0.40/s incl. audio; Veo 3.1 Fast — ~$0.10/s (720p).
                ModelInfo.video("veo-3.1",            Provider.GOOGLE_VERTEX,  400_000L, 10.0),
                ModelInfo.video("veo-3.1-fast",       Provider.GOOGLE_VERTEX,  150_000L, 12.0),  // ~$0.15/s incl. audio; verified 2026-05-21

                // Kling 3.0 (Kuaishou) — China-hosted. ~$0.084/s (Std tier);
                // official credit→USD rate UNVERIFIED.
                ModelInfo.video("kling-3.0",          Provider.KLING,           84_000L, 15.0),

                // Runway Gen-4 — $0.05–0.12/s; ~$0.10/s used here.
                ModelInfo.video("runway-gen4",        Provider.RUNWAY,         100_000L, 12.0),

                // MiniMax Hailuo 2.3 — China-hosted. Unit-billed upstream
                // (~$0.54 per 1080p 6s clip) → ~$0.09/s equivalent.
                ModelInfo.video("minimax-hailuo-2.3", Provider.MINIMAX,         90_000L, 15.0));
    }
}
