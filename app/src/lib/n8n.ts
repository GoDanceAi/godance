// The n8n templates "Run at scale" points at: which one fits, where to
// download it, and the four steps that import it and set the key.
//
// The templates live in this repository under integrations/n8n/. The
// marketing site shows the same steps on a gallery recipe's page and imports
// this file to do it, so this file imports nothing.

/** The n8n credential the templates name. It is a "Header Auth" credential; the key is never in a workflow. */
export const N8N_CREDENTIAL = 'Godance API';
export const N8N_CREDENTIAL_TYPE = 'Header Auth';

/** The templates as raw files on the repository's main branch. */
const N8N_RAW = 'https://raw.githubusercontent.com/GoDanceAi/godance/main/integrations/n8n';

/** The templates' own notes: the sheet's columns, and what each node does. */
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

/** The four steps, in order: import the template, then set the key. */
export function n8nSteps(template: N8nTemplate): string[] {
  return [
    `Download ${template.file}.`,
    'In n8n, open a new workflow, open the menu at the top right and choose "Import from File…". Pick the file you downloaded.',
    `Under Credentials, add a "${N8N_CREDENTIAL_TYPE}" credential named "${N8N_CREDENTIAL}": its Name is Authorization `
      + 'and its Value is the word Bearer, a space, then your API key. The workflow finds it by that name, so the key '
      + 'is never written into the workflow.',
    'Point the Google Sheets nodes at your sheet, then run the workflow. Each row costs what one run costs, so try it '
      + 'on two or three rows first.',
  ];
}
