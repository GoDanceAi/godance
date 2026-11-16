/**
 * "Run at scale" for a gallery recipe: the prompt for an AI assistant, the
 * request as code in three languages, and the n8n template that fits.
 *
 * The wording of the prompt, the code and the n8n steps are the app's own:
 * they are imported from app/src/lib, where the app's "Run at scale" panel
 * reads them too, so the two never differ. Those three files import nothing
 * themselves. Both the CI build and the deploy build run from a checkout of
 * the whole repository, which is what makes the path below resolve.
 */
import {
  ASSISTANT_PROMPT_VERSION,
  assistantPrompt,
  type PromptRecipe,
} from '../../../../app/src/lib/assistant-prompt';
import {
  n8nInputsNote,
  n8nRow,
  n8nSteps,
  n8nTemplate,
  N8N_CREDENTIAL,
  N8N_README,
  type N8nCell,
  type N8nTemplate,
} from '../../../../app/src/lib/n8n';
import {
  CODE_LANGUAGES,
  codeFor,
  createPath,
  fileIdPlaceholder,
  uploadCode,
  type CodeLanguage,
  type CodeRequest,
  type CodeSnippet,
} from '../../../../app/src/lib/run-code';
import { API_ORIGIN, requestBody } from '../../lib/gallery-api';
import type { Recipe, Run } from '../../lib/gallery-recipes';
import { hasInputs, inputRequestBody, slotsOf } from './inputs';

export { ASSISTANT_PROMPT_VERSION, CODE_LANGUAGES, N8N_CREDENTIAL, N8N_README };
export type { CodeLanguage, CodeSnippet, N8nCell, N8nTemplate };

const kindOf = (recipe: Recipe): CodeRequest['kind'] => (recipe.modality === 'VIDEO' ? 'video' : 'image');

/**
 * The recipe as the prompt carries it, in the language the run sent: its
 * template when it has one, the prompt itself when it has none, each slot
 * without its sample, and the model and params of the run.
 */
export function promptRecipeOf(recipe: Recipe, run: Run): PromptRecipe {
  const lang = run.prompt_lang;
  const template = recipe.prompt.template;
  return {
    title: recipe.title.en,
    modality: recipe.modality,
    template: template ? template[lang] : recipe.prompt[lang],
    // A recipe published before templates lists blanks its prompt no longer has: they are not variables.
    variables: template
      ? (recipe.prompt.variables ?? []).map((variable) => ({
          name: variable.name,
          default: lang === 'zh' ? variable.zh_default ?? variable.default : variable.default,
        }))
      : [],
    inputs: slotsOf(recipe).map((slot) => ({ slot: slot.slot, role: slot.role, hint: slot.hint.en })),
    model: run.model,
    params: run.params,
    gallery_recipe: recipe.slug,
  };
}

/** The request that reproduces a run, as the code is written from it. Each input is a file id to fill in. */
export function codeRequestOf(recipe: Recipe, run: Run): CodeRequest {
  return {
    apiBase: API_ORIGIN,
    kind: kindOf(recipe),
    body: hasInputs(recipe) ? inputRequestBody(recipe, run) : requestBody(recipe, run),
    // It asks for a job and reads it until it ends, which works with any key.
    mode: 'poll',
  };
}

/** The prompt for an AI assistant, with this recipe in it. */
export function assistantPromptOf(recipe: Recipe, run: Run): string {
  const request = codeRequestOf(recipe, run);
  return assistantPrompt(promptRecipeOf(recipe, run), {
    base: API_ORIGIN,
    createPath: createPath(request.kind, request.body),
  });
}

export interface LanguageCode {
  language: CodeLanguage;
  label: string;
  /** The upload of an input image, for a recipe that takes one; then the request. */
  snippets: CodeSnippet[];
}

/** The request in each language. A recipe with inputs has the upload of an image first. */
export function codeOf(recipe: Recipe, run: Run): LanguageCode[] {
  const request = codeRequestOf(recipe, run);
  const slots = slotsOf(recipe);
  return CODE_LANGUAGES.map(({ id, label }) => ({
    language: id,
    label,
    snippets: [
      ...(slots.length > 0 ? [uploadCode(id, API_ORIGIN, 'input.png')] : []),
      ...codeFor(id, request),
    ],
  }));
}

/** What stands in the code for each slot's uploaded file, in slot order. */
export function placeholdersOf(recipe: Recipe): string[] {
  const slots = slotsOf(recipe);
  return slots.map((_, index) => fileIdPlaceholder(index, slots.length));
}

/**
 * The n8n template for this kind of recipe, the steps that set the key and
 * import it, what the recipe puts in a row of the sheet, and what the
 * template cannot send of a recipe with inputs (null when there is nothing
 * to say).
 */
export function n8nOf(recipe: Recipe, run: Run): {
  template: N8nTemplate;
  steps: string[];
  row: N8nCell[];
  inputsNote: string | null;
} {
  const template = n8nTemplate(recipe.modality);
  const prompt = promptRecipeOf(recipe, run);
  return {
    template,
    steps: n8nSteps(template),
    row: n8nRow(prompt),
    inputsNote: n8nInputsNote(recipe.modality, prompt.inputs),
  };
}
