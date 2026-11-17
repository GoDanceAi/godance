import { test as base, expect, type Page, type Route } from '@playwright/test';
import type { ApiKeySummary, MeResponse } from '../src/lib/api';
import { API_ORIGIN, APP_HOST } from './api-origin';

// Production's cookie (backend SessionCookies), set and read by the real browser.
export const SESSION_COOKIE = '__Host-godance_session';
const COOKIE_ATTRIBUTES = 'HttpOnly; Secure; SameSite=Strict; Path=/';

// Mirrors src/lib/legacy-session.ts, which can't be imported at runtime here (import.meta.env).
export const LEGACY_TOKEN_KEY = 'godance.token';

export interface ApiCall {
  method: string;
  path: string;
  query: URLSearchParams;
  body: unknown;
  headers: Record<string, string>;
}

export interface Reply {
  status?: number;
  json?: unknown;
  body?: string | Buffer;   // a Buffer for a binary answer (a batch's results.zip)
  contentType?: string;
  setCookie?: string;
}

/** A reply, or a function of the call; a function may also answer the route itself and return nothing. */
type Responder = Reply | ((call: ApiCall, route: Route) => Reply | void | Promise<Reply | void>);

/**
 * RFC 7807 error, the shape the backend sends for every HTTP error; `code` is
 * its extension member, and `extra` any others (a batch's bad `row`).
 */
export function problem(status: number, detail: string, code?: string, extra: Record<string, unknown> = {}): Reply {
  return {
    status,
    contentType: 'application/problem+json',
    json: { type: 'about:blank', title: 'Error', status, detail, ...(code ? { code } : {}), ...extra },
  };
}

/** The API's answer to a sign-in by the app: the cookie, and no token anywhere page script can reach. */
export function sessionStarted(token: string, status = 200): Reply {
  return {
    status,
    json: { accessToken: null, tokenType: 'Cookie', expiresAt: '2030-01-01T00:00:00Z' },
    setCookie: `${SESSION_COOKIE}=${token}; ${COOKIE_ATTRIBUTES}; Max-Age=3600`,
  };
}

/** The API's answer that ends the browser's session: the cookie removed. */
export function sessionCleared(status = 204): Reply {
  return { status, setCookie: `${SESSION_COOKIE}=; ${COOKIE_ATTRIBUTES}; Max-Age=0` };
}

export function makeUser(overrides: Partial<MeResponse> = {}): MeResponse {
  return {
    id: '00000000-0000-4000-8000-000000000001',
    email: 'ada@example.com',
    hasEmail: true,
    pendingEmail: null,
    hasPassword: true,
    fullName: 'Ada Lovelace',
    role: 'USER',
    emailVerified: true,
    creditBalanceCents: 12_34,
    dailyCreditCapCents: null,
    monthlyCreditCapCents: null,
    createdAt: '2026-10-01T00:00:00Z',
    ageStatus: 'ADULT',
    parentEmailMasked: null,
    twoFactorEnabled: false,
    // As the API ships: inputs switched off.
    inputs: { enabled: false, terms_version: 1, accepted: false, accepted_at: null, allow_people: false },
    ...overrides,
  };
}

/** Not a real key: the format only has to look like what the API returns. */
export const FAKE_KEY = 'gd_live_e2e0fake0plaintext0shown0once';

/**
 * One key as GET /v1/keys lists it since round 12: no caps, nothing spent,
 * no expiry. The expiry is under both names, as the API sends it.
 */
export function makeKey(overrides: Partial<ApiKeySummary> = {}): ApiKeySummary {
  const key: ApiKeySummary = {
    id: 'key-1', name: 'production backend', prefix: 'e2e0fake', scopes: null, active: true,
    createdAt: '2026-10-28T12:00:00Z', lastUsedAt: null, expiresAt: null, image_response: 'async',
    daily_cap_cents: null, monthly_cap_cents: null, expires_at: null,
    spent_today_cents: 0, spent_month_cents: 0,
    ...overrides,
  };
  const expiry = overrides.expires_at ?? overrides.expiresAt ?? null;
  return { ...key, expires_at: expiry, expiresAt: expiry };
}

