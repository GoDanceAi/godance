import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useAfterSignIn, useSignedInAccountId } from './after-sign-in';
import { follow, getProfile, listFollowing, unfollow, type OwnProfile } from './community';

// Whether the community is open, for the whole app (round 14), and the
// signed-in account's own profile and follows. lib/community.ts has the calls.

interface CommunityValue {
  /** The API has the community on. False while it is off, unknown, or nobody is signed in. */
  open: boolean;
  /** True from a sign-in until GET /v1/profile has answered. */
  loading: boolean;
  /** The caller's own profile view, while the community is open. */
  profile: OwnProfile | null;
  setProfile: (profile: OwnProfile) => void;
  /** The handles the caller follows: null until asked for (loadFollowing) and read. */
  following: string[] | null;
  loadFollowing: () => void;
  /** Follows or stops following, and keeps `following` in step. */
  setFollowing: (handle: string, on: boolean) => Promise<void>;
}

const CommunityContext = createContext<CommunityValue>({
  open: false,
  loading: false,
  profile: null,
  setProfile: () => undefined,
  following: null,
  loadFollowing: () => undefined,
  setFollowing: async () => undefined,
});

type Loaded = { userId: string; profile: OwnProfile | null };

/**
 * For each signed-in account, one GET /v1/profile: it says whether the
 * community is open (`community_disabled` when not) and holds the account's
 * own profile. Any other failure counts as closed too, quietly.
 */
export function CommunityProvider({ children }: { children: ReactNode }) {
  const userId = useSignedInAccountId();
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [following, setFollowingList] = useState<{ userId: string; handles: string[] } | null>(null);
  const asked = useRef<string | null>(null);

  useAfterSignIn((account) => {
    let current = true;
    getProfile().then(
      (profile) => { if (current) setLoaded({ userId: account, profile }); },
      () => { if (current) setLoaded({ userId: account, profile: null }); },
    );
    return () => {
      current = false;
    };
  });

  const profile = userId !== null && loaded?.userId === userId ? loaded.profile : null;
  const loading = userId !== null && loaded?.userId !== userId;

  const setProfile = useCallback((next: OwnProfile) => {
    if (userId) setLoaded({ userId, profile: next });
  }, [userId]);

  const loadFollowing = useCallback(() => {
    if (!userId || asked.current === userId) return;
    asked.current = userId;
    listFollowing().then(
      (handles) => setFollowingList({ userId, handles }),
      () => { asked.current = null; },
    );
  }, [userId]);

  const setFollowing = useCallback(async (handle: string, on: boolean) => {
    if (on) await follow(handle);
    else await unfollow(handle);
    if (!userId) return;
    setFollowingList((cur) => {
      const handles = cur?.userId === userId ? cur.handles.filter((each) => each !== handle) : [];
      return { userId, handles: on ? [handle, ...handles] : handles };
    });
  }, [userId]);

  const value = useMemo<CommunityValue>(() => ({
    open: profile !== null,
    loading,
    profile,
    setProfile,
    following: following && following.userId === userId ? following.handles : null,
    loadFollowing,
    setFollowing,
  }), [profile, loading, setProfile, following, userId, loadFollowing, setFollowing]);

  return <CommunityContext.Provider value={value}>{children}</CommunityContext.Provider>;
}

export function useCommunity(): CommunityValue {
  return useContext(CommunityContext);
}
