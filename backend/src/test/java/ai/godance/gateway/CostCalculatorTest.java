package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ai.godance.config.BillingProperties;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.gateway.ModelCatalog.ModelInfo;

class CostCalculatorTest {

    private static final BillingProperties TEST_BILLING = new BillingProperties(
            100L, 100_000L, "usd",
            5.0,  // text default — outcome brief Feature #4
            10.0, // image
            12.0  // video
    );

    private final CostCalculator calc = new CostCalculator(TEST_BILLING);

    // Pricing chosen so the math is easy to eyeball.
    private static final ModelInfo CHEAP_TEXT = ModelInfo.text(
            "cheap", Provider.OPENAI, Modality.TEXT,
            150L, 600L, 4096, null);             // $0.15 / $0.60 per 1M
    private static final ModelInfo EXPENSIVE_TEXT = ModelInfo.text(
            "expensive", Provider.OPENAI, Modality.TEXT,
            2500L, 10000L, 4096, null);          // $2.50 / $10 per 1M
    private static final ModelInfo HIGH_MARKUP_TEXT = ModelInfo.text(
            "premium", Provider.OPENAI, Modality.TEXT,
            2500L, 10000L, 4096, 25.0);          // model-level override → 25%

    @Test
    void estimateInputCents_belowOneCent_ceilsToOne() {
        // 500 tokens * 150 microcents/1k = 75 microcents = 0.0075¢
        // × 1.05 markup = 78.75 microcents → ceil to cents = 1
        assertThat(calc.estimateInputCents(CHEAP_TEXT, 500)).isEqualTo(1L);
    }

    @Test
    void estimateInputCents_largeInput_rounds_up_correctly() {
        // 100k tokens * 2500 microcents/1k = 250_000 microcents = 25¢
        // × 1.05 = 262_500 → ceil → 27 (262500 / 10000 ceil = 27)
        assertThat(calc.estimateInputCents(EXPENSIVE_TEXT, 100_000)).isEqualTo(27L);
    }

    @Test
    void actualCents_subCent_roundsToZero_butActualMicrocentsKeepsTheAmount() {
        // 500 in + 200 out for cheap: (500*150 + 200*600)/1k = 75 + 120 = 195 microcents
        // × 1.05 = 204.75 → HALF_UP 205 microcents. Rounded to cents that is 0 (display);
        // the ledger settles the 205 microcents through the carry (R-06).
        assertThat(calc.actualCents(CHEAP_TEXT, 500, 200)).isZero();
        assertThat(calc.actualMicrocents(CHEAP_TEXT, 500, 200)).isEqualTo(205L);
    }

    @Test
    void actualMicrocents_seedPriceGpt4oMini_1kIn1kOut_isTheAssessmentExample() {
        // $0.15 / $0.60 per 1M → 150 + 600 = 750 microcents × 1.05 = 787.5 → HALF_UP 788 µ¢
        // = $0.0000788, previously rounded to a free request.
        assertThat(calc.actualMicrocents(CHEAP_TEXT, 1_000, 1_000)).isEqualTo(788L);
        assertThat(calc.actualCents(CHEAP_TEXT, 1_000, 1_000)).isZero();
    }

    @Test
    void actualCents_isHalfUpOfActualMicrocents() {
        // 1000 in + 1000 out for expensive: 2500 + 10000 = 12_500 × 1.05 = 13_125 µ¢ → 1¢
        assertThat(calc.actualMicrocents(EXPENSIVE_TEXT, 1_000, 1_000)).isEqualTo(13_125L);
        assertThat(calc.actualCents(EXPENSIVE_TEXT, 1_000, 1_000)).isEqualTo(1L);
        // 2000 in + 1000 out: 15_000 × 1.05 = 15_750 µ¢ → 2¢
        assertThat(calc.actualCents(EXPENSIVE_TEXT, 2_000, 1_000)).isEqualTo(2L);
    }

    @Test
    void withMarkup_fractionalPercent_usesIntegerBasisPoints() {
        // 12.5% override on 1_000_000 microcents (100¢): 1_125_000 → 112.5¢ → HALF_UP 113
        ModelInfo m = ModelInfo.text("frac", Provider.OPENAI, Modality.TEXT,
                1_000_000L, 0L, 4096, 12.5);
        assertThat(calc.actualCents(m, 1_000, 0)).isEqualTo(113L);
        // and the pre-debit estimate ceils the same marked-up amount
        assertThat(calc.estimateInputCents(m, 1_000)).isEqualTo(113L);
    }

