/**
 * The Godance API, as far as the pipeline needs it: the model catalogue,
 * chat completions, image and video jobs, and downloading an output.
 *
 * The key is handed in once and kept in this closure. It goes into the
 * Authorization header of requests to the API base and nowhere else: not to
 * the host an output is downloaded from, not into an error, not into
 * anything that is saved or printed.
 */

export class ApiError extends Error {
  constructor(message, { status, retryable = false } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status ?? null;
    /**
     * Whether the API certainly did not accept the request: it answered with
     * a 4xx. With no answer, or a 5xx, a job may still have been created.
     */
    this.rejected = typeof status === 'number' && status >= 400 && status < 500;
    this.retryable = retryable;
  }
}

/** A function that removes the key from any text, for errors and logs. */
export const redactor = (key) => (text) => (key ? String(text).split(key).join('[api key]') : String(text));

const realSleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * @param {object} options
 * @param {string} options.base The API origin, e.g. https://api.godance.ai
 * @param {string} [options.key] Needed for everything but the model catalogue.
 * @param {typeof fetch} [options.fetch]
 * @param {(ms: number) => Promise<void>} [options.sleep]
 * @param {number} [options.maxRetries] How often a 429 is retried.
 */
export function createApi({ base, key, fetch = globalThis.fetch, sleep = realSleep, maxRetries = 6 }) {
  const origin = base.replace(/\/+$/, '');
  const redact = redactor(key);

  async function call(method, path, body, { auth = true } = {}) {
    if (auth && !key) throw new ApiError('the API key is not set: export GODANCE_API_KEY');
    const headers = { Accept: 'application/json' };
    if (auth) headers.Authorization = `Bearer ${key}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    for (let attempt = 0; ; attempt++) {
      let response;
      try {
        response = await fetch(`${origin}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
      } catch (error) {
        // Nothing came back, so whether the request arrived is unknown.
        throw new ApiError(redact(`${method} ${path}: no response (${error.message})`));
      }

      // Too many requests: nothing was accepted. Wait as told, or back off, and try again.
      if (response.status === 429 && attempt < maxRetries) {
        const told = Number(response.headers.get('retry-after'));
        await sleep(Number.isFinite(told) && told > 0 ? told * 1000 : Math.min(60_000, 2000 * 2 ** attempt));
        continue;
      }

      const text = await response.text();
      let json = null;
      try {
        json = text === '' ? null : JSON.parse(text);
      } catch {
        // not JSON; reported below when the status is an error
      }
      if (!response.ok) {
        const detail = json?.detail ?? json?.title ?? json?.error?.message ?? text.slice(0, 200);
        throw new ApiError(redact(`${method} ${path}: ${response.status} ${detail}`.trim()), {
          status: response.status,
          retryable: response.status === 429 || response.status >= 500,
        });
      }
      return { status: response.status, json };
    }
  }

  return {
    origin,
    hasKey: Boolean(key),
    redact,

    /** The model catalogue. Public and free: no key is sent. */
    async models() {
      const { json } = await call('GET', '/v1/models', undefined, { auth: false });
      return Array.isArray(json?.data) ? json.data.filter((model) => model.godance?.virtual !== true) : [];
    },

    /** One chat completion. Returns the reply and the token usage it reports. */
    async chat({ model, messages, maxTokens, temperature = 0 }) {
      const { json } = await call('POST', '/v1/chat/completions', { model, messages, max_tokens: maxTokens, temperature, stream: false });
      return { text: json?.choices?.[0]?.message?.content ?? '', usage: json?.usage ?? null };
    },

    /** Start an image job. Returns its id once the API has accepted it. */
    async submitImage(body) {
      const { json } = await call('POST', '/v1/images/generations', body);
      if (!json?.job_id) throw new ApiError('POST /v1/images/generations: accepted, but the response has no job_id');
      return String(json.job_id);
    },

    /** Start a video job. Returns its id once the API has accepted it. */
    async submitVideo(body) {
      const { json } = await call('POST', '/v1/videos/generations', body);
      if (!json?.job_id) throw new ApiError('POST /v1/videos/generations: accepted, but the response has no job_id');
      return String(json.job_id);
    },

    /** One job, as GET /v1/jobs/{id} returns it. */
    async job(id) {
      const { json } = await call('GET', `/v1/jobs/${encodeURIComponent(id)}`);
      return json ?? {};
    },

    /**
     * Fetch an output from its signed link. The link points at storage, not
     * at the API, so the key is not sent with it.
     */
    async download(url) {
      let response;
      try {
        response = await fetch(url);
      } catch (error) {
        throw new ApiError(redact(`download: no response (${error.message})`));
      }
      if (!response.ok) throw new ApiError(`download: ${response.status}`, { status: response.status });
      return {
        bytes: Buffer.from(await response.arrayBuffer()),
        contentType: (response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase(),
      };
    },
  };
}
