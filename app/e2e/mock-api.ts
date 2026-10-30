import { test as base, expect, type Page, type Route } from '@playwright/test';
import type { MeResponse } from '../src/lib/api';
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
  body?: string;
  contentType?: string;
  setCookie?: string;
}

/** A reply, or a function of the call; a function may also answer the route itself and return nothing. */
type Responder = Reply | ((call: ApiCall, route: Route) => Reply | void | Promise<Reply | void>);

/** RFC 7807 error, the shape the backend sends for every HTTP error. */
export function problem(status: number, detail: string): Reply {
  return {
    status,
    contentType: 'application/problem+json',
    json: { type: 'about:blank', title: 'Error', status, detail },
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
    ...overrides,
  };
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
    this.on('GET', '/v1/auth/me', problem(401, 'Unauthorized'));
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
