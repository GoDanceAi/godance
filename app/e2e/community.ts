import type {
  OwnProfile,
  PublicProfile,
  PublicRecipe,
  PublishSubmission,
  QueueItem,
  ReviewRecipe,
  ReviewReport,
} from '../src/lib/community';
import { mediaUrl } from './generation';
import { problem, type MockApi, type Reply } from './mock-api';

// The community (round 14), as brief 01 built its routes: the caller's
// profile, publishing, public reads, follows, reports and moderation. Each
// serve* helper answers over state a test can read back. Handles, ids and
// hosts are made up.

/** The signed-in user's handle, once they have one. */
export const HANDLE = 'ada-lights';
/** Another author, whose recipes the user runs and follows. */
export const AUTHOR = 'mina-teapots';

/** GET /v1/profile for an adult, verified account with no profile yet: it may publish once it has one. */
export function makeProfile(overrides: Partial<OwnProfile> = {}): OwnProfile {
  return {
    handle: null,
    display_name: null,
    link: null,
    approved: false,
    change_awaiting_review: false,
    review_reason: null,
    followers: 0,
    cannot_publish: 'no_profile',
    licences: ['CC-BY-4.0', 'CC0-1.0'],
    ...overrides,
  };
}

/** The same account with its profile set: it may publish. */
export function profileSet(overrides: Partial<OwnProfile> = {}): OwnProfile {
  return makeProfile({ handle: HANDLE, display_name: 'Ada', link: 'https://example.test/ada', cannot_publish: null, ...overrides });
}

/** A public recipe by AUTHOR: two variables, on gpt-image-1.5 of the e2e catalog. */
export function makePublicRecipe(overrides: Partial<PublicRecipe> = {}): PublicRecipe {
  return {
    public_id: 'k3v9x2m7q1aa',
    title: 'Teapot still life',
    modality: 'image',
    template: 'A {{thing}} on a wooden table, {{light}} light',
    variables: [{ name: 'thing', default: 'teapot' }, { name: 'light', default: 'soft window' }],
    inputs: [],
    model: 'gpt-image-1.5',
    params: { size: '1536x1024', n: 2 },
    examples: [{ url: mediaUrl('teapot-1.png'), content_type: 'image/png' }],
    made_from: null,
    runs: 12,
    runners: 5,
    licence: 'CC-BY-4.0',
    author: { handle: AUTHOR, followers: 3 },
    tags: ['still-life'],
    published_at: '2026-11-15T10:00:00Z',
    ...overrides,
  };
}

export function makeSubmission(overrides: Partial<PublishSubmission> = {}): PublishSubmission {
  return {
    public_id: 'n3wpub000001',
    status: 'PENDING',
    version: 3,
    licence: 'CC-BY-4.0',
    tags: ['mugs'],
    job_ids: [],
    reason: null,
    submitted_at: '2026-11-15T09:00:00Z',
    reviewed_at: null,
    published_at: null,
    ...overrides,
  };
}

/**
 * GET and PUT /v1/profile as the API answers them, over the profile the
 * account has. `taken` and `reserved` are handles the API refuses.
 */
export function serveProfile(
  api: MockApi,
  profile: OwnProfile,
  rules: { taken?: string[]; reserved?: string[] } = {},
): { current: () => OwnProfile } {
  let current = profile;
  api.on('GET', '/v1/profile', () => ({ json: current }))
    .on('PUT', '/v1/profile', (call) => {
      const body = call.body as { handle: string; display_name: string; link: string | null };
      if (current.cannot_publish === 'not_adult' || current.cannot_publish === 'email_not_verified') {
        return problem(403, 'only adult accounts can have a public profile or publish recipes', 'community_not_allowed',
          { reason: current.cannot_publish });
      }
      if (rules.reserved?.includes(body.handle)) return problem(400, 'that handle is reserved', 'invalid_profile', { reason: 'reserved' });
      if (rules.taken?.includes(body.handle)) return problem(409, 'that handle belongs to another account', 'handle_taken');
      if (current.approved && body.handle !== current.handle) {
        return problem(409, 'your handle is fixed once a moderator has approved your profile', 'handle_locked');
      }
      const changed = body.display_name !== current.display_name || body.link !== current.link;
      current = {
        ...current,
        handle: body.handle,
        display_name: body.display_name,
        link: body.link,
        cannot_publish: null,
        change_awaiting_review: current.approved && (current.change_awaiting_review || changed),
        review_reason: changed ? null : current.review_reason,
      };
      return { json: current };
    });
  return { current: () => current };
}

