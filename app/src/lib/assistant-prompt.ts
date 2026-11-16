// The prompt "Run at scale" hands to the user's AI assistant: one constant,
// filled in with the recipe.
//
// The marketing site shows the same prompt on a gallery recipe's page and
// imports this file to do it (marketing/src/components/gallery/run-at-scale.ts).
// So this file imports nothing: nothing of the app, of the browser or of Vite.

/**
 * A recipe as the prompt carries it. This is everything about an account
 * that the prompt holds: no key, no email, no balance, no file or job id.
 */
export interface PromptRecipe {
  title?: string;
  modality: 'IMAGE' | 'VIDEO';
  /** The prompt, with a {{name}} for each variable. A plain prompt is a template without variables. */
  template: string;
  variables: { name: string; default: string }[];
  /** The images it needs, by slot. Never a file: the script uploads its own. */
  inputs: { slot: string; role: string; hint: string }[];
  model: string;
  params: Record<string, unknown>;
  /** One of the user's own recipes: the version a request names to run it. */
  version_id?: string;
  /** A recipe from the public gallery: its slug. */
  gallery_recipe?: string;
}

/** Where the script sends its requests. */
export interface PromptApi {
  /** The API's origin, without /v1: https://api.godance.ai */
  base: string;
  /** The route that runs the recipe once: /v1/images/generations */
  createPath: string;
}

// =============================================================================
// OPERATOR: this is the draft wording of the AI-assistant prompt. Edit it here.
//
// It is the only copy: the app's "Run at scale" panel and the gallery recipe
// pages of the marketing site both show this text. When you change the
// wording, change ASSISTANT_PROMPT_VERSION too.
//
// Three markers are filled in for each recipe; keep them as they are written:
//   [[API_BASE]]     the API's address, e.g. https://api.godance.ai
//   [[CREATE_PATH]]  the route that runs the recipe once, e.g. /v1/images/generations
//   [[RECIPE_JSON]]  the recipe: template, variables, input slots, model, params
//
// Lines between a line "[[IF ...]]" and the next line "[[END]]" are kept only
// for the recipes they are about, and the two marker lines never show:
//   IMAGE, VIDEO     what the recipe makes
//   INPUTS           the recipe needs images (it has input slots)
//   SAVED            one of the user's own recipes, run by its version_id
//   NOT_SAVED        anything else: the script fills the template in itself
//   GALLERY          a recipe from the public gallery
//   OPENAI_VIDEO     a video the script sends to OpenAI's route, POST /v1/videos
//   NOT_OPENAI_VIDEO anything else: an image, or a video with what POST /v1/videos
//                    has no field for (a last frame, reference images), which goes
//                    to POST /v1/videos/generations
// Several words on one line mean all of them, as in "[[IF INPUTS SAVED]]".
// Blocks do not nest.
//
// {{name}} below is not a marker: it is how a recipe writes a variable, and
// the prompt explains it to the assistant.
// =============================================================================
export const ASSISTANT_PROMPT_VERSION = '2026-11-15.3';