    @Test
    void withMarkup_largeVideoAmount_doesNotOverflow() {
        // 400_000 µ¢/s (= $0.40/s) × 600 s × 10 clips = 2.4e9 µ¢ × 1.12 = 2.688e9 µ¢ = 268_800¢
        ModelInfo vid = ModelInfo.video("long", Provider.OPENAI, 400_000L, null);
        assertThat(calc.videoCostCents(vid, 600, 10)).isEqualTo(268_800L);
    }

    @Test
    void actualCents_typicalChatExchange_roundsToExpectedCents() {
        // 1000 in + 500 out for expensive: 2500 + 5000 = 7500 microcents
        // × 1.05 = 7875 → HALF_UP /10000 → 1
        assertThat(calc.actualCents(EXPENSIVE_TEXT, 1000, 500)).isEqualTo(1L);
    }

    @Test
    void actualCents_largeRequest_appliesFivePercentMarkup() {
        // 10_000 in + 5_000 out for expensive:
        //   25_000 + 50_000 = 75_000 microcents = 7.5¢
        //   × 1.05 = 78_750 → HALF_UP /10000 → 8
        assertThat(calc.actualCents(EXPENSIVE_TEXT, 10_000, 5_000)).isEqualTo(8L);
    }

    @Test
    void actualCents_modelLevelMarkupOverride_beatsTierDefault() {
        // Same tokens, 25% markup instead of 5%:
        // 75_000 × 1.25 = 93_750 → HALF_UP /10000 → 9
        assertThat(calc.actualCents(HIGH_MARKUP_TEXT, 10_000, 5_000)).isEqualTo(9L);
    }

    @Test
    void actualCents_zeroTokens_returnsZero() {
        assertThat(calc.actualCents(CHEAP_TEXT, 0, 0)).isZero();
    }

    @Test
    void imageCostCents_appliesImageTierMarkup_perImageFlatPrice() {
        // gpt-image-1: 40 000 µ¢/image = $0.04. Two images = 80 000 µ¢.
        // TEST_BILLING.image = 10% → ×1.10 = 88 000 µ¢ → HALF_UP /10 000 = 9¢.
        ModelInfo img = ModelInfo.image("gpt-image-1", Provider.OPENAI, 40_000L, null);
        assertThat(calc.imageCostCents(img, 2)).isEqualTo(9L);
    }

    @Test
    void imageCostCents_zeroImages_returnsZero() {
        ModelInfo img = ModelInfo.image("gpt-image-1", Provider.OPENAI, 40_000L, null);
        assertThat(calc.imageCostCents(img, 0)).isZero();
    }

    @Test
    void videoCostCents_appliesVideoTierMarkup_perSecondPricing() {
        // sora-2: 150 000 µ¢/sec = $0.15/sec. 10 seconds × 1 clip = 1 500 000 µ¢ = 150¢.
        // TEST_BILLING.video = 12% → ×1.12 = 1 680 000 µ¢ → HALF_UP /10 000 = 168¢.
        ModelInfo vid = ModelInfo.video("sora-2", Provider.OPENAI, 150_000L, null);
        assertThat(calc.videoCostCents(vid, 10, 1)).isEqualTo(168L);
    }

    @Test
    void videoCostCents_zeroDurationOrClips_returnsZero() {
        ModelInfo vid = ModelInfo.video("sora-2", Provider.OPENAI, 150_000L, null);
        assertThat(calc.videoCostCents(vid, 0, 1)).isZero();
        assertThat(calc.videoCostCents(vid, 10, 0)).isZero();
    }

    @Test
    void markupPercent_givenOverride_returnsOverride() {
        assertThat(calc.markupPercent(HIGH_MARKUP_TEXT)).isEqualTo(25.0);
        assertThat(calc.markupPercent(
                ModelInfo.image("sdxl", Provider.STABILITY, 50_000L, 12.0))).isEqualTo(12.0);
    }

    @Test
    void markupPercent_givenNoOverride_returnsModalityDefault() {
        assertThat(calc.markupPercent(CHEAP_TEXT)).isEqualTo(5.0);
        assertThat(calc.markupPercent(
                ModelInfo.image("gpt-image-1", Provider.OPENAI, 40_000L, null))).isEqualTo(10.0);
        assertThat(calc.markupPercent(
                ModelInfo.video("sora-2", Provider.OPENAI, 150_000L, null))).isEqualTo(12.0);
    }
}
