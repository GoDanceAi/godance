// Centralized fetch wrapper. Sends the session cookie, parses problem+json on errors,
// and auto-logs-out on 401 by dispatching a custom event the auth context listens for.
//
// The session is an HttpOnly cookie on the API's host, so nothing here can read
// it: the browser attaches it, because every session call says `credentials:
// 'include'`, and the app and API are one site, so SameSite lets it through.

export const API_BASE: string =
  (import.meta.env.VITE_API_URL as string | undefined) || 'http://localhost:8080';

export const UNAUTHORIZED_EVENT = 'godance.unauthorized';

export class ApiError extends Error {
  status: number;
  detail?: string;
  code?: string;             // problem+json `code`, for a client to branch on
  problem?: Record<string, unknown>;   // the whole problem+json body, for its other members (a batch's `row`)
  constructor(status: number, message: string, detail?: string, code?: string, problem?: Record<string, unknown>) {
    super(message);
    this.status = status;
    this.detail = detail;
    this.code = code;
    this.problem = problem;
  }
}

/**
 * The error a failed response stands for: its problem+json detail and code
 * when it has them, "HTTP <status>" otherwise. For a call that cannot go
 * through {@link apiFetch} (a file download); apiFetch reads errors the same way.
 */
export async function apiErrorFrom(res: Response): Promise<ApiError> {
  let detail = `HTTP ${res.status}`;
  let code: string | undefined;
  let problem: Record<string, unknown> | undefined;
  try {
    const body = (await res.json()) as { detail?: string; title?: string; code?: string };
    detail = body.detail || body.title || detail;
    code = body.code;
    if (body !== null && typeof body === 'object') problem = body as Record<string, unknown>;
  } catch {
    /* non-JSON body — keep the default */
  }
  return new ApiError(res.status, detail, undefined, code, problem);
}

type ApiOptions = Omit<RequestInit, 'body'> & {
  json?: unknown;            // shorthand for JSON body
  body?: BodyInit;           // raw body if you need it (e.g. SSE clients)
  auth?: boolean;            // default true; false sends no session and a 401 is not a lapsed one
};

/**
 * For the calls that set or clear the session cookie: the browser ignores
 * Set-Cookie without `credentials: 'include'`, and the header tells the API
 * this client uses the cookie, so it keeps the token out of the response.
 */
export const SESSION_CALL = {
  method: 'POST',
  auth: false,
  credentials: 'include',
  headers: { 'X-Godance-Session': 'cookie' },
} satisfies ApiOptions;

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

  const res = await fetch(`${API_BASE}${path}`, {
    credentials: auth ? 'include' : 'omit',
    ...rest,
    body,
    headers: finalHeaders,
  });

  if (res.status === 401 && auth) {
    // Session rejected; the API has cleared the cookie. Notify the auth context.
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
  }

  if (!res.ok) {
    // Backend uses RFC 7807 problem+json for errors.
    throw await apiErrorFrom(res);
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
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(body),
    signal,
  });

  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
    throw new ApiError(401, 'unauthorized');
  }
  if (!res.ok || !res.body) {
    // Same problem+json reading as apiFetch, so a 402 says why.
    let detail = `HTTP ${res.status}`;
    let code: string | undefined;
    try {
      const problem = (await res.json()) as { detail?: string; title?: string; code?: string };
      detail = problem.detail || problem.title || detail;
      code = problem.code;
    } catch {
      /* non-JSON body — keep the default */
    }
    throw new ApiError(res.status, detail, undefined, code);
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
  role: UserRole;
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
  // Image inputs (round 11). Absent from an API older than that: treat as switched off.
  inputs?: InputsStatus;
}

/**
 * Where image inputs stand for this account. Everything about inputs is
 * shown only while `enabled` is true. `accepted` is true only for the
 * current `terms_version`.
 */
export interface InputsStatus {
  enabled: boolean;
  terms_version: number;
  accepted: boolean;
  accepted_at: string | null;
  allow_people: boolean;   // false: an image that shows a person is refused
}

export type AgeStatus = 'ADULT' | 'CONSENT_PENDING' | 'CONSENT_GRANTED';

// Each role adds to the one before it. Which reaches what is enforced by the API.
export type UserRole = 'USER' | 'SUPPORT' | 'FINANCE' | 'ADMIN';

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

/** Password sign-in. The session is in the cookie, or with 2FA on there is just a challenge. */
export interface SignInResponse {
  twoFactorToken?: string;
  expiresAt: string;
}

/** POST /v1/auth/2fa/enrol. The secret for typing in, and the URI the QR code encodes. */
export interface TwoFactorEnrolment {
  secret: string;
  otpauthUri: string;
}

/** POST /v1/auth/2fa/enable. The recovery codes are shown this once. */
export interface TwoFactorEnabled {
  recoveryCodes: string[];
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
  image_response?: ImageResponse;   // absent from an API without the setting
}

/**
 * How image requests made with a key answer. sync: they wait for the result
 * and answer as OpenAI's API does. async: they return a job to poll.
 */
export type ImageResponse = 'async' | 'sync';

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
  // Image: what each input image of an edit adds, where the provider charges for one
  per_input_image_microcents?: number;
  // Video
  per_second_microcents?: number;
}

