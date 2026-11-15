/**
 * Reading routes out of two kinds of text, for the check that what the site
 * tells AI assistants (public/llms.txt, public/llms-full.txt) names only
 * routes the API really has.
 *
 * The API's routes come from the backend's OpenAPI file, read from the
 * repository: backend/src/main/resources/static/openapi.yaml. That file is
 * written by hand in a fixed layout, and a backend test (OpenApiDriftTest)
 * fails if the layout changes or if the file and the code disagree. So it is
 * read here line by line, without a YAML parser:
 *
 *   paths:
 *     /v1/jobs/{id}:          a path: a key indented two spaces
 *       get:                  a method: a key indented four spaces
 *         summary: …          one line, indented six
 *         security: [{ apiKey: [] }, { session: [] }]     one line, indented six
 */

const METHODS = 'get|put|post|delete|patch|head|options';

/**
 * Every operation of the OpenAPI file: its method, its path as written
 * (`/v1/jobs/{id}`), its summary, and whether an API key may call it.
 */
export function openApiRoutes(yaml) {
  const routes = [];
  let inPaths = false;
  let path = null;
  let current = null;
  for (const line of yaml.split('\n')) {
    if (line === 'paths:') {
      inPaths = true;
      continue;
    }
    if (!inPaths) continue;
    // The next top-level key ends the paths.
    if (/^\S/.test(line) && !line.startsWith('#')) break;

    const asPath = /^ {2}(\/[^\s:]*):\s*$/.exec(line);
    if (asPath) {
      path = asPath[1];
      current = null;
      continue;
    }
    const asMethod = new RegExp(`^ {4}(${METHODS}):\\s*$`).exec(line);
    if (asMethod && path) {
      current = { method: asMethod[1].toUpperCase(), path, summary: '', apiKey: false };
      routes.push(current);
      continue;
    }
    if (!current) continue;
    const asSummary = /^ {6}summary:\s*(.*)$/.exec(line);
    if (asSummary) current.summary = asSummary[1].replace(/^"(.*)"$/, '$1');
    if (/^ {6}security:.*\bapiKey\b/.test(line)) current.apiKey = true;
  }
  return routes;
}

/** A path as text names it, cut down to what can be compared: no query, no trailing dot or slash. */
function clean(path) {
  return path.replace(/[.,'"]+$/, '').replace(/\/+$/, '');
}

/**
 * Every `METHOD /path` a text names, such as `POST /v1/videos` or
 * `GET /v1/jobs/{id}`. A query string is left off: the path ends at `?`.
 */
export function namedRoutes(text) {
  const found = [];
  const pattern = /\b(GET|POST|PUT|PATCH|DELETE)\s+(\/[A-Za-z0-9_\-{}.$/[\]]+)/g;
  for (const match of text.matchAll(pattern)) {
    found.push({ method: match[1], path: clean(match[2]), line: lineOf(text, match.index) });
  }
  return found;
}

/**
 * Every API path a text contains anywhere: in a URL
 * (`https://api.godance.ai/v1/jobs/$JOB_ID`), in code
 * (`f"{API}/v1/files/{file_id}"`) or in an example body
 * (`"poll_url": "/v1/jobs/6f1c…"`).
 */
export function namedPaths(text) {
  const found = [];
  const pattern = /\/(?:v1\/[A-Za-z0-9_\-{}.$/[\]']+|healthz\b)/g;
  for (const match of text.matchAll(pattern)) {
    found.push({ path: clean(match[0]), line: lineOf(text, match.index) });
  }
  return found;
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length;
}

/**
 * Whether a path as a text names it is the OpenAPI path `template`. A
 * `{variable}` of the template stands for any one segment, so `/v1/jobs/{id}`
 * is matched by `/v1/jobs/{id}`, `/v1/jobs/$JOB_ID`, `/v1/jobs/{job['id']}`
 * and `/v1/jobs/6f1c0b9e-…`. A literal segment must be the same literal:
 * `/v1/jobs/{id}` is not `/v1/jobs/saved`.
 */
export function isPath(template, named) {
  const want = template.split('/');
  const have = splitSegments(named);
  if (want.length !== have.length) return false;
  return want.every((segment, i) => /^\{.*\}$/.test(segment) || segment === have[i]);
}

/** Splits on `/`, but not inside `{…}`: a placeholder in code may hold a slash or a bracket. */
function splitSegments(path) {
  const segments = [];
  let depth = 0;
  let current = '';
  for (const char of path) {
    if (char === '{') depth++;
    if (char === '}') depth = Math.max(0, depth - 1);
    if (char === '/' && depth === 0) {
      segments.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  segments.push(current);
  return segments;
}

/**
 * What a text names that the API does not have: each `METHOD /path` with no
 * such operation, and each path with no operation under any method.
 */
export function unknownRoutes(text, routes) {
  const unknown = [];
  for (const named of namedRoutes(text)) {
    if (!routes.some((route) => route.method === named.method && isPath(route.path, named.path))) {
      unknown.push(`line ${named.line}: ${named.method} ${named.path}`);
    }
  }
  for (const named of namedPaths(text)) {
    if (!routes.some((route) => isPath(route.path, named.path))) {
      unknown.push(`line ${named.line}: ${named.path}`);
    }
  }
  return [...new Set(unknown)];
}

/** The operations among `routes` that a text never names as `METHOD /path`. */
export function unnamedRoutes(text, routes) {
  const named = namedRoutes(text);
  return routes
    .filter((route) => !named.some((n) => n.method === route.method && isPath(route.path, n.path)))
    .map((route) => `${route.method} ${route.path}`);
}