/**
 * GET, POST and DELETE /v1/recipes/{id}/publish over the recipe's latest
 * submission. POST answers `refuse` when a test gives one; a new submission
 * is PENDING. DELETE marks a pending or public one REMOVED with no reason.
 */
export function serveSubmission(
  api: MockApi,
  recipeId: string,
  initial: PublishSubmission | null,
  refuse?: Reply,
): { current: () => PublishSubmission | null } {
  let current = initial;
  const path = `/v1/recipes/${recipeId}/publish`;
  const active = () => current?.status === 'PENDING' || current?.status === 'PUBLIC';
  api.on('GET', path, () => (current ? { json: current } : problem(404, 'this recipe is not published', 'not_published')))
    .on('POST', path, (call) => {
      if (refuse) return refuse;
      if (active()) return problem(409, 'this recipe is already published or waiting for review; unpublish it first', 'already_published');
      const body = call.body as { version: number; licence: string; job_ids: string[]; tags?: string[] };
      current = makeSubmission({
        public_id: 'n3wpub000002', status: 'PENDING', version: body.version, licence: body.licence,
        tags: body.tags ?? [], job_ids: body.job_ids, submitted_at: '2026-11-16T09:30:00Z',
      });
      return { status: 202, json: current };
    })
    .on('DELETE', path, () => {
      if (!active() || !current) return problem(404, 'this recipe is not published', 'not_published');
      current = { ...current, status: 'REMOVED', reason: null };
      return { status: 204 };
    });
  return { current: () => current };
}

/** GET /v1/public/recipes/{id} for each recipe; any other id is not public. */
export function servePublicRecipes(api: MockApi, recipes: PublicRecipe[]): MockApi {
  return api.on('GET', /^\/v1\/public\/recipes\/[^/]+$/, (call) => {
    const id = decodeURIComponent(call.path.split('/').pop() ?? '');
    const recipe = recipes.find((each) => each.public_id === id);
    return recipe ? { json: recipe } : problem(404, 'recipe not found', 'not_found');
  });
}

/** GET /v1/public/profiles/{handle} for each profile; any other handle has none. */
export function servePublicProfiles(api: MockApi, profiles: PublicProfile[]): MockApi {
  return api.on('GET', /^\/v1\/public\/profiles\/[^/]+$/, (call) => {
    const handle = decodeURIComponent(call.path.split('/').pop() ?? '');
    const profile = profiles.find((each) => each.handle === handle);
    return profile ? { json: profile } : problem(404, 'profile not found', 'not_found');
  });
}

/**
 * Follows over `following` (changed in place, most recent first): GET
 * /v1/follows, POST and DELETE /v1/follows/{handle}, and the feed over
 * `feed`, paged by limit and before. Only `public` handles can be followed;
 * HANDLE is the caller's own.
 */
export function serveFollows(
  api: MockApi,
  following: string[],
  feed: PublicRecipe[] = [],
  publicHandles: string[] = [AUTHOR, 'bo-paper', 'cy-ink'],
): MockApi {
  const ONE = /^\/v1\/follows\/([^/]+)$/;
  const handleOf = (path: string) => decodeURIComponent(ONE.exec(path)?.[1] ?? '');
  return api
    .on('GET', '/v1/follows', () => ({ json: following }))
    .on('POST', ONE, (call) => {
      const handle = handleOf(call.path);
      if (handle === HANDLE) return problem(400, 'you cannot follow yourself', 'follow_self');
      if (!publicHandles.includes(handle)) return problem(404, 'profile not found', 'not_found');
      if (!following.includes(handle)) following.unshift(handle);
      return { status: 204 };
    })
    .on('DELETE', ONE, (call) => {
      const at = following.indexOf(handleOf(call.path));
      if (at !== -1) following.splice(at, 1);
      return { status: 204 };
    })
    .on('GET', '/v1/follows/feed', (call) => {
      let list = feed.filter((recipe) => following.includes(recipe.author.handle));
      const before = call.query.get('before');
      if (before) {
        const at = list.findIndex((recipe) => recipe.public_id === before);
        list = at === -1 ? [] : list.slice(at + 1);
      }
      return { json: list.slice(0, Number(call.query.get('limit') ?? 50)) };
    });
}

/** POST /v1/reports: 202, or what a test says the API answers. */
export function serveReports(api: MockApi, answer: Reply = { status: 202 }): MockApi {
  return api.on('POST', '/v1/reports', answer);
}

