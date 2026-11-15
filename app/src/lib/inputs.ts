/**
 * Image inputs in the Studio: the slots a model offers, what the form holds
 * in each, uploading a file, and the fields a request carries for them.
 *
 * The app never sends an address as an input. An input is one of the
 * user's uploaded files (`file_id`) or an output of one of their own jobs
 * (`job_output`).
 */
import {
  apiFetch,
  ApiError,
  type InputFile,
  type InputRef,
  type InputRole,
  type MediaModality,
  type ModelInputs,
  type RecipeInputSlot,
} from './api';

/** The image types an upload may be. */
export const INPUT_ACCEPT = 'image/png,image/jpeg,image/webp';

/** What a slot of the form holds. */
export interface FormInput {
  /** What the request names. Null for a gallery sample that has not been uploaded yet. */
  ref: InputRef | null;
  /** A link to show the image by: the chosen file, a job's output, or a sample. */
  preview: string;
  /** What to call it beside its thumbnail: the file's name, or where it came from. */
  label: string;
  /** A gallery recipe's sample still in place: uploaded when the form is submitted. */
  sample?: boolean;
}

/**
 * One slot of the form. A slot a recipe names keeps its name and hint while
 * empty; a slot the user added goes away when emptied.
 */
export interface Slot {
  key: string;
  role: InputRole;
  name?: string;
  hint?: string;
  input: FormInput | null;
}

let nextKey = 0;
export function slotKey(): string {
  nextKey += 1;
  return `slot-${nextKey}`;
}

/** How many inputs of a role the model takes. Zero when it takes none. */
export function roomFor(takes: ModelInputs | undefined, role: InputRole): number {
  if (!takes) return 0;
  switch (role) {
    case 'image': return takes.images?.max ?? 0;
    case 'mask': return takes.images?.mask ? 1 : 0;
    case 'first_frame': return takes.first_frame ? 1 : 0;
    case 'last_frame': return takes.last_frame ? 1 : 0;
    case 'reference_image': return takes.reference_images?.max ?? 0;
  }
}

/** The roles a kind of job uses, in the order the form shows them. */
export function rolesOf(modality: MediaModality): InputRole[] {
  return modality === 'VIDEO' ? ['first_frame', 'last_frame', 'reference_image'] : ['image', 'mask'];
}

/** Whether the model takes any input at all. */
export function takesInputs(takes: ModelInputs | undefined, modality: MediaModality): boolean {
  return rolesOf(modality).some((role) => roomFor(takes, role) > 0);
}

export const ROLE_LABEL: Record<InputRole, string> = {
  image: 'Image',
  mask: 'Mask',
  first_frame: 'First frame',
  last_frame: 'Last frame',
  reference_image: 'Reference image',
};

/** The slots the model can use: those of a role it takes, no more of each than it takes. */
export function usableSlots(slots: Slot[], takes: ModelInputs | undefined, modality: MediaModality): Slot[] {
  const used: Partial<Record<InputRole, number>> = {};
  return slots.filter((slot) => {
    if (!rolesOf(modality).includes(slot.role)) return false;
    const count = used[slot.role] ?? 0;
    if (count >= roomFor(takes, slot.role)) return false;
    used[slot.role] = count + 1;
    return true;
  });
}

/** The input fields of a request, as the API names them. */
export interface InputFields {
  images?: InputRef[];
  mask?: InputRef;
  first_frame?: InputRef;
  last_frame?: InputRef;
  reference_images?: InputRef[];
}

/**
 * The request's input fields for the filled slots. A sample not yet
 * uploaded has no reference: `pending` stands in for it, so the request can
 * be shown and priced before the upload.
 */
export function inputFields(slots: Slot[], pending: InputRef = { file_id: 'FILE_ID' }): InputFields {
  const filled = slots.filter((slot) => slot.input !== null);
  const refs = (role: InputRole) => filled.filter((slot) => slot.role === role).map((slot) => slot.input!.ref ?? pending);
  const fields: InputFields = {};
  if (refs('image').length) fields.images = refs('image');
  if (refs('mask').length) fields.mask = refs('mask')[0];
  if (refs('first_frame').length) fields.first_frame = refs('first_frame')[0];
  if (refs('last_frame').length) fields.last_frame = refs('last_frame')[0];
  if (refs('reference_image').length) fields.reference_images = refs('reference_image');
  return fields;
}