export const ASSISTANT_PROMPT = `I want to run a Godance recipe at scale. Write me a Python script that runs the recipe below once for every row of a CSV file I give it, and writes the results to a new CSV file.

Godance is an OpenAI-compatible API for image and video generation. Before you write anything, read its reference for AI assistants: https://godance.ai/llms-full.txt (the same for people: https://godance.ai/docs). Use only the routes and fields you find there or in this message.

API base URL: [[API_BASE]]/v1
The request that runs this recipe once: POST [[API_BASE]][[CREATE_PATH]]

The recipe, as JSON:

[[RECIPE_JSON]]

How to read it:
- "template" is the prompt. Each {{name}} in it is a variable, and "variables" lists them with the value each one has when a row gives none.
- A column of my CSV whose header is a variable's name holds that variable's values. If my headers do not match the names, ask me which column is which.
- "model" and every key of "params" go into every request as they are.
[[IF SAVED]]
- "version_id" names this recipe on Godance. Send "recipe": {"version_id": "...", "variables": {...}} in place of "prompt", and Godance fills the template in.
[[END]]
[[IF SAVED OPENAI_VIDEO]]
- client.videos.create_and_poll sends a form, in which "recipe" is JSON text: pass prompt="" and extra_body={"recipe": json.dumps({...})}.
[[END]]
[[IF NOT_SAVED]]
- Fill the template in yourself, and send the result as "prompt".
[[END]]
[[IF GALLERY]]
- Send "gallery_recipe" unchanged with every request. It only records which public recipe a run started from.
[[END]]
[[IF INPUTS]]
- "inputs" lists the images the recipe needs, one for each slot. For each slot, my CSV has a column with the slot's name that holds the path of an image file. Upload each file with POST /v1/files (multipart, in a field named "file") and use the "id" it answers with. Uploading works once I have accepted the input terms in the app's settings.
[[END]]
[[IF INPUTS SAVED]]
- Give each slot its file inside "recipe": "inputs": {"<slot name>": {"file_id": "..."}}.
[[END]]
[[IF INPUTS NOT_SAVED NOT_OPENAI_VIDEO]]
- Each input goes in the field its "role" names: "image" in "images" (a list of {"file_id": "..."}), "mask" in "mask", "first_frame" in "first_frame", "last_frame" in "last_frame", "reference_image" in "reference_images" (a list).
[[END]]
[[IF INPUTS NOT_SAVED OPENAI_VIDEO]]
- The input is the video's first frame: send it as "input_reference": {"file_id": "..."}, which client.videos.create_and_poll takes in extra_body.
[[END]]

Rules the script must follow:
1. Read the API key from the GODANCE_API_KEY environment variable. Never write the key into the code, into a file or into the output. Stop with a clear message if the variable is not set.
2. Use the OpenAI Python SDK (pip install openai) with base_url set to the API base URL above. For a route the SDK has no method for, call client.post(...) and client.get(...) with cast_to=object, which returns the JSON answer as a dict. Do not import httpx: recent versions of the SDK do not install it.
[[IF IMAGE]]
3. An image request answers in one of two ways. With a key set to synchronous image responses it waits and answers 200 with the images under "data". Otherwise it answers 202 with a "job_id": poll GET /v1/jobs/{id} every few seconds until "status" is DONE, FAILED or CANCELLED, then read "outputs" (each has a "url") and "actualCostCents". Handle both answers.
[[END]]
[[IF VIDEO NOT_OPENAI_VIDEO]]
3. A video request always answers 202 with a "job_id". Poll GET /v1/jobs/{id} every few seconds until "status" is DONE, FAILED or CANCELLED, then read "outputs" (each has a "url") and "actualCostCents". A video can take several minutes.
[[END]]
[[IF OPENAI_VIDEO]]
3. A video request answers 200 with the video: its "id", which is the job's id, and "status": "queued". client.videos.create_and_poll(...) sends it and reads GET /v1/videos/{id} every few seconds until "status" is completed or failed. Save the file with client.videos.download_content(id, variant="video"). "cost_cents" is what it cost, and "job_url" is the same video as a job, whose "outputs" each have a "url". A video can take several minutes.
[[END]]
4. Estimate the cost first. Call POST /v1/estimates with the model and the params, multiply "estimated_cost_cents" by the number of rows, show me the total, and wait for my yes before sending anything that costs money.
5. Stop at once on HTTP 402: the balance or the key's spending cap has run out. Do not retry it. Tell me which row it stopped at, so that I can run the rest later.
6. Never send a row twice. Create the client with max_retries=0, and when the script is run again, skip the rows that already have a result.
7. Write one line per row to the results CSV as soon as the row ends: the row's values, the job id, the status, the output URL or URLs, the cost in cents, and the error if it failed. A row that fails does not stop the others.
8. Run at most 2 rows at a time. When the API answers 429, wait for as long as its Retry-After header says, then carry on.

When the script is written, tell me how to run it: what to install, how to set GODANCE_API_KEY, and the command that starts it on my CSV file.`;

