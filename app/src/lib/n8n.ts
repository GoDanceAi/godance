// The n8n templates "Run at scale" points at: which one fits, where to
// download it, the four steps that set the key and import it, and what a
// recipe puts in the sheet.
//
// The templates live in this repository under integrations/n8n/, and their
// README there is the full account; what is said here follows it. The
// marketing site shows the same steps on a gallery recipe's page and imports
// this file to do it, so this file imports nothing.

/** The n8n credential the templates name. It is a "Header Auth" credential; the key is never in a workflow. */
export const N8N_CREDENTIAL = 'Godance API';
export const N8N_CREDENTIAL_TYPE = 'Header Auth';

/** The templates as raw files on the repository's main branch. */
const N8N_RAW = 'https://raw.githubusercontent.com/GoDanceAi/godance/main/integrations/n8n';

/** The templates' own notes: every column, what each node does, how long it waits. */
export const N8N_README = 'https://github.com/GoDanceAi/godance/tree/main/integrations/n8n#readme';

export interface N8nTemplate {
  file: string;
  /** The raw file, to download and import. */
  url: string;
  /** What the workflow does, in a sentence. */
  does: string;
}

const TEMPLATES: Record<'IMAGE' | 'VIDEO', N8nTemplate> = {
  IMAGE: {
    file: 'godance-sheet-to-images.json',
    url: `${N8N_RAW}/godance-sheet-to-images.json`,
    does: 'Each row of a Google Sheet becomes one image job. The workflow waits for the job, then writes the '
      + "image's link and its cost back to the row.",
  },
  VIDEO: {
    file: 'godance-sheet-to-videos.json',
    url: `${N8N_RAW}/godance-sheet-to-videos.json`,
    does: 'Each row of a Google Sheet becomes one video job. The workflow waits for the job, then writes the '
      + "video's link and its cost back to the row.",
  },
};

/** The template for a kind of recipe. */
export function n8nTemplate(modality: 'IMAGE' | 'VIDEO'): N8nTemplate {
  return TEMPLATES[modality];
}

/** The four steps, in order: set the key as a credential, import the template, point it at a sheet, run it. */
export function n8nSteps(template: N8nTemplate): string[] {
  return [
    `In n8n, create a credential of the type "${N8N_CREDENTIAL_TYPE}" and name it "${N8N_CREDENTIAL}". Its Name is `
      + 'Authorization and its Value is the word Bearer, a space, then your API key. n8n stores the key: it is '
      + 'never written into the workflow.',
    `Download ${template.file}. In a new workflow, open the menu, choose "Import from File" and pick it. The `
      + 'credential is found by its name.',
    'Open the two Google Sheets nodes, "Get the rows" and "Update the row", and choose your Google account, the '
      + 'spreadsheet and the sheet.',
    'Click "Execute workflow". Every row is a paid job, so try three rows first: only rows with a prompt and an '
      + 'empty status are run.',
  ];
}

/** One cell a row of the sheet needs: the column's name, and what goes in it. */
export interface N8nCell {
  column: string;
  value: string;
}

/**
 * What a recipe puts in a row of the sheet. The templates send a prompt,
 * not a recipe, so the prompt is the template with its variables filled in;
 * the model and the settings are the recipe's.
 */
export function n8nRow(recipe: {
  modality: 'IMAGE' | 'VIDEO';
  model: string;
  params: Record<string, unknown>;
  variables: { name: string }[];
}): N8nCell[] {
  const cells: N8nCell[] = [
    {
      column: 'prompt',
      value: recipe.variables.length > 0 ? 'the template, with its variables filled in for that row' : 'the prompt',
    },
    { column: 'model', value: recipe.model },
  ];
  if (typeof recipe.params.size === 'string' && recipe.params.size !== '') cells.push({ column: 'size', value: recipe.params.size });
  if (recipe.modality === 'VIDEO' && typeof recipe.params.seconds === 'number') {
    cells.push({ column: 'seconds', value: String(recipe.params.seconds) });
  }
  return cells;
}

/**
 * What the template can do with a recipe's input images, said plainly: the
 * image template sends none, and the video template a first frame only.
 * Null for a recipe without inputs.
 */
export function n8nInputsNote(modality: 'IMAGE' | 'VIDEO', inputs: { role: string }[]): string | null {
  if (inputs.length === 0) return null;
  if (modality === 'IMAGE') {
    return 'This recipe works from an input image, and the image template sends a prompt alone. Run it with the '
      + 'code instead, or as a batch in the app.';
  }
  const firstFrame = inputs.some((slot) => slot.role === 'first_frame');
  const others = inputs.some((slot) => slot.role !== 'first_frame');
  if (firstFrame && !others) {
    return 'For the first frame, upload the image with POST /v1/files and put its id in the first_frame_file_id column.';
  }
  return 'The video template takes a first frame only, in the first_frame_file_id column (the id of an image '
    + 'uploaded with POST /v1/files). The other images this recipe uses cannot be sent from it: run it with the '
    + 'code instead, or as a batch in the app.';
}
