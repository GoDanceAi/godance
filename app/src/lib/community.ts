import { apiFetch, ApiError, type InputRole, type MediaModality, type RecipeInputSlot } from './api';
import { GALLERY_ORIGIN } from './gallery';

// The community (round 14): a user's public profile, publishing one of their
// recipes, following authors, reporting, and the moderators' queue. The API
// decides everything; this module mirrors its routes, shapes and rules.
// Whether the community is open is said once for the whole app, by
// lib/community-context.tsx: while the API answers `community_disabled`,
// nothing about it is shown anywhere.

// ===== Shapes (the API's, snake_case) =====

/** Why an account may not publish, as GET /v1/profile names it. `no_profile`: it may, once it has a profile. */
export type CannotPublish = 'email_not_verified' | 'not_adult' | 'no_profile';

/** GET and PUT /v1/profile: the caller's own profile, as they set it. Its fields are null before one is set. */
export interface OwnProfile {
  handle: string | null;
  display_name: string | null;
  link: string | null;
  approved: boolean;                  // a moderator approved it: the handle is fixed
  change_awaiting_review: boolean;    // a new display name or link waits; the public page shows the approved ones
  review_reason: string | null;       // why a moderator refused the last change
  followers: number;
  cannot_publish: CannotPublish | null;
  licences: string[];                 // what a recipe may be published under
}

export type SubmissionStatus = 'PENDING' | 'PUBLIC' | 'REJECTED' | 'REMOVED';

/** A recipe's latest submission, as its author sees it. `reason` is a moderator's; null when the author unpublished it. */
export interface PublishSubmission {
  public_id: string;
  status: SubmissionStatus;
  version: number;
  licence: string;
  tags: string[];
  job_ids: string[];
  reason: string | null;
  submitted_at: string;
  reviewed_at: string | null;
  published_at: string | null;
}

export interface PublishRequest {
  version: number;
  licence: string;
  job_ids: string[];
  tags?: string[];
}

/** A published recipe as the public routes show it: exactly these fields. */
export interface PublicRecipe {
  public_id: string;
  title: string;
  modality: 'image' | 'video';
  template: string;
  variables: { name: string; default: string }[];
  inputs: { slot: string; role: string; hint: string | null }[];
  model: string;
  params: Record<string, unknown>;
  examples: { url: string; content_type: string }[];
  made_from: { kind: 'gallery' | 'published'; id: string } | null;
  runs: number;      // finished runs by others; the author's own are not counted
  runners: number;   // the accounts that ran it, its author excluded
  licence: string;
  author: { handle: string; followers: number };
  tags: string[];
  published_at: string;
}

export interface PublicProfile {
  handle: string;
  display_name: string | null;
  link: string | null;
  followers: number;
  recipes: PublicRecipe[];
}

export type ReportKind = 'published_recipe' | 'profile';

// ===== Moderation (staff only) =====

export interface ReviewExample {
  position: number;
  url: string | null;          // a signed link, or null once the source's files are gone
  content_type: string;
  source_job_id: string | null;
  copied: boolean;
}

export interface ReviewAuthor {
  user_id: string;
  email: string | null;
  handle: string | null;
  display_name: string | null;
  link: string | null;
  approved_display_name: string | null;
  approved_link: string | null;
  approved_at: string | null;
  followers: number;
}

export interface PriorDecision {
  public_id: string;
  title: string;
  status: SubmissionStatus;
  reason: string | null;
  reviewed_at: string | null;
}

export interface ReviewReport {
  id: string;
  kind: ReportKind;
  target: string | null;       // the recipe's public id, or the profile's handle
  reason: string;
  reporter_id: string;
  created_at: string;
}

export interface ReviewRecipe {
  public_id: string;
  status: SubmissionStatus;
  title: string;
  modality: 'image' | 'video';
  version: number;
  template: string;
  variables: { name: string; default: string }[];
  inputs: { slot: string; role: string; hint: string | null }[];
  model: string;
  params: Record<string, unknown>;
  licence: string;
  tags: string[];
  made_from: { kind: 'gallery' | 'published'; id: string } | null;
  examples: ReviewExample[];
  reason: string | null;
  submitted_at: string;
  published_at: string | null;
  reviewed_at: string | null;
}

/** pending: a new submission. reported: a public recipe with open reports. profile: a profile change or a reported profile. */
export type QueueReason = 'pending' | 'reported' | 'profile';

export interface QueueItem {
  why: QueueReason;
  recipe: ReviewRecipe | null;   // null for a profile item
  author: ReviewAuthor;
  prior_decisions: PriorDecision[];
  reports: ReviewReport[];
}

// ===== The rules, as the API checks them =====

export const HANDLE_MIN = 3;
export const HANDLE_MAX = 30;
export const DISPLAY_NAME_MAX = 60;
export const LINK_MAX = 300;
export const REASON_MAX = 1000;
export const EXAMPLES_MAX = 4;
export const TAGS_MAX = 5;

const HANDLE = /^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$/;
const TAG = /^[a-z0-9][a-z0-9-]{0,23}$/;
const PUBLIC_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;