/** A pending submission by AUTHOR as the queue shows it, with two examples and no history. */
export function makeReviewRecipe(overrides: Partial<ReviewRecipe> = {}): ReviewRecipe {
  return {
    public_id: 'p3nd1ng00001',
    status: 'PENDING',
    title: 'Teapot still life',
    modality: 'image',
    version: 1,
    template: 'A {{thing}} on a wooden table, soft window light.',
    variables: [{ name: 'thing', default: 'teapot' }],
    inputs: [],
    model: 'gpt-image-2',
    params: { size: '1024x1024' },
    licence: 'CC-BY-4.0',
    tags: ['still-life'],
    made_from: null,
    examples: [
      { position: 1, url: mediaUrl('review-1.png'), content_type: 'image/png', source_job_id: '7a0b0000-0000-4000-8000-000000000091', copied: false },
      { position: 2, url: mediaUrl('review-2.png'), content_type: 'image/png', source_job_id: '7a0b0000-0000-4000-8000-000000000092', copied: false },
    ],
    reason: null,
    submitted_at: '2026-11-16T05:48:49Z',
    published_at: null,
    reviewed_at: null,
    ...overrides,
  };
}

export function makeQueueItem(overrides: Partial<QueueItem> = {}): QueueItem {
  return {
    why: 'pending',
    recipe: makeReviewRecipe(),
    author: {
      user_id: '0f0e0d0c-0000-4000-8000-000000000007',
      email: 'mina@example.test',
      handle: AUTHOR,
      display_name: 'Mina',
      link: 'https://example.test/mina',
      approved_display_name: null,
      approved_link: null,
      approved_at: null,
      followers: 0,
    },
    prior_decisions: [],
    reports: [],
    ...overrides,
  };
}

export function makeReport(overrides: Partial<ReviewReport> = {}): ReviewReport {
  return {
    id: 'a1b2c3d4-0000-4000-8000-000000000001',
    kind: 'published_recipe',
    target: 'pub11c000001',
    reason: 'The examples are not what the prompt makes.',
    reporter_id: '0f0e0d0c-0000-4000-8000-000000000008',
    created_at: '2026-11-16T07:00:00Z',
    ...overrides,
  };
}

/**
 * The moderation routes over `queue` and `reports`, changed in place: a
 * decision takes its item out of the queue. A decision a test lists in
 * `refuse` answers that instead. Every decision is recorded in `decisions`.
 */
export function serveModeration(
  api: MockApi,
  queue: QueueItem[],
  reports: ReviewReport[] = [],
  refuse: Partial<Record<string, Reply>> = {},
): { decisions: { action: string; target: string; reason?: string }[] } {
  const decisions: { action: string; target: string; reason?: string }[] = [];
  const RECIPE = /^\/v1\/admin\/community\/recipes\/([^/]+)\/(approve|reject|remove)$/;
  const PROFILE = /^\/v1\/admin\/community\/profiles\/([^/]+)\/(approve|remove)$/;
  api.on('GET', '/v1/admin/community/queue', () => ({ json: queue }))
    .on('GET', '/v1/admin/community/reports', () => ({ json: reports }))
    .on('POST', RECIPE, (call) => {
      const [, id, action] = RECIPE.exec(call.path)!;
      const reason = (call.body as { reason?: string } | null)?.reason;
      if (refuse[action]) return refuse[action];
      if (action !== 'approve' && !reason?.trim()) {
        return problem(400, 'give a reason of 1 to 1000 characters; the author sees it', 'invalid_publish');
      }
      decisions.push({ action, target: id, ...(reason ? { reason } : {}) });
      const at = queue.findIndex((item) => item.recipe?.public_id === id);
      const item = queue[at];
      if (at !== -1) queue.splice(at, 1);
      const status = action === 'approve' ? 'PUBLIC' : action === 'reject' ? 'REJECTED' : 'REMOVED';
      return { json: { ...(item?.recipe ?? makeReviewRecipe({ public_id: id })), status, reason: reason ?? null } };
    })
    .on('POST', PROFILE, (call) => {
      const [, handle, action] = PROFILE.exec(call.path)!;
      const reason = (call.body as { reason?: string } | null)?.reason;
      if (refuse[`profile-${action}`]) return refuse[`profile-${action}`];
      decisions.push({ action: `profile-${action}`, target: handle, ...(reason ? { reason } : {}) });
      const at = queue.findIndex((item) => item.why === 'profile' && item.author.handle === handle);
      const item = queue[at];
      if (at !== -1) queue.splice(at, 1);
      return { json: item ?? makeQueueItem({ why: 'profile', recipe: null }) };
    });
  return { decisions };
}
