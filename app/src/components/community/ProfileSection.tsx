import { useState } from 'react';
import type { FormEvent } from 'react';
import {
  codeOf,
  DISPLAY_NAME_MAX,
  displayNameProblem,
  followersText,
  handleProblem,
  HANDLE_MAX,
  LINK_MAX,
  linkProblem,
  publicProfileUrl,
  putProfile,
  refusal,
  type OwnProfile,
} from '../../lib/community';
import { useCommunity } from '../../lib/community-context';
import { ApiError } from '../../lib/api';

/**
 * Settings: the account's public profile, which its published recipes go
 * out under. Shown only while the community is open. An account that may not
 * have one is told why, and gets no form.
 */
export default function ProfileSection() {
  const { open, profile } = useCommunity();
  if (!open || !profile) return null;
  return (
    <section className="mt-8 card" aria-labelledby="profile-heading" data-profile-section>
      <h2 id="profile-heading" className="font-semibold text-fg">Public profile</h2>
      {profile.cannot_publish === 'not_adult' ? (
        <p className="mt-1 text-sm text-fg-muted" data-why-not="not_adult">
          Public profiles and publishing recipes are for adult accounts only.
        </p>
      ) : profile.cannot_publish === 'email_not_verified' ? (
        <p className="mt-1 text-sm text-fg-muted" data-why-not="email_not_verified">
          Verify your email address to set up a public profile and publish recipes. The link is in the email we
          sent; you can ask for it again above.
        </p>
      ) : (
        <ProfileForm profile={profile} />
      )}
    </section>
  );
}

function ProfileForm({ profile }: { profile: OwnProfile }) {
  const { setProfile } = useCommunity();
  const [handle, setHandle] = useState(profile.handle ?? '');
  const [name, setName] = useState(profile.display_name ?? '');
  const [link, setLink] = useState(profile.link ?? '');
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const handleWrong = handleProblem(handle);
  const nameWrong = displayNameProblem(name);
  const linkWrong = linkProblem(link);
  const changed = handle !== (profile.handle ?? '') || name.trim() !== (profile.display_name ?? '')
    || link.trim() !== (profile.link ?? '');
  const canSave = !busy && changed && !handleWrong && !nameWrong && !linkWrong;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!canSave) return;
    setBusy(true);
    setProblem(null);
    setSaved(false);
    try {
      const next = await putProfile({ handle, display_name: name.trim(), link: link.trim() === '' ? null : link.trim() });
      setProfile(next);
      setHandle(next.handle ?? handle);
      setName(next.display_name ?? '');
      setLink(next.link ?? '');
      setSaved(true);
    } catch (err) {
      const code = codeOf(err);
      const reserved = err instanceof ApiError && err.problem?.reason === 'reserved';
      setProblem(reserved
        ? `“${handle}” is reserved. Choose another handle.`
        : code === 'handle_taken'
          ? `“${handle}” belongs to another account. Choose another handle.`
          : refusal(err, "Couldn't save your profile. Try again."));
    } finally {
      setBusy(false);
    }
  };

  const hasProfile = profile.handle !== null;

  return (
    <form onSubmit={(e) => void submit(e)} aria-label="Public profile" className="mt-1">
      <p className="text-sm text-fg-muted">
        Your recipes are published under this name. People can follow you, and see how many do.
      </p>

      {hasProfile && (
        <div className="mt-3 space-y-1 text-sm" data-profile-status>
          {profile.approved ? (
            <p className="text-success">
              Public at{' '}
              <a href={publicProfileUrl(profile.handle!)} target="_blank" rel="noopener noreferrer" className="font-medium underline">
                godance.ai/u/{profile.handle}
              </a>
              {' · '}{followersText(profile.followers)}
            </p>
          ) : (
            <p className="text-fg-muted">
              Not public yet. Your profile becomes public when a moderator approves the first recipe you publish.
            </p>
          )}
          {profile.change_awaiting_review && (
            <p className="text-warning" data-awaiting-review>
              Your new display name or link waits for a moderator. Until one approves it, your public page shows
              the ones approved before.
            </p>
          )}
          {profile.review_reason && (
            <p className="text-danger" data-review-reason>A moderator didn't accept your last change: {profile.review_reason}</p>
          )}
        </div>
      )}

      <ul className="mt-4 list-disc space-y-1 pl-5 text-xs text-fg-subtle" aria-label="Profile rules">
        <li>A handle has 3 to {HANDLE_MAX} characters: lowercase letters, digits and hyphens. It starts and ends with a letter or a digit, with no two hyphens in a row.</li>
        <li>Some handles are reserved: the site's own page names, and names that could pass for staff.</li>
        <li>Once a moderator approves your profile, your handle can't change.</li>
        <li>A moderator approves your display name and link too. A change waits for one; until then your public page keeps the approved ones.</li>
      </ul>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor="profile-handle" className="block text-sm font-medium text-fg">Handle</label>
          <div className="mt-1.5 flex items-center gap-1">
            <span aria-hidden="true" className="text-sm text-fg-subtle">@</span>
            <input
              id="profile-handle"
              type="text"
              value={handle}
              onChange={(e) => setHandle(e.target.value.toLowerCase())}
              onBlur={() => setTouched(true)}
              maxLength={HANDLE_MAX}
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              disabled={profile.approved}
              aria-invalid={touched && handleWrong !== null}
              aria-describedby="profile-handle-note"
              className="input min-w-0 flex-1"
            />
          </div>
          <p id="profile-handle-note" className={`mt-1.5 text-xs ${touched && handleWrong ? 'text-danger' : 'text-fg-subtle'}`}>
            {profile.approved
              ? 'Fixed: a moderator approved your profile.'
              : touched && handleWrong ? handleWrong : 'How you appear in links: godance.ai/u/your-handle.'}
          </p>
        </div>
        <div>
          <label htmlFor="profile-name" className="block text-sm font-medium text-fg">Display name</label>
          <input
            id="profile-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={DISPLAY_NAME_MAX}
            autoComplete="nickname"
            aria-invalid={touched && nameWrong !== null}
            aria-describedby="profile-name-note"
            className="input mt-1.5"
          />
          <p id="profile-name-note" className={`mt-1.5 text-xs ${touched && nameWrong ? 'text-danger' : 'text-fg-subtle'}`}>
            {touched && nameWrong ? nameWrong : `Up to ${DISPLAY_NAME_MAX} characters.`}
          </p>
        </div>
        <div className="sm:col-span-2">
          <label htmlFor="profile-link" className="block text-sm font-medium text-fg">Link <span className="font-normal text-fg-subtle">(optional)</span></label>
          <input
            id="profile-link"
            type="url"
            value={link}
            onChange={(e) => setLink(e.target.value)}
            maxLength={LINK_MAX}
            placeholder="https://"
            autoComplete="url"
            aria-invalid={touched && linkWrong !== null}
            aria-describedby="profile-link-note"
            className="input mt-1.5"
          />
          <p id="profile-link-note" className={`mt-1.5 text-xs ${touched && linkWrong ? 'text-danger' : 'text-fg-subtle'}`}>
            {touched && linkWrong ? linkWrong : 'Your site or a page about you: an https:// address.'}
          </p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button type="submit" disabled={!canSave} className="btn btn-primary btn-sm">
          {busy ? 'Saving…' : hasProfile ? 'Save profile' : 'Set up profile'}
        </button>
        <p aria-live="polite" className="text-xs text-success">{saved && !changed ? 'Saved.' : ''}</p>
      </div>
      {problem && <p role="alert" className="mt-2 text-sm text-danger">{problem}</p>}
    </form>
  );
}