/** A cap as the API takes it: a whole number of cents, at least 1, or null for none. */
function badCap(value: unknown): boolean {
  return value !== null && !(Number.isInteger(value) && (value as number) >= 1);
}

/** An expiry as the API keeps and returns it: in UTC, to the second. Null for none. */
function utcToTheSecond(expiry: unknown): string | null {
  return typeof expiry === 'string' ? new Date(expiry).toISOString().replace(/\.\d{3}Z$/, 'Z') : null;
}

const KEY_SETTINGS = ['image_response', 'daily_cap_cents', 'monthly_cap_cents', 'expires_at'] as const;

/** What is wrong with the settings a create or an update sent, in the API's own words; null when nothing is. */
function settingsProblem(body: Record<string, unknown>): Reply | null {
  if ('image_response' in body && body.image_response !== 'async' && body.image_response !== 'sync') {
    return problem(400, 'image_response must be "async" or "sync"');
  }
  for (const cap of ['daily_cap_cents', 'monthly_cap_cents'] as const) {
    if (cap in body && badCap(body[cap])) {
      return problem(400, `${cap} must be a whole number of cents, at least 1, or null for no cap`);
    }
  }
  // An expiry is an ISO-8601 time with a zone, in the future, or null for none.
  const expiry = body.expires_at;
  if ('expires_at' in body && expiry !== null) {
    if (typeof expiry !== 'string' || Number.isNaN(Date.parse(expiry))) {
      return problem(400, 'expires_at must be an ISO-8601 date and time, or null for no expiry');
    }
    if (Date.parse(expiry) <= Date.now()) return problem(400, 'expires_at must be in the future');
  }
  return null;
}

/**
 * /v1/keys as the API serves it (the round 12 contract), over `keys`, which
 * is changed in place so a test reads what the app did from it. POST and
 * PATCH take `daily_cap_cents`, `monthly_cap_cents` and `expires_at`: a cap
 * is at least 1 cent or null, the expiry is in the future or null. On PATCH
 * a field left out is left as it is, null clears it, and an empty body is a
 * 400. POST answers the new key with its caps and expiry, and its plaintext
 * ({@link FAKE_KEY}) this once; PATCH answers the key as the list has it.
 * The expiry comes back in UTC, to the second, as the API keeps it.
 */
export function serveKeys(api: MockApi, keys: ApiKeySummary[]): MockApi {
  const ONE = /^\/v1\/keys\/([\w-]+)$/;
  const find = (path: string) => keys.find((key) => key.id === ONE.exec(path)?.[1]);
  return api
    .on('GET', '/v1/keys', () => ({ json: keys }))
    .on('POST', '/v1/keys', (call) => {
      const body = call.body as Record<string, unknown>;
      const wrong = settingsProblem(body);
      if (wrong) return wrong;
      const created = makeKey({
        id: `key-${keys.length + 1}`,
        name: String(body.name),
        prefix: `e2e0k${String(keys.length + 1).padStart(3, '0')}`,
        createdAt: '2026-11-15T09:00:00Z',
        image_response: (body.image_response as ApiKeySummary['image_response']) ?? 'async',
        daily_cap_cents: (body.daily_cap_cents as number | null | undefined) ?? null,
        monthly_cap_cents: (body.monthly_cap_cents as number | null | undefined) ?? null,
        expires_at: utcToTheSecond(body.expires_at),
      });
      keys.push(created);
      return {
        status: 201,
        json: {
          id: created.id, name: created.name, prefix: created.prefix, plaintext: FAKE_KEY, createdAt: created.createdAt,
          image_response: created.image_response, daily_cap_cents: created.daily_cap_cents,
          monthly_cap_cents: created.monthly_cap_cents, expires_at: created.expires_at,
        },
      };
    })
    .on('PATCH', ONE, (call) => {
      const at = keys.findIndex((key) => key.id === ONE.exec(call.path)?.[1]);
      if (at === -1) return problem(404, 'api key not found');
      const body = call.body as Record<string, unknown>;
      if (!KEY_SETTINGS.some((name) => name in body)) return problem(400, 'send at least one setting to change');
      const wrong = settingsProblem(body);
      if (wrong) return wrong;
      const changed: Record<string, unknown> = { ...keys[at] };
      for (const name of KEY_SETTINGS) if (name in body) changed[name] = body[name];
      // The expiry is sent under both names.
      changed.expires_at = utcToTheSecond(changed.expires_at);
      changed.expiresAt = changed.expires_at;
      keys[at] = changed as unknown as ApiKeySummary;
      return { json: keys[at] };
    })
    .on('DELETE', ONE, (call) => {
      const key = find(call.path);
      if (!key) return problem(404, 'api key not found');
      keys.splice(keys.indexOf(key), 1);
      return { status: 204 };
    });
}