/**
 * What an image or video model accepts. The whole block may be absent, which
 * means unknown, not unlimited; so may any field in it. A missing max_n is 1.
 */
export interface ModelCapabilities {
  sizes?: string[];
  default_size?: string;
  durations?: number[];        // video: clip lengths in seconds
  default_seconds?: number;    // video: the length used when a request omits seconds
  max_n?: number;
  inputs?: ModelInputs;        // the images a request may carry (round 11); absent means none
}

/**
 * The images a model takes. An image model: `images`, up to `max`, and a
 * mask when `mask` is true. A video model: a first frame, a last frame, and
 * reference images up to their `max`. A missing part is not taken.
 */
export interface ModelInputs {
  images?: { max: number; mask?: boolean };
  first_frame?: boolean;
  last_frame?: boolean;
  reference_images?: { max: number };
}

/** How a request points at an input: one of the user's files, or an output of one of their jobs. */
export type InputRef = { file_id: string } | { job_output: { job_id: string; index: number } };

/** What an input is to the job that uses it. */
export type InputRole = 'image' | 'mask' | 'first_frame' | 'last_frame' | 'reference_image';

/** POST /v1/files: one stored input image. */
export interface InputFile {
  id: string;
  bytes: number;
  content_type: string;
  width: number;
  height: number;
  contains_person: boolean | null;
}

/** One input a job was given. `url` is a signed link, or null once the file is gone. */
export interface JobInput {
  role: InputRole;
  file_id?: string;
  job_output?: { job_id: string; index: number };
  url: string | null;
}