/** A handle's shape. Whether it is free or reserved only the API can say. */
export function isHandle(value: string): boolean {
  return HANDLE.test(value) && !value.includes('--');
}

/** A published recipe's public id. Anything else is never sent to the API. */
export function isPublicId(value: string): boolean {
  return PUBLIC_ID.test(value);
}

/** What is wrong with a handle, in the words the rules use; null when its shape is right. */
export function handleProblem(handle: string): string | null {
  if (handle === '') return 'Choose a handle.';
  if (handle.length < HANDLE_MIN || handle.length > HANDLE_MAX) return `A handle has ${HANDLE_MIN} to ${HANDLE_MAX} characters.`;
  if (!/^[a-z0-9-]+$/.test(handle)) return 'Use lowercase letters, digits and hyphens only.';
  if (!/^[a-z0-9]/.test(handle) || !/[a-z0-9]$/.test(handle)) return 'Start and end with a letter or a digit.';
  if (handle.includes('--')) return 'Use no two hyphens in a row.';
  return null;
}

export function displayNameProblem(name: string): string | null {
  const trimmed = name.trim();
  if (trimmed === '') return 'Choose a display name.';
  if ([...trimmed].length > DISPLAY_NAME_MAX) return `A display name has at most ${DISPLAY_NAME_MAX} characters.`;
  return null;
}

/** An empty link is fine: it is optional. */
export function linkProblem(link: string): string | null {
  const trimmed = link.trim();
  if (trimmed === '') return null;
  if (trimmed.length > LINK_MAX) return `A link has at most ${LINK_MAX} characters.`;
  try {
    const url = new URL(trimmed);
    if (url.protocol === 'https:' && url.hostname !== '' && url.username === '' && url.password === '') return null;
  } catch {
    // falls through
  }
  return 'A link is an https:// address.';
}

/** Tags typed as text, separated by commas or spaces: the tags, or what is wrong with them. */
export function parseTags(text: string): { tags: string[]; problem: string | null } {
  const tags = [...new Set(text.toLowerCase().split(/[\s,]+/).filter(Boolean))];
  const bad = tags.find((tag) => !TAG.test(tag) || tag.includes('--'));
  if (bad) return { tags, problem: `“${bad}” can't be a tag: use up to 24 lowercase letters, digits and hyphens.` };
  if (tags.length > TAGS_MAX) return { tags, problem: `A recipe has at most ${TAGS_MAX} tags.` };
  return { tags, problem: null };
}

/** What each licence lets others do, in a line. A licence not listed here is shown by its name alone. */
export const LICENCE_TERMS: Record<string, string> = {
  'CC-BY-4.0': 'Anyone may reuse the prompt and the examples, crediting you.',
  'CC0-1.0': 'Anyone may reuse the prompt and the examples, with no conditions.',
};

// ===== Calls =====

/** The API's problem code, when it sent one. */
export function codeOf(err: unknown): string | null {
  return err instanceof ApiError ? err.code ?? null : null;
}

/** What the API said, when it gave a sentence of its own; otherwise ours. */
export function refusal(err: unknown, fallback: string): string {
  return err instanceof ApiError && err.status >= 400 && err.status < 500 && !err.message.startsWith('HTTP ')
    ? err.message
    : fallback;
}

export const isCommunityDisabled = (err: unknown) => codeOf(err) === 'community_disabled';

export function getProfile(signal?: AbortSignal): Promise<OwnProfile> {
  return apiFetch<OwnProfile>('/v1/profile', { signal });
}

export function putProfile(body: { handle: string; display_name: string; link: string | null }): Promise<OwnProfile> {
  return apiFetch<OwnProfile>('/v1/profile', { method: 'PUT', json: body });
}

/** The recipe's latest submission, or null when it was never submitted. */
export async function getSubmission(recipeId: string): Promise<PublishSubmission | null> {
  try {
    return await apiFetch<PublishSubmission>(`/v1/recipes/${encodeURIComponent(recipeId)}/publish`);
  } catch (err) {
    if (codeOf(err) === 'not_published') return null;
    throw err;
  }
}

export function publishRecipe(recipeId: string, body: PublishRequest): Promise<PublishSubmission> {
  return apiFetch<PublishSubmission>(`/v1/recipes/${encodeURIComponent(recipeId)}/publish`, { method: 'POST', json: body });
}

export function unpublishRecipe(recipeId: string): Promise<void> {
  return apiFetch<void>(`/v1/recipes/${encodeURIComponent(recipeId)}/publish`, { method: 'DELETE' });
}

/** A public read: no session goes with it. */
export function getPublicRecipe(publicId: string): Promise<PublicRecipe> {
  return apiFetch<PublicRecipe>(`/v1/public/recipes/${encodeURIComponent(publicId)}`, { auth: false });
}

const publishedLoads = new Map<string, Promise<PublicRecipe>>();

/**
 * A published recipe for the Studio, kept for this page load once read, so
 * moving it to the Studio page for its kind doesn't read it again. A failure
 * is not kept.
 */
