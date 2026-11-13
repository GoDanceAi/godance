/**
 * What a recipe looks like outside its own page: the API requests that
 * reproduce a run, the two JSON endpoints the app reads, the search data,
 * and the structured data for search engines.
 */
import {
  isVideoOutput,
  mediaUrl,
  recipePath,
  thumbFile,
  excerpt,
  type Recipe,
  type Run,
} from './gallery-recipes';

/** The API host, as the docs page writes it. */
export const API_ORIGIN = 'https://api.godance.ai';

const SITE_NAME = 'Godance';

// ---------------------------------------------------------------------------
// The API call
// ---------------------------------------------------------------------------

/** The request body that reproduces a run: the model, the prompt as it was sent, then the parameters. */
export function requestBody(recipe: Recipe, run: Run): Record<string, unknown> {
  const body: Record<string, unknown> = { model: run.model, prompt: recipe.prompt[run.prompt_lang] };
  for (const [key, value] of Object.entries(run.params)) {
    if (!(key in body)) body[key] = value;
  }
  return body;
}

export function generationsEndpoint(recipe: Recipe): string {
  return `${API_ORIGIN}/v1/${recipe.modality === 'VIDEO' ? 'videos' : 'images'}/generations`;
}

/** The word that ends the here-document in the snippet. */
const HEREDOC = 'JSON';

/**
 * The curl command that reproduces a run.
 *
 * The body goes in through a quoted here-document, so the shell reads it
 * exactly as written: no quoting, no `$` or backtick expansion. It is
 * pretty-printed JSON, in which a line break inside the prompt is the two
 * characters `\n`, so no line of it can be the bare closing word.
 */
export function createSnippet(recipe: Recipe, run: Run): string {
  return [
    `curl ${generationsEndpoint(recipe)} \\`,
    '  -H "Authorization: Bearer $GODANCE_API_KEY" \\',
    '  -H "Content-Type: application/json" \\',
    `  --data-binary @- <<'${HEREDOC}'`,
    JSON.stringify(requestBody(recipe, run), null, 2),
    HEREDOC,
  ].join('\n');
}

/** The curl command that polls the job the first request returns. */
export const pollSnippet = [
  '# job_id comes from the 202 response above',
  `curl ${API_ORIGIN}/v1/jobs/$JOB_ID \\`,
  '  -H "Authorization: Bearer $GODANCE_API_KEY"',
].join('\n');

// ---------------------------------------------------------------------------
// JSON endpoints (shapes fixed by the round-8 coordination notes)
// ---------------------------------------------------------------------------

/** GET /gallery/index.json: a light list for "start from an example". */
export function indexJson(recipes: Recipe[], mediaBase: string) {
  return {
    recipes: recipes.map((recipe) => {
      const thumb = thumbFile(recipe.runs[0].outputs[0]);
      return {
        slug: recipe.slug,
        title: recipe.title,
        modality: recipe.modality,
        model: recipe.runs[0].model,
        tags: recipe.tags,
        thumb: thumb === null ? null : mediaUrl(mediaBase, thumb),
        published_on: recipe.published_on,
      };
    }),
  };
}

/** GET /gallery/<slug>.json: one recipe, for the hand-off into the app. */
export function recipeJson(recipe: Recipe, site: URL | string) {
  const run = recipe.runs[0];
  return {
    slug: recipe.slug,
    title: recipe.title,
    modality: recipe.modality,
    prompt: { en: recipe.prompt.en, zh: recipe.prompt.zh },
    run: { model: run.model, prompt_lang: run.prompt_lang, params: run.params },
    url: new URL(recipePath(recipe.slug), site).toString(),
  };
}

/**
 * GET /gallery/_search.json: the titles and prompts in both languages, lower
 * case, one string per recipe. The gallery page fetches it the first time a
 * visitor uses search. No slug can start with "_", so it cannot collide.
 */
export function searchJson(recipes: Recipe[]) {
  return {
    recipes: recipes.map((recipe) => ({
      slug: recipe.slug,
      text: [recipe.title.en, recipe.title.zh ?? '', recipe.prompt.en, recipe.prompt.zh]
        .join('\n')
        .toLowerCase(),
    })),
  };
}

// ---------------------------------------------------------------------------
// Structured data
// ---------------------------------------------------------------------------

/** The canonical page for each licence a source list may carry. */
export const LICENCE_URLS: Record<Recipe['source']['licence'], string> = {
  'CC0-1.0': 'https://creativecommons.org/publicdomain/zero/1.0/',
  'CC-BY-4.0': 'https://creativecommons.org/licenses/by/4.0/',
  MIT: 'https://opensource.org/license/mit',
  'Apache-2.0': 'https://www.apache.org/licenses/LICENSE-2.0',
};

/**
 * Structured data for a recipe page, as one graph of three things, so that
 * who made what stays exact:
 *  - the prompt: written by the original author, based on the original post,
 *    under the source list's licence;
 *  - the output: created by Godance;
 *  - the page: published by Godance.
 */
export function recipeJsonLd(recipe: Recipe, site: URL | string, mediaBase: string) {
  const url = new URL(recipePath(recipe.slug), site).toString();
  const godance = { '@type': 'Organization', name: SITE_NAME, url: new URL('/', site).toString() };
  const run = recipe.runs[0];
  const output = run.outputs[0];
  const description = excerpt(recipe.prompt.en);

  const prompt = {
    '@type': 'CreativeWork',
    '@id': `${url}#prompt`,
    name: `Prompt: ${recipe.title.en}`,
    text: recipe.prompt.en,
    inLanguage: 'en',
    author: { '@type': 'Person', name: recipe.source.author, url: recipe.source.author_url },
    isBasedOn: recipe.source.original_url,
    license: LICENCE_URLS[recipe.source.licence],
  };

  const media = isVideoOutput(output)
    ? {
        '@type': 'VideoObject',
        '@id': `${url}#output`,
        name: recipe.title.en,
        description,
        contentUrl: mediaUrl(mediaBase, output.file),
        encodingFormat: output.content_type,
        uploadDate: run.generated_on,
        duration: `PT${output.duration_seconds}S`,
        width: output.width,
        height: output.height,
        // A thumbnail only when the video has a poster.
        ...(output.poster ? { thumbnailUrl: mediaUrl(mediaBase, output.poster) } : {}),
        creator: godance,
        isBasedOn: { '@id': `${url}#prompt` },
      }
    : {
        '@type': 'ImageObject',
        '@id': `${url}#output`,
        name: recipe.title.en,
        description,
        contentUrl: mediaUrl(mediaBase, output.file),
        thumbnailUrl: mediaUrl(mediaBase, output.thumb),
        encodingFormat: output.content_type,
        uploadDate: run.generated_on,
        width: output.width,
        height: output.height,
        creator: godance,
        isBasedOn: { '@id': `${url}#prompt` },
      };

  const page = {
    '@type': 'WebPage',
    '@id': url,
    url,
    name: recipe.title.en,
    description,
    inLanguage: 'en',
    datePublished: recipe.published_on,
    publisher: godance,
    mainEntity: { '@id': `${url}#output` },
    hasPart: [{ '@id': `${url}#prompt` }],
  };

  return { '@context': 'https://schema.org', '@graph': [page, prompt, media] };
}
