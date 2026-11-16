import { useEffect, useRef, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router-dom';
import {
  codeOf,
  followersText,
  getFeed,
  getPublicProfile,
  isHandle,
  publicProfileUrl,
  type PublicProfile,
  type PublicRecipe,
} from '../lib/community';
import { useCommunity } from '../lib/community-context';
import { useSignedInAccountId } from '../lib/after-sign-in';
import { COMMUNITY_DESTINATION, publicCommunityAvailable } from '../lib/community-discovery';
import FollowButton from '../components/community/FollowButton';
import PublicRecipeCard from '../components/community/PublicRecipeCard';

const PAGE_SIZE = 24;

/**
 * /following: the newest recipes of the authors the user follows, and who
 * those are. `?follow=<handle>` (the Follow link on an author's public page)
 * shows that author with a Follow button: a link alone never follows anyone.
 * Only while the community is open; otherwise it leads back to the dashboard.
 */
export default function FollowingPage() {
  const account = useSignedInAccountId();
  return <AccountFollowing key={account} />;
}

function AccountFollowing() {
  const { open, loading } = useCommunity();
  const [params] = useSearchParams();
  const asked = params.get('follow')?.trim().toLowerCase() ?? '';

  if (loading) {
    return <div className="flex min-h-[40vh] items-center justify-center text-sm text-fg-faint">Loading…</div>;
  }
  if (!open) return <Navigate to="/dashboard" replace />;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Following</h1>
      <p className="mt-2 text-sm text-fg-muted">
        New recipes from the authors you follow. Follow an author from their public page, or from a community recipe
        you open in the Studio.
      </p>
      {isHandle(asked) && <AskedToFollow handle={asked} />}
      <Feed />
      <Followed />
    </div>
  );
}

/** The author a Follow link named, with their public profile and a Follow button. */
function AskedToFollow({ handle }: { handle: string }) {
  const [profile, setProfile] = useState<PublicProfile | null | 'missing' | 'failed'>(null);

  useEffect(() => {
    let cancelled = false;
    setProfile(null);
    getPublicProfile(handle).then(
      (found) => { if (!cancelled) setProfile(found); },
      (err: unknown) => { if (!cancelled) setProfile(codeOf(err) === 'not_found' ? 'missing' : 'failed'); },
    );
    return () => { cancelled = true; };
  }, [handle]);

  return (
    <section aria-label={`Follow @${handle}`} className="mt-6 card" data-asked-to-follow={handle}>
      {profile === null && <p className="text-sm text-fg-faint">Loading…</p>}
      {profile === 'missing' && <p className="text-sm text-fg-muted">@{handle} has no public profile.</p>}
      {profile === 'failed' && <p role="alert" className="text-sm text-danger">Couldn't load @{handle}'s profile.</p>}
      {profile !== null && typeof profile === 'object' && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="break-words font-semibold text-fg">{profile.display_name ?? `@${profile.handle}`}</p>
            <p className="text-sm text-fg-muted">
              <a href={publicProfileUrl(profile.handle)} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
                @{profile.handle}
              </a>
              <span aria-hidden="true"> · </span>{followersText(profile.followers)}
              <span aria-hidden="true"> · </span>{profile.recipes.length} {profile.recipes.length === 1 ? 'recipe' : 'recipes'}
            </p>
          </div>
          <FollowButton handle={profile.handle} className="btn btn-primary btn-sm" />
        </div>
      )}
    </section>
  );
}

/** The feed, newest first, a page at a time. */
function Feed() {
  const { following } = useCommunity();
  const [recipes, setRecipes] = useState<PublicRecipe[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  // Read again when someone is followed or unfollowed here, not when the list is first read.
  const followingKey = following?.join(' ') ?? null;
  const seenKey = useRef<string | null>(null);
  const [reads, setReads] = useState(0);
  useEffect(() => {
    if (followingKey === null) return;
    if (seenKey.current !== null && seenKey.current !== followingKey) setReads((n) => n + 1);
    seenKey.current = followingKey;
  }, [followingKey]);

  useEffect(() => {
    let cancelled = false;
    getFeed({ limit: PAGE_SIZE }).then(
      (page) => {
        if (cancelled) return;
        setRecipes(page);
        setHasMore(page.length === PAGE_SIZE);
        setFailed(false);
      },
      () => { if (!cancelled) setFailed(true); },
    );
    return () => { cancelled = true; };
  }, [reads]);

  const loadMore = async () => {
    const last = recipes?.at(-1);
    if (!last) return;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await getFeed({ limit: PAGE_SIZE, before: last.public_id });
      setRecipes((cur) => {
        const seen = new Set(cur?.map((recipe) => recipe.public_id));
        return [...(cur ?? []), ...page.filter((recipe) => !seen.has(recipe.public_id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch {
      setMoreFailed(true);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <section aria-labelledby="following-feed" className="mt-8">
      <h2 id="following-feed" className="text-sm font-semibold text-fg">New recipes</h2>
      {failed && <p role="alert" className="mt-3 text-sm text-danger">Couldn't load the recipes of the authors you follow.</p>}
      {!failed && recipes === null && <p className="mt-3 text-sm text-fg-faint">Loading…</p>}
      {recipes?.length === 0 && (
        <p className="mt-3 text-sm text-fg-muted">Nothing yet. When an author you follow publishes a recipe, it shows here.</p>
      )}
      {recipes !== null && recipes.length > 0 && (
        <>
          <div className="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {recipes.map((recipe) => <PublicRecipeCard key={recipe.public_id} recipe={recipe} />)}
          </div>
          {hasMore && (
            <div className="mt-6 flex flex-col items-center gap-2">
              <button type="button" onClick={() => void loadMore()} disabled={loadingMore} className="btn btn-secondary">
                {loadingMore ? 'Loading…' : 'Load more'}
              </button>
              {moreFailed && <p role="alert" className="text-sm text-danger">Couldn't load more. Try again.</p>}
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** Who the user follows, most recently followed first, each with a way to stop. */
function Followed() {
  const { following, loadFollowing } = useCommunity();
  const [discovery, setDiscovery] = useState(false);
  useEffect(() => { loadFollowing(); }, [loadFollowing]);
  const empty = following?.length === 0;
  useEffect(() => {
    setDiscovery(false);
    if (!empty) return;
    let active = true;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10_000);
    void publicCommunityAvailable(controller.signal).then((available) => { if (active) setDiscovery(available); });
    return () => { active = false; clearTimeout(timeout); controller.abort(); };
  }, [empty]);

  return (
    <section aria-labelledby="following-authors" className="mt-10">
      <h2 id="following-authors" className="text-sm font-semibold text-fg">Authors you follow</h2>
      {following === null && <p className="mt-3 text-sm text-fg-faint">Loading…</p>}
      {following?.length === 0 && <p className="mt-3 text-sm text-fg-muted">You don't follow anyone yet.</p>}
      {empty && discovery && <a href={COMMUNITY_DESTINATION} className="btn btn-secondary btn-sm mt-3">Explore community recipes</a>}
      {following !== null && following.length > 0 && (
        <ul className="mt-3 card divide-y divide-border p-0">
          {following.map((handle) => (
            <li key={handle} className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 text-sm">
              <a href={publicProfileUrl(handle)} target="_blank" rel="noopener noreferrer" className="font-medium text-brand-600 hover:underline">
                @{handle}
              </a>
              <FollowButton handle={handle} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
