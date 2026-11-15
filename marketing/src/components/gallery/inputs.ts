/**
 * A recipe's inputs outside its own markup: what the JSON endpoints say about
 * them, and the API requests that reproduce a run made with them.
 *
 * A recipe with inputs has slots, each with a sample input generated on
 * Godance. Running it takes two steps: upload an image for each slot, then
 * send the request, naming each upload by the id it was given.
 */
import { API_ORIGIN, requestBody } from '../../lib/gallery-api';
import { mediaUrl, type Recipe, type Run } from '../../lib/gallery-recipes';

export type InputSlot = NonNullable<Recipe['inputs']>[number];

/** The recipe's slots, in order. Empty for a recipe that takes no input. */
export const slotsOf = (recipe: Recipe): InputSlot[] => recipe.inputs ?? [];

export const hasInputs = (recipe: Recipe): boolean => slotsOf(recipe).length > 0;

/** What a slot's role is called on a page. */
export const ROLE_LABELS: Record<InputSlot['role'], string> = {
  image: 'Image',
  first_frame: 'First frame',
  last_frame: 'Last frame',
  reference_image: 'Reference image',
};

// ---------------------------------------------------------------------------
// JSON (shapes fixed by the round-11 coordination notes)
// ---------------------------------------------------------------------------

/**
 * `inputs` in GET /gallery/<slug>.json: each slot with its role, its English
 * hint, and the absolute address of its sample.
 */
export function inputsJson(recipe: Recipe, mediaBase: string) {
  return slotsOf(recipe).map((slot) => ({
    slot: slot.slot,
    role: slot.role,
    hint: slot.hint.en,
    sample_url: mediaUrl(mediaBase, slot.sample.file),
  }));
}

// ---------------------------------------------------------------------------
// The API call
// ---------------------------------------------------------------------------

/**
 * What stands in the request for the id of the file uploaded for a slot. A
 * quoted here-document expands nothing, so the reader puts each id in by
 * hand: FILE_ID, or FILE_ID_1, FILE_ID_2 … when there are several slots.
 */
export function fileIdPlaceholder(recipe: Recipe, index: number): string {
  return slotsOf(recipe).length === 1 ? 'FILE_ID' : `FILE_ID_${index + 1}`;
}

/**
 * The name the upload snippet saves a slot's sample under: its place among
 * the slots and its type. Never the sample's own path, which may hold
 * characters a shell reads.
 */
function sampleName(slot: InputSlot, index: number): string {
  // A media type's second half is limited by the format to letters, digits, ".", "+" and "-".
  const subtype = slot.sample.content_type.slice('image/'.length);
  return `input-${index + 1}.${subtype === 'jpeg' ? 'jpg' : subtype}`;
}

/** A URL inside single quotes, where a shell expands nothing: the one character that would end them is encoded. */
const quotedUrl = (url: string) => `'${url.replace(/'/g, '%27')}'`;

export function inputEndpoint(recipe: Recipe): string {
  return `${API_ORIGIN}/v1/${recipe.modality === 'VIDEO' ? 'videos/generations' : 'images/edits'}`;
}

/**
 * The request body that reproduces a run made with inputs: the body of a
 * plain run, plus each slot's upload in the field its role names. An image
 * recipe lists its images in slot order; a video names its first frame, its
 * last frame and its reference images. `gallery_recipe` stays last.
 */
export function inputRequestBody(recipe: Recipe, run: Run): Record<string, unknown> {
  const { gallery_recipe, ...body } = requestBody(recipe, run);
  const pointers = slotsOf(recipe).map((slot, index) => ({
    role: slot.role,
    pointer: { file_id: fileIdPlaceholder(recipe, index) },
  }));
  const withRole = (role: InputSlot['role']) => pointers.filter((each) => each.role === role).map((each) => each.pointer);

  if (recipe.modality === 'VIDEO') {
    const [first] = withRole('first_frame');
    const [last] = withRole('last_frame');
    const references = withRole('reference_image');
    if (first) body.first_frame = first;
    if (last) body.last_frame = last;
    if (references.length > 0) body.reference_images = references;
  } else {
    body.images = withRole('image');
  }
  return { ...body, gallery_recipe };
}

/**
 * The commands that upload an image for each slot. Each fetches the sample
 * shown on the page, so the run can be reproduced as it was; a reader who
 * wants their own picture uploads that instead. A slot's name is limited to
 * plain characters, the file name is made here, and the sample's address is
 * quoted, so nothing in a recipe is read by the shell.
 */
export function uploadSnippet(recipe: Recipe, mediaBase: string): string {
  return slotsOf(recipe)
    .map((slot, index) =>
      [
        `# ${slot.slot}: the sample from this page, or an image of your own`,
        `curl -o ${sampleName(slot, index)} ${quotedUrl(mediaUrl(mediaBase, slot.sample.file))}`,
        `curl ${API_ORIGIN}/v1/files \\`,
        '  -H "Authorization: Bearer $GODANCE_API_KEY" \\',
        `  -F "file=@${sampleName(slot, index)}"`,
        `# the "id" in the answer goes into the request below, in place of ${fileIdPlaceholder(recipe, index)}`,
      ].join('\n'),
    )
    .join('\n\n');
}

/** The word that ends the here-document in the snippet. */
const HEREDOC = 'JSON';

/**
 * The curl command that reproduces a run made with inputs. The body goes in
 * through a quoted here-document, as on a recipe without inputs, so the
 * shell reads the prompt exactly as written.
 */
export function inputCreateSnippet(recipe: Recipe, run: Run): string {
  return [
    `curl ${inputEndpoint(recipe)} \\`,
    '  -H "Authorization: Bearer $GODANCE_API_KEY" \\',
    '  -H "Content-Type: application/json" \\',
    `  --data-binary @- <<'${HEREDOC}'`,
    JSON.stringify(inputRequestBody(recipe, run), null, 2),
    HEREDOC,
  ].join('\n');
}