export interface GodanceModelExtension {
  modality: 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO_TTS' | 'AUDIO_STT' | 'EMBEDDING';
  requires_async: boolean;
  pricing: ModelPricing;
  // The platform fee billed on top of pricing, as the API charges it.
  // Absent on backends that predate it.
  markup_percent?: number;
  display_name?: string;
  description?: string;
  capabilities?: ModelCapabilities;
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

// ===== Generation jobs (/v1/images/generations, /v1/videos/generations, /v1/jobs) =====

export type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';

/** The modalities the Studio generates. */
export type MediaModality = 'IMAGE' | 'VIDEO';

/** POST /v1/estimates: the exact pre-debit a matching generation request would take. */
export interface Estimate {
  model: string;
  modality: MediaModality;
  n: number;
  seconds: number | null;
  estimated_cost_cents: number;
  currency: string;
  input_images?: number;   // echoed when the request named it
}

/** The 202 answer to an async generation request. */
export interface AsyncJobAcknowledged {
  job_id: string;
  status: JobStatus;
  poll_url: string;
}

/** One file a job produced. The link is signed when the job is read. */
export interface JobOutput {
  index: number;
  url: string;
  contentType: string;
  sizeBytes: number;
  revisedPrompt: string | null;
}

/**
 * The request's own parameter names: size, n, style and quality for images;
 * size, seconds and n for video. Empty once the job's files are purged.
 */
export interface JobParams {
  size?: string;
  n?: number;
  style?: string;
  quality?: string;
  seconds?: number;
}

/**
 * GET /v1/jobs/{id}. The fields from prompt down are newer than the rest and
 * optional here, so the app keeps working against an API without them: it
 * falls back to outputUrl when outputs is absent.
 */
export interface JobView {
  id: string;
  status: JobStatus;
  modality: GodanceModelExtension['modality'];
  provider: string;
  model: string;
  estimatedCostCents: number | null;
  actualCostCents: number | null;
  outputUrl: string | null;            // the first output, signed
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  prompt?: string | null;              // null once purged
  params?: JobParams;
  outputs?: JobOutput[];               // empty until DONE, and again once purged
  purged?: boolean;
  mediaExpiresAt?: string | null;      // null: the files are not scheduled for deletion
  saved?: boolean;                     // kept past the deletion date; absent from an API without saving
  channel?: JobChannel | null;         // how it was made; null for a job from before this was recorded
  galleryRecipe?: string | null;       // the gallery recipe it was a run of, by slug
  recipe?: JobRecipe | null;           // the user's recipe it ran; null for a plain prompt, or once that recipe is deleted
  inputs?: JobInput[];                 // the images it was given (round 11); empty for none, and once purged
  batch?: JobBatch | null;             // the batch it is a row of (round 12); null or absent for any other job
}

/** The batch a job belongs to, and which of its rows the job is (0-based). */
export interface JobBatch {
  id: string;
  row: number;
}

/** The user's recipe a job ran: which version, and the value each variable had (null once purged). */
export interface JobRecipe {
  id: string;
  version: number;
  variables: Record<string, string> | null;
}

/**
 * STUDIO: made by a signed-in session. API: made with an API key. BATCH: a
 * row of a recipe batch (round 12). The API decides which.
 */
export type JobChannel = 'STUDIO' | 'API' | 'BATCH';

/** GET /v1/jobs/saved: how many generations the account has saved, and the most it can. */
export interface SavedJobs {
  count: number;
  limit: number;
}

/** One entry of GET /v1/saved-recipes: a gallery recipe the user saved, by its slug. */
export interface SavedRecipe {
  slug: string;
  savedAt: string;
}

// ===== The user's own recipes (/v1/recipes) =====

/** A template variable: its name, and the value it has when none is given. */
export interface RecipeVariable {
  name: string;
  default: string;
}

/** An input slot of a recipe: its name, what the image is to the job, and a hint for whoever fills it. */
export interface RecipeInputSlot {
  slot: string;
  role: InputRole;
  hint: string;
}

/** One version of a recipe. A version never changes once written. */
export interface UserRecipeVersion {
  id: string;
  number: number;
  template: string;
  variables: RecipeVariable[];
  model: string;
  params: Record<string, unknown>;
  created_at: string;
  inputs?: RecipeInputSlot[];   // its input slots (round 11); absent from an API older than that
}

/** A recipe the user keeps, with its latest version and what its attempts cost. */
export interface UserRecipe {
  id: string;
  title: string;
  modality: MediaModality;
  source_gallery_recipe: string | null;   // the gallery recipe it was made from, by slug
  created_at: string;
  updated_at: string;
  latest_version: UserRecipeVersion;
  attempts: number;                       // jobs that ran any of its versions
  spent_cents: number;                    // what the finished ones were charged
}

// ===== Recipe batches (/v1/recipe-batches, round 12) =====
// One version of a recipe run over many rows; each row becomes one job.

export type BatchStatus = 'QUEUED' | 'RUNNING' | 'PAUSED' | 'DONE' | 'CANCELLED';

/** Why a batch stopped starting rows: the balance ran out, or a spending cap would be passed. */
export type BatchPauseReason = 'insufficient_credit' | 'spending_cap';

/** SKIPPED: the batch was cancelled before the row started. */
export type BatchRowStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED';

/**
 * A batch. The fields from `recipe_title` down are newer than the first
 * contract and optional here, so the pages still work against an API
 * without them.
 */
export interface Batch {
  id: string;
  recipe_id: string | null;        // null once the recipe is deleted
  version_id: string | null;
  model: string;
  status: BatchStatus;
  pause_reason: BatchPauseReason | null;
  rows_total: number;
  rows_done: number;
  rows_failed: number;
  estimated_total_cents: number;
  spent_cents: number;
  created_at: string;
  completed_at: string | null;
  recipe_title?: string | null;
  max_concurrency?: number;
  pause_detail?: string | null;    // which cap paused it, in the API's words
  rows_running?: number;           // right in every status: a paused or cancelled batch can have rows finishing
  rows_skipped?: number;
  started_at?: string | null;
}

/** One file a finished row produced. The link is signed when the row is read. */
export interface BatchRowOutput {
  index: number;
  url: string;
  content_type: string;
}

/** One row of a batch. `index` counts from 0; `variables` is null once the row's job is purged. */
export interface BatchRow {
  index: number;
  status: BatchRowStatus;
  variables: Record<string, string> | null;
  job_id: string | null;
  cost_cents: number | null;
  error: string | null;
  outputs?: BatchRowOutput[];      // empty unless the row is DONE and its files are still kept
}

/** POST /v1/recipe-batches/estimate: what the whole batch would cost, against the balance. */
export interface BatchEstimate {
  rows: number;
  total_cents: number;
  balance_cents: number;
  enough: boolean;
  limits?: { max_rows: number; max_concurrency: number };
}

/** One row of a request: values for some of the variables, and an input for each slot of the version. */
export interface BatchRequestRow {
  variables: Record<string, string>;
  inputs?: Record<string, InputRef>;
}

/** The body of POST /v1/recipe-batches, and of its estimate. `params`, when sent, replaces the version's. */
export interface BatchRequest {
  version_id: string;
  model: string;
  params: Record<string, unknown>;
  rows: BatchRequestRow[];
  max_concurrency: number;
}

// ===== /v1/admin/** (admin console — mirror backend admin DTOs) =====

export interface AdminMeResponse {
  email: string;
  role: UserRole;
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
  role: UserRole;
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

/** How many of a group of sign-ups reached each step. Counts only; a group under 5 people is left out. */
export interface AdminFunnelStages {
  signed_up: number;
  first_studio_job: number;
  first_paid_top_up: number;
  first_api_job: number;
  first_batch?: number;   // started a recipe batch (round 12); absent from an API older than that
}

/** Sign-ups of one UTC ISO week, e.g. "2026-W47". */
export interface AdminFunnelCohort extends AdminFunnelStages {
  week: string;
  median_days_to_api_job: number | null;   // null: nobody in the week has made an API job
}

/** Sign-ups by what brought them: gallery, referral, utm:<source>, utm:other or none. */
export interface AdminFunnelSource extends AdminFunnelStages {
  source: string;
}

export interface AdminFunnelRecipe {
  slug: string;
  users: number;
  jobs: number;
  users_with_api_job: number;
}

/** GET /v1/admin/funnel?from=&to= */
export interface AdminFunnel {
  cohorts: AdminFunnelCohort[];
  by_source: AdminFunnelSource[];
  gallery_recipes: AdminFunnelRecipe[];
}