/**
 * In-browser stand-in for the Godance API. Handlers match on method and
 * pathname; the most recently registered match wins, so a test can override
 * a default. Every call is recorded; a call nothing matches gets a 501 and
 * fails the test at teardown.
 */
export class MockApi {
  readonly calls: ApiCall[] = [];
  readonly unhandled: string[] = [];
  private handlers: { method: string; path: string | RegExp; respond: Responder }[] = [];

  constructor(private readonly page: Page) {
    // The app asks on every load; with no session the API says so.
    this.on('GET', '/v1/auth/me', problem(401, 'Unauthorized'))
      // The dashboard shows the newest generations; an account has none until a test says so.
      .on('GET', '/v1/jobs', { json: [] })
      // Asked once signed in. Referrals ship off, so by default the API says so.
      .on('GET', '/v1/referrals/me', { json: { enabled: false } })
      // Asked once signed in. The community ships off (round 14), so by default the API says so.
      .on('GET', '/v1/profile', problem(404, 'the community is not open', 'community_disabled'))
      // The Studio reads the gallery recipes an account saved; it has none until a test says so.
      .on('GET', '/v1/saved-recipes', { json: [] })
      // A recipe page lists the recipe's batches; an account has none until a test says so.
      .on('GET', '/v1/recipe-batches', { json: [] })
      // Settings lists provider keys. Bring your own key ships with no provider offered.
      .on('GET', '/v1/provider-credentials', { json: [] })
      // Billing reads existing session-only configuration and recent usage.
      .on('GET', '/v1/billing/auto-recharge', { json: {
        enabled: false, threshold_cents: null, topup_cents: null,
        payment_method_on_file: false, last_failure_at: null, last_failure_reason: null,
      } })
      .on('GET', '/v1/usage', { json: [] })
      // Handed in once after a sign-in that follows a signed-out page load (lib/first-touch).
      .on('POST', '/v1/attribution/first-touch', { status: 204 });
  }

  async install(): Promise<void> {
    // Nothing reaches the network but the app server: fonts and any other
    // third-party request are aborted. Registered first, so the API route
    // and any route a test adds take precedence.
    await this.page.route(
      (url) => ![APP_HOST, '127.0.0.1', 'localhost'].includes(url.hostname) && url.origin !== API_ORIGIN,
      (route) => route.abort(),
    );
    await this.page.route(`${API_ORIGIN}/**`, (route) => this.handle(route));
  }

  on(method: string, path: string | RegExp, respond: Responder): this {
    this.handlers.unshift({ method, path, respond });
    return this;
  }

  callsTo(method: string, path: string | RegExp): ApiCall[] {
    return this.calls.filter((c) => c.method === method && matches(path, c.path));
  }

  /** Answer /me (and the admin probe, as a plain user) for `user`, as the API does: only with the cookie. */
  mockUser(user: MeResponse = makeUser()): this {
    return this.on('GET', '/v1/auth/me', (call) =>
        call.headers.cookie?.includes(`${SESSION_COOKIE}=`) ? { json: user } : problem(401, 'Unauthorized'))
      .on('GET', '/v1/admin/me', problem(403, 'Forbidden'));
  }