/** Stores one image as the user's input file. */
export async function uploadInput(file: Blob, filename: string): Promise<InputFile> {
  const form = new FormData();
  form.append('purpose', 'generation_input');
  form.append('file', file, filename);
  const stored = await apiFetch<InputFile>('/v1/files', { method: 'POST', body: form });
  if (typeof stored?.id !== 'string') throw new Error('The image could not be uploaded.');
  return stored;
}

/** A gallery sample as the user's own input: fetched, then uploaded like any file. */
export async function uploadSample(url: string): Promise<InputFile> {
  const response = await fetch(url);
  if (!response.ok) throw new Error('The sample image could not be fetched.');
  const blob = await response.blob();
  return uploadInput(blob, url.split('/').pop()?.split('?')[0] || 'sample');
}

/** What each refusal of an input means, in words a person can act on. */
const INPUT_ERRORS: Record<string, string> = {
  inputs_disabled: 'Image inputs are not available right now.',
  inputs_terms_required: 'Accept the input terms before adding an image.',
  inputs_not_allowed_for_account: 'Image inputs are not available for this account.',
  input_type_unsupported: 'That file is not a PNG, JPEG or WebP still image.',
  input_too_large: 'That image is too large. An input can be up to 20 MB.',
  input_person_not_allowed: 'Images of people are not accepted yet. Choose an image without a person in it.',
  input_check_unavailable: 'The image could not be checked just now, so it was not accepted. Try again in a moment.',
  input_limit_reached: 'You have as many stored images as an account can keep. Delete one in Settings to add another.',
  file_not_found: 'That image is no longer stored. Add it again.',
  job_output_not_found: 'That generation\'s image is no longer kept. Choose another.',
};

/** The message for a failed upload or a refused input: the code's own, or the API's words. */
export function inputErrorMessage(err: unknown, fallback = 'The image could not be added. Try again.'): string {
  if (err instanceof ApiError) {
    if (err.code && INPUT_ERRORS[err.code]) return INPUT_ERRORS[err.code];
    if (err.status === 429) return 'Too many uploads in a short time. Wait a little and try again.';
    if (err.message && !err.message.startsWith('HTTP ')) return err.message;
  }
  return fallback;
}

/** The code of an API error, or null. */
export function errorCode(err: unknown): string | null {
  return err instanceof ApiError ? err.code ?? null : null;
}

/** The name a slot is saved under when the user has given it none: its role, numbered from the second on. */
function defaultName(role: InputRole, nth: number): string {
  const base = ROLE_LABEL[role].toLowerCase();
  return nth === 0 ? base : `${base} ${nth + 1}`;
}

/**
 * The slots with a name each, as a recipe would keep them: a slot's own
 * name, or its role's. A mask is not a recipe input and keeps no name.
 */
export function withNames(slots: Slot[]): Slot[] {
  const taken = new Set(slots.filter((slot) => slot.name).map((slot) => slot.name as string));
  const counts: Partial<Record<InputRole, number>> = {};
  return slots.map((slot) => {
    if (slot.role === 'mask' || slot.name) return slot;
    let nth = counts[slot.role] ?? 0;
    let name = defaultName(slot.role, nth);
    while (taken.has(name)) {
      nth += 1;
      name = defaultName(slot.role, nth);
    }
    counts[slot.role] = nth + 1;
    taken.add(name);
    return { ...slot, name };
  });
}

/** What a recipe keeps of the form's inputs: each slot's name, role and hint. Never a file. */
export function slotsToSave(slots: Slot[]): RecipeInputSlot[] {
  return withNames(slots)
    .filter((slot) => slot.role !== 'mask')
    .map((slot) => ({ slot: slot.name as string, role: slot.role, hint: (slot.hint ?? '').trim() }));
}

/**
 * The inputs a recipe's slots get from the form, by slot name, and the
 * first slot that has none. A recipe that names its inputs runs only when
 * every one of them is filled.
 */
export function recipeInputs(
  recipeSlots: RecipeInputSlot[],
  slots: Slot[],
  pending: InputRef = { file_id: 'FILE_ID' },
): { inputs: Record<string, InputRef>; missing: string | null } {
  const named = withNames(slots);
  const inputs: Record<string, InputRef> = {};
  let missing: string | null = null;
  for (const slot of recipeSlots) {
    const filled = named.find((each) => each.name === slot.slot && each.role === slot.role && each.input !== null);
    if (filled) inputs[slot.slot] = filled.input!.ref ?? pending;
    else missing ??= slot.slot;
  }
  return { inputs, missing };
}
