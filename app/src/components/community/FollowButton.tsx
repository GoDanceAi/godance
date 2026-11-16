import { useEffect, useState } from 'react';
import { codeOf, refusal } from '../../lib/community';
import { useCommunity } from '../../lib/community-context';

/**
 * Follow or stop following an author. It shows only while the community is
 * open, and never for the account's own handle. Pressed means following.
 */
export default function FollowButton({ handle, className = 'btn btn-secondary btn-sm' }: {
  handle: string;
  className?: string;
}) {
  const { open, profile, following, loadFollowing, setFollowing } = useCommunity();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [self, setSelf] = useState(false);

  useEffect(() => {
    if (open) loadFollowing();
  }, [open, loadFollowing]);

  if (!open || self || profile?.handle === handle) return null;
  const on = following?.includes(handle) ?? false;

  const toggle = async () => {
    setBusy(true);
    setProblem(null);
    try {
      await setFollowing(handle, !on);
    } catch (err) {
      if (codeOf(err) === 'follow_self') setSelf(true);
      else if (codeOf(err) === 'not_found') setProblem(`@${handle} has no public profile any more.`);
      else setProblem(refusal(err, on ? "Couldn't stop following. Try again." : "Couldn't follow. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <span className="inline-flex flex-col items-start gap-1">
      <button
        type="button"
        onClick={() => void toggle()}
        disabled={following === null || busy}
        aria-pressed={on}
        aria-label={`Follow @${handle}`}
        title={on ? `You follow @${handle}. Select to stop.` : `See @${handle}'s new recipes under Following`}
        className={className}
      >
        {on ? 'Following' : 'Follow'}
      </button>
      {problem && <span role="alert" className="text-xs text-danger">{problem}</span>}
    </span>
  );
}