  /** Start the page signed in: the session cookie is in the browser before the app boots. */
  async signIn(user: MeResponse = makeUser(), token = 'e2e-session-token'): Promise<void> {
    this.mockUser(user);
    await this.page.context().addCookies([{
      name: SESSION_COOKIE,
      value: token,
      // https: the DevTools protocol refuses a Secure cookie set against http,
      // though the browser accepts one from *.localhost.
      url: API_ORIGIN.replace('http:', 'https:'),
      httpOnly: true,
      secure: true,
      sameSite: 'Strict',
    }]);
  }

  /** A browser signed in before the cookie existed: the token is in localStorage until the app moves it. */
  async seedLegacyToken(token: string): Promise<void> {
    await this.page.addInitScript(([key, value]) => {
      if (sessionStorage.getItem('e2e.seeded')) return;
      sessionStorage.setItem('e2e.seeded', '1');
      localStorage.setItem(key, value);
    }, [LEGACY_TOKEN_KEY, token]);
  }

  /** End the session from the API's side, as a version bump or expiry does: the browser still holds the cookie until told. */
  rejectSession(path: string, method = 'GET'): this {
    return this.on(method, path, { ...problem(401, 'Token expired'), setCookie: sessionCleared().setCookie });
  }

  private async handle(route: Route): Promise<void> {
    const req = route.request();
    const url = new URL(req.url());
    let body: unknown = null;
    try {
      body = req.postDataJSON();
    } catch {
      body = req.postData();
    }
    const call: ApiCall = {
      method: req.method(),
      path: url.pathname,
      query: url.searchParams,
      body,
      headers: req.headers(),
    };
    this.calls.push(call);

    const handler = this.handlers.find((h) => h.method === call.method && matches(h.path, call.path));
    if (!handler) {
      this.unhandled.push(`${call.method} ${call.path}`);
      await fulfil(route, problem(501, `no mock for ${call.method} ${call.path}`));
      return;
    }
    const reply = typeof handler.respond === 'function'
      ? await handler.respond(call, route)
      : handler.respond;
    if (reply) await fulfil(route, reply);
  }
}

function matches(pattern: string | RegExp, path: string): boolean {
  return typeof pattern === 'string' ? pattern === path : pattern.test(path);
}

async function fulfil(route: Route, reply: Reply): Promise<void> {
  const hasJson = reply.json !== undefined;
  const origin = route.request().headers().origin ?? '';
  await route.fulfill({
    status: reply.status ?? 200,
    // Cross-origin from the app, with credentials, so as the API answers: the
    // exact origin, never `*`. Playwright answers the CORS preflight itself.
    headers: {
      'access-control-allow-origin': origin,
      'access-control-allow-credentials': 'true',
      ...(reply.setCookie ? { 'set-cookie': reply.setCookie } : {}),
    },
    contentType: reply.contentType ?? (hasJson ? 'application/json' : undefined),
    body: hasJson ? JSON.stringify(reply.json) : reply.body ?? '',
  });
}

export const test = base.extend<{ api: MockApi }>({
  api: async ({ page }, use) => {
    const api = new MockApi(page);
    await api.install();
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await use(api);

    expect(api.unhandled, 'API calls with no mock').toEqual([]);
    expect(pageErrors, 'uncaught errors in the page').toEqual([]);
  },
});

export { expect };

/** The session token in the browser's cookie jar, or null. Page script can't read it: see {@link readableToken}. */
export async function sessionToken(page: Page): Promise<string | null> {
  const cookies = await page.context().cookies(API_ORIGIN);
  return cookies.find((c) => c.name === SESSION_COOKIE)?.value ?? null;
}

/** Everything page script could read a session from: both must stay empty. */
export function readableToken(page: Page): Promise<{ cookie: string; storage: string[] }> {
  return page.evaluate(() => ({
    cookie: document.cookie,
    storage: Object.keys(localStorage).filter((key) => key !== 'gd-theme'),
  }));
}
