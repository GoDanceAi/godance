// Centralized fetch wrapper. Adds Authorization, parses problem+json on errors,
// and auto-logs-out on 401 by dispatching a custom event the auth context listens for.

export const API_BASE: string =
  (import.meta.env.VITE_API_URL as string | undefined) || 'http://localhost:8080';

export const TOKEN_KEY = 'godance.token';
export const UNAUTHORIZED_EVENT = 'godance.unauthorized';

export class ApiError extends Error {
  status: number;
  detail?: string;
  code?: string;             // problem+json `code`, for a client to branch on
  constructor(status: number, message: string, detail?: string, code?: string) {
    super(message);
    this.status = status;
    this.detail = detail;
    this.code = code;
  }
}

type ApiOptions = Omit<RequestInit, 'body'> & {
  json?: unknown;            // shorthand for JSON body
  body?: BodyInit;           // raw body if you need it (e.g. SSE clients)
  auth?: boolean;            // default true; set false on /v1/auth/{signup,login}
};

export async function apiFetch<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  const { json, auth = true, headers, ...rest } = opts;
  const finalHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...(headers as Record<string, string> | undefined),
  };

  let body = opts.body;
  if (json !== undefined) {
    body = JSON.stringify(json);
    finalHeaders['Content-Type'] = 'application/json';
  }

  if (auth) {
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) finalHeaders.Authorization = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}${path}`, { ...rest, body, headers: finalHeaders });

  if (res.status === 401 && auth) {
    // Token rejected; clear it and notify the auth context.
    localStorage.removeItem(TOKEN_KEY);
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
  }

  if (!res.ok) {
    // Backend uses RFC 7807 problem+json for errors.
    let detail = `HTTP ${res.status}`;
    let code: string | undefined;
    try {
      const body = (await res.json()) as { detail?: string; title?: string; code?: string };
      detail = body.detail || body.title || detail;
      code = body.code;
    } catch {
      /* non-JSON body — keep the default */
    }
    throw new ApiError(res.status, detail, undefined, code);
  }

  if (res.status === 204) return undefined as T;

  const contentType = res.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    return (await res.json()) as T;
  }
  return (await res.text()) as T;
}

/** Streamed POST for /v1/chat/completions. Yields parsed chunk objects from SSE. */
export async function* streamChat(path: string, body: unknown, signal?: AbortSignal):
    AsyncGenerator<ChatChunk> {
  const token = localStorage.getItem(TOKEN_KEY);
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal,
  });

  if (res.status === 401) {
    localStorage.removeItem(TOKEN_KEY);
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
    throw new ApiError(401, 'unauthorized');
  }
  if (!res.ok || !res.body) {
    // Same problem+json reading as apiFetch, so a 402 says why.
    let detail = `HTTP ${res.status}`;
    try {
      const problem = (await res.json()) as { detail?: string; title?: string };
      detail = problem.detail || problem.title || detail;
    } catch {
      /* non-JSON body — keep the default */
    }
    throw new ApiError(res.status, detail);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // SSE frames are separated by \n\n; each frame may have multiple lines.
    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      for (const line of frame.split('\n')) {
        if (!line.startsWith('data:')) continue;
        const payload = line.slice(5).trim();
        if (payload === '[DONE]') return;
        try {
          yield JSON.parse(payload) as ChatChunk;
        } catch {
          /* skip non-JSON frames */
        }
      }
    }
  }
}

// ===== Shared response shapes (mirror backend DTOs) =====

export interface MeResponse {
  id: string;
  email: string;
  hasEmail: boolean;         // false: `email` is a placeholder (X sign-in shares no address)
  pendingEmail: string | null; // address being added or changed to, until verified
  hasPassword: boolean;      // true: changing the email asks for the current password
  fullName: string;
  role: 'USER' | 'ADMIN';
  emailVerified: boolean;
  creditBalanceCents: number;
  dailyCreditCapCents: number | null;
  monthlyCreditCapCents: number | null;
  createdAt: string;
  // CONSENT_PENDING: a child's account waiting for a parent's approval. Its
  // session reaches /v1/auth/me and the consent resend only.
  ageStatus: AgeStatus;
  parentEmailMasked: string | null; // where the consent request went, masked; null for adults
  twoFactorEnabled: boolean; // true: signing in needs an authenticator code
}

export type AgeStatus = 'ADULT' | 'CONSENT_PENDING' | 'CONSENT_GRANTED';

/** GET /v1/auth/parental-consent: what the parent's consent page shows. */
export interface ParentalConsentView {
  childFirstName: string;
  childEmailMasked: string;
  minTopupCents: number;
  maxTopupCents: number;
  currency: string;
  expiresAt: string;
  notice: ParentalNotice;
}

/** The direct notice to the parent; the same text the email carries. */
export interface ParentalNotice {
  collected: string[];
  notStored: string;
  sharing: string;
  noSale: string;
  parentRights: string;
  contactEmail: string;
  privacyPolicyUrl: string;
}

export interface TopUpResponse {
  sessionId: string;
  checkoutUrl: string;
  amountCents: number;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
}

/** Password sign-in and reset. With 2FA on there is no session yet, just a challenge. */
export type SignInResponse = AuthResponse | { twoFactorToken: string; expiresAt: string };

/** POST /v1/auth/2fa/enrol. The secret for typing in, and the URI the QR code encodes. */
export interface TwoFactorEnrolment {
  secret: string;
  otpauthUri: string;
}

/** POST /v1/auth/2fa/enable. The recovery codes are shown this once. */
export interface TwoFactorEnabled {
  recoveryCodes: string[];
  session: AuthResponse;
}

export interface TwoFactorStatus {
  enabled: boolean;
  recoveryCodesRemaining: number;
}

export interface ApiKeySummary {
  id: string;
  name: string;
  prefix: string;
  scopes: string[] | null;
  active: boolean;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
}

export interface ChatChunk {
  id: string;
  object: 'chat.completion.chunk';
  created: number;
  model: string;
  choices: {
    index: number;
    delta: { role?: string; content?: string };
    finish_reason: string | null;
  }[];
  usage: { prompt_tokens: number; completion_tokens: number; total_tokens: number } | null;
}

// ===== /v1/models (OpenAI-shaped + Godance pricing extension) =====

export interface ModelPricing {
  // Text/audio/embedding models
  input_microcents_per_1k?: number;
  output_microcents_per_1k?: number;
  // Image
  per_image_microcents?: number;
  // Video
  per_second_microcents?: number;
}

export interface GodanceModelExtension {
  modality: 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO_TTS' | 'AUDIO_STT' | 'EMBEDDING';
  requires_async: boolean;
  pricing: ModelPricing;
}

// Routing presets (godance-fast/auto/quality). Each request resolves to a
// concrete model and is billed at that model's price, so there is no pricing.
export interface GodanceVirtualModelExtension {
  virtual: true;
  modality: GodanceModelExtension['modality'];
}

export interface ModelEntry {
  id: string;
  object: 'model';
  owned_by: string;
  godance: GodanceModelExtension;
}

export interface VirtualModelEntry {
  id: string;
  object: 'model';
  owned_by: string;
  godance: GodanceVirtualModelExtension;
}

export interface ModelListResponse {
  object: 'list';
  data: (ModelEntry | VirtualModelEntry)[];
}

export function isVirtualModel(m: ModelEntry | VirtualModelEntry): m is VirtualModelEntry {
  return 'virtual' in m.godance && m.godance.virtual === true;
}

// ===== /v1/admin/** (admin console — mirror backend admin DTOs) =====

export interface AdminMeResponse {
  email: string;
}

export interface AdminProposal {
  id: number;
  type: 'PRICE_CHANGE' | 'NEW_MODEL' | string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'ANOMALY';
  modelName: string;
  provider: string | null;
  modality: string | null;
  payload: Record<string, unknown>;
  source: string | null;
  sourceUrl: string | null;
  detectedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
}

export interface AdminProposalListResponse {
  proposals: AdminProposal[];
}

export interface AdminCatalogModel {
  id: number;
  modelName: string;
  provider: string | null;
  modality: string | null;
  status: 'ACTIVE' | 'STAGED' | 'DISCOVERED' | 'DEPRECATED';
  enabled: boolean;
  inputMicrocentsPer1k: number;
  outputMicrocentsPer1k: number;
  perImageMicrocents: number;
  perSecondMicrocents: number;
  markupOverride: number | null;
  effectiveMarkupPercent: number;
  requiresAsync: boolean;
  costSource: string | null;
  lastVerifiedAt: string | null;
  updatedAt: string;
}

export interface AdminCatalogListResponse {
  models: AdminCatalogModel[];
}

export interface AdminPriceHistoryEntry {
  id: number;
  field: string | null;
  oldValue: number | null;
  newValue: number | null;
  source: string | null;
  sourceUrl: string | null;
  observedAt: string;
}

export interface AdminPriceHistoryResponse {
  history: AdminPriceHistoryEntry[];
}

export interface AdminUserView {
  id: string;
  email: string;
  fullName: string;
  role: 'USER' | 'ADMIN';
  active: boolean;
  creditBalanceCents: number;
  dailyCreditCapCents: number | null;
  monthlyCreditCapCents: number | null;
  apiKeyCount: number;
  createdAt: string;
}

export interface AdminUserSearchResponse {
  users: AdminUserView[];
}

export interface AdminLedgerEntry {
  id: string;
  deltaCents: number;
  source: string;
  sourceRef: string | null;
  note: string | null;
  occurredAt: string;
}

export interface AdminLedgerPage {
  entries: AdminLedgerEntry[];
  page: number;
  totalPages: number;
  totalEntries: number;
}

export interface AdminAdjustResult {
  user: AdminUserView;
  entry: AdminLedgerEntry;
}

export interface AdminDailyPoint {
  date: string;
  revenueCents: number;
  costCents: number;
  requests: number;
}

export interface AdminTopModel {
  modelName: string;
  revenueCents: number;
  costCents: number;
  requests: number;
}

export interface AdminTopUser {
  userId: string;
  email: string;
  revenueCents: number;
  requests: number;
}

export interface AdminUsageSummary {
  from: string;
  to: string;
  days: AdminDailyPoint[];
  topModels: AdminTopModel[];
  topUsers: AdminTopUser[];
}
