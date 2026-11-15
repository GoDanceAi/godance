/**
 * "Describe as prompt": an image, sent to a chat model that can see, with a
 * fixed instruction to write a prompt that would reproduce it. It is a
 * normal chat call and is charged as one.
 */
import { API_BASE, apiFetch, isVirtualModel, type ModelEntry, type ModelListResponse } from './api';
import { uploadSample } from './inputs';

/** What the model is asked. Fixed: nothing the user typed is part of it. */
export const DESCRIBE_INSTRUCTION =
  'Describe this image as a prompt for an image generation model, so that the prompt would reproduce the image: '
  + 'the subject, the setting, the composition, the lighting, the colours and the style. '
  + 'Answer with the prompt only, in one paragraph, with no introduction.';

/** The longest answer asked for, in tokens. */
export const DESCRIBE_MAX_TOKENS = 300;

/** What an image is allowed for when the call is priced before it is made: a large still, counted high. */
const IMAGE_TOKENS_ALLOWED = 2500;
const INSTRUCTION_TOKENS = 60;

/** The cheapest chat model that takes images, or undefined when none does. */
export function describeModel(catalog: ModelListResponse): ModelEntry | undefined {
  const seeing = catalog.data.filter((m): m is ModelEntry =>
    !isVirtualModel(m) && m.godance.modality === 'TEXT' && (m.godance.capabilities?.inputs?.images?.max ?? 0) > 0);
  const price = (m: ModelEntry) =>
    (m.godance.pricing.input_microcents_per_1k ?? 0) * (IMAGE_TOKENS_ALLOWED + INSTRUCTION_TOKENS)
    + (m.godance.pricing.output_microcents_per_1k ?? 0) * DESCRIBE_MAX_TOKENS;
  return seeing.reduce<ModelEntry | undefined>(
    (best, m) => (best === undefined || price(m) < price(best) ? m : best), undefined);
}

/**
 * About what the call costs, in cents, rounded up: the instruction and an
 * image counted high as input, a full answer as output, with the platform
 * fee. The real charge follows the tokens the provider reports.
 */
export function describeCostCents(model: ModelEntry): number {
  const microcents =
    ((model.godance.pricing.input_microcents_per_1k ?? 0) * (IMAGE_TOKENS_ALLOWED + INSTRUCTION_TOKENS)
      + (model.godance.pricing.output_microcents_per_1k ?? 0) * DESCRIBE_MAX_TOKENS) / 1000;
  const withFee = microcents * (1 + (model.godance.markup_percent ?? 0) / 100);
  return Math.max(1, Math.ceil(withFee / 10_000));
}

/** The image to describe: one of the user's files, or a link to fetch and store as one. */
export interface DescribeSource {
  fileId: string | null;
  url: string;
}

interface ChatAnswer {
  choices?: { message?: { content?: string | null } }[];
}

/**
 * Asks the model for a prompt that would reproduce the image. The image goes
 * as one of the user's own files, by its content address on the API, which
 * the API reads from storage: an image that is not a file yet is stored as
 * one first. The app never sends a link to an image somewhere else.
 */
export async function describeAsPrompt(source: DescribeSource, model: string): Promise<string> {
  const fileId = source.fileId ?? (await uploadSample(source.url)).id;
  const answer = await apiFetch<ChatAnswer>('/v1/chat/completions', {
    method: 'POST',
    json: {
      model,
      max_tokens: DESCRIBE_MAX_TOKENS,
      messages: [{
        role: 'user',
        content: [
          { type: 'text', text: DESCRIBE_INSTRUCTION },
          { type: 'image_url', image_url: { url: `${API_BASE}/v1/files/${fileId}/content` } },
        ],
      }],
    },
  });
  const text = answer?.choices?.[0]?.message?.content?.trim() ?? '';
  if (text === '') throw new Error('The model gave no description.');
  return text;
}
