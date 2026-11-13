/**
 * What a call will cost, from the live model catalogue (GET /v1/models, a
 * free public endpoint) or, when that cannot be reached, from the prices
 * the catalogue was seeded with.
 *
 * The catalogue's "microcents" are millionths of a dollar: gpt-image-2 at
 * 53000 per image is $0.053. The platform fee (markup_percent) is added on
 * top. A projection is only that: the amount recorded for a run is always
 * the job's own actualCostCents.
 */

/** Seed prices, used only when the catalogue is unreachable. */
export const SEED_MODELS = [
  { id: 'gpt-image-2', godance: { modality: 'IMAGE', pricing: { per_image_microcents: 53000 }, markup_percent: 12 } },
  { id: 'seedance-2.0', godance: { modality: 'VIDEO', pricing: { per_second_microcents: 140000 }, markup_percent: 15 } },
  { id: 'gpt-4o-mini', godance: { modality: 'TEXT', pricing: { input_microcents_per_1k: 150, output_microcents_per_1k: 600 }, markup_percent: 15 } },
];

const MICRO_PER_CENT = 10_000;

/** Rough token count: about 3.5 Latin characters or 0.8 CJK characters to a token. */
export function estimateTokens(text) {
  const cjk = (text.match(/[぀-ヿ㐀-鿿가-힯]/g) ?? []).length;
  return Math.ceil((text.length - cjk) / 3.5 + cjk * 1.2);
}

/**
 * @param {object[] | null} models The `data` array of GET /v1/models, or null to use the seed prices.
 */
export function priceBook(models) {
  const live = Array.isArray(models) && models.length > 0;
  const byId = new Map((live ? models : SEED_MODELS).map((model) => [model.id, model]));

  const find = (id) => {
    const model = byId.get(id);
    if (!model) throw new Error(`no price for "${id}": the catalogue does not list it`);
    return model;
  };
  const withFee = (model, micro) => (micro * (1 + (model.godance?.markup_percent ?? 0) / 100)) / MICRO_PER_CENT;

  return {
    /** 'live' when read from the API, 'seed' when it fell back. */
    source: live ? 'live' : 'seed',
    has: (id) => byId.has(id),
    modality: (id) => byId.get(id)?.godance?.modality ?? null,
    /** Whole cents an image job holds. */
    image(id, n = 1) {
      const model = find(id);
      return Math.ceil(withFee(model, (model.godance.pricing.per_image_microcents ?? 0) * n));
    },
    /** Whole cents a video job holds. */
    video(id, seconds, n = 1) {
      const model = find(id);
      return Math.ceil(withFee(model, (model.godance.pricing.per_second_microcents ?? 0) * seconds * n));
    },
    /** Cents (a fraction) for a text call of this many tokens. */
    text(id, inputTokens, outputTokens) {
      const model = find(id);
      const pricing = model.godance.pricing;
      const micro = ((pricing.input_microcents_per_1k ?? 0) * inputTokens + (pricing.output_microcents_per_1k ?? 0) * outputTokens) / 1000;
      return withFee(model, micro);
    },
  };
}