/** What a block of the wording can be about. A block is kept when every word on its [[IF …]] line is true. */
export const PROMPT_CONDITIONS = [
  'IMAGE', 'VIDEO', 'INPUTS', 'SAVED', 'NOT_SAVED', 'GALLERY', 'OPENAI_VIDEO', 'NOT_OPENAI_VIDEO',
] as const;
export type PromptCondition = (typeof PROMPT_CONDITIONS)[number];

/** OpenAI's video route. A video sent there answers as a video, read at GET /v1/videos/{id}, not as a job. */
export const OPENAI_VIDEO_PATH = '/v1/videos';

/** Which blocks of the wording are about this recipe, run by a request to this route. */
export function promptConditions(recipe: PromptRecipe, api: Pick<PromptApi, 'createPath'>): Record<PromptCondition, boolean> {
  const saved = Boolean(recipe.version_id);
  const openAiVideo = recipe.modality === 'VIDEO' && api.createPath === OPENAI_VIDEO_PATH;
  return {
    IMAGE: recipe.modality === 'IMAGE',
    VIDEO: recipe.modality === 'VIDEO',
    INPUTS: recipe.inputs.length > 0,
    SAVED: saved,
    NOT_SAVED: !saved,
    GALLERY: Boolean(recipe.gallery_recipe),
    OPENAI_VIDEO: openAiVideo,
    NOT_OPENAI_VIDEO: !openAiVideo,
  };
}

/** A block: its [[IF …]] line, its lines, its [[END]] line. */
const BLOCK = /^\[\[IF ([A-Z_ ]+)\]\]\n([\s\S]*?)^\[\[END\]\]\n/gm;

/**
 * The wording for a recipe: each block kept or dropped, and its two marker
 * lines gone either way. A word that is not a condition keeps nothing.
 */
export function promptWording(conditions: Record<string, boolean>, wording: string = ASSISTANT_PROMPT): string {
  return wording.replace(BLOCK, (_, words: string, lines: string) =>
    (words.trim().split(/\s+/).every((word) => conditions[word] === true) ? lines : ''));
}

/**
 * The recipe as the prompt shows it: the same keys in the same order for
 * every recipe, and the two optional ones only when the recipe has them.
 */
export function promptRecipeJson(recipe: PromptRecipe): string {
  const shown: Record<string, unknown> = {};
  if (recipe.title) shown.title = recipe.title;
  shown.modality = recipe.modality;
  shown.template = recipe.template;
  shown.variables = recipe.variables.map((variable) => ({ name: variable.name, default: variable.default }));
  shown.inputs = recipe.inputs.map((slot) => ({ slot: slot.slot, role: slot.role, hint: slot.hint }));
  shown.model = recipe.model;
  shown.params = recipe.params;
  if (recipe.version_id) shown.version_id = recipe.version_id;
  if (recipe.gallery_recipe) shown.gallery_recipe = recipe.gallery_recipe;
  return JSON.stringify(shown, null, 2);
}

/**
 * The prompt for one recipe. The blocks are settled first, on the wording
 * alone; then each marker is filled in one pass, so nothing a recipe holds
 * is ever read as a marker or as a block.
 */
export function assistantPrompt(recipe: PromptRecipe, api: PromptApi): string {
  const values: Record<string, string> = {
    API_BASE: api.base.replace(/\/+$/, ''),
    CREATE_PATH: api.createPath,
    RECIPE_JSON: promptRecipeJson(recipe),
  };
  return promptWording(promptConditions(recipe, api))
    .replace(/\[\[(API_BASE|CREATE_PATH|RECIPE_JSON)\]\]/g, (_, name: string) => values[name]);
}