export function loadPublishedRecipe(publicId: string): Promise<PublicRecipe> {
  let load = publishedLoads.get(publicId);
  if (!load) {
    load = getPublicRecipe(publicId);
    publishedLoads.set(publicId, load);
    load.catch(() => publishedLoads.delete(publicId));
  }
  return load;
}

export function getPublicProfile(handle: string): Promise<PublicProfile> {
  return apiFetch<PublicProfile>(`/v1/public/profiles/${encodeURIComponent(handle)}`, { auth: false });
}

export function listFollowing(signal?: AbortSignal): Promise<string[]> {
  return apiFetch<string[]>('/v1/follows', { signal });
}

export function follow(handle: string): Promise<void> {
  return apiFetch<void>(`/v1/follows/${encodeURIComponent(handle)}`, { method: 'POST' });
}

export function unfollow(handle: string): Promise<void> {
  return apiFetch<void>(`/v1/follows/${encodeURIComponent(handle)}`, { method: 'DELETE' });
}

/** The newest public recipes of the authors the caller follows; `before` is the last public id of the page before. */
export function getFeed(query: { limit: number; before?: string }, signal?: AbortSignal): Promise<PublicRecipe[]> {
  const params = new URLSearchParams({ limit: String(query.limit) });
  if (query.before) params.set('before', query.before);
  return apiFetch<PublicRecipe[]>(`/v1/follows/feed?${params}`, { signal });
}

export function report(body: { kind: ReportKind; id: string; reason: string }): Promise<void> {
  return apiFetch<void>('/v1/reports', { method: 'POST', json: body });
}

export const moderation = {
  queue: () => apiFetch<QueueItem[]>('/v1/admin/community/queue'),
  reports: () => apiFetch<ReviewReport[]>('/v1/admin/community/reports'),
  approve: (publicId: string) =>
    apiFetch<ReviewRecipe>(`/v1/admin/community/recipes/${encodeURIComponent(publicId)}/approve`, { method: 'POST' }),
  reject: (publicId: string, reason: string) =>
    apiFetch<ReviewRecipe>(`/v1/admin/community/recipes/${encodeURIComponent(publicId)}/reject`, { method: 'POST', json: { reason } }),
  remove: (publicId: string, reason: string) =>
    apiFetch<ReviewRecipe>(`/v1/admin/community/recipes/${encodeURIComponent(publicId)}/remove`, { method: 'POST', json: { reason } }),
  approveProfile: (handle: string) =>
    apiFetch<QueueItem>(`/v1/admin/community/profiles/${encodeURIComponent(handle)}/approve`, { method: 'POST' }),
  removeProfile: (handle: string, reason: string) =>
    apiFetch<QueueItem>(`/v1/admin/community/profiles/${encodeURIComponent(handle)}/remove`, { method: 'POST', json: { reason } }),
};

// ===== Where things are =====

/** A recipe's public page, on the marketing site, which rebuilds daily. */
export const publicRecipeUrl = (publicId: string) => `${GALLERY_ORIGIN}/community/${encodeURIComponent(publicId)}`;
/** An author's public page, on the marketing site. */
export const publicProfileUrl = (handle: string) => `${GALLERY_ORIGIN}/u/${encodeURIComponent(handle)}`;

export const toMediaModality = (modality: PublicRecipe['modality']): MediaModality => (modality === 'video' ? 'VIDEO' : 'IMAGE');

/** The Studio with a published recipe loaded: nothing runs until Generate. */
export const runPublishedLink = (recipe: Pick<PublicRecipe, 'public_id' | 'modality'>) =>
  `/create/${recipe.modality}?published=${encodeURIComponent(recipe.public_id)}`;

export const reportLink = (kind: ReportKind, id: string) => `/report?kind=${kind}&id=${encodeURIComponent(id)}`;

const ROLES: InputRole[] = ['image', 'mask', 'first_frame', 'last_frame', 'reference_image'];
export const asInputRole = (role: string): InputRole | null => (ROLES.includes(role as InputRole) ? (role as InputRole) : null);

/** A published recipe's input slots, as the Studio's recipe slots are: a role it knows, and a hint. */
export function publishedSlots(recipe: Pick<PublicRecipe, 'inputs'>): RecipeInputSlot[] {
  return recipe.inputs.flatMap((slot) => {
    const role = asInputRole(slot.role);
    return role ? [{ slot: slot.slot, role, hint: slot.hint ?? '' }] : [];
  });
}

/** "Run 12 times by 5 people". Its author's own runs are not counted. */
export function runsText(recipe: Pick<PublicRecipe, 'runs' | 'runners'>): string {
  if (recipe.runs === 0) return 'Not run by anyone else yet';
  return `Run ${recipe.runs.toLocaleString('en-US')} ${recipe.runs === 1 ? 'time' : 'times'} by `
    + `${recipe.runners.toLocaleString('en-US')} ${recipe.runners === 1 ? 'person' : 'people'}`;
}

export const followersText = (n: number) => `${n.toLocaleString('en-US')} ${n === 1 ? 'follower' : 'followers'}`;
